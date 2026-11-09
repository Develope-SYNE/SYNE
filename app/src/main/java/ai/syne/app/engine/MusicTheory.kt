package ai.syne.app.engine

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 모든 장르 팩이 공유하는 음악 상수(박자·화성·조향 멜로디). 템포·스윙은 장르 팩(GenrePack)에 있다.
 * 템포는 장르 안에서 고정(원칙 2) — 속도는 편성에만 반영한다.
 */
object MusicTheory {
    const val BEATS_PER_BAR = 4
    const val STEPS_PER_BEAT = 4
    const val STEPS_PER_BAR = BEATS_PER_BAR * STEPS_PER_BEAT // 16분음표 16개

    /** A 마이너 펜타토닉 (A C D E G), A 기준 반음 간격 */
    private val PENTATONIC = intArrayOf(0, 3, 5, 7, 10)

    /** 펜타토닉 도수 0 = A3 (MIDI 57). 도수 5 = A4. */
    const val PENTATONIC_ROOT_MIDI = 57

    /** 조향 몇 도마다 리드가 한 도수 움직이는가 */
    const val STEER_DEG_PER_DEGREE = 20f
    const val STEER_MAX_DEGREE_OFFSET = 6

    fun midiToHz(midi: Float): Float = 440f * 2f.pow((midi - 69f) / 12f)

    /** 펜타토닉 도수(음수 가능) → MIDI. 스케일 밖 음은 나올 수 없다(양자화). */
    fun pentatonicMidi(degree: Int): Int {
        val oct = Math.floorDiv(degree, PENTATONIC.size)
        val idx = Math.floorMod(degree, PENTATONIC.size)
        return PENTATONIC_ROOT_MIDI + oct * 12 + PENTATONIC[idx]
    }

    /** 조향각 → 리드 멜로디 도수 오프셋. 양수(오른쪽) = 위로. */
    fun steeringToDegreeOffset(steeringDeg: Float): Int =
        (steeringDeg / STEER_DEG_PER_DEGREE).roundToInt()
            .coerceIn(-STEER_MAX_DEGREE_OFFSET, STEER_MAX_DEGREE_OFFSET)

    /** 조향각 → 패닝 -0.8(왼쪽) ~ +0.8(오른쪽) */
    fun steeringToPan(steeringDeg: Float): Float = (steeringDeg / 120f).coerceIn(-0.8f, 0.8f)

    /**
     * triad: 기본 3화음(패드), ext: [7th, 9th] — 부드러운 주행이 이어질 때 RICH 레이어로 얹는다.
     * keys는 triad + 7th 4음.
     */
    class Chord(val name: String, val bassMidi: Int, val triad: IntArray, val ext: IntArray) {
        val keys: IntArray = triad + ext[0]
    }

    /** i – VI – III – VII, 마디당 1코드 */
    val PROGRESSION = arrayOf(
        Chord("Am", 45, intArrayOf(57, 60, 64), intArrayOf(67, 71)), // + G(7), B(9)
        Chord("Fmaj", 41, intArrayOf(53, 57, 60), intArrayOf(64, 67)), // + E(maj7), G(9)
        Chord("C", 48, intArrayOf(55, 60, 64), intArrayOf(71, 74)), // + B(maj7), D(9)
        Chord("G", 43, intArrayOf(55, 59, 62), intArrayOf(65, 69)), // + F(7), A(9)
    )

    /** 엔딩 4마디 코드: F – G – Am – Am(울림) */
    val ENDING_CHORDS = intArrayOf(1, 3, 0, 0)

    /** 엔딩 리드: 마디별 (16분 위치, 펜타토닉 도수). 마지막은 A4(도수 5)로 끝난다. */
    val ENDING_LEAD_STEPS = arrayOf(intArrayOf(0, 4, 8, 12), intArrayOf(0, 4, 8, 12), intArrayOf(0))
    val ENDING_LEAD_DEGREES = arrayOf(intArrayOf(8, 7, 6, 5), intArrayOf(7, 6, 4, 2), intArrayOf(5))

    /** 클라이맥스에서 리드가 추가로 치는 16분 위치와, 몇 번째 기본음에서 3도(+2도수) 위로 응답하는지 */
    val LEAD_CLIMAX_EXTRA_STEPS = intArrayOf(2, 10)
    val LEAD_CLIMAX_EXTRA_FROM = intArrayOf(0, 3)

    /** 리드 리듬(16분 위치)과 코드별 기본 모티프(펜타토닉 도수). 조향 오프셋이 여기에 더해진다. */
    val LEAD_STEPS = intArrayOf(0, 3, 6, 8, 11, 14)
    val LEAD_MOTIF = arrayOf(
        intArrayOf(7, 6, 5, 5, 4, 5),
        intArrayOf(5, 4, 3, 4, 5, 7),
        intArrayOf(6, 5, 4, 3, 4, 6),
        intArrayOf(6, 7, 8, 7, 6, 4),
    )
    const val LEAD_MIN_DEGREE = 2
    const val LEAD_MAX_DEGREE = 14
}
