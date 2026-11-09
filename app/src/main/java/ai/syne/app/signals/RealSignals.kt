package ai.syne.app.signals

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.syne.app.SyneLog
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 실차(또는 Pleos 에뮬레이터) 신호.
 * - 조향·브레이크·방향지시등: Pleos Vehicle SDK 2.3.2 (checkXxxCapabilities → register 리스너)
 * - 속도·기어: AAOS CarPropertyManager (PERF_VEHICLE_SPEED, GEAR_SELECTION; config 존재 확인 후 등록)
 * - 남은 시간·제한속도: Pleos NaviHelper 3.0.5
 *
 * 1회용: start() → stop() 후 재사용하지 않는다. 다시 쓰려면 새로 만든다(SignalHub가 관리).
 * 모든 SDK 호출은 예외를 삼키고 채널 상태(FAILED/UNSUPPORTED)로 보고한다 → SignalHub가 Replay로 전환.
 */
class RealSignals(
    context: Context,
    parentScope: CoroutineScope,
) : VehicleSignals {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private val _source = MutableStateFlow(SignalSource.REAL)
    private val _speed = MutableStateFlow(0f)
    private val _steering = MutableStateFlow(0f)
    private val _brake = MutableStateFlow(false)
    private val _turn = MutableStateFlow(TurnSignal.OFF)
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    private val _remain = MutableStateFlow<Int?>(null)
    private val _limit = MutableStateFlow<Int?>(null)
    private val _channels = MutableStateFlow(Channel.entries.associateWith { ChannelState.PENDING })

    override val source: StateFlow<SignalSource> = _source.asStateFlow()
    override val speedKmh: StateFlow<Float> = _speed.asStateFlow()
    override val steeringDeg: StateFlow<Float> = _steering.asStateFlow()
    override val brakePressed: StateFlow<Boolean> = _brake.asStateFlow()
    override val turnSignal: StateFlow<TurnSignal> = _turn.asStateFlow()
    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    override val remainSec: StateFlow<Int?> = _remain.asStateFlow()
    override val speedLimitKmh: StateFlow<Int?> = _limit.asStateFlow()
    override val channels: StateFlow<Map<Channel, ChannelState>> = _channels.asStateFlow()

    private val _position = MutableStateFlow<GeoFix?>(null)
    private val _destination = MutableStateFlow<DestinationRef?>(null)
    override val position: StateFlow<GeoFix?> = _position.asStateFlow()
    override val destination: StateFlow<DestinationRef?> = _destination.asStateFlow()
    @Volatile private var routeDistanceM: Int? = null

    override fun requestPosition() {
        runCatching { navi?.getCurrentLocationInfo() }.onFailure { SyneLog.w("getCurrentLocationInfo failed: ${it.message}") }
    }

    @Volatile private var stopped = false
    private var started = false

    // ---- Pleos Vehicle SDK ----
    private var vehicle: Vehicle? = null

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) {
            if (angle != null) _steering.value = angle * STEERING_SIGN
        }
        override fun onFailed(e: Exception) = fail(Channel.STEERING, e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            if (pressed != null) _brake.value = pressed
        }
        override fun onFailed(e: Exception) = fail(Channel.BRAKE, e)
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            if (state == null) return
            _turn.value = when (state) {
                TurnSignalValue.LEFT -> TurnSignal.LEFT
                TurnSignalValue.RIGHT -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            }
        }
        override fun onFailed(e: Exception) = fail(Channel.TURN_SIGNAL, e)
    }

    // ---- AAOS CarPropertyManager ----
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        @Suppress("DEPRECATION") // status: API 35에서 deprecated, Pleos 에뮬레이터는 API 34
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    (value.value as? Float)?.let { ms -> _speed.value = abs(ms) * 3.6f }
                VehiclePropertyIds.GEAR_SELECTION ->
                    (value.value as? Int)?.let { _gear.value = mapAaosGear(it) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            SyneLog.w("CarProperty error prop=$propertyId area=$areaId")
        }
    }

    // ---- NaviHelper ----
    private var navi: NaviHelper? = null
    private var destinationPoll: Job? = null

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            SyneLog.d("navi routeStarted duration=${info.duration} distance=${info.distance}")
            _remain.value = info.duration.takeIf { it > 0 }
            routeDistanceM = info.distance.takeIf { it > 0 }
            startDestinationPolling()
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            // 경로가 없을 때도 remainTime=0으로 응답한다(Pleos 에뮬레이터 확인) → 0 이하는 '경로 없음'
            _remain.value = info.remainTime.takeIf { it > 0 }
            _destination.value = if (info.remainTime > 0) {
                // 경로 시작 이벤트를 놓쳤으면 처음 본 남은 거리를 총 거리로 쓴다
                if (routeDistanceM == null && info.remainDistance > 0) routeDistanceM = info.remainDistance
                DestinationRef(info.name, info.latitude, info.longitude, routeDistanceM)
            } else {
                null
            }
        }

        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            _position.value = GeoFix(info.latitude, info.longitude, info.address?.takeIf { it.isNotBlank() }, System.currentTimeMillis())
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            SyneLog.d("navi status=$status")
            if (status == NavigationStatus.ROUTE_DRIVE) {
                startDestinationPolling()
            } else {
                stopDestinationPolling()
                _remain.value = null
            }
        }

        override fun onDestinationArrived(info: DestinationArrivedInfo) = routeFinished()
        override fun onRouteEnded() = routeFinished()
        override fun onRouteCancelled() = routeFinished()

        override fun onCurrentRoadSpeedLimit(limit: Int) {
            _limit.value = limit.takeIf { it > 0 }
        }
    }

    override fun start() {
        check(!started) { "RealSignals is single-use" }
        started = true
        SyneLog.i("RealSignals start")
        startPleosVehicle()
        startCarProperties()
        startNavi()
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        SyneLog.i("RealSignals stop")
        stopDestinationPolling()

        vehicle?.let { v ->
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turnListener) }
            runCatching { v.release() }
        }
        vehicle = null

        @Suppress("DEPRECATION")
        runCatching { propertyManager?.unregisterCallback(propertyCallback) }
        runCatching { car?.disconnect() }
        propertyManager = null
        car = null

        navi?.let { n ->
            runCatching { n.removeListener(naviListener) }
            runCatching { n.release() }
        }
        navi = null

        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Pleos Vehicle SDK: 조향 · 브레이크 · 방향지시등
    // ------------------------------------------------------------------

    private fun startPleosVehicle() {
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            SyneLog.w("Pleos Vehicle init failed", t)
            setChannel(Channel.STEERING, ChannelState.UNSUPPORTED)
            setChannel(Channel.BRAKE, ChannelState.UNSUPPORTED)
            setChannel(Channel.TURN_SIGNAL, ChannelState.UNSUPPORTED)
            return
        }
        vehicle = v

        checkCapability(
            channel = Channel.STEERING,
            check = { ok, err ->
                v.getSteeringWheel().checkSteeringWheelAngleCapabilities({ config, bounds ->
                    SyneLog.d("steering bounds=$bounds")
                    ok(config)
                }, err)
            },
            register = { v.getSteeringWheel().registerSteeringWheelAngle(steeringListener) },
        )
        checkCapability(
            channel = Channel.BRAKE,
            check = { ok, err -> v.getBrake().checkBrakePedalStatusCapabilities(ok, err) },
            register = { v.getBrake().registerBrakePedalStatus(brakeListener) },
        )
        checkCapability(
            channel = Channel.TURN_SIGNAL,
            check = { ok, err -> v.getTurnSignal().checkTurnSignalStateCapabilities(ok, err) },
            register = { v.getTurnSignal().registerTurnSignalState(turnListener) },
        )
    }

    /**
     * checkXxxCapabilities → READ 가능하면 register.
     * initialize() 직후엔 서비스 바인딩이 끝나지 않았을 수 있어 실패 시 몇 번 재시도한다.
     */
    private fun checkCapability(
        channel: Channel,
        check: (onResult: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        register: () -> Unit,
        attempt: Int = 1,
    ) {
        if (stopped) return
        val onResult: (CapabilityConfig) -> Unit = { config ->
            if (!stopped) {
                val readable = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
                SyneLog.d("$channel capability access=${config.accessType}")
                if (readable) {
                    runCatching(register)
                        .onSuccess { setChannel(channel, ChannelState.LIVE) }
                        .onFailure { fail(channel, it) }
                } else {
                    setChannel(channel, ChannelState.UNSUPPORTED)
                }
            }
        }
        val onFailed: (Exception) -> Unit = { e ->
            // 권한 부재(SecurityException)는 재시도해도 같으므로 바로 FAILED. 그 외(바인딩 전 등)만 재시도.
            if (e !is SecurityException && attempt < CAPABILITY_RETRIES && !stopped) {
                SyneLog.d("$channel capability check failed (attempt $attempt): ${e.message}")
                scope.launch {
                    delay(CAPABILITY_RETRY_DELAY_MS)
                    checkCapability(channel, check, register, attempt + 1)
                }
            } else {
                fail(channel, e)
            }
        }
        try {
            check(onResult, onFailed)
        } catch (t: Throwable) {
            fail(channel, t)
        }
    }

    // ------------------------------------------------------------------
    // AAOS CarPropertyManager: 속도 · 기어
    // ------------------------------------------------------------------

    private fun startCarProperties() {
        val c = try {
            Car.createCar(appContext)
        } catch (t: Throwable) { // 비자동차 기기: android.car 클래스 자체가 없음
            SyneLog.w("Car.createCar unavailable", t)
            null
        }
        val pm = try {
            c?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        } catch (t: Throwable) {
            SyneLog.w("CarPropertyManager unavailable", t)
            null
        }
        if (c == null || pm == null) {
            setChannel(Channel.SPEED, ChannelState.UNSUPPORTED)
            setChannel(Channel.GEAR, ChannelState.UNSUPPORTED)
            return
        }
        car = c
        propertyManager = pm
        registerProperty(pm, Channel.SPEED, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ, Car.PERMISSION_SPEED)
        registerProperty(
            pm, Channel.GEAR, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, Car.PERMISSION_POWERTRAIN,
        )
    }

    @Suppress("DEPRECATION") // subscribePropertyEvents는 API 35+; Pleos 에뮬레이터는 API 34
    private fun registerProperty(
        pm: CarPropertyManager,
        channel: Channel,
        propertyId: Int,
        rateHz: Float,
        permission: String,
    ) {
        // 권한이 없으면 getCarPropertyConfig가 null을 돌려줘 '미지원'처럼 보이므로 먼저 구분한다.
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            SyneLog.w("$channel failed: permission denied ($permission)")
            setChannel(channel, ChannelState.FAILED)
            return
        }
        try {
            val config = pm.getCarPropertyConfig(propertyId)
            if (config == null) {
                setChannel(channel, ChannelState.UNSUPPORTED)
                return
            }
            val rate = if (rateHz > 0f && config.maxSampleRate > 0f) minOf(rateHz, config.maxSampleRate) else rateHz
            val ok = pm.registerCallback(propertyCallback, propertyId, rate)
            setChannel(channel, if (ok) ChannelState.LIVE else ChannelState.FAILED)
        } catch (t: Throwable) { // 권한 거부 시 SecurityException
            fail(channel, t)
        }
    }

    // ------------------------------------------------------------------
    // NaviHelper: 남은 시간 · 제한속도
    // ------------------------------------------------------------------

    private fun startNavi() {
        try {
            val helper = NaviHelper(appContext)
            navi = helper
            helper.initialize(
                {
                    if (stopped) return@initialize
                    helper.addListener(naviListener)
                    helper.getDestinationInfo()
                    helper.getCurrentLocationInfo()
                    setChannel(Channel.NAVI, ChannelState.LIVE)
                },
                { t ->
                    SyneLog.w("NaviHelper init failed: ${t.javaClass.simpleName}: ${t.message}")
                    setChannel(Channel.NAVI, ChannelState.FAILED)
                },
            )
        } catch (t: Throwable) {
            fail(Channel.NAVI, t)
        }
    }

    /**
     * onDestinationInfo는 값이 바뀌어도 자동 통지되지 않는다(PLEOS_NOTES). 안내 중에만 주기적으로 요청.
     * register 리스너를 쓸 수 없는 유일한 예외 — 5초 간격 IPC 1회라 CPU 영향은 무시 가능.
     */
    private fun startDestinationPolling() {
        if (destinationPoll?.isActive == true || stopped) return
        destinationPoll = scope.launch {
            while (isActive) {
                runCatching { navi?.getDestinationInfo() }
                delay(DESTINATION_POLL_MS)
            }
        }
    }

    private fun stopDestinationPolling() {
        destinationPoll?.cancel()
        destinationPoll = null
    }

    private fun routeFinished() {
        stopDestinationPolling()
        _remain.value = null
        // 도착 직후 엔딩에서 목적지 이름을 쓸 수 있도록 _destination은 유지, 다음 경로 시작 때 교체
        routeDistanceM = null
    }

    // ------------------------------------------------------------------

    private fun setChannel(channel: Channel, state: ChannelState) {
        _channels.update { it + (channel to state) }
    }

    private fun fail(channel: Channel, t: Throwable) {
        // 권한·서비스 부재는 예상 가능한 실패라 한 줄로. 그 외는 스택트레이스까지.
        val expected = t is SecurityException || t is IllegalStateException
        SyneLog.w("$channel failed: ${t.javaClass.simpleName}: ${t.message}", t.takeUnless { expected })
        setChannel(channel, ChannelState.FAILED)
    }

    private fun mapAaosGear(value: Int): Gear = when {
        value == VehicleGear.GEAR_PARK -> Gear.PARK
        value == VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        value == VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        value == VehicleGear.GEAR_DRIVE || value >= VehicleGear.GEAR_FIRST -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    private companion object {
        /**
         * SDK 조향 부호 → SYNE 규약(양수 = 오른쪽).
         * 2026-11-02 Pleos 에뮬레이터 v2.1.0에서 확인: Virtual Console steering=+25(G29·AAOS PERF_STEERING_ANGLE 규약상 오른쪽)
         * → SDK onSteeringWheelAngleUpdated = -25. 즉 SDK는 왼쪽이 양수이므로 뒤집는다.
         */
        const val STEERING_SIGN = -1f
        const val SPEED_RATE_HZ = 10f
        const val CAPABILITY_RETRIES = 3
        const val CAPABILITY_RETRY_DELAY_MS = 1_500L
        const val DESTINATION_POLL_MS = 5_000L
    }
}
