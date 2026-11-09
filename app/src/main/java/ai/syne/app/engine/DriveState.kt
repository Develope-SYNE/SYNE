package ai.syne.app.engine

import kotlin.math.abs
import kotlin.math.exp

/** 1초 단위 기록 한 줄 */
data class SecondSample(
    val t: Int,
    val speedKmh: Float,
    val steeringDeg: Float,
    val smoothness: Int,
    val hardBrakes: Int,
)

/**
 * 주행 상태 분석기. 순수 로직(안드로이드 의존 없음), 한 스레드(오디오 스레드)에서만 호출한다.
 *
 * - 가감속: 속도 변화가 들어올 때마다 Δv/Δt를 구해 지수평활(τ=0.25s). 신호가 10Hz 계단이어도 안정적.
 * - 급제동: 브레이크 ON + 감속도 ≥ 2.9 m/s² 가 0.3초 이상 지속 → 1회. 브레이크를 떼면 재무장.
 * - 정지 예상 시간: 감속 중이면 v / 감속도. '잦아듦'(정지 직전 페이드)에 쓴다.
 * - 부드러움 지수(0~100): 1초마다 가속도 변화량(저크)·조향 변화량·급제동으로 점수를 매기고 τ=8초로 평활.
 * - 풍성함 자격: 주행 중 부드러움 ≥ 75가 30초 이상 지속되면 ON, 65 미만으로 떨어지면 OFF.
 */
class DriveState {

    companion object {
        const val HARD_BRAKE_DECEL = 2.9f // m/s²
        const val HARD_BRAKE_HOLD_SEC = 0.3f
        private const val ACCEL_TAU_SEC = 0.25f
        private const val MAX_SAMPLE_GAP_SEC = 0.5f
        private const val DECEL_FOR_STOP = 0.4f // 이보다 약하면 '정지하러 가는 중'으로 보지 않음

        private const val JERK_FULL = 2.5f // m/s³ (1초간 가속도 변화 합) — 이 이상이면 저크 감점 최대
        private const val STEER_RATE_FULL = 150f // deg/s — 이 이상이면 조향 감점 최대
        private const val SMOOTH_TAU_SEC = 8f

        const val RICH_ON = 75
        const val RICH_OFF = 65
        const val RICH_HOLD_SEC = 30
    }

    var accelMs2 = 0f
        private set
    val decelMs2: Float get() = if (accelMs2 < 0f) -accelMs2 else 0f

    var hardBrakeCount = 0
        private set
    private var hardHold = 0f
    private var hardArmed = true

    /** 감속해서 멈추기까지 남은 예상 시간(초). 감속 중이 아니면 무한대. */
    var timeToStopSec = Float.POSITIVE_INFINITY
        private set

    private var smoothF = 100f
    val smoothness: Int get() = smoothF.toInt().coerceIn(0, 100)

    var richEligible = false
        private set
    private var highStreakSec = 0

    var maxSpeedKmh = 0f
        private set
    private var smoothSum = 0.0
    private var smoothCount = 0
    /** 주행(기어 P 아님) 중 1초 샘플들의 평균 부드러움 */
    val averageSmoothness: Float get() = if (smoothCount == 0) 100f else (smoothSum / smoothCount).toFloat()

    private var lastSpeed = Float.NaN
    private var sinceChange = 0f
    private var lastSteer = Float.NaN

    // 1초 집계
    private var secAcc = 0f
    private var secIndex = 0
    private var jerkAcc = 0f
    private var steerAcc = 0f
    private var hardThisSec = false
    private var prevAccel = 0f

    /**
     * @return 이번 호출에서 급제동이 새로 판정되면 true
     * @param onSecond 1초 경계를 넘을 때마다 호출
     */
    fun update(
        speedKmh: Float,
        steeringDeg: Float,
        brakePressed: Boolean,
        driving: Boolean,
        dtSec: Float,
        onSecond: ((SecondSample) -> Unit)? = null,
    ): Boolean {
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh

        // --- 가감속 (속도 값이 바뀌었거나 0.5초 동안 그대로면 표본 1개) ---
        if (lastSpeed.isNaN()) lastSpeed = speedKmh
        sinceChange += dtSec
        if (speedKmh != lastSpeed || sinceChange >= MAX_SAMPLE_GAP_SEC) {
            val a = (speedKmh - lastSpeed) / 3.6f / sinceChange
            val alpha = 1f - exp(-sinceChange / ACCEL_TAU_SEC)
            accelMs2 += (a - accelMs2) * alpha
            jerkAcc += abs(accelMs2 - prevAccel)
            prevAccel = accelMs2
            lastSpeed = speedKmh
            sinceChange = 0f
        }

        // --- 조향 변화량 ---
        if (!lastSteer.isNaN()) steerAcc += abs(steeringDeg - lastSteer)
        lastSteer = steeringDeg

        // --- 급제동 ---
        var fired = false
        if (!brakePressed) {
            hardArmed = true
            hardHold = 0f
        } else if (decelMs2 >= HARD_BRAKE_DECEL) {
            hardHold += dtSec
            if (hardArmed && hardHold >= HARD_BRAKE_HOLD_SEC) {
                hardArmed = false
                hardBrakeCount++
                hardThisSec = true
                fired = true
            }
        } else {
            hardHold = 0f
        }

        // --- 정지까지 남은 시간 ---
        val v = speedKmh / 3.6f
        timeToStopSec = if (decelMs2 > DECEL_FOR_STOP && v > 0.3f) v / decelMs2 else Float.POSITIVE_INFINITY

        // --- 1초 집계 ---
        secAcc += dtSec
        while (secAcc >= 1f) {
            secAcc -= 1f
            secIndex++
            val pJerk = (jerkAcc / JERK_FULL).coerceIn(0f, 1f)
            val pSteer = (steerAcc / STEER_RATE_FULL).coerceIn(0f, 1f)
            val score = (100f - 40f * pJerk - 35f * pSteer - if (hardThisSec) 60f else 0f).coerceIn(0f, 100f)
            smoothF += (score - smoothF) * (1f - exp(-1f / SMOOTH_TAU_SEC))
            jerkAcc = 0f
            steerAcc = 0f
            hardThisSec = false

            if (driving) {
                smoothSum += smoothF
                smoothCount++
                highStreakSec = if (smoothness >= RICH_ON) highStreakSec + 1 else 0
                if (highStreakSec >= RICH_HOLD_SEC) richEligible = true
            } else {
                highStreakSec = 0
            }
            if (smoothness < RICH_OFF) richEligible = false

            onSecond?.invoke(SecondSample(secIndex, speedKmh, steeringDeg, smoothness, hardBrakeCount))
        }
        return fired
    }
}
