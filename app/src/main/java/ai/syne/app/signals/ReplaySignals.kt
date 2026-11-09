package ai.syne.app.signals

import ai.syne.app.SyneLog
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * assets의 녹화 주행(JSON, 10Hz)을 실시간으로 재생한다. 시연·개발용.
 * 경과 시간 기준으로 프레임을 고르므로 지연이 생겨도 재생 속도가 밀리지 않는다.
 *
 * 1회용: start() → stop().
 */
class ReplaySignals(
    context: Context,
    parentScope: CoroutineScope,
    private val assetName: String = DEFAULT_ASSET,
    private val loop: Boolean = true,
) : VehicleSignals {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private val _source = MutableStateFlow(SignalSource.REPLAY)
    private val _speed = MutableStateFlow(0f)
    private val _steering = MutableStateFlow(0f)
    private val _brake = MutableStateFlow(false)
    private val _turn = MutableStateFlow(TurnSignal.OFF)
    private val _gear = MutableStateFlow(Gear.PARK)
    private val _remain = MutableStateFlow<Int?>(null)
    private val _limit = MutableStateFlow<Int?>(null)
    private val _channels = MutableStateFlow(Channel.entries.associateWith { ChannelState.PENDING })
    private val _positionSec = MutableStateFlow(0f)

    override val source: StateFlow<SignalSource> = _source.asStateFlow()
    override val speedKmh: StateFlow<Float> = _speed.asStateFlow()
    override val steeringDeg: StateFlow<Float> = _steering.asStateFlow()
    override val brakePressed: StateFlow<Boolean> = _brake.asStateFlow()
    override val turnSignal: StateFlow<TurnSignal> = _turn.asStateFlow()
    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    override val remainSec: StateFlow<Int?> = _remain.asStateFlow()
    override val speedLimitKmh: StateFlow<Int?> = _limit.asStateFlow()
    override val channels: StateFlow<Map<Channel, ChannelState>> = _channels.asStateFlow()

    /** 재생 위치(초). 디버그 표시용. */
    val positionSec: StateFlow<Float> = _positionSec.asStateFlow()

    private val _position = MutableStateFlow<GeoFix?>(null)
    private val _destination = MutableStateFlow<DestinationRef?>(null)
    override val position: StateFlow<GeoFix?> = _position.asStateFlow()
    override val destination: StateFlow<DestinationRef?> = _destination.asStateFlow()

    @Volatile private var trip: ReplayTrip? = null
    @Volatile private var current: ReplayFrame? = null

    /** 실제 반복 주행처럼 매 재생마다 출발점을 0~40m 흔든다(코스 클러스터링 반경 300m 검증용) */
    private val jitterX = (Math.random() * 2 - 1) * 28.0
    private val jitterY = (Math.random() * 2 - 1) * 28.0

    @Volatile private var positionPending = false

    override fun requestPosition() {
        val t = trip ?: run {
            positionPending = true // 데모 파일 로딩 전이면 로딩 직후 응답(곡 시작 위치가 빠지지 않게)
            return
        }
        positionPending = false
        val o = t.origin ?: return
        val f = current
        val (lat, lon) = o.offset((f?.xM ?: 0f) + jitterX, (f?.yM ?: 0f) + jitterY)
        // 출발 전(P, 0m)이면 출발지 이름, 그 외엔 이름 없음(도착지는 destination 이름을 쓴다)
        val label = if (f == null || (f.xM == 0f && f.yM == 0f)) o.label else null
        _position.value = GeoFix(lat, lon, label, System.currentTimeMillis())
    }

    private var started = false

    override fun start() {
        check(!started) { "ReplaySignals is single-use" }
        started = true
        scope.launch(Dispatchers.Default) {
            val trip = try {
                withContext(Dispatchers.IO) {
                    ReplayTrip.parse(appContext.assets.open(assetName).bufferedReader().use { it.readText() })
                }
            } catch (t: Throwable) {
                SyneLog.e("Replay load failed: $assetName", t)
                _channels.value = Channel.entries.associateWith { ChannelState.FAILED }
                return@launch
            }
            SyneLog.i("Replay start: ${trip.name} ($assetName), ${trip.frames.size} frames, ${trip.durationSec}s")
            this@ReplaySignals.trip = trip
            trip.destination?.let { d ->
                val o = trip.origin
                val (lat, lon) = if (o != null) {
                    val end = trip.frames.last()
                    o.offset(end.xM + jitterX, end.yM + jitterY)
                } else d.lat to d.lon
                _destination.value = DestinationRef(d.name, lat, lon, d.distanceM)
            }
            _channels.value = Channel.entries.associateWith { ChannelState.LIVE }
            if (positionPending) requestPosition()
            do {
                playOnce(trip)
                if (loop) {
                    delay(LOOP_PAUSE_MS) // 도착(P) 상태를 잠시 유지한 뒤 다시 출발
                }
            } while (loop && isActive)
        }
    }

    override fun stop() {
        scope.cancel()
    }

    private suspend fun playOnce(trip: ReplayTrip) {
        val frames = trip.frames
        if (frames.isEmpty()) return
        val frameMs = (1000L / trip.hz).coerceAtLeast(10L)
        val startMs = SystemClock.elapsedRealtime()
        var idx = 0
        while (scope.isActive) {
            val elapsedSec = (SystemClock.elapsedRealtime() - startMs) / 1000f
            while (idx + 1 < frames.size && frames[idx + 1].t <= elapsedSec) idx++
            emit(frames[idx])
            _positionSec.value = elapsedSec.coerceAtMost(trip.durationSec)
            if (idx == frames.lastIndex) return
            delay(frameMs)
        }
    }

    private fun emit(f: ReplayFrame) {
        current = f
        // StateFlow는 같은 값이면 통지하지 않으므로 그대로 대입해도 불필요한 재구성이 없다.
        _speed.value = f.speedKmh
        _steering.value = f.steeringDeg
        _brake.value = f.brake
        _turn.value = f.turn
        _gear.value = f.gear
        _remain.value = f.remainSec
        _limit.value = f.speedLimitKmh
    }

    companion object {
        const val DEFAULT_ASSET = "trip_demo.json"

        /** 개발자 메뉴에서 고를 수 있는 데모 코스 */
        val COURSES = linkedMapOf("A" to "trip_demo.json", "B" to "trip_demo_b.json")
        private const val LOOP_PAUSE_MS = 5_000L
    }
}

