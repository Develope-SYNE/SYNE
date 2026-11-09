package ai.syne.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

class DriveStateTest {

    private fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    /** 오디오 블록(≈21ms)마다 update, 신호는 실제처럼 10Hz 계단으로 들어온다 */
    private fun run(
        d: DriveState,
        seconds: Float,
        speed: (Float) -> Float,
        brake: (Float) -> Boolean = { false },
        steer: (Float) -> Float = { 0f },
        driving: Boolean = true,
        t0: Float = 0f,
    ): Int {
        val dt = 1024f / 48_000f
        var t = 0f
        var fired = 0
        while (t < seconds) {
            val q = floor((t0 + t) * 10f) / 10f
            if (d.update(speed(q), steer(q), brake(q), driving, dt)) fired++
            t += dt
        }
        return fired
    }

    @Test fun `일반 감속 정차는 급제동이 아니다`() {
        val d = DriveState()
        // 50 → 0 km/h 를 8초에 (평균 1.7 m/s², 최대 약 2.6 m/s²)
        val fired = run(d, 10f, speed = { t -> 50f * (1 - smoothstep(t / 8f)) }, brake = { true })
        assertEquals(0, fired)
        assertEquals(0, d.hardBrakeCount)
    }

    @Test fun `강한 감속 + 브레이크 0_3초 이상이면 급제동 1회`() {
        val d = DriveState()
        run(d, 2f, speed = { 60f })
        // 60 → 20 km/h 를 2초에 (평균 5.6 m/s²)
        val fired = run(d, 4f, speed = { t -> if (t < 2f) 60f - 20f * t else 20f }, brake = { true })
        assertEquals(1, fired) // 계속 밟고 있어도 한 번만
        assertEquals(1, d.hardBrakeCount)
    }

    @Test fun `브레이크 없이 감속만 강하면 급제동 아님`() {
        val d = DriveState()
        run(d, 2f, speed = { 60f })
        assertEquals(0, run(d, 3f, speed = { t -> (60f - 20f * t).coerceAtLeast(0f) }, brake = { false }))
    }

    @Test fun `순간적인 강한 감속(0_3초 미만)은 무시`() {
        val d = DriveState()
        run(d, 2f, speed = { 60f })
        // 0.2초 동안만 급감속 후 일정 속도
        val fired = run(d, 2f, speed = { t -> if (t < 0.2f) 60f - 25f * t else 55f }, brake = { t -> t < 0.2f })
        assertEquals(0, fired)
    }

    @Test fun `감속 중에는 정지 예상 시간이 유한하다`() {
        val d = DriveState()
        run(d, 2f, speed = { 40f })
        assertTrue(d.timeToStopSec.isInfinite())
        run(d, 1.5f, speed = { t -> 40f - 7f * t }, brake = { true })
        assertTrue(d.timeToStopSec in 2f..12f)
    }

    @Test fun `부드러운 순항은 높고, 지그재그 조향은 낮다`() {
        val calm = DriveState()
        run(calm, 40f, speed = { 50f }, steer = { t -> 5f * kotlin.math.sin(t * 0.5f) })
        assertTrue("calm=${calm.smoothness}", calm.smoothness >= 95)

        val rough = DriveState()
        run(rough, 40f, speed = { t -> 40f + 15f * kotlin.math.sin(t * 2f) }, steer = { t -> 120f * kotlin.math.sin(t * 3f) })
        assertTrue("rough=${rough.smoothness}", rough.smoothness < 60)
    }

    @Test fun `부드러움 30초 유지 시 풍성함 자격, P에서는 쌓이지 않음`() {
        val parked = DriveState()
        run(parked, 60f, speed = { 0f }, driving = false)
        assertFalse(parked.richEligible)

        val d = DriveState()
        run(d, 25f, speed = { 50f })
        assertFalse(d.richEligible)
        run(d, 10f, speed = { 50f })
        assertTrue(d.richEligible)
    }

    @Test fun `급제동은 부드러움을 떨어뜨린다`() {
        val d = DriveState()
        run(d, 20f, speed = { 60f })
        val before = d.smoothness
        run(d, 3f, speed = { t -> if (t < 2f) 60f - 20f * t else 20f }, brake = { true })
        assertTrue("before=$before after=${d.smoothness}", d.smoothness <= before - 10)
    }
}
