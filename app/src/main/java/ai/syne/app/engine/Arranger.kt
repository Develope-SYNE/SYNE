package ai.syne.app.engine

/**
 * 레이어. MELODY는 조향 멜로디로, 움직이는 동안(5km/h~) 항상 나오고 속도는 음색만 바꾼다([MelodyTone]).
 * KEYS(로즈 컴핑)는 저속에선 은은하게(-6dB), 30km/h부터 제 볼륨.
 * RICH(패드의 7th·9th 화음)는 속도와 무관하게 '부드러운 주행 30초 유지'로만 붙는 보상 레이어.
 */
enum class Layer { PAD, MELODY, BASS, DRUMS, KEYS, RICH }

/** 조향 멜로디 음색: 5~30km/h 부드러운 pluck / 30~55 keys / 55~ 밝은 lead(+옥타브) */
enum class MelodyTone(val label: String) { PLUCK("pluck"), KEYS("keys"), LEAD("lead") }

/** 레이어 집합 ↔ 비트마스크(기록용) */
fun List<Layer>.toMask(): Int = fold(0) { m, l -> m or (1 shl l.ordinal) }

/**
 * 마디 경계마다 호출되어 다음 마디의 편성을 정한다. 오디오 스레드 전용(동기화 없음), 순수 로직.
 *
 * - 속도 구간(히스테리시스): 정차 PAD / 5km/h~ PAD·MELODY·BASS·DRUMS·KEYS(은은) / 30~ KEYS 제 볼륨 / 55~ 멜로디가 밝은 lead
 * - 템포는 건드리지 않는다. 속도는 편성과 음색에만 반영(원칙 2).
 * - 제한속도 초과(원칙 2: 과속은 절대 보상하지 않음):
 *   레벨은 '제한속도'로 계산(더 빨리 달려도 더 풍성해지지 않음) + 멜로디 제거 + 음색 pluck.
 *   즉각적인 먹먹함(마스터 로우패스)은 Renderer가 블록 단위로 처리한다.
 */
class Arranger {

    /** 0 정차, 1 저속, 2 중속, 3 고속 */
    var level = 0
        private set

    /** 이번 마디가 제한속도 초과 상태로 정해졌는가 */
    var overLimit = false
        private set

    /** 이번 마디 멜로디 음색. 정차(level 0)면 null */
    val melodyTone: MelodyTone?
        get() = when {
            level == 0 -> null
            overLimit -> MelodyTone.PLUCK
            level == 1 -> MelodyTone.PLUCK
            level == 2 -> MelodyTone.KEYS
            else -> MelodyTone.LEAD
        }

    /** 멜로디 '선율'이 진행 중인가(들리든 안 들리든). 노트 로그는 이 기준으로 항상 기록한다. */
    val melodyLine: Boolean get() = level >= 1

    /** KEYS 볼륨 배율: 저속 은은하게(-6dB), 30km/h~ 1 */
    val keysGain: Float get() = if (level >= 2) 1f else 0.5f

    /**
     * @param maxLayers 곡 구간(SongPlanner)이 거는 상한. 줄이기만 하고 늘리지는 않는다.
     */
    fun onBar(speedKmh: Float, speedLimitKmh: Int?, parked: Boolean, maxLayers: Int = 6): List<Layer> {
        overLimit = !parked && speedLimitKmh != null && speedLimitKmh > 0 && speedKmh > speedLimitKmh
        val effective = if (overLimit) speedLimitKmh!!.toFloat() else speedKmh
        if (parked) {
            level = 0
        } else {
            while (level < 3 && effective >= UP_KMH[level]) level++
            while (level > 0 && effective < DOWN_KMH[level - 1]) level--
        }
        val full = LEVEL_LAYERS[level]
        val layers = if (overLimit) full - Layer.MELODY else full
        return layers.take(maxLayers.coerceAtLeast(1))
    }

    companion object {
        /** level → level+1 진입 속도 */
        val UP_KMH = floatArrayOf(5f, 30f, 55f)

        /** level+1 → level 이탈 속도 (UP보다 낮게: 히스테리시스) */
        val DOWN_KMH = floatArrayOf(2f, 26f, 50f)

        private val MOVING = listOf(Layer.PAD, Layer.MELODY, Layer.BASS, Layer.DRUMS, Layer.KEYS)
        val LEVEL_LAYERS = listOf(listOf(Layer.PAD), MOVING, MOVING, MOVING)
    }
}
