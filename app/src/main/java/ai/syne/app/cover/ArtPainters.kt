package ai.syne.app.cover

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 기기 안에서 그리는 앨범 아트(서버 생성이 없거나 실패할 때의 기본 경로).
 * 매체마다 다른 화가가 그린 것처럼: 필름 사진, 리소 인쇄, 수채 일러스트, 콜라주, 안개 풍경…
 * 글자·로고·경로 지도는 그리지 않는다. 보라 계열 색 없음.
 * 경로 선과 "SYNE · Vol.n" 서명은 여기서 그리지 않는다 — CoverSignature가 모든 커버(AI 생성 포함)에 같은 형식으로 얹는다.
 */
object ArtPainters {

    fun paint(b: ArtBrief, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val a = Art(Canvas(bmp), size.toFloat(), b)
        when (b.medium) {
            ArtMedium.FILM_PHOTO -> a.filmPhoto()
            ArtMedium.RISO_PRINT -> a.riso()
            ArtMedium.HAND_DRAWN -> a.handDrawn()
            ArtMedium.VINTAGE_EDITORIAL -> a.editorial()
            ArtMedium.SUNSET_STRIPES -> a.sunsetStripes()
            ArtMedium.NEON_CITY -> a.neonCity()
            ArtMedium.GEOMETRIC_POP -> a.geometricPop()
            ArtMedium.FOG_LANDSCAPE -> a.fogLandscape()
            ArtMedium.STILL_HORIZON -> a.stillHorizon()
            ArtMedium.ORGANIC_FORMS -> a.organicForms()
            ArtMedium.COLLAGE -> a.collage()
            ArtMedium.STREET_GRAPHIC -> a.streetGraphic()
            ArtMedium.HARD_LIGHT -> a.hardLight()
            ArtMedium.WATERCOLOR -> a.watercolor()
            ArtMedium.OIL_PAINTING -> a.oilPainting()
            ArtMedium.COLOR_FIELD -> a.colorField()
            ArtMedium.SURREAL -> a.surreal()
            ArtMedium.NIGHT_BOKEH -> a.nightBokeh()
        }
        return bmp
    }
}

// =====================================================================
//  공통 도구
// =====================================================================

private fun argb(hex: Long) = hex.toInt()

