package ai.syne.app.cover

import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

/** 시간대: 빛과 하늘색을 정한다 */
enum class TimeOfDay(val prompt: String) { DAWN("at dawn, pale early light"), DAY("in soft daylight"), DUSK("at golden hour"), NIGHT("at night") }

/** 계절: 색 온도와 소재를 살짝 바꾼다 */
enum class Season(val prompt: String) { SPRING("spring"), SUMMER("summer"), AUTUMN("autumn"), WINTER("winter") }

/**
 * 주행의 결(1초 속도 기록에서): 거리 · 멈췄다 가기(도심) · 가감속의 출렁임.
 * 어떤 사물을 그릴지 정하지 않고, 매체의 가중치와 화면의 리듬에만 영향을 준다.
 */
data class DriveFeel(val distanceM: Int, val stopsPerKm: Float, val surge: Float) {
    companion object {
        val Unknown = DriveFeel(0, 0f, 0.3f)

        fun of(speedsKmh: List<Float>): DriveFeel {
            if (speedsKmh.size < 5) return Unknown
            val dist = speedsKmh.sumOf { it / 3.6 }.toInt()
            var stops = 0
            var moving = false
            for (v in speedsKmh) {
                if (v > 5f) moving = true
                else if (moving && v < 1f) { stops++; moving = false }
            }
            // 1초 사이 속도 변화의 평균 크기(km/h/s) → 0~1
            val dv = speedsKmh.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
            return DriveFeel(dist, stops / (dist / 1000f).coerceAtLeast(0.3f), (dv / 3f).coerceIn(0f, 1f))
        }
    }
}

/**
 * 커버 한 장의 설계서. 주행 데이터를 '영감'으로만 번역한다 — 경로 지도·그래프를 그리지 않는다.
 * 좌표·장소 이름은 들어가지 않는다(원칙 3: 서버로 보낼 수 있는 값만).
 */
