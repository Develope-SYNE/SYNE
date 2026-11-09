package ai.syne.core.design

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// =====================================================================
//  공통: 누름 피드백
// =====================================================================

@Composable
private fun pressed(src: MutableInteractionSource): Boolean = src.collectIsPressedAsState().value

@Composable
private fun pressScale(src: MutableInteractionSource, to: Float = 0.97f): Float {
    val p = pressed(src)
    val s by animateFloatAsState(if (p) to else 1f, tween(140), label = "press")
    return s
}

// =====================================================================
//  브랜드 · 머리줄
// =====================================================================

/** 로고: 궤도 기호 + SYNE 워드마크 */
@Composable
fun Brand(modifier: Modifier = Modifier, height: Dp = Syne.dims.icon) {
    val t = Syne.type
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(height * 0.5f)) {
        Canvas(Modifier.size(height)) {
            val st = Stroke(width = size.minDimension * 0.06f)
            rotate(-24f) { drawOval(SynePalette.Text, Offset(0f, size.height * 0.3f), Size(size.width, size.height * 0.4f), style = st) }
            rotate(30f) { drawOval(SynePalette.Text.copy(alpha = 0.55f), Offset(size.width * 0.14f, size.height * 0.34f), Size(size.width * 0.72f, size.height * 0.32f), style = st) }
        }
        Text("SYNE", style = t.control.copy(fontSize = t.control.fontSize * 0.95f, letterSpacing = t.control.fontSize * 0.34f))
    }
}

/** 화면 머리줄: [뒤로/아이콘] 제목 ……… [오른쪽 조작] */
@Composable
fun NavHeader(title: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, leading: SyneIcon = SyneIcon.Back, trailing: @Composable RowScope.() -> Unit = {}) {
    val d = Syne.dims
    Row(modifier.fillMaxWidth().height(d.touch), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(leading, onBack)
            Spacer(Modifier.width(d.gapS))
        }
        if (title != null) Text(title, style = Syne.type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 아이콘만 있는 정사각 버튼(터치 영역 = dims.touch) */
@Composable
fun IconButton(icon: SyneIcon, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = SynePalette.Text) {
    val d = Syne.dims
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (pressed(src)) SynePalette.Surface2 else Color.Transparent, tween(160), label = "ib")
    Box(
        modifier.size(d.touch).clip(RoundedCornerShape(d.radiusS)).background(bg).clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (enabled) tint else SynePalette.TextTertiary) }
}

// =====================================================================
//  버튼
// =====================================================================

enum class ButtonKind { Primary, Secondary }
enum class ButtonSize { Compact, Regular, Large }

/**
 * 버튼. 모서리 radiusM, 높이는 크기 단계별 고정(Compact = touch, Regular = touch+…, Large = touchL).
 *  - Primary: 파랑 면 + 흰 글자 — 화면에서 가장 강한 행동 하나에만
 *  - Secondary: 차콜 면 + 1dp 선
 * 누르면 색이 짙어지고 0.97배로 들어간다(180ms). 비활성은 회색.
 */
@Composable
fun SyneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    size: ButtonSize = ButtonSize.Regular,
    icon: SyneIcon? = null,
    enabled: Boolean = true,
) {
    val d = Syne.dims
    val src = remember { MutableInteractionSource() }
    val p = pressed(src)
    val fill by animateColorAsState(
        when {
            !enabled -> SynePalette.Surface
            kind == ButtonKind.Primary -> if (p) SynePalette.PrimaryPressed else SynePalette.Primary
            p -> SynePalette.Surface2
            else -> SynePalette.Surface
        },
        tween(180), label = "fill",
    )
    val line = when {
        kind == ButtonKind.Primary && enabled -> Color.Transparent
        p -> SynePalette.TextTertiary
        else -> SynePalette.LineStrong
    }
    val ink = if (!enabled) SynePalette.TextTertiary else if (kind == ButtonKind.Primary) SynePalette.OnPrimary else SynePalette.Text
    val h = when (size) {
        ButtonSize.Compact -> d.touch
        ButtonSize.Regular -> (d.touch + d.touchL) / 2
        ButtonSize.Large -> d.touchL
    }
    val scale = pressScale(src)
    val shape = RoundedCornerShape(d.radiusM)
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.height(h).clip(shape).background(fill).border(1.dp, line, shape)
            .clickable(src, null, enabled, onClick = onClick).padding(horizontal = d.gapL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, ink, size = d.icon * 0.8f)
            Spacer(Modifier.width(d.gapS))
        }
        Text(text, style = Syne.type.control.copy(color = ink), maxLines = 1)
    }
}

