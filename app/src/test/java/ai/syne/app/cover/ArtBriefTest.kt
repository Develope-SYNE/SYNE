package ai.syne.app.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ArtBriefTest {

    private fun at(hour: Int, month: Int = Calendar.NOVEMBER, minute: Int = 30): Long =
        Calendar.getInstance().apply { set(2026, month, 2, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private val notes = List(40) { 60 + (it * 5) % 12 }

    /** 실제 저장 흐름처럼: 앞 곡들의 매체를 recent로 넘기며 연속으로 만든다 */
    private fun sequence(mood: String, n: Int, hour: Int = 9, smooth: Float = 85f): List<ArtMedium> {
        val made = ArrayList<ArtMedium>()
        repeat(n) { i ->
            val recent = made.asReversed().take(3).map { it.id }
            made += ArtBrief.from(mood, at(hour, minute = i) + i * 7_919L, 120f, smooth, 0, notes, i, recent).medium
        }
        return made
    }

    @Test
    fun `최근 세 곡과 같은 매체는 나오지 않는다`() {
        val seq = sequence("lofi", 24)
        seq.windowed(4).forEach { w -> assertFalse("${w.last()} 반복: $w", w.last() in w.dropLast(1)) }
    }

    @Test
    fun `한 무드가 하나의 화풍에 묶이지 않는다`() {
        ArtDirections.moods.forEach { m -> assertTrue("$m: ${sequence(m, 24).toSet()}", sequence(m, 24).toSet().size >= 6) }
    }

    @Test
    fun `무드마다 경향이 다르다`() {
        val tops = ArtDirections.moods.map { ArtDirections.forMood(it).typical.first() }
        assertEquals(tops.size, tops.toSet().size)
    }

    @Test
    fun `밤 주행은 밤 매체 쪽으로 기운다`() {
        val night = sequence("lofi", 40, hour = 23).count { it == ArtMedium.NIGHT_BOKEH || it == ArtMedium.NEON_CITY }
        val day = sequence("lofi", 40, hour = 11).count { it == ArtMedium.NIGHT_BOKEH || it == ArtMedium.NEON_CITY }
        assertTrue("night=$night day=$day", night > day)
    }

    @Test
    fun `시간대와 계절이 설계서에 반영된다`() {
        assertEquals(TimeOfDay.DUSK, ArtBrief.from("ambient", at(18), 120f, 80f, 0, notes, 0).time)
        assertEquals(TimeOfDay.NIGHT, ArtBrief.from("ambient", at(23), 120f, 80f, 0, notes, 0).time)
        assertEquals(Season.SUMMER, ArtBrief.from("ambient", at(12, Calendar.JULY), 120f, 80f, 0, notes, 0).season)
    }

    @Test
    fun `프롬프트에는 글자 금지가 있고 위치 정보가 없다`() {
        val b = ArtBrief.from("hiphop", at(21), 300f, 55f, 3, notes, 1, drive = DriveFeel(12000, 0.5f, 0.4f))
        assertTrue(b.prompt.contains("no text"))
        assertTrue(b.negativePrompt.contains("purple"))
        assertFalse(Regex("""\d{2,3}\.\d{3,}""").containsMatchIn(b.prompt))
    }

    @Test
    fun `같은 기록이면 같은 설계서(재현 가능)`() {
        val a = ArtBrief.from("citypop", at(19), 200f, 70f, 1, notes, 2, listOf("neon_city"))
        val b = ArtBrief.from("citypop", at(19), 200f, 70f, 1, notes, 2, listOf("neon_city"))
        assertEquals(a.seed, b.seed)
        assertEquals(a.medium, b.medium)
        assertEquals(a.prompt, b.prompt)
        assertNotEquals(ArtMedium.NEON_CITY, a.medium)
    }

    @Test
    fun `주행의 결 - 정차가 잦으면 도심으로 판단한다`() {
        val stopGo = List(300) { i -> if ((i / 20) % 2 == 0) 30f else 0f }
        val f = DriveFeel.of(stopGo)
        assertTrue(f.stopsPerKm > 2f)
        assertTrue(f.distanceM in 1300..1360) // 160초 × 30km/h = 1333m
    }
}
