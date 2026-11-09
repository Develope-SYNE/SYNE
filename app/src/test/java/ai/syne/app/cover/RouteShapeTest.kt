package ai.syne.app.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteShapeTest {
    @Test fun `너무 짧은 주행은 경로 모양이 없다`() {
        assertNull(RouteShape.from(List(10) { 5f }, List(10) { 0f })) // 약 14m
    }

    @Test fun `직진은 평평한 실루엣, 굽은 길은 능선이 생긴다`() {
        val straight = RouteShape.from(List(120) { 40f }, List(120) { 0f })!!
        val curvy = RouteShape.from(List(120) { 40f }, List(120) { k -> 120f * kotlin.math.sin(k / 15f) })!!
        val flat = straight.profile.maxOf { abs(it) }
        val wavy = curvy.profile.maxOf { abs(it) }
        println("profile max: straight=%.2f curvy=%.2f".format(flat, wavy))
        assertTrue(flat < 0.6f || straight.profile.distinct().size <= 3)
        assertEquals(1f, wavy, 1e-3f)
        assertEquals(RouteShape.N * 2, curvy.points.size)
        assertTrue(curvy.points.all { it in 0f..1f })
    }

    @Test fun `경로 모양은 좌표가 아니라 정규화된 형태다`() {
        val r = RouteShape.from(List(200) { 50f }, List(200) { k -> if (k in 60..90) 200f else 0f })!!
        assertTrue(r.points.all { it in 0f..1f })
        assertTrue(r.profile.all { it in -1f..1f })
    }
}
