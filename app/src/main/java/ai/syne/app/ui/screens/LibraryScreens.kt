package ai.syne.app.ui.screens

import ai.syne.app.Genre
import ai.syne.app.library.ArtState
import ai.syne.app.library.CourseLogic
import ai.syne.app.library.MediaState
import ai.syne.app.library.TrackRow
import ai.syne.app.library.TripEntity
import ai.syne.app.ui.MainViewModel
import ai.syne.app.ui.Route
import ai.syne.app.ui.TripCover
import ai.syne.app.ui.formatDate
import ai.syne.app.ui.formatDuration
import ai.syne.app.ui.formatDurationKo
import ai.syne.app.ui.formatKm
import ai.syne.app.ui.formatShortDate
import ai.syne.core.art.WaveData
import ai.syne.core.design.AlbumCard
import ai.syne.core.design.ButtonKind
import ai.syne.core.design.ButtonSize
import ai.syne.core.design.Choice
import ai.syne.core.design.FavoriteButton
import ai.syne.core.design.FilterChips
import ai.syne.core.design.HairLine
import ai.syne.core.design.Icon
import ai.syne.core.design.IconButton
import ai.syne.core.design.MetaRow
import ai.syne.core.design.MiniPlayer
import ai.syne.core.design.NavHeader
import ai.syne.core.design.PlayButton
import ai.syne.core.design.ScoreText
import ai.syne.core.design.StatTile
import ai.syne.core.design.StatusIndicator
import ai.syne.core.design.StatusTone
import ai.syne.core.design.Syne
import ai.syne.core.design.SyneButton
import ai.syne.core.design.SyneIcon
import ai.syne.core.design.SynePalette
import ai.syne.core.design.TagChip
import ai.syne.core.design.waveLevels
import ai.syne.core.share.EditRules
import ai.syne.core.share.UserEdit
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import java.io.File

private fun kindLabel(kind: String) = if (kind == CourseLogic.KIND_TRAVEL) "여행 앨범" else "코스 앨범"
private fun genreLabel(id: String) = Genre.fromId(id)?.label ?: id
private fun defaultTitle(volume: Int, genre: String) = "Vol.$volume · ${genreLabel(genre)}"
private fun destinationOf(albumName: String) = albumName.substringAfterLast("→ ").trim()

/** 재생 시간 글자: 0:12 / 0:30 */
private fun clock(progress: Float, total: Int = 30) = "%d:%02d / %d:%02d".format((progress * total).toInt() / 60, (progress * total).toInt() % 60, total / 60, total % 60)

/** 보관함 오른쪽 그리드 열 수. 4열(타일 ≈230dp)과 비교해 3열(≈315dp)로 결정 — 제목 26sp·메타 20sp가 말줄임 없이 들어간다 */
private const val GRID_COLUMNS = 3

// =====================================================================
//  SCREEN 04 — 보관함
// =====================================================================

/**
 * 차량용 음악 라이브러리.
 *  - 머리줄 한 줄: [홈] 보관함 n곡 ……… [휴대폰용 파일 만들기]
 *  - 필터 줄: 전체 · 즐겨찾기 · 장르 — 대표 앨범 왼쪽 끝과 같은 기준선에서 시작
 *  - 왼쪽: 선택된 곡(대표) — 큰 커버 + 재생, 사용자 제목 우선, [즐겨찾기][꾸미기][기록][앨범]
 *  - 오른쪽: 3열 그리드 — 카드 높이 일정, 제목 한 줄 + 메타 한 줄(점수는 대표 영역에만), 즐겨찾기는 채운 하트
 */
