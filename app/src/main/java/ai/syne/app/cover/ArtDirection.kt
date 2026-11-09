package ai.syne.app.cover

/**
 * 앨범 아트 디렉션.
 *
 * 앱 UI(검정·흰색·파랑·금빛, SignatureObject)와 앨범 커버는 **서로 다른 시각 정체성**을 가진다.
 * 커버는 주행의 기억을 담은 개별 작품 — 무드는 '경향'(가중치)일 뿐 하나의 화풍에 묶이지 않는다.
 * 어떤 무드든 모든 매체가 나올 수 있고, 주행 데이터가 가중치를 움직이며, 최근에 쓴 매체는 피한다.
 * 보라 계열 색·글자·로고·경로 지도는 쓰지 않는다.
 */
enum class ArtMedium(val id: String, val promptStyle: String, val subjects: List<String>) {
    FILM_PHOTO("film_photo", "analog 35mm film photograph, faded colors, soft light leak, visible film grain",
        listOf("a quiet apartment window glowing", "laundry swaying on a rooftop", "an empty bus stop under a street lamp", "a corner shop with its lights on", "a puddle reflecting the sky in a parking lot")),
    RISO_PRINT("riso_print", "two-color risograph print, slight misregistration, halftone texture, uncoated paper",
        listOf("overlapping leaves and plants", "rolling hills with birds", "stacked abstract shapes", "a window with houseplants")),
    HAND_DRAWN("hand_drawn", "hand-drawn ink illustration with loose watercolor wash",
        listOf("a small hillside village", "a lamp-lit street in perspective", "a cluttered desk by a window", "a cat asleep on a windowsill")),
    VINTAGE_EDITORIAL("vintage_editorial", "mid-century editorial graphic design, muted flat color fields, printed paper texture",
        listOf("stacked arches", "a grid of quarter circles", "bold diagonal bands", "a single abstract figure")),
    WATERCOLOR("watercolor", "loose watercolor painting, wet-on-wet washes, granulating pigment, white paper showing through",
        listOf("misty layered hills", "a cluster of wildflowers", "rain over a river town", "clouds drifting over fields")),
    OIL_PAINTING("oil_painting", "expressive oil painting, thick impasto brush strokes, visible canvas texture",
        listOf("a windswept field", "a still life of fruit and a jug", "a stormy sky over rooftops", "an abstract tangle of color")),
    COLOR_FIELD("color_field", "color field abstract painting, soft-edged rectangles of luminous color",
        listOf("two floating rectangles of color", "three stacked bands of soft color", "a glowing field divided once")),
    SURREAL("surreal", "quiet surrealist painting, dreamlike and calm, long soft shadows",
        listOf("a giant smooth stone floating above a plain", "a doorway standing alone in a field", "stairs that lead into the sky", "a small island hovering over still water")),
    NIGHT_BOKEH("night_bokeh", "out-of-focus night photograph through a rainy windshield, soft bokeh city lights",
        listOf("city lights blurred by rain", "traffic lights dissolving into bokeh", "a neon sign smeared by water drops")),

    SUNSET_STRIPES("sunset_stripes", "1980s retro graphic illustration, striped setting sun over the sea, vivid pastel gradient sky",
        listOf("a striped sun over calm sea", "palm silhouettes at sunset", "an island at dusk")),
    NEON_CITY("neon_city", "night cityscape with glowing windows and neon reflections on water, retro illustration",
        listOf("a harbor skyline at night", "towers reflected in a canal", "a downtown block with neon strips")),
    GEOMETRIC_POP("geometric_pop", "bold geometric composition, pastel and vivid color blocks, hard drop shadows",
        listOf("playful floating shapes", "a dotted grid with triangles", "squiggles and circles")),

    FOG_LANDSCAPE("fog_landscape", "minimalist photograph of layered mountains in fog, atmospheric perspective",
        listOf("ridges fading into fog", "a pine forest in mist", "valley clouds at first light")),
    STILL_HORIZON("still_horizon", "long-exposure minimalist seascape, calm water, vast negative space",
        listOf("a calm sea with a distant rock", "a lake mirror at dusk", "a pier vanishing into haze")),
    ORGANIC_FORMS("organic_forms", "soft abstract organic forms, blurred gradients, restrained palette",
        listOf("soft overlapping blobs of color", "a single curved line through haze")),

