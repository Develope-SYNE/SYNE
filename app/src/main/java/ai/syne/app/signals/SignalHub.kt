package ai.syne.app.signals

import ai.syne.app.SyneLog
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 앱이 실제로 붙잡는 단일 VehicleSignals.
 * 내부 구현(Real/Replay)을 갈아끼워도 소비자는 같은 StateFlow를 계속 수집하면 된다.
 *
 * AUTO: RealSignals를 먼저 시도하고, 제한 시간 안에 차량 채널이 하나도 LIVE가 되지 않으면
 *       (SDK 초기화 실패·미지원·권한 거부) ReplaySignals로 자동 전환한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignalHub(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleSignals {

    enum class Mode { AUTO, REAL, REPLAY }

    private val appContext = context.applicationContext
    private val active = MutableStateFlow<VehicleSignals?>(null)
    private val _mode = MutableStateFlow(Mode.AUTO)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private var switchJob: Job? = null
    private var running = false

    private fun <T> follow(initial: T, pick: (VehicleSignals) -> StateFlow<T>): StateFlow<T> =
        active.flatMapLatest { s -> s?.let(pick) ?: flowOf(initial) as Flow<T> }
            .stateIn(scope, SharingStarted.Eagerly, initial)

    override val source = follow(SignalSource.NONE) { it.source }
    override val speedKmh = follow(0f) { it.speedKmh }
    override val steeringDeg = follow(0f) { it.steeringDeg }
    override val brakePressed = follow(false) { it.brakePressed }
    override val turnSignal = follow(TurnSignal.OFF) { it.turnSignal }
    override val gear = follow(Gear.UNKNOWN) { it.gear }
    override val remainSec = follow<Int?>(null) { it.remainSec }
    override val speedLimitKmh = follow<Int?>(null) { it.speedLimitKmh }
    override val channels = follow(emptyMap<Channel, ChannelState>()) { it.channels }
    override val position = follow<GeoFix?>(null) { it.position }
    override val destination = follow<DestinationRef?>(null) { it.destination }

    override fun requestPosition() {
        active.value?.requestPosition()
    }

    private val _replayCourse = MutableStateFlow(ReplaySignals.COURSES.keys.first())
    /** Replay에 쓸 데모 코스(A/B) */
    val replayCourse: StateFlow<String> = _replayCourse.asStateFlow()

    /** 데모 코스 선택. Replay 중이면 처음(P)부터 다시 재생한다. */
    fun setReplayCourse(course: String) {
        if (course !in ReplaySignals.COURSES) return
        _replayCourse.value = course
        if (running && _mode.value == Mode.REPLAY) activate(Mode.REPLAY)
    }

    /** 데모 주행은 반복하지 않는다: 도착(P) 후 그대로 정차. 코스를 다시 고르면 처음부터. */
    private fun newReplay() = ReplaySignals(appContext, scope, ReplaySignals.COURSES.getValue(_replayCourse.value), loop = false)

    /** Replay 재생 위치(초). Replay가 아닐 땐 null. */
    val replayPositionSec: StateFlow<Float?> =
        active.flatMapLatest { s -> (s as? ReplaySignals)?.positionSec ?: flowOf(null) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override fun start() {
        if (running) return
        running = true
        activate(_mode.value)
    }

    override fun stop() {
        running = false
        switchJob?.cancel()
        active.value?.stop()
        active.value = null
    }

    /** 신호원 변경. 주행 중 전환은 UI에서 막는다(정차 P 전용). */
    fun setMode(mode: Mode) {
        if (_mode.value == mode && running) return
        _mode.value = mode
        if (running) activate(mode)
    }

    private fun activate(mode: Mode) {
        switchJob?.cancel()
        active.value?.stop()
        active.value = null
        SyneLog.i("SignalHub mode=$mode")
        when (mode) {
            Mode.REAL -> use(RealSignals(appContext, scope))
            Mode.REPLAY -> use(newReplay())
            Mode.AUTO -> {
                val real = RealSignals(appContext, scope)
                use(real)
                switchJob = scope.launch {
                    val settled = withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                        real.channels.first { m ->
                            VEHICLE_CHANNELS.any { m[it] == ChannelState.LIVE } ||
                                VEHICLE_CHANNELS.all { m[it] == ChannelState.UNSUPPORTED || m[it] == ChannelState.FAILED }
                        }
                    }
                    val live = settled != null && VEHICLE_CHANNELS.any { settled[it] == ChannelState.LIVE }
                    if (live) {
                        SyneLog.i("AUTO → REAL ($settled)")
                    } else {
                        SyneLog.w("AUTO → REPLAY fallback (real=${real.channels.value})")
                        real.stop()
                        use(newReplay())
                    }
                }
            }
        }
    }

    private fun use(signals: VehicleSignals) {
        active.value = signals
        signals.start()
    }

    private companion object {
        /** capability 재시도(최대 3회 × 1.5초)를 넉넉히 덮는 시간 */
        const val FALLBACK_TIMEOUT_MS = 6_000L
    }
}
