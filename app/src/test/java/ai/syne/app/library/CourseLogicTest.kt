package ai.syne.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLogicTest {
    private val lat0 = 37.39470
    private val lon0 = 127.11120
    private fun north(m: Double) = lat0 + m / 111_320.0

    @Test fun `거리 계산`() {
        assertEquals(500.0, CourseLogic.distanceM(lat0, lon0, north(500.0), lon0), 2.0)
    }

    @Test fun `반경 300m 안이면 같은 장소, 밖이면 새 장소`() {
        val home = PlaceEntity(1, lat0, lon0, "집", 1)
        assertEquals(home, CourseLogic.nearestPlace(listOf(home), north(250.0), lon0))
        assertNull(CourseLogic.nearestPlace(listOf(home), north(350.0), lon0))
    }

    @Test fun `여러 후보 중 가장 가까운 장소`() {
        val a = PlaceEntity(1, lat0, lon0, "A", 1)
        val b = PlaceEntity(2, north(200.0), lon0, "B", 1)
        assertEquals(b, CourseLogic.nearestPlace(listOf(a, b), north(150.0), lon0))
    }

    @Test fun `방문할수록 중심이 평균으로 수렴`() {
        val p = PlaceEntity(1, lat0, lon0, "집", 1)
        val m = CourseLogic.merge(p, north(100.0), lon0)
        assertEquals(2, m.visits)
        assertEquals(50.0, CourseLogic.distanceM(lat0, lon0, m.lat, m.lon), 1.0)
    }

    @Test fun `50km 이상이면 여행 앨범`() {
        assertTrue(CourseLogic.isTravel(62_000, 40_000.0))
        assertFalse(CourseLogic.isTravel(12_000, 9_000.0))
        assertTrue(CourseLogic.isTravel(null, 51_000.0)) // 내비 없으면 직선거리
    }

    @Test fun `코스 키는 방향이 있고 종류를 구분한다`() {
        assertNotEquals(CourseLogic.courseKey("COURSE", 1, 2), CourseLogic.courseKey("COURSE", 2, 1))
        assertNotEquals(CourseLogic.courseKey("COURSE", 1, 2), CourseLogic.courseKey("TRAVEL", 1, 2))
    }

    @Test fun `앨범 이름에는 좌표가 없다`() {
        assertEquals("분당구 삼평동 → 판교역 사무실", CourseLogic.albumName("COURSE", "분당구 삼평동", "판교역 사무실"))
        assertEquals("여행 · 강릉 바다", CourseLogic.albumName("TRAVEL", "집", "강릉 바다"))
        assertEquals("동네 한 바퀴", CourseLogic.albumName("COURSE", "동네", "동네"))
    }
}