private fun mix(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(sh: Int) = (((a ushr sh) and 0xFF) + ((((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * u)).toInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun alpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

private class Art(val c: Canvas, val s: Float, val b: ArtBrief) {
    val r = Random(b.seed)

    fun rnd(lo: Float, hi: Float) = lo + (hi - lo) * r.nextFloat()
    fun <T> pick(list: List<T>) = list[r.nextInt(list.size)]
    fun contour(i: Int) = b.contour[Math.floorMod(i, b.contour.size)]

    /** 매체 안의 구도 번호: 같은 매체가 다시 올 때마다 다음 구도로 */
    fun layout(n: Int) = Math.floorMod(b.variant, n)

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun vGradient(top: Int, bottom: Int, y0: Float = 0f, y1: Float = s, mid: Int? = null) {
        val colors = if (mid != null) intArrayOf(top, mid, bottom) else intArrayOf(top, bottom)
        val p = Paint().apply { shader = LinearGradient(0f, y0, 0f, y1, colors, null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, y0, s, y1, p)
    }

    fun blurDisc(x: Float, y: Float, rad: Float, color: Int, blur: Float) {
        val p = fill(color).apply { if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
        c.drawCircle(x, y, rad, p)
    }

    fun glow(x: Float, y: Float, rad: Float, color: Int, mode: PorterDuff.Mode = PorterDuff.Mode.SCREEN) {
        val p = Paint().apply {
            shader = RadialGradient(x, y, rad, intArrayOf(color, alpha(color, 0f)), null, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(mode)
        }
        c.drawCircle(x, y, rad, p)
    }

    /** 필름·인쇄 입자 */
    fun grain(amount: Float, strength: Float = 0.10f, size: Float = 1.4f) {
        val n = (s * s * amount).toInt()
        val light = FloatArray(n); val dark = FloatArray(n)
        var li = 0; var di = 0
        repeat(n / 2) {
            val x = r.nextFloat() * s; val y = r.nextFloat() * s
            if (r.nextBoolean()) { light[li++] = x; light[li++] = y } else { dark[di++] = x; dark[di++] = y }
        }
        val p = Paint().apply { strokeWidth = size * s / 1024f; strokeCap = Paint.Cap.SQUARE }
        p.color = alpha(0xFFFFFFFF.toInt(), strength); c.drawPoints(light, 0, li, p)
        p.color = alpha(0xFF000000.toInt(), strength * 1.2f); c.drawPoints(dark, 0, di, p)
    }

    fun vignette(strength: Float) {
        val p = Paint().apply {
            shader = RadialGradient(s / 2, s / 2, s * 0.75f, intArrayOf(0, 0, alpha(0xFF000000.toInt(), strength)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s, s, p)
    }

    /** 종이: 바탕색 + 섬유 + 얼룩 */
    fun paper(base: Int) {
        c.drawColor(base)
        val p = stroke(alpha(mix(base, 0xFF000000.toInt(), 0.5f), 0.05f), 1.2f * s / 1024f)
        repeat(900) {
            val x = r.nextFloat() * s; val y = r.nextFloat() * s; val l = rnd(4f, 18f) * s / 1024f; val ang = r.nextFloat() * PI.toFloat()
            c.drawLine(x, y, x + cos(ang) * l, y + sin(ang) * l, p)
        }
        cloud(alpha(0xFF6B5A40.toInt(), 0.06f), 6)
    }

    /** 부드러운 얼룩·구름 질감: 작은 무작위 비트맵을 크게 늘려 그린다 */
    fun cloud(color: Int, cells: Int) {
        val n = cells.coerceAtLeast(2)
        val rad = s / n
        val base = (color ushr 24) / 255f
        val p = fill(color).apply { maskFilter = BlurMaskFilter(rad * 0.9f, BlurMaskFilter.Blur.NORMAL) }
        repeat(n * n / 2 + 4) {
            p.color = alpha(color, base * r.nextFloat())
            c.drawCircle(r.nextFloat() * s, r.nextFloat() * s, rad * rnd(0.6f, 1.3f), p)
        }
    }

    /** 망점(halftone): clip 안에 intensity(0~1)만큼 큰 점 */
    fun halftone(clip: Path?, spacing: Float, color: Int, intensity: (Float, Float) -> Float) {
        c.save()
        clip?.let { c.clipPath(it) }
        val p = fill(color)
        var y = 0f
        var row = 0
        while (y < s + spacing) {
            var x = if (row % 2 == 0) 0f else spacing / 2
            while (x < s + spacing) {
                val k = intensity(x / s, y / s).coerceIn(0f, 1f)
                if (k > 0.03f) c.drawCircle(x, y, spacing * 0.55f * sqrt(k), p)
                x += spacing
            }
            y += spacing * 0.866f; row++
        }
        c.restore()
    }

    /** 능선: contour와 사인 합으로 만든 산 모양 경로(아래는 화면 끝까지 닫힘) */
    fun ridge(baseY: Float, amp: Float, freq: Float, rough: Float, phase: Float): Path {
        val path = Path().apply { moveTo(0f, s) }
        val steps = 96
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val ci = u * (b.contour.size - 1)
            val k = ci.toInt(); val f = ci - k
            val cont = contour(k) * (1 - f) + contour(k + 1) * f
            val y = baseY - amp * (0.55f * cont + 0.30f * sin(u * freq * 2 * PI.toFloat() + phase) + rough * 0.15f * sin(u * 37f + phase * 3) * sin(u * 11f))
            path.lineTo(u * s, y)
        }
        path.lineTo(s, s); path.close()
        return path
    }

    /** 손으로 그린 듯 흔들리는 선 */
    fun wobble(points: List<Pair<Float, Float>>, paint: Paint, amount: Float) {
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            val jx = x + (r.nextFloat() - 0.5f) * amount; val jy = y + (r.nextFloat() - 0.5f) * amount
            if (i == 0) path.moveTo(jx, jy) else path.lineTo(jx, jy)
        }
        c.drawPath(path, paint)
    }

    fun wobbleCircle(cx: Float, cy: Float, rad: Float, paint: Paint, amount: Float) {
        val pts = (0..48).map { i -> val t = i / 48f * 2 * PI.toFloat(); cx + cos(t) * rad to cy + sin(t) * rad }
        wobble(pts, paint, amount)
    }

    /** 찢은 종이 조각 */
    fun torn(cx: Float, cy: Float, w: Float, h: Float, rot: Float, jag: Float): Path {
        val pts = ArrayList<Pair<Float, Float>>()
        fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
            val n = 14
            for (i in 0 until n) {
                val t = i / n.toFloat()
                pts += (x0 + (x1 - x0) * t + (r.nextFloat() - 0.5f) * jag) to (y0 + (y1 - y0) * t + (r.nextFloat() - 0.5f) * jag)
            }
        }
        edge(-w / 2, -h / 2, w / 2, -h / 2); edge(w / 2, -h / 2, w / 2, h / 2); edge(w / 2, h / 2, -w / 2, h / 2); edge(-w / 2, h / 2, -w / 2, -h / 2)
        val cr = cos(rot); val sr = sin(rot)
        return Path().apply {
            pts.forEachIndexed { i, (x, y) ->
                val px = cx + x * cr - y * sr; val py = cy + x * sr + y * cr
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    // ---- 하늘·계절 팔레트 ----
    fun sky(): Pair<Int, Int> = when (b.time) {
        TimeOfDay.DAWN -> argb(0xFF36465E) to argb(0xFFF1C7A2)
        TimeOfDay.DAY -> argb(0xFF86AFCF) to argb(0xFFE6ECEC)
        TimeOfDay.DUSK -> argb(0xFF2C4256) to argb(0xFFEE8B4E)
        TimeOfDay.NIGHT -> argb(0xFF0A1220) to argb(0xFF1E3248)
    }

    fun seasonTint(): Int = when (b.season) {
        Season.SPRING -> argb(0xFFF4BFC3)
        Season.SUMMER -> argb(0xFF7FC8A9)
        Season.AUTUMN -> argb(0xFFD98B3A)
        Season.WINTER -> argb(0xFFB9C8D4)
    }

    /** 장면의 '흔들림' — 부드럽게 달릴수록 작다 */
    val jitter get() = (1f - b.calm) * 0.8f + 0.2f
    val px get() = s / 1024f
}

// =====================================================================
//  LO-FI
// =====================================================================

/** 35mm 필름 사진: 창가·골목 불빛·지붕선·바닷가 중 하나, 바랜 색, 빛샘, 굵은 입자 */
private fun Art.filmPhoto() {
    val scene = layout(4)
    if (scene == 2) return filmWindow()
    if (scene == 3) return filmStreetLights()
    val (top, bottom) = sky()
    val fade = argb(0xFFCFC4B2)
    vGradient(mix(top, fade, 0.28f), mix(bottom, fade, 0.22f))
    val horizon = s * rnd(0.58f, 0.74f)
    val night = b.time == TimeOfDay.NIGHT
    // 해·달은 가끔만(반복되는 '큰 해' 구도를 피한다)
    val sunX = s * rnd(0.18f, 0.82f)
    val sunY = horizon - s * rnd(0.12f, 0.3f)
    if (r.nextFloat() < 0.4f) {
        blurDisc(sunX, sunY, s * rnd(0.035f, 0.06f), if (night) argb(0xFFE9E4D0) else argb(0xFFFFE2B0), 18f * px)
        glow(sunX, sunY, s * 0.35f, alpha(if (night) argb(0xFF9FB3C8) else argb(0xFFFFC98A), 0.35f))
    } else glow(s * rnd(0.2f, 0.8f), horizon, s * 0.6f, alpha(argb(0xFFFFC98A), 0.18f))
    val ground = mix(bottom, argb(0xFF14110E), 0.78f)
    if (scene == 0) {
        // 지붕선 실루엣(높이 = 멜로디 윤곽)
        var x = -s * 0.02f
        var i = 0
        val lit = fill(alpha(argb(0xFFFFD08A), if (night || b.time == TimeOfDay.DUSK) 0.85f else 0f))
        while (x < s) {
            val w = s * rnd(0.06f, 0.16f) * (1.3f - b.density * 0.5f)
            val h = s * (0.04f + 0.16f * contour(i)) * rnd(0.8f, 1.2f)
            c.drawRect(x, horizon - h, x + w + 1, s, fill(ground))
            if (r.nextFloat() < 0.4f) c.drawRect(x + w * 0.3f, horizon - h - s * 0.03f, x + w * 0.42f, horizon - h, fill(ground)) // 굴뚝·안테나
            if (lit.color ushr 24 != 0) repeat(r.nextInt(3)) {
                val wx = x + rnd(0.1f, 0.8f) * w; val wy = horizon - h + rnd(0.15f, 0.7f) * h
                c.drawRect(wx, wy, wx + s * 0.012f, wy + s * 0.016f, lit)
            }
            x += w; i++
        }
        // 전깃줄(가끔만)
        val wire = stroke(alpha(ground, 0.9f), 2.2f * px)
        val poleX = s * rnd(0.1f, 0.9f)
        if (r.nextFloat() < 0.4f) c.drawRect(poleX - 5 * px, horizon - s * 0.46f, poleX + 5 * px, horizon, fill(ground))
        if (r.nextFloat() < 0.4f) repeat(2 + r.nextInt(2)) { k ->
            val y0 = horizon - s * (0.42f - k * 0.035f)
            val sag = s * rnd(0.03f, 0.08f)
            c.drawPath(Path().apply { moveTo(-10f, y0 + rnd(-20f, 20f) * px); quadTo(poleX / 2, y0 + sag, poleX, y0) }, wire)
            c.drawPath(Path().apply { moveTo(poleX, y0); quadTo((poleX + s) / 2, y0 + sag, s + 10, y0 + rnd(-20f, 20f) * px) }, wire)
        }
    } else {
        // 바닷가: 수평선 + 물결 반사
        vGradient(mix(bottom, argb(0xFF2A3A44), 0.55f), mix(top, argb(0xFF101418), 0.6f), horizon, s)
        val refl = stroke(alpha(argb(0xFFFFE2B0), 0.5f), 3f * px)
        repeat(26) { k ->
            val y = horizon + (k + 1) * s * 0.012f * (1 + k * 0.08f)
            val w = s * 0.12f * (1 - k / 30f) * rnd(0.5f, 1.2f)
            c.drawLine(sunX - w / 2 + rnd(-10f, 10f) * px, y, sunX + w / 2, y, refl)
        }
        c.drawRect(0f, horizon - 1.5f * px, s, horizon + 1.5f * px, fill(alpha(ground, 0.5f)))
    }
    // 새 몇 마리
    val bird = stroke(alpha(ground, 0.8f), 2.4f * px)
    repeat(r.nextInt(4)) {
        val bx = s * rnd(0.1f, 0.9f); val by = s * rnd(0.15f, 0.4f); val w = s * rnd(0.012f, 0.022f)
        c.drawPath(Path().apply { moveTo(bx - w, by); quadTo(bx - w / 2, by - w * 0.6f, bx, by); quadTo(bx + w / 2, by - w * 0.6f, bx + w, by) }, bird)
    }
    filmDevelop()
}

/** 필름 현상: 바랜 검정, 색 캐스트, 빛샘, 입자, 비네팅 */
private fun Art.filmDevelop() {
    c.drawColor(alpha(argb(0xFFFFF1DC), 0.07f))
    c.drawColor(alpha(seasonTint(), 0.06f))
    val corner = pick(listOf(0f to 0f, s to 0f, 0f to s, s to s))
    glow(corner.first, corner.second * 0.9f, s * rnd(0.5f, 0.8f), alpha(argb(0xFFFF6A3D), rnd(0.35f, 0.6f)))
    grain(0.10f, 0.09f + (1 - b.calm) * 0.05f, 1.6f)
    vignette(0.5f)
}

/** 필름: 실내에서 본 창 — 창틀 실루엣, 바깥의 빛, 화분 */
private fun Art.filmWindow() {
    val (top, bottom) = sky()
    val room = mix(argb(0xFF1D1A17), seasonTint(), 0.12f)
    c.drawColor(room)
    val wx = s * rnd(0.12f, 0.3f); val wy = s * rnd(0.1f, 0.2f); val ww = s * rnd(0.45f, 0.62f); val wh = s * rnd(0.5f, 0.62f)
    c.save(); c.clipRect(wx, wy, wx + ww, wy + wh)
    vGradient(mix(top, argb(0xFFCFC4B2), 0.3f), mix(bottom, argb(0xFFFFE6C6), 0.3f), wy, wy + wh)
    // 바깥: 흐린 건물·나무 덩어리
    repeat(5) { blurDisc(wx + rnd(0f, ww), wy + wh * rnd(0.6f, 1f), s * rnd(0.08f, 0.16f), alpha(mix(bottom, argb(0xFF2A3328), 0.6f), 0.7f), 30f * px) }
    c.restore()
    // 창살
    val frame = fill(argb(0xFF14110F))
    c.drawRect(wx + ww / 2 - 6 * px, wy, wx + ww / 2 + 6 * px, wy + wh, frame)
    c.drawRect(wx, wy + wh * rnd(0.35f, 0.5f) - 5 * px, wx + ww, wy + wh * rnd(0.35f, 0.5f) + 5 * px, frame)
    // 창턱의 빛과 화분 실루엣
    glow(wx + ww / 2, wy + wh, s * 0.5f, alpha(argb(0xFFFFD9A8), 0.35f))
    val pot = wx + ww * rnd(0.15f, 0.8f)
    c.drawRect(pot - s * 0.035f, wy + wh - s * 0.07f, pot + s * 0.035f, wy + wh, frame)
    repeat(6) { k ->
        val ang = (-PI / 2 + (k - 2.5) * 0.35).toFloat()
        val len = s * rnd(0.08f, 0.14f)
        c.drawLine(pot, wy + wh - s * 0.07f, pot + cos(ang) * len, wy + wh - s * 0.07f + sin(ang) * len, stroke(argb(0xFF14110F), 7f * px))
    }
    filmDevelop()
}

/** 필름: 젖은 길 위 가로등 불빛과 반사 */
private fun Art.filmStreetLights() {
    vGradient(argb(0xFF141A22), argb(0xFF2A2420))
    val horizon = s * rnd(0.5f, 0.6f)
    vGradient(argb(0xFF221E1B), argb(0xFF0E0D0C), horizon, s)
    val warm = listOf(argb(0xFFFFC27A), argb(0xFFFFE0A8), argb(0xFF9ED7E0))
    repeat(3 + (b.density * 4).toInt()) { k ->
        val x = s * rnd(0.05f, 0.95f); val y = horizon - s * rnd(0.05f, 0.3f); val col = pick(warm)
        blurDisc(x, y, s * rnd(0.012f, 0.025f), col, 6f * px)
        glow(x, y, s * rnd(0.12f, 0.22f), alpha(col, 0.4f))
        // 젖은 길의 세로 반사
        c.drawRect(x - s * 0.006f, horizon + s * 0.02f, x + s * 0.006f, horizon + s * rnd(0.2f, 0.4f), fill(alpha(col, 0.25f)).apply { maskFilter = BlurMaskFilter(10f * px, BlurMaskFilter.Blur.NORMAL) })
    }
    filmDevelop()
}

/** 2도 리소그래프: 크림 종이, 잉크 겹침(곱하기), 어긋난 판, 망점 */
private fun Art.riso() {
    paper(argb(0xFFF1EADB))
    val inks = pick(
        listOf(
            argb(0xFFFF6F61) to argb(0xFF00838F),
            argb(0xFF3255A4) to argb(0xFFFFC93C),
            argb(0xFF00A65A) to argb(0xFFF26B8A),
            argb(0xFFE8572A) to argb(0xFF2F6F9F),
        ),
    )
    val mul = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    val ink1 = fill(alpha(inks.first, 0.88f)).apply { xfermode = mul }
    val ink2 = fill(alpha(inks.second, 0.85f)).apply { xfermode = mul }
    val off = (6f + 14f * jitter) * px
    val ox = rnd(-off, off); val oy = rnd(-off, off)
    when (layout(3)) {
        0 -> { // 언덕 띠 + 새(해는 가끔)
            val cx = s * rnd(0.3f, 0.7f); val cy = s * rnd(0.28f, 0.42f); val rad = s * rnd(0.16f, 0.24f)
            if (r.nextFloat() < 0.3f) c.drawCircle(cx, cy, rad, ink1)
            else repeat(5 + r.nextInt(6)) {
                val bx = s * rnd(0.1f, 0.9f); val by = s * rnd(0.15f, 0.45f); val bw = s * rnd(0.02f, 0.04f)
                c.drawPath(Path().apply { moveTo(bx - bw, by); quadTo(bx - bw / 2, by - bw * 0.7f, bx, by); quadTo(bx + bw / 2, by - bw * 0.7f, bx + bw, by) }, stroke(alpha(inks.first, 0.9f), 6f * px))
            }
            repeat(3) { k ->
                val path = ridge(s * (0.62f + k * 0.1f), s * 0.12f, 1.2f + k * 0.4f, 0.3f, k * 1.7f + rnd(0f, 3f))
                c.save(); c.translate(ox * k, oy * k)
                if (k == 1) halftone(path, 14f * px, alpha(inks.second, 0.9f)) { _, y -> 0.3f + 0.7f * (y - 0.6f) * 3f } else c.drawPath(path, ink2)
                c.restore()
            }
        }
        1 -> { // 아치형 창 + 잎
            val w = s * rnd(0.42f, 0.56f); val h = s * rnd(0.55f, 0.66f)
            val l = (s - w) / 2; val t = s * 0.18f
            val arch = Path().apply {
                moveTo(l, t + h); lineTo(l, t + w / 2); arcTo(RectF(l, t, l + w, t + w), 180f, 180f); lineTo(l + w, t + h); close()
            }
            c.drawPath(arch, ink2)
            halftone(arch, 12f * px, alpha(inks.first, 0.9f)) { _, y -> 1f - y }
            c.save(); c.translate(ox, oy)
            repeat(5 + (b.density * 5).toInt()) {
                val lx = l + rnd(-0.1f, 1.1f) * w; val ly = t + h + rnd(-0.25f, 0.05f) * h
                val leaf = Path().apply {
                    val lw = s * rnd(0.05f, 0.09f); val lh = s * rnd(0.12f, 0.22f)
                    moveTo(lx, ly); quadTo(lx - lw, ly - lh / 2, lx, ly - lh); quadTo(lx + lw, ly - lh / 2, lx, ly); close()
                }
                c.drawPath(leaf, ink1)
            }
            c.restore()
        }
        else -> { // 겹친 원들(리듬 = 멜로디)
            repeat(5) { i ->
                val cx = s * (0.18f + i * 0.16f); val cy = s * (0.3f + 0.4f * contour(i))
                val rad = s * (0.1f + 0.08f * contour(i + 3))
                c.drawCircle(cx, cy, rad, if (i % 2 == 0) ink1 else ink2)
            }
            c.drawRect(0f, s * 0.82f + oy, s, s, ink2)
        }
    }
    grain(0.05f, 0.08f, 1.2f)
}

/** 잉크 드로잉 + 수채: 언덕 마을 또는 가로등 길 */
private fun Art.handDrawn() {
    paper(argb(0xFFF4EFE6))
    val tint = seasonTint()
    val washes = listOf(tint, mix(sky().second, argb(0xFFFFFFFF), 0.2f), mix(sky().first, argb(0xFFFFFFFF), 0.4f))
    // 수채 번짐: 하늘 띠 + 땅 띠 + 흩어진 얼룩
    vGradient(alpha(washes[2], 0.45f), alpha(washes[1], 0.0f), s * 0.05f, s * 0.55f)
    repeat(9) {
        blurDisc(s * rnd(0.05f, 0.95f), s * rnd(0.1f, 0.95f), s * rnd(0.12f, 0.28f), alpha(pick(washes), rnd(0.28f, 0.42f)), 40f * px)
    }
    val inkColor = argb(0xFF3B3129)
    val ink = stroke(inkColor, 4.4f * px)
    val thin = stroke(alpha(inkColor, 0.75f), 2.2f * px)
    val wob = 5f * px * (0.6f + jitter)
    if (layout(2) == 0) {
        // 언덕 + 집
        val hy = s * rnd(0.55f, 0.66f)
        val hill = (0..24).map { i -> val u = i / 24f; u * s to hy - s * 0.08f * contour((u * 7).toInt()) - s * 0.04f * sin(u * 5f) }
        wobble(hill, ink, wob)
        // 언덕 아래 빗금
        for (k in 0 until 60) {
            val x = s * k / 60f; val y = hy + s * rnd(0.02f, 0.25f)
            c.drawLine(x, y, x + s * 0.025f, y + s * 0.025f, thin)
        }
        repeat(2 + (b.density * 4).toInt()) {
            val x = s * rnd(0.08f, 0.85f); val w = s * rnd(0.07f, 0.11f); val h = w * rnd(0.7f, 1.0f); val base = hy + s * rnd(-0.06f, 0.02f)
            wobble(listOf(x to base, x to base - h, x + w to base - h, x + w to base, x to base), ink, wob)
            wobble(listOf(x - w * 0.1f to base - h, x + w / 2 to base - h - w * 0.55f, x + w * 1.1f to base - h), ink, wob)
            wobble(listOf(x + w * 0.35f to base, x + w * 0.35f to base - h * 0.5f, x + w * 0.6f to base - h * 0.5f, x + w * 0.6f to base), thin, wob)
        }
        // 나무
        val tx = s * rnd(0.1f, 0.9f)
        wobble(listOf(tx to hy + s * 0.02f, tx to hy - s * 0.1f), ink, wob)
        wobbleCircle(tx, hy - s * 0.15f, s * 0.06f, ink, wob * 2)
    } else {
        // 가로등이 선 길(소실점)
        val vx = s * rnd(0.35f, 0.65f); val vy = s * rnd(0.42f, 0.52f)
        c.drawPath(Path().apply { moveTo(s * 0.05f, s); lineTo(vx, vy); lineTo(s * 0.95f, s); close() }, fill(alpha(washes[0], 0.35f)))
        wobble(listOf(s * 0.05f to s, vx to vy), ink, wob)
        wobble(listOf(s * 0.95f to s, vx to vy), ink, wob)
        wobble(listOf(0f to vy, s to vy), thin, wob)
        repeat(4) { k ->
            val t = 1f - k / 4.5f
            val x = vx + (s * 0.85f - vx) * t; val yb = vy + (s * 0.95f - vy) * t; val h = s * 0.45f * t
            wobble(listOf(x to yb, x to yb - h, x - s * 0.05f * t to yb - h), ink, wob)
            blurDisc(x - s * 0.05f * t, yb - h + 6 * px, s * 0.06f * t, alpha(argb(0xFFF2B84B), 0.75f), 24f * px)
        }
    }
    // 해·달
    wobbleCircle(s * rnd(0.6f, 0.85f), s * rnd(0.14f, 0.26f), s * rnd(0.04f, 0.07f), ink, wob)
    grain(0.03f, 0.05f, 1.2f)
}

/** 빈티지 편집 디자인: 차분한 색면 구성, 인쇄 종이 */
private fun Art.editorial() {
    val palettes = listOf(
        listOf(argb(0xFFE3D5B8), argb(0xFFC9573C), argb(0xFF2B3442), argb(0xFF7A8C6A)),
        listOf(argb(0xFF2F4A3A), argb(0xFFE8D9B5), argb(0xFFD9A441), argb(0xFF9E3D2B)),
        listOf(argb(0xFF2B3442), argb(0xFFD9CBB0), argb(0xFFC9573C), argb(0xFF6F8FA6)),
        listOf(argb(0xFFD8C7A6), argb(0xFF3E5C76), argb(0xFFB8452F), argb(0xFF1F2A2E)),
    )
    val p = palettes[Math.floorMod(b.variant + r.nextInt(2), palettes.size)]
    paper(p[0])
    when (layout(3)) {
        0 -> { // 나란한 아치들
            val n = 3 + (b.density * 2).toInt()
            val w = s * 0.76f / n
            repeat(n) { k ->
                val x = s * 0.12f + k * w; val top = s * (0.25f + 0.3f * contour(k)); val col = fill(p[1 + k % 3])
                c.drawRect(x + w * 0.08f, top + w * 0.42f, x + w * 0.92f, s * 0.86f, col)
                c.drawArc(RectF(x + w * 0.08f, top, x + w * 0.92f, top + w * 0.84f), 180f, 180f, true, col)
            }
        }
        1 -> { // 사분원 격자
            val n = 3
            val cell = s * 0.76f / n
            for (i in 0 until n) for (j in 0 until n) {
                val x = s * 0.12f + i * cell; val y = s * 0.12f + j * cell
                val col = p[1 + (i + j + r.nextInt(2)) % 3]
                val start = 90f * r.nextInt(4)
                val (ax, ay) = when (start.toInt()) { 0 -> x to y; 90 -> x + cell to y; 180 -> x + cell to y + cell; else -> x to y + cell }
                c.save(); c.clipRect(x, y, x + cell, y + cell)
                c.drawCircle(ax, ay, cell * (0.6f + 0.4f * contour(i * 3 + j)), fill(col))
                c.restore()
            }
        }
        else -> { // 대각선 띠 구성
            c.save(); c.rotate(rnd(-38f, -22f), s / 2, s / 2)
            repeat(5) { k ->
                val y = s * (0.1f + k * 0.2f); val h = s * (0.05f + 0.1f * contour(k))
                c.drawRect(-s * 0.3f, y, s * 1.3f, y + h, fill(p[1 + k % 3]))
            }
            c.restore()
            c.drawCircle(s * rnd(0.15f, 0.85f), s * rnd(0.15f, 0.85f), s * 0.05f, fill(p[3]))
        }
    }
    cloud(alpha(argb(0xFFFFFFFF), 0.08f), 9)
    grain(0.04f, 0.07f, 1.3f)
}

// =====================================================================
//  CITY POP
// =====================================================================

/** 80년대 레트로: 줄무늬 해가 바다로 지는 장면, 야자수 실루엣 */
private fun Art.sunsetStripes() {
    val skies = listOf(
        Triple(argb(0xFF2E5C8A), argb(0xFFFF8E72), argb(0xFFFFD29D)),
        Triple(argb(0xFF1C7C8C), argb(0xFFFFA36C), argb(0xFFFFE3A3)),
        Triple(argb(0xFF2A4D7A), argb(0xFFFF7A7A), argb(0xFFFFC48C)),
    )
    val sk = pick(skies)
    val horizon = s * rnd(0.6f, 0.7f)
    vGradient(sk.first, sk.third, 0f, horizon, sk.second)
    val sunX = s * rnd(0.35f, 0.65f); val sunR = s * rnd(0.2f, 0.27f); val sunY = horizon - sunR * rnd(0.2f, 0.6f)
    val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, sunY - sunR, 0f, sunY + sunR, intArrayOf(argb(0xFFFFE66D), argb(0xFFFF7B54)), null, Shader.TileMode.CLAMP)
    }
    c.save(); c.clipRect(0f, 0f, s, horizon)
    c.drawCircle(sunX, sunY, sunR, sun)
    // 아래쪽으로 갈수록 두꺼워지는 줄무늬
    val gap = fill(sk.second)
    repeat(6) { k ->
        val y = sunY + sunR * (0.05f + k * 0.16f)
        c.drawRect(sunX - sunR, y, sunX + sunR, y + sunR * (0.02f + k * 0.018f), gap)
    }
    c.restore()
    // 바다 + 반사
    vGradient(argb(0xFF1D4E6E), argb(0xFF0F2A40), horizon, s)
    val refl = fill(alpha(argb(0xFFFFB36B), 0.8f))
    repeat(14) { k ->
        val y = horizon + s * 0.014f + k * s * 0.024f
        val w = sunR * 1.6f * (1 - k / 16f) * rnd(0.6f, 1f)
        c.drawRect(sunX - w / 2, y, sunX + w / 2, y + s * 0.007f, refl)
    }
    // 야자수, 아니면 먼 섬 실루엣
    if (r.nextFloat() >= 0.6f) {
        val isle = Path().apply {
            val x0 = s * rnd(-0.1f, 0.4f); val w = s * rnd(0.35f, 0.6f)
            moveTo(x0, horizon); cubicTo(x0 + w * 0.2f, horizon - s * 0.12f, x0 + w * 0.5f, horizon - s * rnd(0.08f, 0.2f), x0 + w, horizon); close()
        }
        c.drawPath(isle, fill(argb(0xFF1A2E48)))
    } else repeat(1 + r.nextInt(2)) { k ->
        val tx = if (k == 0) s * rnd(0.05f, 0.25f) else s * rnd(0.75f, 0.95f)
        val dark = fill(argb(0xFF14213A))
        val trunk = stroke(argb(0xFF14213A), 14f * px)
        val topX = tx + s * rnd(-0.06f, 0.06f); val topY = s * rnd(0.22f, 0.35f)
        c.drawPath(Path().apply { moveTo(tx, s); quadTo(tx + (topX - tx) * 0.3f + s * 0.04f, (s + topY) / 2, topX, topY) }, trunk)
        repeat(7) { f ->
            val ang = -PI.toFloat() * (0.1f + f * 0.13f)
            val len = s * rnd(0.12f, 0.18f)
            val ex = topX + cos(ang) * len; val ey = topY + sin(ang) * len * 0.5f + len * 0.35f
            c.drawPath(Path().apply { moveTo(topX, topY); quadTo((topX + ex) / 2, topY + sin(ang) * len * 0.6f, ex, ey); quadTo((topX + ex) / 2 + 10 * px, topY + sin(ang) * len * 0.4f, topX, topY + 8 * px); close() }, dark)
        }
    }
    grain(0.03f, 0.06f, 1.2f)
}

/** 네온 시티: 밤 스카이라인, 불 켜진 창, 물에 비친 네온 */
private fun Art.neonCity() {
    val horizon = s * rnd(0.6f, 0.68f)
    vGradient(argb(0xFF0D1A2C), argb(0xFF1F5568), 0f, horizon, argb(0xFF173650))
    glow(s * rnd(0.3f, 0.7f), horizon, s * 0.6f, alpha(argb(0xFFFF7A8A), 0.35f))
    if (r.nextBoolean()) blurDisc(s * rnd(0.15f, 0.85f), s * rnd(0.12f, 0.25f), s * 0.035f, argb(0xFFF5F0DC), 6f * px)
    val city = Bitmap.createBitmap(s.toInt(), horizon.toInt() + 1, Bitmap.Config.ARGB_8888)
    val cc = Canvas(city)
    val layers = listOf(argb(0xFF20405A), argb(0xFF16304A), argb(0xFF0C1C2E))
    val windows = listOf(argb(0xFFFFD27A), argb(0xFF8FE6F2), argb(0xFFFFB0A0))
    layers.forEachIndexed { li, col ->
        var x = -10f
        var i = li * 3
        while (x < s) {
            val w = s * rnd(0.05f, 0.11f)
            val h = s * (0.12f + 0.32f * contour(i) * (0.6f + li * 0.2f)) * rnd(0.7f, 1.2f) * (0.7f + b.density * 0.5f)
            cc.drawRect(x, horizon - h, x + w + 1, horizon + 1, fill(col))
            if (li > 0) {
                val wp = fill(alpha(pick(windows), 0.85f))
                var wy = horizon - h + s * 0.02f
                while (wy < horizon - s * 0.02f) {
                    var wx = x + s * 0.01f
                    while (wx < x + w - s * 0.015f) {
                        if (r.nextFloat() < 0.32f) cc.drawRect(wx, wy, wx + s * 0.008f, wy + s * 0.011f, wp)
                        wx += s * 0.017f
                    }
                    wy += s * 0.022f
                }
            }
            x += w + s * rnd(0f, 0.01f); i++
        }
    }
    c.drawBitmap(city, 0f, 0f, null)
    // 네온 띠
    repeat(2 + r.nextInt(2)) {
        val y = horizon - s * rnd(0.05f, 0.3f); val x0 = s * rnd(0f, 0.6f); val len = s * rnd(0.1f, 0.3f)
        val col = pick(listOf(argb(0xFFFF5C8A), argb(0xFF4DE1F0), argb(0xFFFFC45C)))
        c.drawRect(x0, y, x0 + len, y + 5 * px, fill(col).apply { maskFilter = BlurMaskFilter(10f * px, BlurMaskFilter.Blur.SOLID) })
    }
    // 물 + 반사(뒤집어 흐리게, 물결 틈)
    vGradient(argb(0xFF0B1826), argb(0xFF06101A), horizon, s)
    c.save(); c.clipRect(0f, horizon, s, s)
    c.scale(1f, -0.8f, 0f, horizon)
    c.drawBitmap(city, 0f, 0f, Paint().apply { alpha = 90 })
    c.restore()
    val ripple = fill(argb(0xFF06101A))
    var y = horizon + 4 * px
    while (y < s) { c.drawRect(0f, y, s, y + 2.5f * px, ripple); y += s * rnd(0.008f, 0.02f) }
    city.recycle()
    grain(0.04f, 0.07f, 1.3f)
    vignette(0.4f)
}

/** 대담한 기하 구성: 파스텔 바탕 + 선명한 도형 + 딱딱한 그림자 */
private fun Art.geometricPop() {
    val bg = pick(listOf(argb(0xFFBFE8D9), argb(0xFFFFD6BA), argb(0xFFFFF1B5), argb(0xFFBEE3F8)))
    c.drawColor(bg)
    val vivid = listOf(argb(0xFFFF6B6B), argb(0xFF2851A3), argb(0xFFFFC233), argb(0xFF19A7A0), argb(0xFF1B1B1B), argb(0xFFFF8FA3))
    val shadow = fill(alpha(argb(0xFF1B1B1B), 0.85f))
    val sh = 10f * px
    val grid = 4
    val cell = s / grid
    // 바탕의 큰 형태 하나(구도의 축)
    val big = fill(pick(vivid.filter { it != argb(0xFF1B1B1B) }))
    when (r.nextInt(3)) {
        0 -> c.drawArc(RectF(-s * 0.2f, s * 0.45f, s * 1.2f, s * 1.6f), 180f, 180f, true, big)
        1 -> c.drawRect(0f, s * rnd(0.55f, 0.7f), s, s, big)
        else -> c.drawCircle(s * rnd(0.3f, 0.7f), s * rnd(0.3f, 0.7f), s * rnd(0.28f, 0.36f), big)
    }
    val cells = (0 until grid * grid).shuffled(r).take(6 + (b.density * 5).toInt())
    cells.forEachIndexed { n, idx ->
        val cx = (idx % grid + 0.5f) * cell + rnd(-0.2f, 0.2f) * cell
        val cy = (idx / grid + 0.5f) * cell + rnd(-0.2f, 0.2f) * cell
        val size = cell * (0.6f + 0.45f * contour(n))
        val col = fill(pick(vivid))
        when (r.nextInt(5)) {
            0 -> { c.drawCircle(cx + sh, cy + sh, size / 2, shadow); c.drawCircle(cx, cy, size / 2, col) }
            1 -> {
                val tri = Path().apply { moveTo(cx, cy - size / 2); lineTo(cx + size / 2, cy + size / 2); lineTo(cx - size / 2, cy + size / 2); close() }
                c.save(); c.translate(sh, sh); c.drawPath(tri, shadow); c.restore(); c.drawPath(tri, col)
            }
            2 -> { c.drawRect(cx - size / 2 + sh, cy - size / 3 + sh, cx + size / 2 + sh, cy + size / 3 + sh, shadow); c.drawRect(cx - size / 2, cy - size / 3, cx + size / 2, cy + size / 3, col) }
            3 -> { // 물결선
                val path = Path().apply { moveTo(cx - size / 2, cy); for (k in 1..24) { val u = k / 24f; lineTo(cx - size / 2 + size * u, cy + sin(u * 4 * PI.toFloat()) * size * 0.12f) } }
                c.save(); c.translate(sh * 0.6f, sh * 0.6f); c.drawPath(path, stroke(alpha(argb(0xFF1B1B1B), 0.85f), 14f * px)); c.restore()
                c.drawPath(path, stroke(col.color, 14f * px))
            }
            else -> { // 점 격자
                val dot = fill(col.color)
                for (i in 0 until 5) for (j in 0 until 5) c.drawCircle(cx - size / 2 + i * size / 4, cy - size / 2 + j * size / 4, 6 * px, dot)
            }
        }
    }
    grain(0.02f, 0.05f, 1.2f)
}

// =====================================================================
//  AMBIENT
// =====================================================================

/** 안개 낀 겹산: 대기 원근, 차분한 색 */
private fun Art.fogLandscape() {
    val (top, bottom) = sky()
    val muted = pick(listOf(argb(0xFFB9BEC0), argb(0xFFC9BBA6), argb(0xFFA9BDB6), argb(0xFFAFC0D4)))
    val skyTop = mix(top, muted, rnd(0.35f, 0.6f)); val skyBot = mix(bottom, mix(argb(0xFFE8E6E0), muted, 0.3f), 0.5f)
    val pines = r.nextFloat() < 0.5f
    vGradient(skyTop, skyBot)
    if (r.nextBoolean()) blurDisc(s * rnd(0.2f, 0.8f), s * rnd(0.2f, 0.35f), s * rnd(0.05f, 0.09f), alpha(argb(0xFFFFF6E6), 0.8f), 30f * px)
    val dark = mix(top, argb(0xFF1C2328), 0.75f)
    val n = 4 + (b.density * 3).toInt()
    for (k in 0 until n) {
        val t = k / (n - 1f)
        val base = s * (0.42f + 0.5f * t)
        val col = mix(skyBot, dark, 0.15f + 0.8f * t * t)
        c.drawPath(ridge(base, s * (0.1f + 0.08f * (1 - t)), 1f + k * 0.6f, 0.4f + (1 - b.calm), k * 2.1f + r.nextFloat() * 4), fill(col))
        // 앞쪽 능선엔 침엽수 숲(있을 때만)
        if (pines && k >= n - 2) {
            val tree = fill(col)
            var x = rnd(0f, 20f) * px
            while (x < s) {
                val h = s * rnd(0.04f, 0.09f) * (0.6f + t * 0.6f); val w = h * 0.38f; val y = base - s * 0.02f + rnd(-8f, 8f) * px
                c.drawPath(Path().apply { moveTo(x, y - h); lineTo(x + w, y + 2 * px); lineTo(x - w, y + 2 * px); close() }, tree)
                x += w * rnd(0.9f, 1.8f)
            }
        }
        // 능선 사이 안개 띠
        vGradient(alpha(skyBot, 0f), alpha(skyBot, 0.55f * (1 - t * 0.5f)), base - s * 0.08f, base + s * 0.02f)
    }
    grain(0.03f, 0.05f, 1.2f)
}

/** 장노출 수평선: 넓은 여백, 작은 해, 잔잔한 물 */
private fun Art.stillHorizon() {
    val (top, bottom) = sky()
    val soft = argb(0xFFDCDDD8)
    val horizon = s * pick(listOf(0.5f, 0.62f, 0.72f, 0.38f))
    vGradient(mix(top, soft, 0.4f), mix(bottom, soft, 0.35f), 0f, horizon)
    vGradient(mix(bottom, argb(0xFF8A9499), 0.5f), mix(top, argb(0xFF2A3236), 0.55f), horizon, s)
    val sunX = s * rnd(0.2f, 0.8f)
    blurDisc(sunX, horizon - s * rnd(0.04f, 0.16f), s * rnd(0.015f, 0.03f), alpha(argb(0xFFFFF4E0), 0.95f), 4f * px)
    glow(sunX, horizon, s * 0.4f, alpha(argb(0xFFFFE6C8), 0.25f))
    // 장노출 물결: 아주 옅은 가로줄
    val streak = stroke(alpha(argb(0xFFFFFFFF), 0.05f), 2f * px)
    repeat(40) { val y = horizon + r.nextFloat() * (s - horizon); c.drawLine(0f, y, s, y, streak) }
    c.drawRect(0f, horizon - 0.8f * px, s, horizon + 0.8f * px, fill(alpha(argb(0xFFFFFFFF), 0.25f)))
    // 먼 바위 하나(있을 때만)
    if (r.nextFloat() < 0.5f) {
        val rx = s * rnd(0.1f, 0.9f); val rw = s * rnd(0.03f, 0.07f)
        c.drawArc(RectF(rx - rw, horizon - rw * 0.6f, rx + rw, horizon + rw * 0.6f), 180f, 180f, true, fill(mix(top, argb(0xFF20262A), 0.7f)))
    }
    grain(0.025f, 0.04f, 1.1f)
}

/** 부드러운 유기 형태: 크게 번진 색면 + 가는 곡선 하나 */
private fun Art.organicForms() {
    val palettes = listOf(
        listOf(argb(0xFFE9E4DA), argb(0xFFB7C4B0), argb(0xFFD9B99B), argb(0xFF9FB4C2)),
        listOf(argb(0xFFDDE3E3), argb(0xFFA9C2C9), argb(0xFFE7CFC0), argb(0xFFC8D1B6)),
        listOf(argb(0xFF2E4A4F), argb(0xFF6FA3A0), argb(0xFFE0B98F), argb(0xFF9CC5B5)),
    )
    val p = pick(palettes)
    c.drawColor(p[0])
    repeat(5 + (b.density * 3).toInt()) { i ->
        blurDisc(s * rnd(0f, 1f), s * rnd(0f, 1f), s * rnd(0.16f, 0.32f), alpha(p[1 + i % 3], rnd(0.7f, 0.95f)), s * 0.07f)
    }
    val darkBase = p[0] == palettes[2][0]
    val line = stroke(if (darkBase) alpha(argb(0xFFF2EDE4), 0.6f) else alpha(mix(p[0], argb(0xFF000000), 0.55f), 0.6f), 3f * px)
    val path = Path().apply {
        moveTo(s * rnd(0f, 0.2f), s * rnd(0.3f, 0.7f))
        cubicTo(s * rnd(0.2f, 0.4f), s * rnd(0f, 1f), s * rnd(0.6f, 0.8f), s * rnd(0f, 1f), s * rnd(0.8f, 1f), s * rnd(0.3f, 0.7f))
    }
    c.drawPath(path, line)
    grain(0.04f, 0.05f, 1.2f)
}

// =====================================================================
//  HIP-HOP
// =====================================================================

/** 실험적 콜라주: 찢은 종이, 복사기 망점, 테이프 */
private fun Art.collage() {
    val dark = r.nextBoolean()
    c.drawColor(if (dark) argb(0xFF141414) else argb(0xFFE9E2D3))
    cloud(alpha(if (dark) argb(0xFFFFFFFF) else argb(0xFF000000), 0.07f), 10)
    val accents = listOf(argb(0xFFE63B2E), argb(0xFFF2C230), argb(0xFF1F4BC0), argb(0xFF2E9E6A))
    val accent = pick(accents)
    val rot = 0.25f + 0.5f * b.tension
    repeat(5 + (b.density * 4).toInt()) { i ->
        val path = torn(s * rnd(0.15f, 0.85f), s * rnd(0.15f, 0.85f), s * rnd(0.25f, 0.55f), s * rnd(0.18f, 0.45f), rnd(-rot, rot), 10f * px)
        when (i % 4) {
            0 -> { // 망점 '사진'
                c.drawPath(path, fill(argb(0xFFD8D4CC)))
                val cx = rnd(0.2f, 0.8f); val cy = rnd(0.2f, 0.8f)
                halftone(path, 11f * px, argb(0xFF1A1A1A)) { x, y -> 1f - sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) * 2.2f + contour(i) * 0.3f }
            }
            1 -> c.drawPath(path, fill(accent))
            2 -> { // 신문지 같은 가는 줄(글자 아님)
                c.drawPath(path, fill(argb(0xFFF4F0E6)))
                c.save(); c.clipPath(path)
                val ln = stroke(alpha(argb(0xFF333333), 0.35f), 2f * px)
                var y = 0f
                while (y < s) { c.drawLine(0f, y, s, y + rnd(-2f, 2f) * px, ln); y += 9f * px }
                c.restore()
            }
            else -> c.drawPath(path, fill(if (dark) argb(0xFFF4F0E6) else argb(0xFF141414)))
        }
    }
    // 테이프
    repeat(2 + r.nextInt(3)) {
        c.save(); c.rotate(rnd(-40f, 40f), s / 2, s / 2)
        val x = s * rnd(0.1f, 0.8f); val y = s * rnd(0.1f, 0.9f)
        c.drawRect(x, y, x + s * 0.18f, y + s * 0.05f, fill(alpha(argb(0xFFEDE3C4), 0.6f)))
        c.restore()
    }
    grain(0.08f, 0.12f, 1.8f)
}

/** 스트리트 그래픽: 큰 덩어리, 스프레이 입자, 흘러내림 */
private fun Art.streetGraphic() {
    val concrete = r.nextBoolean()
    c.drawColor(if (concrete) argb(0xFF8C8A85) else argb(0xFF101010))
    cloud(alpha(argb(0xFF000000), if (concrete) 0.25f else 0.1f), 14)
    cloud(alpha(argb(0xFFFFFFFF), 0.12f), 40)
    val accent = pick(listOf(argb(0xFFFF4F1F), argb(0xFFFFD21F), argb(0xFF1FA8FF), argb(0xFF39D98A)))
    val ink = if (concrete) argb(0xFF111111) else argb(0xFFF2F0EA)
    // 큰 형태: 두꺼운 고리 또는 겹친 블록
    if (r.nextBoolean()) {
        val cx = s * rnd(0.35f, 0.65f); val cy = s * rnd(0.4f, 0.6f); val rad = s * rnd(0.22f, 0.32f)
        c.drawCircle(cx, cy, rad, stroke(ink, s * 0.11f).apply { strokeCap = Paint.Cap.BUTT })
        c.drawCircle(cx + s * 0.05f, cy - s * 0.04f, rad * 0.45f, fill(accent))
    } else {
        repeat(3) { k ->
            val x = s * rnd(0.05f, 0.5f); val y = s * (0.15f + k * 0.22f)
            c.drawRect(x, y, x + s * rnd(0.35f, 0.6f) * (0.6f + contour(k)), y + s * 0.14f, fill(if (k == 1) accent else ink))
        }
    }
    // 스프레이 입자: 가장자리에서 흩날림
    val spray = FloatArray(9000)
    var n = 0
    val sx = s * rnd(0.2f, 0.8f); val sy = s * rnd(0.2f, 0.8f)
    while (n < spray.size) {
        val ang = r.nextFloat() * 2 * PI.toFloat(); val d = abs((r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f)) * s * 0.35f
        spray[n++] = sx + cos(ang) * d; spray[n++] = sy + sin(ang) * d
    }
    c.drawPoints(spray, Paint().apply { color = alpha(accent, 0.6f); strokeWidth = 2.2f * px; strokeCap = Paint.Cap.ROUND })
    // 흘러내림
    val drip = stroke(alpha(ink, 0.9f), 5f * px)
    repeat(4 + (b.tension * 6).toInt()) {
        val x = s * rnd(0.1f, 0.9f); val y = s * rnd(0.35f, 0.7f); val len = s * rnd(0.05f, 0.25f)
        c.drawLine(x, y, x, y + len, drip)
        c.drawCircle(x, y + len, 5f * px, fill(drip.color))
    }
    grain(0.06f, 0.12f, 1.6f)
    vignette(0.35f)
}

/** 하드 라이트 흑백 사진: 콘크리트 벽에 떨어진 창빛, 깊은 그림자 */
private fun Art.hardLight() {
    val warm = r.nextBoolean()
    vGradient(argb(0xFF1A1A1A), argb(0xFF2B2B2B))
    cloud(alpha(argb(0xFFFFFFFF), 0.08f), 24)
    // 창빛: 기울어진 평행사변형 여러 개(창살 그림자 사이)
    val light = if (warm) argb(0xFFF1E6D2) else argb(0xFFE6EAEE)
    val skew = s * rnd(0.15f, 0.35f) * (if (r.nextBoolean()) 1 else -1)
    val x0 = s * rnd(0.1f, 0.4f); val y0 = s * rnd(0.1f, 0.25f)
    val cols = 2 + r.nextInt(2); val rows = 2 + r.nextInt(2)
    val pw = s * rnd(0.12f, 0.18f); val ph = s * rnd(0.14f, 0.2f); val bar = s * 0.018f
    val paint = fill(light).apply {
        maskFilter = BlurMaskFilter(6f * px * (0.5f + jitter), BlurMaskFilter.Blur.NORMAL)
        shader = LinearGradient(0f, y0, 0f, y0 + s * 0.7f, intArrayOf(light, alpha(light, 0.35f)), null, Shader.TileMode.CLAMP)
    }
    if (r.nextFloat() < 0.45f) {
        // 블라인드 줄빛
        val slats = 7 + r.nextInt(5)
        val top = s * rnd(0.12f, 0.3f); val left = s * rnd(0.05f, 0.35f); val w = s * rnd(0.45f, 0.65f); val gapH = s * rnd(0.022f, 0.035f)
        repeat(slats) { k ->
            val y = top + k * gapH * 1.7f
            val path = Path().apply { moveTo(left + skew * (y / s), y); lineTo(left + w + skew * (y / s), y); lineTo(left + w + skew * ((y + gapH) / s), y + gapH); lineTo(left + skew * ((y + gapH) / s), y + gapH); close() }
            c.drawPath(path, paint)
        }
    } else for (i in 0 until cols) for (j in 0 until rows) {
        val x = x0 + i * (pw + bar); val y = y0 + j * (ph + bar)
        val path = Path().apply {
            moveTo(x + skew * (y / s), y); lineTo(x + pw + skew * (y / s), y)
            lineTo(x + pw + skew * ((y + ph) / s), y + ph); lineTo(x + skew * ((y + ph) / s), y + ph); close()
        }
        c.drawPath(path, paint)
    }
    // 잎 그림자
    if (r.nextFloat() < 0.6f) repeat(6) {
        val lx = x0 + rnd(0f, 0.4f) * s; val ly = y0 + rnd(0f, 0.4f) * s
        c.save(); c.rotate(rnd(0f, 180f), lx, ly)
        c.drawOval(RectF(lx - s * 0.06f, ly - s * 0.02f, lx + s * 0.06f, ly + s * 0.02f), fill(alpha(argb(0xFF161616), 0.85f)).apply { maskFilter = BlurMaskFilter(8f * px, BlurMaskFilter.Blur.NORMAL) })
        c.restore()
    }
    // 바닥선
    c.drawRect(0f, s * rnd(0.78f, 0.88f), s, s, fill(alpha(argb(0xFF0E0E0E), 0.85f)))
    glow(s * 0.5f, s * 0.4f, s * 0.7f, alpha(light, 0.08f))
    grain(0.12f, 0.12f, 1.8f)
    vignette(0.55f)
}

@Suppress("unused")
private fun clamp01(v: Float) = min(1f, kotlin.math.max(0f, v))

// =====================================================================
//  화풍 확장(어느 무드에서도 나올 수 있다)
// =====================================================================

/** 수채: 젖은 번짐을 여러 겹, 테두리에 안료가 모이고 종이가 비친다. 선은 쓰지 않는다 */
private fun Art.watercolor() {
    paper(argb(0xFFF6F2EA))
    val palettes = listOf(
        listOf(argb(0xFF6E9FBF), argb(0xFF9DBF8E), argb(0xFFE0B482)),
        listOf(argb(0xFFD98C7A), argb(0xFFE8C16C), argb(0xFF7FA6A0)),
        listOf(argb(0xFF4F7CA0), argb(0xFF8EA8C3), argb(0xFFC9B8A0)),
        listOf(seasonTint(), argb(0xFF86A87C), argb(0xFF5F86A8)),
    )
    val p = palettes[Math.floorMod(b.variant + r.nextInt(4), palettes.size)]
    fun wash(path: Path, col: Int, a: Float) {
        c.drawPath(path, fill(alpha(col, a)).apply { maskFilter = BlurMaskFilter(6f * px, BlurMaskFilter.Blur.NORMAL) })
        c.drawPath(path, stroke(alpha(mix(col, argb(0xFF000000), 0.25f), a * 0.6f), 3f * px).apply { maskFilter = BlurMaskFilter(3f * px, BlurMaskFilter.Blur.NORMAL) })
    }
    if (layout(2) == 0) {
        // 겹친 언덕 번짐
        repeat(4) { k ->
            val path = ridge(s * (0.45f + k * 0.14f), s * 0.14f, 0.8f + k * 0.5f, 0.8f, k * 1.9f + rnd(0f, 5f))
            wash(path, p[k % 3], 0.32f + k * 0.06f)
        }
        vGradient(alpha(p[0], 0.25f), alpha(p[0], 0f), 0f, s * 0.4f)
    } else {
        // 꽃·잎 같은 덩어리 번짐
        repeat(7 + (b.density * 6).toInt()) { i ->
            val cx = s * rnd(0.12f, 0.88f); val cy = s * rnd(0.12f, 0.88f); val rad = s * rnd(0.06f, 0.16f)
            val path = Path().apply {
                for (k in 0..20) {
                    val a = k / 20f * 2 * PI.toFloat(); val rr = rad * (0.8f + 0.35f * r.nextFloat())
                    if (k == 0) moveTo(cx + cos(a) * rr, cy + sin(a) * rr) else lineTo(cx + cos(a) * rr, cy + sin(a) * rr)
                }
                close()
            }
            wash(path, p[i % 3], rnd(0.3f, 0.5f))
        }
    }
    // 안료 알갱이
    grain(0.03f, 0.07f, 1.6f)
}

/** 유화: 밑칠 위에 두꺼운 붓 자국(방향·색이 조금씩 다른 짧은 획) */
private fun Art.oilPainting() {
    val (top, bottom) = sky()
    val palettes = listOf(
        listOf(argb(0xFF2F4858), argb(0xFF86BBD8), argb(0xFFF6AE2D), argb(0xFFF26419)),
        listOf(argb(0xFF3D405B), argb(0xFF81B29A), argb(0xFFF2CC8F), argb(0xFFE07A5F)),
        listOf(argb(0xFF283618), argb(0xFF606C38), argb(0xFFDDA15E), argb(0xFFBC6C25)),
        listOf(mix(top, argb(0xFF20242A), 0.3f), mix(bottom, argb(0xFFFFFFFF), 0.1f), seasonTint(), argb(0xFF1E3A5F)),
    )
    val p = palettes[Math.floorMod(b.variant + r.nextInt(4), palettes.size)]
    val landscape = layout(2) == 0
    vGradient(p[0], if (landscape) p[2] else p[1])
    val strokes = (2600 * (0.6f + b.density * 0.6f)).toInt()
    val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    repeat(strokes) {
        val x = r.nextFloat() * s; val y = r.nextFloat() * s
        val u = y / s
        val base = if (landscape) {
            // 하늘(위) → 땅(아래) 띠, 경계는 멜로디 윤곽
            val edge = 0.45f + 0.2f * contour((x / s * 7).toInt())
            if (u < edge) mix(p[1], p[0], u / edge) else mix(p[2], p[3], (u - edge) / (1 - edge))
        } else {
            p[((x / s * 2 + u * 2 + r.nextFloat() * 1.2f).toInt()) % 4]
        }
        val col = mix(base, if (r.nextBoolean()) argb(0xFFFFFFFF) else argb(0xFF000000), r.nextFloat() * 0.18f)
        val ang = if (landscape) rnd(-0.35f, 0.35f) + (if (u > 0.5f) 0f else 0.15f) else (x / s + u) * 3f + rnd(-0.6f, 0.6f)
        val len = s * rnd(0.02f, 0.06f) * (1.3f - b.calm * 0.4f)
        pt.color = alpha(col, rnd(0.75f, 0.95f)); pt.strokeWidth = s * rnd(0.008f, 0.02f)
        c.drawLine(x, y, x + cos(ang) * len, y + sin(ang) * len, pt)
    }
    grain(0.02f, 0.05f, 1.2f)
}

/** 컬러 필드: 은은하게 빛나는 색 사각형 2~3개 */
private fun Art.colorField() {
    val palettes = listOf(
        listOf(argb(0xFF5B1F1A), argb(0xFFD9653B), argb(0xFFF0C27B), argb(0xFF2B1210)),
        listOf(argb(0xFF16302B), argb(0xFF3E7B6B), argb(0xFFC9D6B8), argb(0xFF0D1F1C)),
        listOf(argb(0xFF1D2A3A), argb(0xFF4A6FA5), argb(0xFFE6C79C), argb(0xFF101823)),
        listOf(argb(0xFF3A2E1E), argb(0xFFB8893F), argb(0xFFE8DFC8), argb(0xFF1F190F)),
    )
    val p = palettes[Math.floorMod(b.variant + r.nextInt(4), palettes.size)]
    c.drawColor(p[0])
    cloud(alpha(p[3], 0.5f), 6)
    val n = 2 + (if (b.density > 0.5f) 1 else 0)
    var y = s * 0.08f
    val gap = s * 0.04f
    val totalH = s * 0.84f - gap * (n - 1)
    repeat(n) { k ->
        val h = totalH * (if (n == 2) (if (k == 0) 0.6f else 0.4f) else listOf(0.3f, 0.45f, 0.25f)[k]) * (0.85f + 0.3f * contour(k))
        val rect = RectF(s * 0.1f, y, s * 0.9f, (y + h).coerceAtMost(s * 0.92f))
        c.drawRect(rect, fill(alpha(p[1 + k % 2], 0.92f)).apply { maskFilter = BlurMaskFilter(s * 0.02f, BlurMaskFilter.Blur.NORMAL) })
        y = rect.bottom + gap
    }
    grain(0.03f, 0.05f, 1.3f)
    vignette(0.3f)
}

/** 초현실: 고요한 들판 위 떠 있는 것(돌·문·계단·섬)과 긴 그림자 */
private fun Art.surreal() {
    val (top, bottom) = sky()
    val soft = argb(0xFFE7DDD0)
    val horizon = s * rnd(0.6f, 0.72f)
    vGradient(mix(top, soft, 0.35f), mix(bottom, soft, 0.25f), 0f, horizon)
    val ground = mix(argb(0xFF8C7B64), seasonTint(), 0.2f)
    vGradient(mix(ground, soft, 0.2f), mix(ground, argb(0xFF2A241D), 0.5f), horizon, s)
    val cx = s * rnd(0.3f, 0.7f); val cy = horizon - s * rnd(0.22f, 0.34f)
    val shadow = fill(alpha(argb(0xFF1C1814), 0.35f)).apply { maskFilter = BlurMaskFilter(14f * px, BlurMaskFilter.Blur.NORMAL) }
    val obj = argb(0xFF3B342D)
    c.drawOval(RectF(cx - s * 0.16f, horizon + s * 0.06f, cx + s * 0.22f, horizon + s * 0.11f), shadow)
    when (layout(4)) {
        0 -> { // 떠 있는 매끈한 돌
            val rect = RectF(cx - s * 0.13f, cy - s * 0.09f, cx + s * 0.13f, cy + s * 0.09f)
            c.drawOval(rect, fill(obj))
            c.drawOval(RectF(rect.left + s * 0.03f, rect.top + s * 0.015f, rect.right - s * 0.08f, rect.centerY()), fill(alpha(argb(0xFFFFFFFF), 0.12f)))
        }
        1 -> { // 홀로 선 문
            val w = s * 0.14f; val h = s * 0.3f
            c.drawRect(cx - w / 2, horizon - h, cx + w / 2, horizon, fill(obj))
            c.drawRect(cx - w / 2 + s * 0.02f, horizon - h + s * 0.02f, cx + w / 2 - s * 0.02f, horizon, fill(mix(top, soft, 0.5f)))
        }
        2 -> { // 하늘로 이어지는 계단
            repeat(7) { k ->
                val x = cx - s * 0.2f + k * s * 0.06f; val y = horizon - k * s * 0.05f
                c.drawRect(x, y - s * 0.05f, x + s * 0.06f, y, fill(mix(obj, soft, k * 0.06f)))
            }
        }
        else -> { // 물 위에 떠 있는 작은 섬
            val isl = Path().apply { moveTo(cx - s * 0.16f, cy); lineTo(cx + s * 0.16f, cy); lineTo(cx + s * 0.04f, cy + s * 0.14f); lineTo(cx - s * 0.06f, cy + s * 0.1f); close() }
            c.drawPath(isl, fill(obj))
            c.drawOval(RectF(cx - s * 0.17f, cy - s * 0.03f, cx + s * 0.17f, cy + s * 0.02f), fill(mix(argb(0xFF5F7A55), soft, 0.2f)))
            repeat(3) { k -> c.drawCircle(cx - s * 0.06f + k * s * 0.05f, cy - s * 0.05f, s * 0.035f, fill(argb(0xFF4F6A48))) }
        }
    }
    grain(0.03f, 0.05f, 1.2f)
}

/** 비 오는 앞유리 너머의 밤: 흐려진 불빛 망울, 물방울 */
private fun Art.nightBokeh() {
    vGradient(argb(0xFF0B1018), argb(0xFF151B24))
    val lights = pick(
        listOf(
            listOf(argb(0xFFFF6B4A), argb(0xFFFFB25C), argb(0xFF5EC8E5)),
            listOf(argb(0xFFF2C14E), argb(0xFFE76F51), argb(0xFF2A9D8F)),
            listOf(argb(0xFF9AD1D4), argb(0xFFFFD6A5), argb(0xFFFF8C61)),
        ),
    )
    val band = s * rnd(0.35f, 0.65f)
    repeat(26 + (b.density * 30).toInt()) {
        val x = s * rnd(-0.05f, 1.05f); val y = band + s * rnd(-0.22f, 0.22f) * (1f + r.nextFloat())
        val rad = s * rnd(0.03f, 0.09f)
        val col = pick(lights)
        c.drawCircle(x, y, rad, fill(alpha(col, rnd(0.25f, 0.6f))).apply { maskFilter = BlurMaskFilter(rad * 0.25f, BlurMaskFilter.Blur.NORMAL) })
    }
    // 물방울: 작고 밝은 테두리 원
    repeat(60) {
        val x = r.nextFloat() * s; val y = r.nextFloat() * s; val rad = s * rnd(0.004f, 0.014f)
        c.drawCircle(x, y, rad, fill(alpha(argb(0xFF000000), 0.35f)))
        c.drawCircle(x - rad * 0.3f, y - rad * 0.3f, rad * 0.35f, fill(alpha(argb(0xFFFFFFFF), 0.35f)))
    }
    // 흘러내린 빗줄기
    repeat(8 + (b.tension * 10).toInt()) {
        val x = r.nextFloat() * s; val y0 = r.nextFloat() * s * 0.6f
        c.drawLine(x, y0, x + rnd(-6f, 6f) * px, y0 + s * rnd(0.1f, 0.3f), stroke(alpha(argb(0xFFFFFFFF), 0.12f), 3f * px))
    }
    grain(0.05f, 0.08f, 1.4f)
    vignette(0.45f)
}
