package ai.syne.app.cover

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 커버 '서명' — 스타일(매체)과 상관없이 모든 커버에 같은 형식으로 얹는다(기기 페인터·AI 서버 생성 모두).
 *  - 그날 경로의 모양(RouteShape): 가운데 64% 상자에 얇은 선 + 은은한 금빛 글로우, 출발 점·도착 고리
 *  - 오른쪽 아래 작은 "SYNE · Vol.n"
 * 대비는 배경 밝기에 맞춰 자동: 어두운 곳엔 흰 선, 밝은 곳엔 먹색 선(둘 다 금빛 글로우). 선 아래에 반대색 얇은 그늘을 깔아
 * 복잡한 그림 위에서도 선명하게 읽힌다. 좌표가 아니라 정규화된 형태만 쓰므로 위치 정보는 없다.
 */
object CoverSignature {
    private const val GOLD = 0xFFFFC94A.toInt()
    private const val INK_LIGHT = 0xFFFFFFFF.toInt()
    private const val INK_DARK = 0xFF16130F.toInt()

    /** [src]가 불변이면 복사본에 그린다. 결과는 항상 수정 가능한 비트맵 */
    fun apply(src: Bitmap, route: RouteShape?, volume: Int?, typeface: Typeface? = null): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        val c = Canvas(bmp)
        val s = bmp.width.toFloat()
        val px = s / 1024f
        if (route != null) drawRoute(c, bmp, route, s, px)
        if (volume != null) drawMark(c, bmp, "SYNE · Vol.$volume", s, px, typeface)
        return bmp
    }

    private fun drawRoute(c: Canvas, bmp: Bitmap, route: RouteShape, s: Float, px: Float) {
        val m = s * 0.18f
        val box = s * 0.64f
        val pts = route.points
        val n = pts.size / 2
        fun x(k: Int) = m + pts[2 * k] * box
        fun y(k: Int) = m + pts[2 * k + 1] * box
        val path = Path().apply {
            moveTo(x(0), y(0))
            for (k in 1 until n) quadTo(x(k - 1), y(k - 1), (x(k - 1) + x(k)) / 2, (y(k - 1) + y(k)) / 2)
            lineTo(x(n - 1), y(n - 1))
        }
        // 선이 지나가는 자리의 평균 밝기로 선 색을 정한다
        var lum = 0f
        for (k in 0 until n step 3) lum += luminance(bmp.getPixel(x(k).toInt().coerceIn(0, bmp.width - 1), y(k).toInt().coerceIn(0, bmp.height - 1)))
        lum /= ((n + 2) / 3)
        val dark = lum < 0.55f
        val ink = if (dark) INK_LIGHT else INK_DARK
        val shade = if (dark) INK_DARK else INK_LIGHT

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        // 1) 금빛 글로우(넓게 번짐)
        stroke.color = withAlpha(GOLD, if (dark) 0.42f else 0.5f)
        stroke.strokeWidth = 16f * px
        stroke.maskFilter = BlurMaskFilter(14f * px, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, stroke)
        // 2) 반대색 얇은 그늘(복잡한 배경 위 가독성)
        stroke.maskFilter = BlurMaskFilter(2.5f * px, BlurMaskFilter.Blur.NORMAL)
        stroke.color = withAlpha(shade, 0.35f)
        stroke.strokeWidth = 7f * px
        c.drawPath(path, stroke)
        // 3) 얇은 선
        stroke.maskFilter = null
        stroke.color = withAlpha(ink, 0.92f)
        stroke.strokeWidth = 3.2f * px
        c.drawPath(path, stroke)
        // 출발 점 · 도착 고리
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0.95f) }
        c.drawCircle(x(0), y(0), 7f * px, fill)
        stroke.color = withAlpha(GOLD, 0.95f)
        stroke.strokeWidth = 3.2f * px
        c.drawCircle(x(n - 1), y(n - 1), 10f * px, stroke)
    }

    private fun drawMark(c: Canvas, bmp: Bitmap, text: String, s: Float, px: Float, typeface: Typeface?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f * px
            letterSpacing = 0.12f
            this.typeface = typeface ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val xr = s - 36f * px
        val yb = s - 36f * px
        val w = paint.measureText(text)
        var lum = 0f
        for (i in 0 until 6) lum += luminance(bmp.getPixel((xr - w * i / 5f).toInt().coerceIn(0, bmp.width - 1), (yb - 10f * px).toInt().coerceIn(0, bmp.height - 1)))
        val dark = lum / 6 < 0.55f
        paint.color = withAlpha(if (dark) INK_DARK else INK_LIGHT, 0.45f)
        paint.maskFilter = BlurMaskFilter(4f * px, BlurMaskFilter.Blur.NORMAL)
        c.drawText(text, xr, yb, paint)
        paint.maskFilter = null
        paint.color = withAlpha(if (dark) INK_LIGHT else INK_DARK, 0.88f)
        c.drawText(text, xr, yb, paint)
    }

    private fun luminance(c: Int) = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
    private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
