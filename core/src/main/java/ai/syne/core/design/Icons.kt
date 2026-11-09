package ai.syne.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** SYNE 선 아이콘(24 격자, 같은 선 굵기·둥근 끝). 외부 아이콘 세트에 의존하지 않는다 */
enum class SyneIcon { Home, Play, Pause, Check, Clock, Distance, Smooth, Brake, Pin, Close, Back, Archive, Share, Import, Export, Car, Phone, Music, Sync, Record, Chevron, Spark, Alert, Calendar, Prev, Next, Heart, HeartFill, Edit, Plus }

@Composable
fun Icon(icon: SyneIcon, tint: Color = SynePalette.Text, size: Dp = Syne.dims.icon, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float, close: Boolean = false) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
            if (close) close()
        }
        fun line(vararg pts: Float) = drawPath(p(*pts), tint, style = st)
        fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
            if (fill) drawCircle(tint, r * u, Offset(cx * u, cy * u)) else drawCircle(tint, r * u, Offset(cx * u, cy * u), style = st)
        when (icon) {
            SyneIcon.Home -> { line(4f, 11f, 12f, 4f, 20f, 11f); line(6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); line(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
            SyneIcon.Play -> drawPath(p(8f, 5f, 19f, 12f, 8f, 19f, close = true), tint)
            SyneIcon.Pause -> { drawRect(tint, Offset(7f * u, 5f * u), Size(3.4f * u, 14f * u)); drawRect(tint, Offset(13.6f * u, 5f * u), Size(3.4f * u, 14f * u)) }
            SyneIcon.Check -> { circle(12f, 12f, 9f); line(8f, 12.3f, 11f, 15f, 16.2f, 9.5f) }
            SyneIcon.Clock -> { circle(12f, 12f, 9f); line(12f, 7f, 12f, 12f, 15.5f, 14f) }
            SyneIcon.Distance -> { line(5f, 19f, 9f, 5f); line(19f, 19f, 15f, 5f); line(12f, 6f, 12f, 8f); line(12f, 11f, 12f, 13f); line(12f, 16f, 12f, 18f) }
            SyneIcon.Smooth -> wave(tint, st, u)
            SyneIcon.Brake -> { circle(12f, 12f, 9f); circle(12f, 12f, 4.5f) }
            SyneIcon.Pin -> { drawPath(Path().apply { moveTo(12f * u, 21f * u); cubicTo(6f * u, 15f * u, 5f * u, 12f * u, 5f * u, 9.5f * u); cubicTo(5f * u, 5.5f * u, 8.2f * u, 3f * u, 12f * u, 3f * u); cubicTo(15.8f * u, 3f * u, 19f * u, 5.5f * u, 19f * u, 9.5f * u); cubicTo(19f * u, 12f * u, 18f * u, 15f * u, 12f * u, 21f * u); close() }, tint, style = st); circle(12f, 9.5f, 2.5f) }
            SyneIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            SyneIcon.Back -> { line(19f, 12f, 5f, 12f); line(11f, 6f, 5f, 12f, 11f, 18f) }
            SyneIcon.Chevron -> line(9f, 5f, 16f, 12f, 9f, 19f)
            SyneIcon.Archive -> { drawRoundRect(tint, Offset(4f * u, 4f * u), Size(7f * u, 7f * u), style = st); drawRoundRect(tint, Offset(13f * u, 4f * u), Size(7f * u, 7f * u), style = st); drawRoundRect(tint, Offset(4f * u, 13f * u), Size(7f * u, 7f * u), style = st); drawRoundRect(tint, Offset(13f * u, 13f * u), Size(7f * u, 7f * u), style = st) }
            SyneIcon.Share -> { line(12f, 15f, 12f, 3f); line(7.5f, 7.5f, 12f, 3f, 16.5f, 7.5f); line(5f, 12f, 5f, 20f, 19f, 20f, 19f, 12f) }
            SyneIcon.Import -> { line(12f, 3f, 12f, 15f); line(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); line(5f, 16f, 5f, 20f, 19f, 20f, 19f, 16f) }
            SyneIcon.Export -> { line(12f, 15f, 12f, 3f); line(7.5f, 7.5f, 12f, 3f, 16.5f, 7.5f); line(5f, 16f, 5f, 20f, 19f, 20f, 19f, 16f) }
            SyneIcon.Car -> { line(4f, 15f, 5.5f, 9f, 18.5f, 9f, 20f, 15f, 20f, 18f, 4f, 18f, 4f, 15f, 20f, 15f); circle(7.5f, 18.5f, 1.4f, true); circle(16.5f, 18.5f, 1.4f, true) }
            SyneIcon.Phone -> { drawRoundRect(tint, Offset(7f * u, 3f * u), Size(10f * u, 18f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = st); line(11f, 18f, 13f, 18f) }
            SyneIcon.Music -> { line(9f, 17f, 9f, 5f, 19f, 3.5f, 19f, 15.5f); circle(6.5f, 17f, 2.5f); circle(16.5f, 15.5f, 2.5f) }
            SyneIcon.Sync -> { drawArc(tint, 200f, 250f, false, Offset(4f * u, 4f * u), Size(16f * u, 16f * u), style = st); line(17f, 3.5f, 18.5f, 7.2f, 14.6f, 7.8f) }
            SyneIcon.Record -> { line(5f, 6f, 19f, 6f); line(5f, 12f, 19f, 12f); line(5f, 18f, 13f, 18f) }
            SyneIcon.Spark -> { line(12f, 3f, 12f, 8f); line(12f, 16f, 12f, 21f); line(3f, 12f, 8f, 12f); line(16f, 12f, 21f, 12f); circle(12f, 12f, 1.6f, true) }
            SyneIcon.Alert -> { circle(12f, 12f, 9f); line(12f, 7.5f, 12f, 13f); circle(12f, 16.5f, 1.1f, true) }
            SyneIcon.Prev -> { drawPath(p(18f, 6f, 9f, 12f, 18f, 18f, close = true), tint); drawRect(tint, Offset(6f * u, 6f * u), Size(2.2f * u, 12f * u)) }
            SyneIcon.Next -> { drawPath(p(6f, 6f, 15f, 12f, 6f, 18f, close = true), tint); drawRect(tint, Offset(15.8f * u, 6f * u), Size(2.2f * u, 12f * u)) }
            SyneIcon.Heart, SyneIcon.HeartFill -> {
                val h = Path().apply {
                    moveTo(12f * u, 20f * u)
                    cubicTo(4f * u, 14.5f * u, 2.5f * u, 11f * u, 3.2f * u, 8f * u)
                    cubicTo(4f * u, 4.6f * u, 8.6f * u, 3.6f * u, 12f * u, 7.4f * u)
                    cubicTo(15.4f * u, 3.6f * u, 20f * u, 4.6f * u, 20.8f * u, 8f * u)
                    cubicTo(21.5f * u, 11f * u, 20f * u, 14.5f * u, 12f * u, 20f * u)
                    close()
                }
                if (icon == SyneIcon.HeartFill) drawPath(h, tint) else drawPath(h, tint, style = st)
            }
            SyneIcon.Edit -> { line(4f, 20f, 4.5f, 16f, 15.5f, 5f, 19f, 8.5f, 8f, 19.5f, 4f, 20f); line(13.5f, 7f, 17f, 10.5f) }
            SyneIcon.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            SyneIcon.Calendar -> { drawRoundRect(tint, Offset(4f * u, 5f * u), Size(16f * u, 15f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = st); line(4f, 10f, 20f, 10f); line(8.5f, 3f, 8.5f, 6.5f); line(15.5f, 3f, 15.5f, 6.5f) }
        }
    }
}

private fun DrawScope.wave(tint: Color, st: Stroke, u: Float) {
    val hs = floatArrayOf(4f, 9f, 14f, 9f, 6f, 11f, 4f)
    hs.forEachIndexed { i, h ->
        val x = (3f + i * 3f) * u
        drawLine(tint, Offset(x, (12f - h / 2) * u), Offset(x, (12f + h / 2) * u), strokeWidth = st.width, cap = StrokeCap.Round)
    }
}