@Composable
fun LibraryScreen(vm: MainViewModel) {
    val rows by vm.trackRows.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val selectedId by vm.selectedTrack.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    val ctx = LocalContext.current
    var hideBanner by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(SynePalette.Bg).padding(horizontal = d.margin).padding(top = 40.dp)) {
        // ---------- 머리줄 ----------
        Row(Modifier.height(d.touch), verticalAlignment = Alignment.CenterVertically) {
            SyneButton("홈", { vm.stopHighlight(); vm.goHome() }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Home)
            Spacer(Modifier.width(d.gapL))
            Text("보관함", style = t.title.copy(fontSize = t.display.fontSize * 0.7f, lineHeight = t.display.lineHeight * 0.7f))
            Spacer(Modifier.width(d.gapS))
            Text("${rows.size}곡", style = t.title.copy(color = SynePalette.TextTertiary))
            Spacer(Modifier.weight(1f))
            // 보조 기능: 작게(테두리 없는 글자 버튼). 터치 영역은 touch 높이 그대로
            val working = export is MainViewModel.ExportState.Working
            Row(
                Modifier.height(d.touch).clip(RoundedCornerShape(d.radiusS)).clickable(enabled = !working) { hideBanner = false; vm.exportToPhone() }
                    .padding(horizontal = d.gapS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SyneIcon.Export, SynePalette.TextSecondary, size = d.icon * 0.7f)
                Spacer(Modifier.width(d.gapXS))
                Text(if (working) "파일 만드는 중…" else "휴대폰용 파일", style = t.meta)
            }
        }
        Spacer(Modifier.height(d.gapM))
        FilterChips(
            // 사운드팩이 있는 장르만(준비 중 장르는 숨김)
            listOf(Choice(null, "전체"), Choice(MainViewModel.FAVORITES, "♥ 즐겨찾기")) + Genre.entries.filter { it.available }.map { Choice(it.id, it.label) },
            selected = filter, onSelect = vm::setFilter,
        )
        // 내보내기 결과: 실제로 일어난 일(파일 저장)과 다음 할 일(옮기기·가져오기)을 그대로 말한다
        if (!hideBanner) ExportBanner(export) { hideBanner = true }
        Spacer(Modifier.height(d.gapL))

        if (rows.isEmpty()) {
            Text(
                when (filter) {
                    null -> "첫 주행이 끝나면 이곳에 앨범이 생겨요."
                    MainViewModel.FAVORITES -> "아직 즐겨찾기한 앨범이 없어요. 앨범을 고른 뒤 ♡ 즐겨찾기를 눌러 보세요."
                    else -> "이 장르로 만든 곡이 아직 없어요."
                },
                style = t.body,
            )
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.gapXL)) {
            // ---------- 왼쪽: 선택된 곡 ----------
            val sel = rows.firstOrNull { it.id == selectedId } ?: rows.first()
            FeaturedTrack(vm, sel, Modifier.width(480.dp))
            // ---------- 오른쪽: 그리드 ----------
            LazyVerticalGrid(
                GridCells.Fixed(GRID_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(d.gapL),
                verticalArrangement = Arrangement.spacedBy(d.gapXL),
                contentPadding = PaddingValues(bottom = d.gapXL),
            ) {
                items(rows, key = { it.id }) { r ->
                    AlbumCard(
                        cover = r.coverPath?.let { File(ctx.filesDir, it) },
                        title = r.customTitle ?: defaultTitle(r.volume, r.genre),
                        meta = if (r.customTitle != null) "Vol.${r.volume} · ${genreLabel(r.genre)} · ${destinationOf(r.albumName)}"
                        else "${destinationOf(r.albumName)} · ${formatShortDate(r.startedAt)}",
                        score = null,
                        selected = r.id == sel.id,
                        favorite = r.favorite,
                        onClick = { vm.select(r.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportBanner(export: MainViewModel.ExportState, onClose: () -> Unit) {
    val d = Syne.dims
    val t = Syne.type
    val done = export as? MainViewModel.ExportState.Done
    val failed = export as? MainViewModel.ExportState.Failed
    if (done == null && failed == null) return
    Spacer(Modifier.height(d.gapM))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(start = d.cardPad, top = d.gapS, bottom = d.gapS, end = d.gapXS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (done != null) {
                StatusIndicator(StatusTone.Done, "${done.count}곡을 파일로 저장했어요 · ${done.where}", style = t.heading)
                Text("USB나 파일 공유로 휴대폰에 옮긴 뒤, SYNE 모바일 ▸ 연결 ▸ ‘번들 파일 가져오기’를 누르세요. 실시간 동기화는 아직 지원하지 않아요.", style = t.meta)
            } else if (failed != null) {
                StatusIndicator(StatusTone.Error, "파일을 만들지 못했어요 · ${failed.reason}", style = t.heading)
            }
        }
        IconButton(SyneIcon.Close, onClose, tint = SynePalette.TextSecondary)
    }
}

/** 보관함 왼쪽: 선택된 곡 */
@Composable
private fun FeaturedTrack(vm: MainViewModel, sel: TrackRow, modifier: Modifier) {
    val d = Syne.dims
    val t = Syne.type
    val ctx = LocalContext.current
    val trip by remember(sel.id) { vm.track(sel.id) }.collectAsState(null)
    val highlightOf by vm.highlightOf.collectAsStateWithLifecycle()
    val parked by vm.parkConfirmed.collectAsStateWithLifecycle()
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            TripCover(sel.coverPath, Modifier.fillMaxSize(), targetPx = 1024, corner = d.radiusM, placeholder = "앨범 아트 없음")
            val ready = trip?.highlightPath?.let { File(ctx.filesDir, it).exists() } == true
            Box(Modifier.align(Alignment.BottomEnd).padding(d.gapM)) {
                PlayButton(highlightOf == sel.id, ready, { trip?.let(vm::toggleHighlight) })
            }
        }
        Spacer(Modifier.height(d.gapM))
        Text(sel.customTitle ?: defaultTitle(sel.volume, sel.genre), style = t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            (if (sel.customTitle != null) "${defaultTitle(sel.volume, sel.genre)} · " else "") + sel.albumName,
            style = t.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(d.gapXS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetaRow(SyneIcon.Calendar, formatShortDate(sel.startedAt))
            Spacer(Modifier.width(d.gapM))
            MetaRow(SyneIcon.Clock, formatDuration(sel.songSec))
            Spacer(Modifier.width(d.gapM))
            Icon(SyneIcon.Smooth, SynePalette.TextSecondary, size = d.icon * 0.7f)
            Spacer(Modifier.width(d.gapXS))
            Text("부드러움 ", style = t.meta)
            ScoreText(sel.smoothness.toInt(), t.meta.copy(color = SynePalette.Text))
        }
        Spacer(Modifier.height(d.gapM))
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            FavoriteButton(sel.favorite, { vm.toggleFavorite(sel.id) }, Modifier.weight(1f))
            SyneButton("꾸미기", { vm.stopHighlight(); vm.openEdit(sel.id) }, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Edit, enabled = parked)
        }
        Spacer(Modifier.height(d.gapS))
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            SyneButton("기록 보기", { vm.stopHighlight(); vm.push(Route.Track(sel.id)) }, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Compact)
            SyneButton("코스 앨범", { vm.stopHighlight(); vm.push(Route.Album(sel.albumId)) }, Modifier.weight(1f), kind = ButtonKind.Secondary, size = ButtonSize.Compact)
        }
        if (!parked) {
            Spacer(Modifier.height(d.gapXS))
            Text("정차(P)가 확인되지 않아 편집을 잠갔어요 · 휴대폰에서도 꾸밀 수 있어요", style = t.meta)
        }
    }
}

// =====================================================================
//  코스 앨범(같은 코스의 Vol.n 모음)
// =====================================================================

@Composable
fun AlbumScreen(vm: MainViewModel, albumId: Long) {
    val album by remember(albumId) { vm.album(albumId) }.collectAsState(null)
    val tracks by remember(albumId) { vm.tracks(albumId) }.collectAsState(emptyList())
    val d = Syne.dims
    val t = Syne.type

    Column(Modifier.fillMaxSize().background(SynePalette.Bg).padding(horizontal = d.margin, vertical = 40.dp)) {
        NavHeader("보관함", { vm.pop() })
        Spacer(Modifier.height(d.gapL))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.gapXL)) {
            Column(Modifier.width(460.dp)) {
                TripCover(tracks.firstOrNull()?.coverPath, Modifier.fillMaxWidth().aspectRatio(1f), targetPx = 1024, corner = d.radiusM)
                Spacer(Modifier.height(d.gapM))
                Text(album?.let { kindLabel(it.kind) } ?: "", style = t.label)
                Text(album?.name ?: "", style = t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(d.gapXS))
                Text("${tracks.size}곡 · ${formatDurationKo(tracks.sumOf { it.songSec.toDouble() }.toFloat())}", style = t.meta)
            }
            Column(Modifier.weight(1f)) {
                Text("같은 길, 매번 다른 곡", style = t.title)
                Spacer(Modifier.height(d.gapM))
                HairLine()
                LazyColumn(contentPadding = PaddingValues(bottom = d.gapXL)) {
                    items(tracks, key = { it.id }) { tr -> AlbumTrackLine(vm, tr) { vm.push(Route.Track(tr.id)) } }
                }
            }
        }
    }
}

@Composable
private fun AlbumTrackLine(vm: MainViewModel, tr: TripEntity, onClick: () -> Unit) {
    val d = Syne.dims
    val t = Syne.type
    val edit by remember(tr.id) { vm.edit(tr.id) }.collectAsState(UserEdit.None)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) SynePalette.Surface2 else Color.Transparent, tween(160), label = "row")
    Column {
        Row(
            Modifier.fillMaxWidth().height(d.touchL + d.gapL).background(bg).clickable(src, indication = null, onClick = onClick).padding(horizontal = d.gapXS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TripCover(tr.coverPath, Modifier.size(d.touchL), targetPx = 256)
            Spacer(Modifier.width(d.gapM))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(EditRules.displayTitle(defaultTitle(tr.volume, tr.genre), edit), style = t.heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (edit.favorite) { Spacer(Modifier.width(d.gapXS)); Icon(SyneIcon.HeartFill, SynePalette.Gold, size = d.icon * 0.8f) }
                }
                Text("${formatDate(tr.startedAt)} · ${formatDuration(tr.songSec)}", style = t.meta)
            }
            ScoreText(tr.avgSmoothness.toInt(), t.heading)
            Spacer(Modifier.width(d.gapXS))
        }
        HairLine()
    }
}

