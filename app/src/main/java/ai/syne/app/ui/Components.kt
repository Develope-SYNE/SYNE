package ai.syne.app.ui

import ai.syne.core.design.Cover
import ai.syne.core.design.Syne
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 차량 DB의 상대 경로(filesDir 기준) 커버 → 공통 Cover. 모바일도 같은 Cover로 같은 파일을 그린다 */
@Composable
fun TripCover(rel: String?, modifier: Modifier = Modifier, targetPx: Int = 512, corner: Dp = Syne.dims.radiusS, placeholder: String? = null) {
    val ctx = LocalContext.current
    Cover(rel?.let { File(ctx.filesDir, it) }, modifier, targetPx, corner, placeholder)
}

fun formatDuration(sec: Float): String {
    val s = sec.toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** 2분 11초 / 1시간 05분 */
fun formatDurationKo(sec: Float): String {
    val s = sec.toInt()
    return if (s >= 3600) String.format(Locale.KOREA, "%d시간 %02d분", s / 3600, s % 3600 / 60)
    else if (s >= 60) String.format(Locale.KOREA, "%d분 %02d초", s / 60, s % 60) else "${s}초"
}

fun formatKm(m: Int): String = String.format(Locale.US, "%.1f", m / 1000f)
fun formatDate(ms: Long): String = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA).format(Date(ms))
fun formatShortDate(ms: Long): String = SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(ms))