    COLLAGE("collage", "experimental mixed-media collage, torn paper, photocopy halftone, tape",
        listOf("torn magazine scraps and halftone", "layered paper with tape", "cut-out shapes and newsprint lines")),
    STREET_GRAPHIC("street_graphic", "bold street-inspired graphic, spray paint texture, high contrast with one accent color",
        listOf("a bold painted ring", "stacked blocks with drips", "a stencil-like shape on concrete")),
    HARD_LIGHT("hard_light", "high-contrast black and white photograph, hard window light on concrete, deep shadows",
        listOf("window light on a wall", "blinds casting stripes", "a plant shadow across concrete")),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** 한 무드의 경향: 매체별 기본 가중치 + 프롬프트 어휘 */
class MoodDirection(val mood: String, val weights: Map<ArtMedium, Float>, val keywords: String) {
    /** 가장 무거운(대표) 매체들 — 아트 랩·테스트용 */
    val typical: List<ArtMedium> get() = weights.entries.sortedByDescending { it.value }.map { it.key }
}

object ArtDirections {
    private fun w(vararg p: Pair<ArtMedium, Float>): Map<ArtMedium, Float> {
        // 명시하지 않은 매체도 아주 작은 가중치로 열어 둔다(한 장르 = 한 화풍이 되지 않게)
        val base = ArtMedium.entries.associateWith { 0.25f }.toMutableMap()
        p.forEach { (m, v) -> base[m] = v }
        return base
    }

    private val byMood = mapOf(
        "lofi" to MoodDirection(
            "lofi",
            w(ArtMedium.FILM_PHOTO to 2.5f, ArtMedium.WATERCOLOR to 2.5f, ArtMedium.RISO_PRINT to 2f, ArtMedium.HAND_DRAWN to 2f,
                ArtMedium.VINTAGE_EDITORIAL to 1.6f, ArtMedium.OIL_PAINTING to 1.6f, ArtMedium.NIGHT_BOKEH to 1.6f, ArtMedium.COLOR_FIELD to 1f, ArtMedium.SURREAL to 1f),
            "quiet everyday memory, nostalgic, warm and laid-back",
        ),
        "citypop" to MoodDirection(
            "citypop",
            w(ArtMedium.NEON_CITY to 3f, ArtMedium.GEOMETRIC_POP to 2.5f, ArtMedium.SUNSET_STRIPES to 2f, ArtMedium.NIGHT_BOKEH to 2f,
                ArtMedium.SURREAL to 1.2f, ArtMedium.VINTAGE_EDITORIAL to 1f, ArtMedium.RISO_PRINT to 1f),
            "1980s city pop, breezy city nights and seaside drives, pastel and vivid colors",
        ),
        "ambient" to MoodDirection(
            "ambient",
            w(ArtMedium.FOG_LANDSCAPE to 3f, ArtMedium.COLOR_FIELD to 2.5f, ArtMedium.ORGANIC_FORMS to 2.5f, ArtMedium.STILL_HORIZON to 2f,
                ArtMedium.WATERCOLOR to 2f, ArtMedium.SURREAL to 1.8f, ArtMedium.OIL_PAINTING to 0.8f),
            "calm, atmospheric, soft restrained palette, airy and cinematic",
        ),
        "hiphop" to MoodDirection(
            "hiphop",
            w(ArtMedium.COLLAGE to 3f, ArtMedium.STREET_GRAPHIC to 3f, ArtMedium.HARD_LIGHT to 2.5f, ArtMedium.NIGHT_BOKEH to 1.8f,
                ArtMedium.OIL_PAINTING to 1.6f, ArtMedium.GEOMETRIC_POP to 1f, ArtMedium.RISO_PRINT to 1f, ArtMedium.SURREAL to 0.8f),
            "boom-bap, urban texture, bold and expressive, high contrast",
        ),
    )

    fun forMood(mood: String): MoodDirection = byMood[mood] ?: byMood.getValue("lofi")

    /** 아트 랩(개발자 메뉴)에서 보여 줄 무드 순서 */
    val moods = listOf("lofi", "citypop", "ambient", "hiphop")
}
