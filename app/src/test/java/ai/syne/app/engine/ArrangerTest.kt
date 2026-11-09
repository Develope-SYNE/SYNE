package ai.syne.app.engine

import ai.syne.app.engine.Layer.BASS
import ai.syne.app.engine.Layer.DRUMS
import ai.syne.app.engine.Layer.KEYS
import ai.syne.app.engine.Layer.MELODY
import ai.syne.app.engine.Layer.PAD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangerTest {

    @Test fun `속도 구간별 편성 - 움직이면 멜로디와 은은한 keys까지`() {
        assertEquals(listOf(PAD), Arranger().onBar(0f, null, parked = false))
        val moving = listOf(PAD, MELODY, BASS, DRUMS, KEYS)
        assertEquals(moving, Arranger().onBar(20f, null, parked = false))
        assertEquals(moving, Arranger().onBar(40f, null, parked = false))
        assertEquals(moving, Arranger().onBar(70f, null, parked = false))
    }

    @Test fun `속도는 멜로디 음색과 keys 볼륨만 바꾼다`() {
        fun a(kmh: Float) = Arranger().also { it.onBar(kmh, null, false) }
        assertEquals(null, a(0f).melodyTone)
        assertEquals(MelodyTone.PLUCK, a(10f).melodyTone)
        assertEquals(MelodyTone.KEYS, a(40f).melodyTone)
        assertEquals(MelodyTone.LEAD, a(70f).melodyTone)
        assertEquals(0.5f, a(10f).keysGain, 0f)
        assertEquals(1f, a(40f).keysGain, 0f)
    }

    @Test fun `P에서는 속도와 무관하게 패드만`() {
        assertEquals(listOf(PAD), Arranger().onBar(40f, null, parked = true))
    }

    @Test fun `경계 근처 떨림은 히스테리시스로 무시`() {
        val a = Arranger()
        a.onBar(31f, null, false)
        assertEquals(2, a.level)
        a.onBar(28f, null, false)
        assertEquals(2, a.level)
        a.onBar(25f, null, false)
        assertEquals(1, a.level)
    }

    @Test fun `제한속도 초과 - 멜로디 제거, 레벨은 제한속도 기준(과속 보상 없음)`() {
        val a = Arranger()
        assertEquals(listOf(PAD, BASS, DRUMS, KEYS), a.onBar(76f, 50, false))
        assertTrue(a.overLimit)
        assertEquals(2, a.level) // 76km/h지만 제한 50 기준 → 중속, 고속(lead)으로 올라가지 않음
        assertEquals(MelodyTone.PLUCK, a.melodyTone)
        assertTrue(a.melodyLine) // 들리지 않아도 선율(노트 로그)은 진행
        // 제한속도 아래로 오면 다음 마디에 멜로디 복귀
        assertEquals(listOf(PAD, MELODY, BASS, DRUMS, KEYS), a.onBar(45f, 50, false))
    }

    @Test fun `구간 상한은 줄이기만 한다`() {
        assertEquals(listOf(PAD, MELODY, BASS), Arranger().onBar(70f, null, parked = false, maxLayers = 3))
        assertEquals(listOf(PAD), Arranger().onBar(0f, null, parked = false, maxLayers = 6))
    }

    @Test fun `리드는 항상 A 마이너 펜타토닉 안의 음`() {
        val pc = setOf(9, 0, 2, 4, 7) // A C D E G
        for (deg in -10..20) assertTrue(MusicTheory.pentatonicMidi(deg) % 12 in pc)
        assertEquals(69, MusicTheory.pentatonicMidi(5)) // A4
        assertEquals(0, MusicTheory.steeringToDegreeOffset(5f))
        assertEquals(6, MusicTheory.steeringToDegreeOffset(500f))
        assertEquals(-6, MusicTheory.steeringToDegreeOffset(-500f))
    }

    @Test fun `84BPM 16분음표 길이`() {
        assertEquals(7875.0, GenrePack.LOFI.samplesPerStep(44_100), 1e-9)
        assertEquals(60.0 / 84 / 4 * 48_000, GenrePack.LOFI.samplesPerStep(48_000), 1e-9)
    }

    @Test fun `힙합 팩은 96BPM 정박(스윙 0)`() {
        assertEquals(96, GenrePack.HIPHOP.bpm)
        assertEquals(0.0, GenrePack.HIPHOP.swing, 0.0)
        assertEquals(50, GenrePack.HIPHOP.swingPercent)
        assertEquals(60.0 / 96 / 4 * 48_000, GenrePack.HIPHOP.samplesPerStep(48_000), 1e-9)
    }

    @Test fun `원칙 2 - 어떤 장르도 템포 상한 100BPM을 넘지 않는다`() {
        GenrePack.entries.forEach { assertTrue("${it.id}=${it.bpm}", it.bpm <= GenrePack.MAX_BPM) }
        assertEquals(100, GenrePack.MAX_BPM)
        assertEquals(GenrePack.HIPHOP, GenrePack.of("hiphop"))
        assertEquals(GenrePack.LOFI, GenrePack.of("ambient")) // 사운드팩이 없는 장르는 Lo-fi로
    }
}