// =====================================================================
//  선택: 장르 선택 · 필터 칩 · 2~3칸 스위치 — 모두 같은 '선택' 시각 언어
//  선택됨 = 파랑 옅은 면 + 파랑 2dp 테두리 + 흰 글자 / 기본 = 차콜 면 + 1dp 선 / 불가 = 회색 + 사유
// =====================================================================

data class Choice(val key: String?, val name: String, val meta: String? = null, val enabled: Boolean = true)

@Composable
private fun ChoiceCell(c: Choice, selected: Boolean, modifier: Modifier, tall: Boolean, onClick: () -> Unit) {
    val d = Syne.dims
    val t = Syne.type
    val src = remember(c.key) { MutableInteractionSource() }
    val p = pressed(src)
    val bg by animateColorAsState(
        when {
            selected -> SynePalette.PrimaryTint
            p && c.enabled -> SynePalette.Surface2
            else -> SynePalette.Surface
        },
        tween(180), label = "cell",
    )
    val shape = RoundedCornerShape(d.radiusS)
    Box(
        modifier.height(if (tall) d.touchL + d.gapS else d.touch).clip(shape).background(bg)
            .border(if (selected) 2.dp else 1.dp, if (selected) SynePalette.Primary else SynePalette.Line, shape)
            .clickable(src, null, c.enabled && !selected) { onClick() }.padding(horizontal = d.gapM),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                c.name,
                style = (if (tall) t.heading else t.control).copy(
                    color = when {
                        !c.enabled -> SynePalette.TextTertiary
                        selected -> SynePalette.Text
                        else -> SynePalette.TextSecondary
                    },
                ),
                maxLines = 1,
            )
            if (c.meta != null) {
                Text(c.meta, style = t.meta.copy(color = if (selected) SynePalette.Focus else SynePalette.TextTertiary), maxLines = 1)
            }
        }
    }
}

/** 장르 선택: 같은 너비의 큰 칸(미지원 장르는 '준비 중'으로 누를 수 없음) */
@Composable
fun GenreSelector(choices: List<Choice>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Syne.dims.gapS)) {
        choices.forEach { c -> ChoiceCell(c, c.key == selected, Modifier.weight(1f), tall = true) { onSelect(c.key) } }
    }
}

/** 필터 칩(보관함 장르 필터 등): 글자 길이만큼, 높이 touch */
@Composable
fun FilterChips(choices: List<Choice>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Syne.dims.gapS)) {
        choices.forEach { c -> ChoiceCell(c, c.key == selected, Modifier, tall = false) { onSelect(c.key) } }
    }
}

// =====================================================================
//  상태 표시
// =====================================================================

enum class StatusTone { Waiting, Active, Done, Error }

/**
 * 상태 표시: 정적인 기호 + 짧은 문장. 깜빡임·회전 없음(주행 중에도 쓴다).
 *  Waiting = 회색 빈 원 / Active = 금빛 반원 / Done = 파랑 체크 / Error = 빨강 느낌표
 */
@Composable
fun StatusIndicator(tone: StatusTone, text: String, modifier: Modifier = Modifier, style: TextStyle = Syne.type.heading) {
    val sz = with(androidx.compose.ui.platform.LocalDensity.current) { style.fontSize.toDp() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        when (tone) {
            StatusTone.Done -> Icon(SyneIcon.Check, SynePalette.Primary, size = sz * 1.1f)
            StatusTone.Error -> Icon(SyneIcon.Alert, SynePalette.Error, size = sz * 1.1f)
            else -> Canvas(Modifier.size(sz * 1.1f)) {
                val w = size.minDimension * 0.12f
                val r = size.minDimension / 2 - w
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(SynePalette.LineStrong, r, c, style = Stroke(w))
                if (tone == StatusTone.Active) drawArc(SynePalette.Gold, -90f, 240f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            }
        }
        Spacer(Modifier.width(sz * 0.5f))
        Text(
            text,
            style = style.copy(
                color = when (tone) {
                    StatusTone.Active -> SynePalette.Gold
                    StatusTone.Done -> SynePalette.Text
                    StatusTone.Error -> SynePalette.Error
                    StatusTone.Waiting -> SynePalette.TextSecondary
                },
            ),
        )
    }
}

// =====================================================================
//  기록 표시
// =====================================================================

/** 기록 한 칸: 아이콘 + 라벨 / 숫자(고정폭) + 단위. accent = 금빛 숫자(점수) */
@Composable
fun StatTile(icon: SyneIcon, label: String, value: String, unit: String = "", modifier: Modifier = Modifier, accent: Boolean = false, tile: Boolean = true) {
    val d = Syne.dims
    val t = Syne.type
    Column(
        modifier.then(if (tile) Modifier.clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(d.cardPad) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, SynePalette.TextSecondary, size = d.icon * 0.75f)
            Spacer(Modifier.width(d.gapXS))
            Text(label, style = t.label, maxLines = 1)
        }
        Spacer(Modifier.height(d.gapXS))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = if (accent) SynePalette.Gold else SynePalette.Text)) { append(value) }
                if (unit.isNotEmpty()) withStyle(SpanStyle(fontSize = t.meta.fontSize, color = SynePalette.TextSecondary, fontFeatureSettings = null)) { append(" $unit") }
            },
            style = t.numeric, maxLines = 1,
        )
    }
}