// =====================================================================
//  SCREEN 03 — 주행 완료 / 앨범 결과
// =====================================================================

/**
 * 커버와 오른쪽 정보 단이 같은 높이(위·아래 끝 맞춤)로 하나의 '음반'처럼 보이게:
 *  커버 | 저장 상태 → 제목(사용자 제목 우선) → Vol·장르·코스 → 날짜 → [꾸미기][즐겨찾기] → 기록 4칸 → (여백) → 하이라이트 플레이어
 * 플레이어가 커버 바로 옆, 아래 끝에 붙어 '커버 + 소리'가 한 묶음이 된다.
 */
@Composable
fun TrackScreen(vm: MainViewModel, tripId: Long, fresh: Boolean = false) {
    val trip by remember(tripId) { vm.track(tripId) }.collectAsState(null)
    val album by remember(trip?.albumId) { trip?.albumId?.let { vm.album(it) } ?: flowOf(null) }.collectAsState(null)
    val edit by remember(tripId) { vm.edit(tripId) }.collectAsState(UserEdit.None)
    val highlightOf by vm.highlightOf.collectAsStateWithLifecycle()
    val progress by vm.highlightProgress.collectAsStateWithLifecycle()
    val parked by vm.parkConfirmed.collectAsStateWithLifecycle()
    val wave by produceState(WaveData.Calm, tripId) { value = vm.trackWave(tripId) }
    val meters by produceState<Int?>(null, tripId) { value = vm.drivenMeters(tripId) }
    val tr = trip ?: return
    val hl by remember(tr.id, tr.highlightPath) { vm.highlightState(tr) }.collectAsState(MediaState.NONE)
    val art by remember(tr.id) { vm.artState(tr.id) }.collectAsState(ArtState.LOCAL)
    val levels = remember(wave) { waveLevels(wave.notes, bars = 64) }
    val playingThis = highlightOf == tr.id
    val d = Syne.dims
    val t = Syne.type
    val auto = defaultTitle(tr.volume, tr.genre)

    BoxWithConstraints(Modifier.fillMaxSize().background(SynePalette.Bg).padding(horizontal = d.margin, vertical = 40.dp)) {
        val coverSize = (maxHeight - d.touch - d.gapL * 2).coerceAtMost(maxWidth * 0.48f)
        Column(Modifier.fillMaxSize()) {
            // ---------- 머리줄 ----------
            Row(Modifier.height(d.touch), verticalAlignment = Alignment.CenterVertically) {
                if (fresh) StatusIndicator(StatusTone.Done, "주행 완료", style = t.title)
                else NavHeader("보관함", { vm.stopHighlight(); vm.pop() }, Modifier.width(420.dp))
                Spacer(Modifier.weight(1f))
                SyneButton("홈", { vm.stopHighlight(); vm.goHome() }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Home)
                Spacer(Modifier.width(d.gapS))
                SyneButton("보관함", { vm.stopHighlight(); vm.openLibrary(tr.id) }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Archive)
            }
            // 커버+정보 묶음을 남은 높이의 가운데에(화면 비율이 바뀌어도 위·아래 여백이 같게)
            Spacer(Modifier.weight(1f).height(d.gapL))

            Row(Modifier.height(coverSize), horizontalArrangement = Arrangement.spacedBy(d.gapXL)) {
                // ---------- 커버 ----------
                Box(Modifier.size(coverSize)) {
                    TripCover(tr.coverPath, Modifier.fillMaxSize(), targetPx = 1024, corner = d.radiusM, placeholder = "앨범 아트를 그리는 중")
                    val badge = when (art) {
                        ArtState.AI_PENDING -> "AI 아트 생성 중 · 기본 아트 표시"
                        ArtState.AI_FAILED -> "AI 생성 실패 · 기기에서 그린 아트"
                        ArtState.AI_DONE -> "AI 생성 아트"
                        else -> null
                    }
                    badge?.let {
                        Text(
                            it, style = t.meta.copy(color = SynePalette.Text),
                            modifier = Modifier.align(Alignment.TopStart).padding(d.gapS).clip(RoundedCornerShape(d.radiusS))
                                .background(SynePalette.Scrim).padding(horizontal = d.gapS, vertical = d.gapXS),
                        )
                    }
                }
                // ---------- 정보 단(커버와 같은 높이) ----------
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("오늘의 주행이\n작품이 되었습니다.", style = t.display.copy(fontSize = t.display.fontSize * 0.72f, lineHeight = t.display.lineHeight * 0.72f))
                    Spacer(Modifier.height(d.gapS))
                    when (hl) {
                        MediaState.PENDING -> StatusIndicator(StatusTone.Active, "음악을 저장하는 중", style = t.label)
                        MediaState.FAILED -> StatusIndicator(StatusTone.Error, "하이라이트를 만들지 못했어요 · 기록과 아트는 저장됨", style = t.label)
                        MediaState.READY -> StatusIndicator(StatusTone.Done, if (fresh) "앨범 저장 완료 · 보관함에 담겼어요" else "보관함에 저장됨", style = t.label)
                        MediaState.NONE -> StatusIndicator(StatusTone.Waiting, "하이라이트 없이 저장된 곡", style = t.label)
                    }
                    Spacer(Modifier.height(d.gapM))
                    Text(EditRules.displayTitle(auto, edit), style = t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (edit.customTitle != null) "$auto · " else "") + (album?.name ?: ""),
                        style = t.heading.copy(color = SynePalette.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(formatDate(tr.startedAt), style = t.meta)
                    if (edit.tags.isNotEmpty()) {
                        Spacer(Modifier.height(d.gapXS))
                        Row(horizontalArrangement = Arrangement.spacedBy(d.gapXS)) { edit.tags.take(4).forEach { TagChip(it) } }
                    }

                    Spacer(Modifier.height(d.gapM))
                    Row(horizontalArrangement = Arrangement.spacedBy(d.gapS), verticalAlignment = Alignment.CenterVertically) {
                        SyneButton("앨범 꾸미기", { vm.stopHighlight(); vm.openEdit(tr.id) }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Edit, enabled = parked)
                        FavoriteButton(edit.favorite, { vm.toggleFavorite(tr.id) })
                    }
                    if (!parked) Text("정차(P)가 확인되면 편집할 수 있어요 · 휴대폰에서도 꾸밀 수 있어요", style = t.meta)

                    Spacer(Modifier.height(d.gapM))
                    // 통계 줄: 첫 칸은 넓은 부드러움 카드(주행 중엔 숨겨 둔 점수를 여기서 공개 — 링 + 큰 금빛 숫자), 이어서 거리·시간·급제동
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
                        SmoothCard(tr.avgSmoothness.toInt(), Modifier.weight(1.7f).fillMaxHeight())
                        StatTile(SyneIcon.Distance, "거리", meters?.let { formatKm(it) } ?: "–", "km", Modifier.weight(1f).fillMaxHeight())
                        StatTile(SyneIcon.Clock, "시간", formatDuration(tr.songSec), "", Modifier.weight(1f).fillMaxHeight())
                        StatTile(SyneIcon.Brake, "급제동", "${tr.hardBrakes}", "회", Modifier.weight(1f).fillMaxHeight())
                    }
                    Spacer(Modifier.weight(1f))
                    MiniPlayer(
                        cover = null,
                        title = "하이라이트 · 도착 직전 30초",
                        subtitle = EditRules.displayTitle(auto, edit),
                        levels = levels,
                        progress = progress,
                        timeText = if (playingThis) clock(progress) else "0:30",
                        playing = playingThis,
                        enabled = hl == MediaState.READY,
                        onToggle = { vm.toggleHighlight(tr) },
                        disabledText = when (hl) {
                            MediaState.PENDING -> "음악을 저장하는 중"
                            MediaState.FAILED -> "만들지 못했어요"
                            else -> "하이라이트 없음"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 부드러움 카드: 링 + 큰 금빛 숫자(통계 카드와 같은 모양·높이) */
@Composable
private fun SmoothCard(value: Int, modifier: Modifier) {
    val d = Syne.dims
    val t = Syne.type
    Row(
        modifier.clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(horizontal = d.cardPad, vertical = d.gapM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmoothRing(value, 76.dp)
        Spacer(Modifier.width(d.gapM))
        Column {
            Text("부드러움", style = t.label)
            Text(
                buildAnnotatedString {
                    append("$value")
                    withStyle(SpanStyle(fontSize = t.numericL.fontSize * 0.3f)) { append(" %") }
                },
                style = t.numericL.copy(color = SynePalette.Gold, fontSize = t.numericL.fontSize * 0.7f, lineHeight = t.numericL.lineHeight * 0.7f),
                maxLines = 1,
            )
        }
    }
}

/** 부드러움 링(금빛): 0~100%를 원호로 */
@Composable
private fun SmoothRing(value: Int, size: Dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.minDimension * 0.1f
        val inset = w / 2
        val arc = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(SynePalette.LineStrong, 0f, 360f, false, tl, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(SynePalette.Gold, -90f, 360f * value.coerceIn(0, 100) / 100f, false, tl, arc,
            style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}