data class ReplayFrame(
    val t: Float,
    val speedKmh: Float,
    val steeringDeg: Float,
    val brake: Boolean,
    val turn: TurnSignal,
    val gear: Gear,
    val remainSec: Int?,
    val speedLimitKmh: Int?,
    /** 출발점 기준 로컬 좌표(m). 실제 위경도가 아니며 커버용 경로 형태에만 쓴다. */
    val xM: Float,
    val yM: Float,
)

/** 데모 코스의 가상 출발점. 로컬 좌표(m)를 위경도로 바꿀 때 쓴다(앱 내부 전용). */
data class ReplayOrigin(val lat: Double, val lon: Double, val label: String?) {
    fun offset(xM: Number, yM: Number): Pair<Double, Double> {
        val lat2 = lat + yM.toDouble() / 111_320.0
        val lon2 = lon + xM.toDouble() / (111_320.0 * Math.cos(Math.toRadians(lat)))
        return lat2 to lon2
    }
}

data class ReplayDestination(val name: String?, val lat: Double, val lon: Double, val distanceM: Int?)

data class ReplayTrip(
    val name: String,
    val hz: Int,
    val durationSec: Float,
    val frames: List<ReplayFrame>,
    val origin: ReplayOrigin? = null,
    val destination: ReplayDestination? = null,
) {
    companion object {
        /**
         * 형식: { "hz":10, "fields":["t","speedKmh",...], "frames":[[...], ...] }
         * 필드 순서는 "fields"로 찾으므로 열 추가·재배치에 안전하다. 없는 필드는 기본값.
         */
        fun parse(json: String): ReplayTrip {
            val root = JSONObject(json)
            val fields = root.getJSONArray("fields").let { arr -> List(arr.length()) { arr.getString(it) } }
            val col = fields.withIndex().associate { (i, name) -> name to i }
            val rows = root.getJSONArray("frames")

            fun JSONArray.num(name: String, default: Double = 0.0): Double =
                col[name]?.let { if (isNull(it)) null else optDouble(it, default) } ?: default

            fun JSONArray.intOrNull(name: String): Int? =
                col[name]?.let { if (isNull(it)) null else optInt(it) }

            fun JSONArray.str(name: String): String? =
                col[name]?.let { if (isNull(it)) null else optString(it) }

            val frames = List(rows.length()) { i ->
                val r = rows.getJSONArray(i)
                ReplayFrame(
                    t = r.num("t").toFloat(),
                    speedKmh = r.num("speedKmh").toFloat().coerceAtLeast(0f),
                    steeringDeg = r.num("steeringDeg").toFloat(),
                    brake = r.num("brake") != 0.0,
                    turn = when (r.str("turn")) {
                        "LEFT" -> TurnSignal.LEFT
                        "RIGHT" -> TurnSignal.RIGHT
                        else -> TurnSignal.OFF
                    },
                    gear = when (r.str("gear")) {
                        "P" -> Gear.PARK
                        "R" -> Gear.REVERSE
                        "N" -> Gear.NEUTRAL
                        "D" -> Gear.DRIVE
                        else -> Gear.UNKNOWN
                    },
                    remainSec = r.intOrNull("remainSec"),
                    speedLimitKmh = r.intOrNull("speedLimitKmh"),
                    xM = r.num("xM").toFloat(),
                    yM = r.num("yM").toFloat(),
                )
            }
            return ReplayTrip(
                name = root.optString("name", "replay"),
                hz = root.optInt("hz", 10).coerceAtLeast(1),
                durationSec = root.optDouble("durationSec", frames.lastOrNull()?.t?.toDouble() ?: 0.0).toFloat(),
                frames = frames,
                origin = root.optJSONObject("origin")?.let {
                    ReplayOrigin(it.getDouble("lat"), it.getDouble("lon"), it.optString("label").ifBlank { null })
                },
                destination = root.optJSONObject("destination")?.let {
                    ReplayDestination(
                        it.optString("name").ifBlank { null }, it.optDouble("lat"), it.optDouble("lon"),
                        if (it.has("distanceM")) it.optInt("distanceM") else null,
                    )
                },
            )
        }
    }
}