/** 작은 메타 줄: 아이콘 + 글자 */
@Composable
fun MetaRow(icon: SyneIcon, text: String, modifier: Modifier = Modifier) {
    val d = Syne.dims
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, SynePalette.TextSecondary, size = d.icon * 0.7f)
        Spacer(Modifier.width(d.gapXS))
        Text(text, style = Syne.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 점수: 숫자 + 금빛 % (보관함 카드·목록에서 같은 위치·같은 모양) */
@Composable
fun ScoreText(value: Int, style: TextStyle = Syne.type.control) {
    Text(
        buildAnnotatedString {
            append("$value")
            withStyle(SpanStyle(color = SynePalette.Gold, fontSize = style.fontSize * 0.7f)) { append("%") }
        },
        style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1,
    )
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(SynePalette.Line))
}

// =====================================================================
//  커버(앨범 아트) — 차량·모바일이 같은 파일을 같은 방식으로 그린다
// =====================================================================

/**
 * 커버 디코딩 캐시 — 개수가 아니라 **바이트(KB)로** 상한을 둔다.
 * (1024px 커버 한 장 = 4MB. 개수 48장 상한이면 주행이 쌓일수록 최대 ~190MB까지 자랐다 — 안정성 측정에서 발견)
 */
private object CoverCache {
    private const val MAX_KB = 24 * 1024
    val cache = object : LruCache<String, ImageBitmap>(MAX_KB) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }
    fun load(f: File, targetPx: Int): ImageBitmap? {
        val key = "${f.absolutePath}@${f.lastModified()}@$targetPx"
        cache.get(key)?.let { return it }
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

/** 앨범 아트. 파일이 없으면(생성 중·실패) 차분한 자리 표시와 [placeholder] 문구 */
@Composable
fun Cover(file: File?, modifier: Modifier = Modifier, targetPx: Int = 512, corner: Dp = Syne.dims.radiusS, placeholder: String? = null) {
    val img by produceState<ImageBitmap?>(null, file?.absolutePath, file?.lastModified(), targetPx) {
        value = file?.let { withContext(Dispatchers.IO) { CoverCache.load(it, targetPx) } }
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(SynePalette.Surface), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (placeholder != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(SyneIcon.Spark, SynePalette.TextTertiary)
            Spacer(Modifier.height(Syne.dims.gapXS))
            Text(placeholder, style = Syne.type.meta.copy(color = SynePalette.TextTertiary))
        }
    }
}

/**
 * 앨범 카드(보관함 그리드): 정사각 커버 + 제목/점수 한 줄 + 메타 한 줄. 카드 높이 일정, 긴 글자는 말줄임.
 * 선택됨 = 커버에 파랑 2dp 테두리.
 */
@Composable
fun AlbumCard(cover: File?, title: String, meta: String, score: Int?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, favorite: Boolean = false) {
    val d = Syne.dims
    val t = Syne.type
    val src = remember { MutableInteractionSource() }
    val scale = pressScale(src)
    Column(modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clickable(src, null) { onClick() }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(cover, Modifier.fillMaxSize(), targetPx = 384)
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, SynePalette.Primary, RoundedCornerShape(d.radiusS)))
        }
        Spacer(Modifier.height(d.gapS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = t.heading.copy(color = if (selected) SynePalette.Focus else SynePalette.Text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // 즐겨찾기: 색만이 아니라 '채운 하트'로 구분(해제된 곡에는 아무것도 그리지 않는다)
            if (favorite) {
                Spacer(Modifier.width(d.gapXS))
                Icon(SyneIcon.HeartFill, SynePalette.Gold, size = d.icon * 0.8f)
            }
            if (score != null) { Spacer(Modifier.width(d.gapXS)); ScoreText(score, t.control) }
        }
        Text(meta, style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// =====================================================================
//  재생
// =====================================================================

/** 재생 버튼: 흰 원 + 직접 그린 ▶/❚❚. 누르면 파랑, 재생 중이면 금빛 */
@Composable
fun PlayButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit, size: Dp = Syne.dims.touchL) {
    val src = remember { MutableInteractionSource() }
    val p = pressed(src)
    val bg by animateColorAsState(
        when {
            !enabled -> SynePalette.Surface2
            p -> SynePalette.Primary
            playing -> SynePalette.Gold
            else -> SynePalette.Text
        },
        tween(180), label = "play",
    )
    val scale = pressScale(src, 0.94f)
    val ink = if (!enabled) SynePalette.TextTertiary else if (p) SynePalette.OnPrimary else SynePalette.OnLight
    Box(
        Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(bg).clickable(src, null, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(if (playing) SyneIcon.Pause else SyneIcon.Play, ink, size = size * 0.42f) }
}

/** 파형: 곡의 멜로디로 만든 막대. 재생한 만큼 금빛 */
@Composable
fun Waveform(levels: List<Float>, progress: Float, modifier: Modifier = Modifier, played: Color = SynePalette.Gold, rest: Color = SynePalette.LineStrong) {
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = size.width / n * 0.5f
        val bw = (size.width - gap * (n - 1)) / n
        levels.forEachIndexed { i, v ->
            val h = size.height * (0.16f + 0.84f * v.coerceIn(0f, 1f))
            val x = i * (bw + gap)
            drawRoundRect(if ((i + 0.5f) / n <= progress) played else rest, Offset(x, (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2, bw / 2))
        }
    }
}

/** 재생 위치 막대(끌어서·눌러서 이동). onSeek가 null이면 표시만 */
@Composable
fun SeekBar(progress: Float, onSeek: ((Float) -> Unit)?, modifier: Modifier = Modifier) {
    val d = Syne.dims
    Box(
        modifier.height(d.touch).then(
            if (onSeek != null) Modifier
                .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } }
            else Modifier,
        ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(d.touch)) {
            val y = size.height / 2
            val th = 4.dp.toPx()
            drawRoundRect(SynePalette.LineStrong, Offset(0f, y - th / 2), Size(size.width, th), CornerRadius(th / 2))
            drawRoundRect(SynePalette.Gold, Offset(0f, y - th / 2), Size(size.width * progress.coerceIn(0f, 1f), th), CornerRadius(th / 2))
            drawCircle(SynePalette.Text, 8.dp.toPx(), Offset(size.width * progress.coerceIn(0f, 1f), y))
        }
    }
}

/**
 * 미니 플레이어: [커버] 제목·부제 / 파형 + 시간 [재생].
 * 재생 파일이 없으면 [disabledText](예: '음악을 저장하는 중')를 보여 주고 버튼은 비활성.
 */
@Composable
fun MiniPlayer(
    cover: File?,
    title: String,
    subtitle: String,
    levels: List<Float>,
    progress: Float,
    timeText: String,
    playing: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    disabledText: String? = null,
) {
    val d = Syne.dims
    val t = Syne.type
    Row(
        modifier.clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(d.cardPad * 0.75f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(playing, enabled, onToggle)
        Spacer(Modifier.width(d.gapM))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = t.heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (enabled) timeText else disabledText ?: "", style = t.meta.copy(fontFeatureSettings = "tnum"))
            }
            Text(subtitle, style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(d.gapXS))
            Waveform(levels, if (playing) progress else 0f, Modifier.fillMaxWidth().height(d.icon * 1.2f))
        }
        if (cover != null) {
            Spacer(Modifier.width(d.gapM))
            Cover(cover, Modifier.size(d.touchL).fillMaxHeight(), targetPx = 256)
        }
    }
}

