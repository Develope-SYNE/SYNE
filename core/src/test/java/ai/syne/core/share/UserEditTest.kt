package ai.syne.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class UserEditTest {

    @Test
    fun `빈 제목은 기본 제목으로 돌아간다`() {
        assertNull(EditRules.clean("   ", EditRules.TITLE_MAX, singleLine = true))
        val e = UserEdit(customTitle = null, updatedAt = 1)
        assertEquals("Vol.13 · Lo-fi", EditRules.displayTitle("Vol.13 · Lo-fi", e))
        assertEquals("노을이 지던 퇴근길", EditRules.displayTitle("Vol.13 · Lo-fi", e.copy(customTitle = "노을이 지던 퇴근길")))
    }

    @Test
    fun `제목은 한 줄로 정리되고 길이 제한을 넘으면 저장 전에 알린다`() {
        assertEquals("노을 지던 길", EditRules.clean("  노을\n 지던   길 ", EditRules.TITLE_MAX, singleLine = true))
        assertTrue(EditRules.problem("가".repeat(41), "", "") != null)
        assertNull(EditRules.problem("가".repeat(40), "", ""))
    }

    @Test
    fun `태그는 #과 공백을 지우고 중복과 개수를 제한한다`() {
        assertEquals(listOf("퇴근길", "노을", "Lofi"), EditRules.tags(listOf("#퇴근길", " 노 을 ", "#퇴근길", "Lofi", "lofi", "", "#")))
        assertEquals(EditRules.TAGS_MAX, EditRules.tags(List(20) { "t$it" }).size)
    }

    @Test
    fun `다시 가져와도 더 새로운 휴대폰 편집은 지켜진다`() {
        val phone = UserEdit(memo = "휴대폰 메모", favorite = true, updatedAt = 2000)
        val olderCar = UserEdit(customTitle = "차에서 지은 제목", updatedAt = 1000)
        val newerCar = UserEdit(customTitle = "나중에 고친 제목", updatedAt = 3000)
        assertEquals(phone, EditRules.merge(phone, olderCar))
        assertEquals(newerCar, EditRules.merge(phone, newerCar))
        assertEquals(phone, EditRules.merge(phone, null))
        assertEquals(olderCar, EditRules.merge(null, olderCar))
        assertEquals(phone, EditRules.merge(phone, phone.copy(memo = "같은 시각")))
    }

    @Test
    fun `예전 번들(편집 필드 없음)도 읽히고 새 번들은 편집·시간대를 왕복한다`() {
        val base = SharedTrack(
            "trk-1", "alb-1", "A → B", "COURSE", 1, "lofi", 0, 1000, 60f, 500, 90, 0, true, null, "local",
            emptyList(), emptyList(), emptyList(), null, null, null,
        )
        // 예전 형식: edit/timeZone 키 자체가 없다
        val old = AlbumBundle.trackJson(base)
        assertTrue(!old.has("edit") && !old.has("timeZone"))
        assertNull(AlbumBundle.trackFrom(old).edit)

        val edited = base.copy(edit = UserEdit("노을", "설명", "메모", listOf("퇴근길"), true, 42), timeZone = "Asia/Seoul")
        val tmp = Files.createTempDirectory("syne").toFile()
        val bytes = ByteArrayOutputStream().also { AlbumBundle.write(it, BundleManifest(1, 0, "car", listOf(edited)), emptyMap()) }.toByteArray()
        val back = AlbumBundle.read(ByteArrayInputStream(bytes), tmp).tracks.single()
        assertEquals(edited.edit, back.edit)
        assertEquals("Asia/Seoul", back.timeZone)
    }
}
