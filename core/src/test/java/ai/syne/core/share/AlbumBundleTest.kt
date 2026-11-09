package ai.syne.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AlbumBundleTest {

    private fun track(uid: String) = SharedTrack(
        uid = uid, albumUid = "alb-1", albumName = "분당구 삼평동 → 판교역 사무실", albumKind = "COURSE", volume = 3, genre = "lofi",
        startedAt = 1_762_000_000_000, endedAt = 1_762_000_131_000, songSec = 131f, distanceM = 1012, smoothness = 88, hardBrakes = 1,
        completed = true, artMedium = "film_photo", artSource = "local", notes = listOf(60, 64, 67), 
        timeline = listOf(TimelineEvent(0, TimelineEvent.DEPART, "출발")), tags = listOf("genre:lofi"),
        coverFile = "covers/$uid.jpg", audioFile = "audio/$uid.m4a", audioSec = 30f,
    )

    @Test
    fun `번들을 쓰고 읽으면 같은 곡과 파일이 나온다`() {
        val tmp = Files.createTempDirectory("syne").toFile()
        val cover = File(tmp, "c.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val m = BundleManifest(BundleManifest.SCHEMA, 1L, "car", listOf(track("trk-1")))
        val bytes = ByteArrayOutputStream().also { AlbumBundle.write(it, m, mapOf("covers/trk-1.jpg" to cover)) }.toByteArray()
        val dest = File(tmp, "out")
        val back = AlbumBundle.read(ByteArrayInputStream(bytes), dest)
        assertEquals(m.tracks, back.tracks)
        assertTrue(File(dest, "covers/trk-1.jpg").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `경로 탈출 항목은 풀지 않는다`() {
        val tmp = Files.createTempDirectory("syne").toFile()
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("manifest.json")); z.write(AlbumBundle.toJson(BundleManifest(1, 0, "car", emptyList())).toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("covers/../../evil.txt")); z.write(1); z.closeEntry()
            }
        }.toByteArray()
        AlbumBundle.read(ByteArrayInputStream(bytes), File(tmp, "out"))
        assertFalse(File(tmp, "evil.txt").exists())
    }

    @Test
    fun `타임라인은 기록에 있는 사건만 만든다`() {
        val samples = buildList {
            repeat(10) { add(SampleLite(it, 0f, "INTRO")) }                  // 출발 전
            repeat(20) { add(SampleLite(10 + it, 30f, if (it < 5) "INTRO" else "DEVELOP")) }
            repeat(8) { add(SampleLite(30 + it, 0f, "DEVELOP")) }             // 8초 정차
            repeat(20) { add(SampleLite(38 + it, 40f, "CLIMAX")) }
        }
        val tl = Timeline.build(samples, 60f)
        assertEquals(TimelineEvent.DEPART, tl.first().kind)
        assertEquals(10, tl.first().tSec)
        assertTrue(tl.any { it.kind == TimelineEvent.STOP && it.tSec == 30 })
        assertEquals(2, tl.count { it.kind == TimelineEvent.SECTION })
        assertEquals(TimelineEvent.ARRIVE, tl.last().kind)
    }
}
