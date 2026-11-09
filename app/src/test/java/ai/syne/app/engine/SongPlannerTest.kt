package ai.syne.app.engine

import ai.syne.app.signals.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongPlannerTest {

    @Test fun `내비 없으면 시간 기반 구조`() {
        val p = SongPlanner()
        val sections = (0 until 40).map { p.onBar(it, it * 2.86f, null, Gear.DRIVE) }
        assertEquals(Section.INTRO, sections[0])
        assertEquals(Section.INTRO, sections[7])
        assertEquals(Section.DEVELOP, sections[8])
        assertEquals(Section.CLIMAX, sections[24])
        assertEquals(Section.DEVELOP, sections[32])
    }

    @Test fun `내비 남은 시간으로 진행률 배분`() {
        val p = SongPlanner()
        assertEquals(Section.INTRO, p.onBar(1, 5f, 100, Gear.DRIVE))
        assertEquals(Section.DEVELOP, p.onBar(10, 30f, 70, Gear.DRIVE))
        assertEquals(Section.CLIMAX, p.onBar(20, 70f, 30, Gear.DRIVE))
        assertEquals(Section.FINALE, p.onBar(30, 90f, 10, Gear.DRIVE))
        assertEquals(4, p.maxLayers())
    }

    @Test fun `출발 전 P는 엔딩이 아니다`() {
        val p = SongPlanner()
        repeat(3) { assertNotEquals(Section.ENDING, p.onBar(it, it * 2.86f, null, Gear.PARK)) }
    }

    @Test fun `주행 후 P면 엔딩 4마디 뒤 끝`() {
        val p = SongPlanner()
        p.onBar(0, 0f, null, Gear.DRIVE)
        p.onBar(1, 2.86f, null, Gear.DRIVE)
        val after = (2 until 8).map { p.onBar(it, it * 2.86f, null, Gear.PARK) }
        assertEquals(
            listOf(Section.ENDING, Section.ENDING, Section.ENDING, Section.ENDING, Section.END, Section.END),
            after,
        )
    }

    @Test fun `엔딩 중 다시 D로 바꿔도 엔딩은 끝까지`() {
        val p = SongPlanner()
        p.onBar(0, 0f, null, Gear.DRIVE)
        p.onBar(1, 3f, null, Gear.PARK)
        p.onBar(2, 6f, null, Gear.DRIVE)
        assertEquals(Section.ENDING, p.section)
        assertEquals(1, p.endingBar)
    }
}