data class ArtBrief(
    val mood: String,
    val medium: ArtMedium,
    val time: TimeOfDay,
    val season: Season,
    /** 곡 길이로 정한 밀도(0~1): 긴 곡일수록 장면에 요소가 많다 */
    val density: Float,
    /** 부드러움(0~1): 높을수록 차분하고 깨끗, 낮을수록 거칠고 흔들린다 */
    val calm: Float,
    /** 급제동이 있었으면 긴장감(찢김·대비) */
    val tension: Float,
    /** 멜로디 윤곽(0~1, 8개): 능선·건물 높이·도형 크기의 리듬 */
    val contour: FloatArray,
    val seed: Long,
    /** 같은 매체 안의 구도 번호(곡마다 바뀜) */
    val variant: Int = 0,
    val drive: DriveFeel = DriveFeel.Unknown,
    /** 그날 경로의 모양(좌표 아님). 없으면 실루엣을 넣지 않는다 */
    val route: RouteShape? = null,
) {
    private val rnd get() = Random(seed xor 0x5EED)

    /** 이미지 생성 서버용 프롬프트: 매체 · 주제(풀에서 하나) · 구도 · 빛 · 질감. 글자·로고 금지 */
    val prompt: String
        get() = buildString {
            val r = rnd
            append("Square album cover artwork. ")
            append(medium.promptStyle).append(". ")
            append("Subject: ").append(medium.subjects[r.nextInt(medium.subjects.size)]).append(", ")
            append(time.prompt).append(", ").append(season.prompt).append(". ")
            append("Composition: ").append(COMPOSITIONS[r.nextInt(COMPOSITIONS.size)]).append(". ")
            append("Mood: ").append(ArtDirections.forMood(mood).keywords).append(". ")
            append(if (calm > 0.8f) "Serene and clean. " else if (calm > 0.55f) "Gentle, slightly textured. " else "Restless, raw texture. ")
            if (tension > 0.3f || drive.surge > 0.6f) append("A hint of tension and contrast. ")
            if (drive.stopsPerKm > 2f) append("Stop-and-go city rhythm. ") else if (drive.distanceM > 5000) append("A sense of open distance. ")
            // 경로 선·서명은 앱이 이미지 위에 따로 얹는다(CoverSignature) — 생성 이미지엔 넣지 않는다
            append("Original artwork, no text, no letters, no numbers, no logos, no watermark.")
        }

    val negativePrompt: String
        get() = "text, letters, typography, numbers, watermark, logo, signature, frame with caption, purple, violet, copy of an existing album cover"

    override fun equals(other: Any?) = other is ArtBrief && other.seed == seed && other.medium == medium
    override fun hashCode() = (seed * 31 + medium.ordinal).toInt()

    companion object {
        private val COMPOSITIONS = listOf(
            "off-center subject with generous negative space", "intimate close-up detail", "wide establishing view",
            "top-down view", "symmetrical frontal frame", "low angle looking up", "subject cropped at the edge",
        )

        /**
         * 주행 기록 → 설계서.
         * 매체는 무드의 경향 × 주행 데이터 보정으로 가중 추첨하되, 같은 무드에서 최근 [recent] 매체는 제외한다.
         * @param moodIndex 같은 무드에서 이 곡이 몇 번째인지 — 매체 안 구도 순환에 쓴다
         * @param recent 같은 무드의 최근 매체 id(최신순)
         */
        fun from(
            mood: String,
            startedAtMs: Long,
            songSec: Float,
            smoothness: Float,
            hardBrakes: Int,
            pitches: List<Int>,
            moodIndex: Int,
            recent: List<String> = emptyList(),
            drive: DriveFeel = DriveFeel.Unknown,
            route: RouteShape? = null,
        ): ArtBrief {
            val cal = Calendar.getInstance().apply { timeInMillis = startedAtMs }
            val time = when (cal.get(Calendar.HOUR_OF_DAY)) {
                in 5..7 -> TimeOfDay.DAWN
                in 8..16 -> TimeOfDay.DAY
                in 17..19 -> TimeOfDay.DUSK
                else -> TimeOfDay.NIGHT
            }
            val season = when (cal.get(Calendar.MONTH)) {
                in 2..4 -> Season.SPRING
                in 5..7 -> Season.SUMMER
                in 8..10 -> Season.AUTUMN
                else -> Season.WINTER
            }
            val calm = (smoothness / 100f).coerceIn(0f, 1f)
            val tension = (hardBrakes / 4f).coerceIn(0f, 1f)
            val seed = startedAtMs * 2654435761L xor (pitches.sum().toLong() shl 7)
            val medium = pickMedium(mood, time, calm, tension, drive, recent, seed)
            return ArtBrief(
                mood = mood, medium = medium, time = time, season = season,
                density = (songSec / 600f).coerceIn(0.15f, 1f), calm = calm, tension = tension,
                contour = contourOf(pitches, seed), seed = seed, variant = Math.floorMod(moodIndex, 1 shl 20), drive = drive, route = route,
            )
        }

        /** 무드 경향 × 데이터 보정 → 가중 추첨(최근 매체 제외) */
        fun pickMedium(mood: String, time: TimeOfDay, calm: Float, tension: Float, drive: DriveFeel, recent: List<String>, seed: Long): ArtMedium {
            val w = ArtDirections.forMood(mood).weights.toMutableMap()
            fun boost(f: Float, vararg ms: ArtMedium) = ms.forEach { w[it] = (w[it] ?: 0f) * f }
            when (time) {
                TimeOfDay.NIGHT -> { boost(2.2f, ArtMedium.NIGHT_BOKEH, ArtMedium.NEON_CITY); boost(1.3f, ArtMedium.HARD_LIGHT); boost(0.3f, ArtMedium.SUNSET_STRIPES, ArtMedium.WATERCOLOR) }
                TimeOfDay.DUSK -> boost(1.4f, ArtMedium.SUNSET_STRIPES, ArtMedium.FILM_PHOTO, ArtMedium.OIL_PAINTING)
                else -> { boost(1.4f, ArtMedium.WATERCOLOR, ArtMedium.FOG_LANDSCAPE, ArtMedium.HAND_DRAWN); boost(0.3f, ArtMedium.NIGHT_BOKEH, ArtMedium.NEON_CITY) }
            }
            if (calm > 0.85f) boost(1.4f, ArtMedium.FOG_LANDSCAPE, ArtMedium.STILL_HORIZON, ArtMedium.ORGANIC_FORMS, ArtMedium.COLOR_FIELD, ArtMedium.WATERCOLOR)
            if (calm < 0.6f) boost(1.6f, ArtMedium.COLLAGE, ArtMedium.STREET_GRAPHIC, ArtMedium.OIL_PAINTING, ArtMedium.GEOMETRIC_POP)
            if (tension > 0.3f) boost(1.4f, ArtMedium.COLLAGE, ArtMedium.STREET_GRAPHIC, ArtMedium.HARD_LIGHT)
            if (drive.stopsPerKm > 2f) boost(1.5f, ArtMedium.NIGHT_BOKEH, ArtMedium.NEON_CITY, ArtMedium.FILM_PHOTO)
            if (drive.distanceM > 5000) boost(1.4f, ArtMedium.FOG_LANDSCAPE, ArtMedium.STILL_HORIZON, ArtMedium.WATERCOLOR)
            if (drive.surge > 0.6f) boost(1.3f, ArtMedium.GEOMETRIC_POP, ArtMedium.OIL_PAINTING)
            recent.take(3).forEach { id -> ArtMedium.fromId(id)?.let { w[it] = 0f } }
            val total = w.values.sum()
            if (total <= 0f) return ArtDirections.forMood(mood).typical.first()
            var x = Random(seed).nextFloat() * total
            for ((m, v) in w.entries.sortedBy { it.key.ordinal }) {
                x -= v
                if (x <= 0f && v > 0f) return m
            }
            return w.entries.last { it.value > 0f }.key
        }

        /** 멜로디 → 8개 윤곽값. 노트가 없으면 시드로 */
        private fun contourOf(pitches: List<Int>, seed: Long): FloatArray {
            if (pitches.size < 8) {
                val r = Random(seed)
                return FloatArray(8) { 0.3f + 0.5f * r.nextFloat() }
            }
            val lo = pitches.min()
            val hi = pitches.max().coerceAtLeast(lo + 1)
            val step = pitches.size / 8f
            return FloatArray(8) { i ->
                val a = (i * step).toInt()
                val e = ((i + 1) * step).toInt().coerceAtMost(pitches.size).coerceAtLeast(a + 1)
                ((pitches.subList(a, e).average().toFloat() - lo) / (hi - lo)).coerceIn(0f, 1f)
            }
        }
    }
}
