package ai.syne.app.signals

import kotlinx.coroutines.flow.StateFlow

/**
 * SYNE가 소비하는 차량 신호. 실제 SDK(RealSignals)·녹화 재생(ReplaySignals)·자동 전환(SignalHub)이
 * 모두 이 인터페이스 뒤에 있다. 상위 계층(engine/, ui/)은 구현체를 알지 않는다.
 *
 * 모든 값은 StateFlow라 마지막 값을 항상 갖고 있고, 수집은 어느 스레드에서 해도 안전하다.
 */
interface VehicleSignals {
    /** 지금 값이 어디서 오는가. SignalHub는 실행 중 REAL → REPLAY로 바뀔 수 있다. */
    val source: StateFlow<SignalSource>

    /** 차속 km/h (항상 0 이상). */
    val speedKmh: StateFlow<Float>

    /** 핸들 각도 deg. 양수 = 오른쪽, 음수 = 왼쪽. */
    val steeringDeg: StateFlow<Float>

    val brakePressed: StateFlow<Boolean>
    val turnSignal: StateFlow<TurnSignal>
    val gear: StateFlow<Gear>

    /** 목적지까지 남은 시간(초). 경로 안내 중이 아니면 null. */
    val remainSec: StateFlow<Int?>

    /** 현재 도로 제한속도 km/h. 모르면 null. (원칙 2: 초과 시 편성 축소용) */
    val speedLimitKmh: StateFlow<Int?>

    /** 채널별 지원·연결 상태. 디버그 화면과 자동 전환 판단에 쓴다. */
    val channels: StateFlow<Map<Channel, ChannelState>>

    /**
     * 마지막으로 요청해 받은 현재 위치. **앱 내부 전용**(코스 클러스터링) — 화면·서버로 내보내지 않는다.
     * 폴링하지 않고 [requestPosition] 때만 갱신된다(곡 시작·끝).
     */
    val position: StateFlow<GeoFix?>

    /** 안내 중인 목적지. 경로가 없으면 null. 좌표는 내부 전용, 이름만 화면에 쓴다. */
    val destination: StateFlow<DestinationRef?>

    /** 현재 위치 1회 요청(결과는 [position]으로 비동기 도착) */
    fun requestPosition()

    fun start()
    fun stop()
}

enum class SignalSource { NONE, REAL, REPLAY }

/** 위치 1점. label은 내비가 주는 행정동 수준 주소(예: '수정구 시흥동') — 코스 이름에만 쓴다. */
data class GeoFix(val lat: Double, val lon: Double, val label: String?, val atMs: Long)

/** 목적지. routeDistanceM: 경로 시작 시 총 거리(여행 앨범 판정용) */
data class DestinationRef(val name: String?, val lat: Double, val lon: Double, val routeDistanceM: Int?)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

enum class Channel { SPEED, STEERING, BRAKE, TURN_SIGNAL, GEAR, NAVI }

enum class ChannelState {
    /** 확인 중 */
    PENDING,

    /** 지원 확인 + 리스너 등록 완료 */
    LIVE,

    /** checkXxxCapabilities 결과 미지원(또는 차량 서비스 없음) */
    UNSUPPORTED,

    /** 권한 거부·SDK 예외 */
    FAILED,
}

/** 차량 신호 채널(내비 제외) 중 하나라도 LIVE면 실차 신호로 쓸 수 있다고 본다. */
val VEHICLE_CHANNELS = listOf(Channel.SPEED, Channel.STEERING, Channel.BRAKE, Channel.TURN_SIGNAL, Channel.GEAR)