/** 멜로디 노트(MIDI) → 파형 막대 높이(실제 데이터, 시각 보정만) */
fun waveLevels(notes: List<Int>, bars: Int = 48): List<Float> {
    val src = if (notes.size >= 2) notes else listOf(64, 67, 62, 69, 64, 60, 67, 64)
    val lo = src.min(); val hi = src.max().coerceAtLeast(lo + 1)
    return List(bars) { i ->
        val x = i / (bars - 1f) * (src.size - 1)
        val k = x.toInt().coerceAtMost(src.size - 2); val f = x - k
        val v = ((src[k] * (1 - f) + src[k + 1] * f) - lo) / (hi - lo)
        (0.25f + 0.6f * v + 0.15f * kotlin.math.sin(i * 1.7f)).coerceIn(0.1f, 1f)
    }
}

// =====================================================================
//  사용자 편집 · 즐겨찾기
// =====================================================================

/** 즐겨찾기 토글: 빈 하트(해제) ↔ 채운 금빛 하트(등록). 글자를 함께 쓰면 상태가 색에만 의존하지 않는다 */
@Composable
fun FavoriteButton(favorite: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: Boolean = true, size: ButtonSize = ButtonSize.Compact) {
    val d = Syne.dims
    val src = remember { MutableInteractionSource() }
    val p = pressed(src)
    val bg by animateColorAsState(if (p) SynePalette.Surface2 else if (favorite) SynePalette.GoldTint else SynePalette.Surface, tween(180), label = "fav")
    val shape = RoundedCornerShape(d.radiusM)
    val h = if (size == ButtonSize.Compact) d.touch else (d.touch + d.touchL) / 2
    Row(
        modifier.height(h).then(if (!label) Modifier.width(h) else Modifier).clip(shape).background(bg)
            .border(1.dp, if (favorite) SynePalette.Gold.copy(alpha = 0.6f) else SynePalette.LineStrong, shape)
            .clickable(src, null, onClick = onClick).padding(horizontal = if (label) d.gapM else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(if (favorite) SyneIcon.HeartFill else SyneIcon.Heart, if (favorite) SynePalette.Gold else SynePalette.Text, size = d.icon * 0.85f)
        if (label) {
            Spacer(Modifier.width(d.gapS))
            Text(if (favorite) "즐겨찾기 해제" else "즐겨찾기", style = Syne.type.control, maxLines = 1)
        }
    }
}

/** 태그 칩(#태그). onRemove가 있으면 × 로 지울 수 있다(터치 영역 = touch) */
@Composable
fun TagChip(tag: String, onRemove: (() -> Unit)? = null) {
    val d = Syne.dims
    Row(
        Modifier.height(if (onRemove != null) d.touch else d.touch * 0.7f).clip(RoundedCornerShape(d.radiusS)).background(SynePalette.PrimaryTint)
            .padding(start = d.gapS, end = if (onRemove != null) 0.dp else d.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#$tag", style = Syne.type.label.copy(color = SynePalette.Focus), maxLines = 1)
        if (onRemove != null) {
            Box(Modifier.size(d.touch).clickable(onClick = onRemove), contentAlignment = Alignment.Center) { Icon(SyneIcon.Close, SynePalette.Focus, size = d.icon * 0.6f) }
        }
    }
}

/**
 * 입력칸: 차콜 면 + 테두리(포커스 = 파랑 2dp) + 글자 수(넘으면 빨강). 여러 줄이면 [minLines]만큼 높이를 잡는다.
 */
@Composable
fun SyneTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxChars: Int? = null,
    enabled: Boolean = true,
    imeAction: androidx.compose.ui.text.input.ImeAction = if (singleLine) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Default,
    onDone: (() -> Unit)? = null,
) {
    val d = Syne.dims
    val t = Syne.type
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val over = maxChars != null && value.trim().length > maxChars
    val line by animateColorAsState(if (over) SynePalette.Error else if (focused) SynePalette.Primary else SynePalette.Line, tween(180), label = "tf")
    val shape = RoundedCornerShape(d.radiusS)
    val style = (if (singleLine) t.heading else t.body).copy(color = if (enabled) SynePalette.Text else SynePalette.TextTertiary)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = if (singleLine) d.touch else d.touch * 0.6f * minLines.coerceAtLeast(2))
                .clip(shape).background(SynePalette.Surface).border(if (focused || over) 2.dp else 1.dp, line, shape)
                .padding(horizontal = d.gapM, vertical = d.gapS),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = SynePalette.TextTertiary), maxLines = if (singleLine) 1 else minLines)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
                textStyle = style, cursorBrush = androidx.compose.ui.graphics.SolidColor(SynePalette.Primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = imeAction),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (maxChars != null) {
            Text(
                "${value.trim().length} / $maxChars", style = t.meta.copy(color = if (over) SynePalette.Error else SynePalette.TextTertiary),
                modifier = Modifier.align(Alignment.End).padding(top = d.gapXS),
            )
        }
    }
}
