package ai.syne.mobile.ui

import ai.syne.core.art.OrbitParams
import ai.syne.core.art.SignatureObject
import ai.syne.core.art.WaveData
import ai.syne.core.design.AlbumCard
import ai.syne.core.design.Brand
import ai.syne.core.design.ButtonKind
import ai.syne.core.design.ButtonSize
import ai.syne.core.design.Choice
import ai.syne.core.design.Cover
import ai.syne.core.design.FilterChips
import ai.syne.core.design.HairLine
import ai.syne.core.design.Icon
import ai.syne.core.design.IconButton
import ai.syne.core.design.MetaRow
import ai.syne.core.design.NavHeader
import ai.syne.core.design.PlayButton
import ai.syne.core.design.ScoreText
import ai.syne.core.design.SeekBar
import ai.syne.core.design.StatTile
import ai.syne.core.design.StatusIndicator
import ai.syne.core.design.StatusTone
import ai.syne.core.design.Syne
import ai.syne.core.design.SyneButton
import ai.syne.core.design.SyneIcon
import ai.syne.core.design.SynePalette
import ai.syne.core.design.Waveform
import ai.syne.core.design.waveLevels
import ai.syne.core.share.SharedTrack
import ai.syne.core.share.EditRules
import ai.syne.core.share.UserEdit
import ai.syne.core.design.FavoriteButton
import ai.syne.core.design.SyneTextField
import ai.syne.core.design.TagChip
import ai.syne.core.share.SyncState
import ai.syne.core.share.TimelineEvent
import ai.syne.mobile.MobileViewModel
import ai.syne.mobile.Screen
import ai.syne.mobile.Tab
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================
//  표시 도우미(저장된 값만 쓴다)
// =====================================================================

private val GENRES = listOf("lofi" to "Lo-fi", "hiphop" to "Hip-hop", "ambient" to "Ambient")
private fun genreLabel(id: String) = GENRES.firstOrNull { it.first == id }?.second ?: if (id == "citypop") "City Pop" else id
private val ART = mapOf(
    "film_photo" to "필름 사진", "riso_print" to "리소 인쇄", "hand_drawn" to "잉크 드로잉", "vintage_editorial" to "빈티지 편집",
    "watercolor" to "수채화", "oil_painting" to "유화", "color_field" to "컬러 필드", "surreal" to "초현실", "night_bokeh" to "밤 불빛",
    "sunset_stripes" to "레트로 노을", "neon_city" to "네온 시티", "geometric_pop" to "기하 팝", "fog_landscape" to "안개 풍경",
    "still_horizon" to "고요한 수평선", "organic_forms" to "유기적 형태", "collage" to "콜라주", "street_graphic" to "스트리트 그래픽", "hard_light" to "하드 라이트",
)
private fun dur(sec: Float) = "%d:%02d".format(sec.toInt() / 60, sec.toInt() % 60)
private fun km(m: Int) = "%.1f".format(m / 1000f)
/**
 * 시간대 정책: 주행 기록의 날짜·시각은 **주행한 곳(차량)의 현지 시각**으로 표시한다(번들의 timeZone).
 * 그래서 차량과 휴대폰이 같은 곡을 같은 날짜로 보여 준다. timeZone이 없는 예전 번들만 이 기기 시간대로 표시한다.
 */
private fun fmt(pattern: String, ms: Long, tz: String?) = SimpleDateFormat(pattern, Locale.KOREA).apply { tz?.let { timeZone = java.util.TimeZone.getTimeZone(it) } }.format(Date(ms))
private fun SharedTrack.day(ms: Long = startedAt) = fmt("yyyy.MM.dd (E)", ms, timeZone)
private fun SharedTrack.hm(ms: Long = startedAt) = fmt("HH:mm", ms, timeZone)
/** 휴대폰 시간대와 다를 때만 붙이는 표시(예: ' · KST') */
private fun SharedTrack.zoneNote(): String = timeZone?.takeIf { it != java.util.TimeZone.getDefault().id }
    ?.let { " · " + java.util.TimeZone.getTimeZone(it).getDisplayName(false, java.util.TimeZone.SHORT, Locale.KOREA) } ?: ""
private fun SharedTrack.timeOfDay(ms: Long = startedAt) = when (Calendar.getInstance(timeZone?.let { java.util.TimeZone.getTimeZone(it) } ?: java.util.TimeZone.getDefault()).apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)) {
    in 5..7 -> "새벽"; in 8..11 -> "아침"; in 12..16 -> "낮"; in 17..19 -> "저녁"; else -> "밤"
}
private fun autoTitle(t: SharedTrack) = "Vol.${t.volume} · ${genreLabel(t.genre)}"
/** 화면 제목: 사용자 제목이 있으면 그것, 없으면 자동 제목 */
private fun title(t: SharedTrack, e: UserEdit) = EditRules.displayTitle(autoTitle(t), e)

// =====================================================================
//  루트: 하단 탭 + 상세 화면
// =====================================================================

@Composable
fun MobileRoot(vm: MobileViewModel, onImport: () -> Unit, onBackExit: () -> Unit) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    BackHandler { if (!vm.back()) onBackExit() }

    Box(Modifier.fillMaxSize().background(SynePalette.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (val s = screen) {
                    is Screen.AlbumDetail -> AlbumDetail(vm, s.uid)
                    is Screen.NowPlaying -> NowPlaying(vm, s.uid)
                    is Screen.Record -> RecordScreen(vm, s.uid)
                    is Screen.Edit -> EditScreen(vm, s.uid)
                    null -> when (tab) {
                        Tab.Home -> HomeTab(vm, onImport)
                        Tab.Library -> LibraryTab(vm, onImport)
                        Tab.Records -> RecordsTab(vm, onImport)
                        Tab.Connect -> ConnectTab(vm, onImport)
                    }
                }
            }
            if (screen !is Screen.NowPlaying && screen !is Screen.Edit) BottomNav(tab) { vm.selectTab(it) }
        }
        toast?.let {
            LaunchedEffect(it) { delay(2600); vm.clearToast() }
            Text(
                it, style = Syne.type.body.copy(color = SynePalette.Text),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)
                    .clip(RoundedCornerShape(Syne.dims.radiusM)).background(SynePalette.Surface2).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun BottomNav(active: Tab, onSelect: (Tab) -> Unit) {
    val items = listOf(Tab.Home to (SyneIcon.Home to "홈"), Tab.Library to (SyneIcon.Archive to "보관함"), Tab.Records to (SyneIcon.Record to "기록"), Tab.Connect to (SyneIcon.Sync to "연결"))
    Column {
        HairLine()
        Row(Modifier.fillMaxWidth().height(64.dp).background(SynePalette.Bg)) {
            items.forEach { (tab, v) ->
                val on = tab == active
                val src = remember(tab) { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(src, null) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(v.first, if (on) SynePalette.Primary else SynePalette.TextTertiary)
                    Spacer(Modifier.height(4.dp))
                    Text(v.second, style = Syne.type.label.copy(color = if (on) SynePalette.Primary else SynePalette.TextTertiary))
                }
            }
        }
    }
}

/** 아직 가져온 앨범이 없을 때: 무엇을 하면 되는지 정직하게 */
@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    val d = Syne.dims
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(d.cardPad)) {
        Icon(SyneIcon.Car, SynePalette.TextSecondary)
        Spacer(Modifier.height(d.gapS))
        Text("아직 가져온 앨범이 없어요", style = Syne.type.heading)
        Spacer(Modifier.height(d.gapXS))
        Text("차량 SYNE 보관함의 ‘휴대폰용 파일 만들기’로 만든 .syne 파일을 USB·파일 공유로 이 휴대폰에 옮긴 뒤 가져오세요. 자동으로 전송되지는 않아요.", style = Syne.type.body)
        Spacer(Modifier.height(d.gapM))
        SyneButton("번들 파일 가져오기", onImport, Modifier.fillMaxWidth(), icon = SyneIcon.Import)
    }
}

// =====================================================================
//  MOBILE 01 — 홈 / 내 앨범
// =====================================================================

@Composable
private fun HomeTab(vm: MobileViewModel, onImport: () -> Unit) {
    val tracks by vm.store.tracks.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val bundle by vm.store.bundleStatus.collectAsStateWithLifecycle()
    val play by vm.player.state.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    val edits by vm.store.edits.collectAsStateWithLifecycle()
    val shown = tracks.filter {
        when (filter) { null -> true; MobileViewModel.FAVORITES -> edits[it.uid]?.favorite == true; else -> it.genre == filter }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = d.margin)) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Brand(height = 22.dp)
            Spacer(Modifier.weight(1f))
            SyncBadge(bundle.state, bundle.lastAt) { vm.selectTab(Tab.Connect) }
        }
        // 헤드라인과 궤도 그래픽은 각자 자리(겹치지 않게): 글자 단 | 그래픽 칸
        Row(Modifier.fillMaxWidth().height(112.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("당신의 드라이브가\n만든 음악 앨범들", style = t.display, modifier = Modifier.weight(1f))
            Box(Modifier.size(width = 132.dp, height = 112.dp)) {
                SignatureObject(
                    OrbitParams.from(tracks.firstOrNull()?.let { WaveData(5, it.smoothness, it.notes.take(12)) } ?: WaveData.Calm, seed = 11),
                    animated = true, modifier = Modifier.fillMaxSize(), centerX = 0.5f, centerY = 0.5f, scale = 1.15f,
                )
            }
        }
        Spacer(Modifier.height(d.gapXS))
        Text("주행의 풍경과 리듬이 담긴 앨범을 다시 들어 보세요.", style = t.body)
        Spacer(Modifier.height(d.gapL))
        if (tracks.isEmpty()) {
            EmptyLibrary(onImport)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.selectTab(Tab.Library) }) {
            Text("내 보관함", style = t.title)
            Spacer(Modifier.width(d.gapS))
            Text("${tracks.size}곡", style = t.body)
            Spacer(Modifier.weight(1f))
            Icon(SyneIcon.Chevron, SynePalette.TextSecondary)
        }
        Spacer(Modifier.height(d.gapM))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChips(listOf(Choice(null, "전체"), Choice(MobileViewModel.FAVORITES, "♥ 즐겨찾기")) + GENRES.map { Choice(it.first, it.second) }, filter, vm::setFilter)
        }
        Spacer(Modifier.height(d.gapM))
        // 최신 앨범(대표)
        shown.firstOrNull()?.let { f ->
            val src = remember(f.uid) { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface)
                    .border(1.dp, SynePalette.Line, RoundedCornerShape(d.radiusM)).clickable(src, null) { vm.open(Screen.AlbumDetail(f.uid)) }.padding(d.gapM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(vm.store.file(f.coverFile), Modifier.size(96.dp), targetPx = 384)
                Spacer(Modifier.width(d.gapM))
                Column(Modifier.weight(1f)) {
                    Text("가장 최근 앨범", style = t.label.copy(color = SynePalette.Primary))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title(f, edits[f.uid] ?: UserEdit.None), style = t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (edits[f.uid]?.favorite == true) { Spacer(Modifier.width(d.gapXS)); Icon(SyneIcon.HeartFill, SynePalette.Gold, size = 18.dp) }
                    }
                    Text(f.albumName, style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.day()} · ${dur(f.songSec)}", style = t.meta)
                }
                PlayButton(play.uid == f.uid && play.playing, vm.player.canPlay(f), { vm.player.play(f) }, size = 48.dp)
            }
        }
        Spacer(Modifier.height(d.gapL))
        Text("최근 생성 앨범", style = t.title)
        Spacer(Modifier.height(d.gapS))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(d.gapM)) {
            items(shown.drop(1).take(12), key = { it.uid }) { a ->
                val e = edits[a.uid] ?: UserEdit.None
                AlbumCard(vm.store.file(a.coverFile), title(a, e), genreLabel(a.genre), null, false, { vm.open(Screen.AlbumDetail(a.uid)) }, Modifier.width(132.dp), favorite = e.favorite)
            }
        }
        Spacer(Modifier.height(d.gapXL))
    }
}

@Composable
private fun SyncBadge(state: SyncState, lastAt: Long?, onClick: () -> Unit) {
    val text = when (state) {
        SyncState.WORKING -> "가져오는 중"
        SyncState.ERROR -> "가져오기 실패"
        SyncState.DONE -> lastAt?.let { "가져옴 · ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(it))}" } ?: "가져옴"
        else -> "차량 미연결"
    }
    Row(
        Modifier.clip(RoundedCornerShape(Syne.dims.radiusS)).background(SynePalette.Surface).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (state == SyncState.ERROR) SynePalette.Error else if (state == SyncState.DONE) SynePalette.Gold else SynePalette.TextTertiary))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Syne.type.label)
    }
}

// =====================================================================
//  보관함 탭(전체 앨범 그리드)
// =====================================================================

@Composable
private fun LibraryTab(vm: MobileViewModel, onImport: () -> Unit) {
    val tracks by vm.store.tracks.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val d = Syne.dims
    val edits by vm.store.edits.collectAsStateWithLifecycle()
    val shown = tracks.filter {
        when (filter) { null -> true; MobileViewModel.FAVORITES -> edits[it.uid]?.favorite == true; else -> it.genre == filter }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = d.margin)) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("보관함", style = Syne.type.display)
            Spacer(Modifier.width(d.gapS))
            Text("${shown.size}곡", style = Syne.type.body)
        }
        if (tracks.isEmpty()) { EmptyLibrary(onImport); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChips(listOf(Choice(null, "전체"), Choice(MobileViewModel.FAVORITES, "♥ 즐겨찾기")) + GENRES.map { Choice(it.first, it.second) }, filter, vm::setFilter)
        }
        Spacer(Modifier.height(d.gapM))
        LazyVerticalGrid(
            GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(d.gapM), verticalArrangement = Arrangement.spacedBy(d.gapL),
            contentPadding = PaddingValues(bottom = d.gapXL),
        ) {
            items(shown, key = { it.uid }) { a ->
                val e = edits[a.uid] ?: UserEdit.None
                AlbumCard(
                    vm.store.file(a.coverFile), title(a, e),
                    if (e.customTitle != null) "${autoTitle(a)} · ${a.albumName.substringAfterLast("→ ")}" else "${a.albumName.substringAfterLast("→ ")} · ${a.day()}",
                    null, false, { vm.open(Screen.AlbumDetail(a.uid)) }, favorite = e.favorite,
                )
            }
        }
    }
}

// =====================================================================
//  기록 탭(날짜순 주행 목록)
// =====================================================================

@Composable
private fun RecordsTab(vm: MobileViewModel, onImport: () -> Unit) {
    val tracks by vm.store.tracks.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    Column(Modifier.fillMaxSize().padding(horizontal = d.margin)) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) { Text("주행 기록", style = t.display) }
        if (tracks.isEmpty()) { EmptyLibrary(onImport); return@Column }
        LazyColumn(contentPadding = PaddingValues(bottom = d.gapXL)) {
            var lastDay = ""
            tracks.forEach { tr ->
                val dd = tr.day()
                if (dd != lastDay) {
                    lastDay = dd
                    item(key = "h$dd") { Text(dd, style = t.label, modifier = Modifier.padding(top = d.gapL, bottom = d.gapS)) }
                }
                item(key = tr.uid) {
                    Row(
                        Modifier.fillMaxWidth().height(76.dp).clickable { vm.open(Screen.Record(tr.uid)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(vm.store.file(tr.coverFile), Modifier.size(56.dp), targetPx = 192)
                        Spacer(Modifier.width(d.gapM))
                        Column(Modifier.weight(1f)) {
                            Text(tr.albumName, style = t.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${tr.hm()} 출발 · ${km(tr.distanceM)}km · ${dur(tr.songSec)}", style = t.meta)
                        }
                        ScoreText(tr.smoothness, t.control)
                    }
                    HairLine()
                }
            }
        }
    }
}

// =====================================================================
//  연결 탭: 실제로 되는 것(번들 파일)과 아직 안 되는 것(실시간 동기화)을 구분
// =====================================================================

@Composable
private fun ConnectTab(vm: MobileViewModel, onImport: () -> Unit) {
    val bundle by vm.store.bundleStatus.collectAsStateWithLifecycle()
    val tracks by vm.store.tracks.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = d.margin)) {
        Row(Modifier.height(56.dp), verticalAlignment = Alignment.CenterVertically) { Text("차량 연결", style = t.display) }
        Spacer(Modifier.height(d.gapS))
        // 번들 파일 — 동작함
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(d.cardPad)) {
            StatusIndicator(
                when (bundle.state) { SyncState.DONE -> StatusTone.Done; SyncState.ERROR -> StatusTone.Error; SyncState.WORKING -> StatusTone.Active; else -> StatusTone.Waiting },
                when (bundle.state) { SyncState.DONE -> "번들 파일로 가져옴"; SyncState.ERROR -> "가져오기 실패"; SyncState.WORKING -> "가져오는 중"; else -> "아직 가져오지 않음" },
            )
            Spacer(Modifier.height(d.gapS))
            bundle.lastAt?.let { Text("마지막 가져오기 ${SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(it))} · ${tracks.size}곡", style = t.meta) }
            bundle.message?.takeIf { bundle.state == SyncState.ERROR }?.let { Text(it, style = t.meta.copy(color = SynePalette.Error)) }
            Spacer(Modifier.height(d.gapM))
            Text("1. 차량 SYNE ▸ 보관함 ▸ ‘휴대폰용 파일 만들기’\n2. 다운로드/SYNE의 .syne 파일을 USB·파일 공유로 휴대폰에 옮기기\n3. 아래 버튼으로 가져오기 — 같은 곡은 중복되지 않아요", style = t.body)
            Spacer(Modifier.height(d.gapS))
            Text("같은 곡을 다시 가져오면 주행 기록·음악·아트는 새 파일로 맞추고, 제목·메모·태그·즐겨찾기는 더 나중에 고친 쪽을 남겨요. 휴대폰에서 고친 내용은 차량으로 돌아가지 않아요.", style = t.meta)
            Spacer(Modifier.height(d.gapM))
            SyneButton("번들 파일 가져오기", onImport, Modifier.fillMaxWidth(), icon = SyneIcon.Import)
        }
        Spacer(Modifier.height(d.gapM))
        // 실시간 동기화 — 아직 없음
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).border(1.dp, SynePalette.Line, RoundedCornerShape(d.radiusM)).padding(d.cardPad)) {
            StatusIndicator(StatusTone.Waiting, "실시간 동기화 · 준비 중")
            Spacer(Modifier.height(d.gapS))
            Text(vm.store.remoteStatus.message ?: "", style = t.body)
            Text("서버가 생기면 같은 앨범 ID로 자동으로 이어집니다.", style = t.meta)
        }
        Spacer(Modifier.height(d.gapXL))
    }
}

// =====================================================================
//  MOBILE 02 — 앨범 상세
// =====================================================================

@Composable
private fun AlbumDetail(vm: MobileViewModel, uid: String) {
    val tr = vm.track(uid) ?: return
    val edits by vm.store.edits.collectAsStateWithLifecycle()
    val e = edits[uid] ?: UserEdit.None
    val ctx = LocalContext.current
    val d = Syne.dims
    val t = Syne.type
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = d.margin)) {
        NavHeader("앨범 상세", { vm.back() }) {
            FavoriteButton(e.favorite, { vm.toggleFavorite(uid) }, label = false)
            Spacer(Modifier.width(d.gapXS))
            IconButton(SyneIcon.Edit, { vm.open(Screen.Edit(uid)) })
        }
        Spacer(Modifier.height(d.gapS))
        Cover(vm.store.file(tr.coverFile), Modifier.fillMaxWidth(0.82f).aspectRatio(1f).align(Alignment.CenterHorizontally), targetPx = 1024, corner = d.radiusM, placeholder = "앨범 아트 없음")
        Spacer(Modifier.height(d.gapL))
        Text(title(tr, e), style = t.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text((if (e.customTitle != null) "${autoTitle(tr)} · " else "") + tr.albumName, style = t.heading.copy(color = SynePalette.TextSecondary))
        Spacer(Modifier.height(d.gapS))
        MetaRow(SyneIcon.Calendar, "${tr.day()} ${tr.hm()}–${tr.hm(tr.endedAt)}${tr.zoneNote()}")
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapM)) {
            MetaRow(SyneIcon.Music, genreLabel(tr.genre))
            MetaRow(SyneIcon.Clock, dur(tr.songSec))
            tr.artMedium?.let { MetaRow(SyneIcon.Spark, (ART[it] ?: it) + if (tr.artSource == "ai") " · AI" else "") }
        }
        // 사용자가 쓴 글(설명) — 자동 기록과 구분되는 본문
        e.description?.let {
            Spacer(Modifier.height(d.gapM))
            Text(it, style = t.body.copy(color = SynePalette.Text))
        }
        if (e.tags.isNotEmpty()) {
            Spacer(Modifier.height(d.gapS))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.gapXS)) { e.tags.forEach { TagChip(it) } }
        }
        Spacer(Modifier.height(d.gapL))
        val canPlay = vm.player.canPlay(tr)
        SyneButton(if (canPlay) "하이라이트 재생" else "재생할 음악 파일 없음", { vm.player.play(tr); vm.open(Screen.NowPlaying(uid)) }, Modifier.fillMaxWidth(), size = ButtonSize.Large, icon = SyneIcon.Play, enabled = canPlay)
        Spacer(Modifier.height(d.gapM))
        // 개인 메모 — '나만 보는 기록'으로 따로, 비어 있으면 쓰기 안내
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).clickable { vm.open(Screen.Edit(uid)) }.padding(d.cardPad),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SyneIcon.Edit, SynePalette.TextSecondary, size = 16.dp)
                Spacer(Modifier.width(d.gapXS))
                Text("내 메모", style = t.label)
            }
            Spacer(Modifier.height(d.gapXS))
            Text(e.memo ?: "그날의 기분이나 있었던 일을 적어 보세요.", style = t.body.copy(color = if (e.memo != null) SynePalette.Text else SynePalette.TextTertiary))
        }
        Spacer(Modifier.height(d.gapM))
        Text("주행 기록", style = t.label)
        Spacer(Modifier.height(d.gapXS))
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            StatTile(SyneIcon.Distance, "거리", km(tr.distanceM), "km", Modifier.weight(1f))
            StatTile(SyneIcon.Clock, "시간", dur(tr.songSec), "", Modifier.weight(1f))
            StatTile(SyneIcon.Smooth, "부드러움", "${tr.smoothness}", "%", Modifier.weight(1f), accent = true)
        }
        Spacer(Modifier.height(d.gapM))
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            SyneButton("타임라인", { vm.open(Screen.Record(uid)) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = SyneIcon.Record)
            SyneButton("공유", {
                // 실제 공유: 앨범 아트 이미지 + 글(메모는 개인 기록이라 넣지 않는다)
                val f = vm.store.file(tr.coverFile)
                val send = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_TEXT, "SYNE · ${title(tr, e)} — ${tr.albumName}, 주행 ${km(tr.distanceM)}km, 부드러움 ${tr.smoothness}%")
                    if (f != null) {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "ai.syne.mobile.files", f))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else type = "text/plain"
                }
                ctx.startActivity(Intent.createChooser(send, "앨범 공유"))
            }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = SyneIcon.Share)
        }
        Spacer(Modifier.height(d.gapXL))
    }
}

// =====================================================================
//  앨범 편집(모바일) — 입력 편의 우선
// =====================================================================

@Composable
private fun EditScreen(vm: MobileViewModel, uid: String) {
    val tr = vm.track(uid) ?: return
    val start = remember(uid) { vm.store.edit(uid) }
    var title by remember(uid) { mutableStateOf(start.customTitle ?: "") }
    var description by remember(uid) { mutableStateOf(start.description ?: "") }
    var memo by remember(uid) { mutableStateOf(start.memo ?: "") }
    var tags by remember(uid) { mutableStateOf(start.tags) }
    var tagInput by remember(uid) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = title != (start.customTitle ?: "") || description != (start.description ?: "") || memo != (start.memo ?: "") || tags != start.tags || tagInput.isNotBlank()
    val d = Syne.dims
    val t = Syne.type
    fun addTag() { tags = EditRules.tags(tags + tagInput); tagInput = "" }
    fun leave() { if (dirty) confirmLeave = true else vm.back() }
    BackHandler { leave() }

    Column(Modifier.fillMaxSize().imePadding()) {
        NavHeader("앨범 편집", ::leave, Modifier.padding(horizontal = d.margin), leading = SyneIcon.Close) {
            SyneButton("저장", { error = vm.saveEdit(uid, title, description, memo, tags + listOfNotNull(EditRules.tag(tagInput))) }, size = ButtonSize.Compact)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.margin)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(vm.store.file(tr.coverFile), Modifier.size(72.dp), targetPx = 256)
                Spacer(Modifier.width(d.gapM))
                Column {
                    Text(EditRules.clean(title, EditRules.TITLE_MAX, true) ?: autoTitle(tr), style = t.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${autoTitle(tr)} · ${tr.day()}", style = t.meta)
                }
            }
            Spacer(Modifier.height(d.gapS))
            Text("음악·앨범 아트·주행 기록은 바뀌지 않아요.", style = t.meta.copy(color = SynePalette.TextTertiary))
            Spacer(Modifier.height(d.gapL))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("제목", style = t.label, modifier = Modifier.weight(1f))
                if (title.isNotEmpty()) Text("기본 제목으로", style = t.label.copy(color = SynePalette.Primary), modifier = Modifier.clip(RoundedCornerShape(d.radiusS)).clickable { title = "" }.padding(8.dp))
            }
            SyneTextField(title, { title = it.replace("\n", " ") }, "비우면 ‘${autoTitle(tr)}’", Modifier.fillMaxWidth(), maxChars = EditRules.TITLE_MAX)
            Spacer(Modifier.height(d.gapM))
            Text("설명", style = t.label)
            SyneTextField(description, { description = it }, "이 앨범을 한두 문장으로", Modifier.fillMaxWidth(), singleLine = false, minLines = 3, maxChars = EditRules.DESCRIPTION_MAX)
            Spacer(Modifier.height(d.gapM))
            Text("개인 메모 · 나만 보는 기록", style = t.label)
            SyneTextField(memo, { memo = it }, "그날의 기분이나 있었던 일", Modifier.fillMaxWidth(), singleLine = false, minLines = 5, maxChars = EditRules.MEMO_MAX)
            Spacer(Modifier.height(d.gapM))
            Text("태그 · 최대 ${EditRules.TAGS_MAX}개", style = t.label)
            Spacer(Modifier.height(d.gapXS))
            if (tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.gapXS)) {
                    tags.forEach { tag -> TagChip(tag, onRemove = { tags = tags - tag }) }
                }
                Spacer(Modifier.height(d.gapXS))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyneTextField(tagInput, { tagInput = it }, "#퇴근길", Modifier.weight(1f), imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = ::addTag, enabled = tags.size < EditRules.TAGS_MAX)
                Spacer(Modifier.width(d.gapS))
                SyneButton("추가", ::addTag, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Plus, enabled = tagInput.isNotBlank() && tags.size < EditRules.TAGS_MAX)
            }
            error?.let { Spacer(Modifier.height(d.gapS)); StatusIndicator(StatusTone.Error, it, style = t.body) }
            Spacer(Modifier.height(d.gapXL))
        }
    }
    if (confirmLeave) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = SynePalette.Surface2,
            title = { Text("저장하지 않고 나갈까요?", style = t.title) },
            text = { Text("고친 내용이 사라져요.", style = t.body) },
            confirmButton = { SyneButton("나가기", { confirmLeave = false; vm.back() }, kind = ButtonKind.Secondary, size = ButtonSize.Compact) },
            dismissButton = { SyneButton("계속 편집", { confirmLeave = false }, size = ButtonSize.Compact) },
        )
    }
}

// =====================================================================
//  MOBILE 03 — 음악 재생
// =====================================================================

@Composable
private fun NowPlaying(vm: MobileViewModel, uid: String) {
    val tr = vm.track(uid) ?: return
    val play by vm.player.state.collectAsStateWithLifecycle()
    val tracks by vm.store.tracks.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    val levels = remember(uid) { waveLevels(tr.notes, bars = 56) }
    val mine = play.uid == uid
    val idx = tracks.indexOfFirst { it.uid == uid }
    fun go(delta: Int) {
        val next = tracks.getOrNull(idx + delta) ?: return
        if (!vm.player.canPlay(next)) return
        vm.player.play(next); vm.back(); vm.open(Screen.NowPlaying(next.uid))
    }
    Column(Modifier.fillMaxSize().padding(horizontal = d.margin), horizontalAlignment = Alignment.CenterHorizontally) {
        val edits by vm.store.edits.collectAsStateWithLifecycle()
        val e = edits[uid] ?: UserEdit.None
        NavHeader("지금 재생 중", { vm.back() }) { FavoriteButton(e.favorite, { vm.toggleFavorite(uid) }, label = false) }
        Spacer(Modifier.weight(0.6f))
        Cover(vm.store.file(tr.coverFile), Modifier.fillMaxWidth(0.86f).aspectRatio(1f), targetPx = 1024, corner = d.radiusM)
        Spacer(Modifier.height(d.gapXL))
        Text(title(tr, e), style = t.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text((if (e.customTitle != null) "${autoTitle(tr)} · " else "") + tr.albumName, style = t.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(d.gapL))
        Waveform(levels, if (mine) play.progress else 0f, Modifier.fillMaxWidth().height(40.dp))
        SeekBar(if (mine) play.progress else 0f, if (mine) vm.player::seek else null, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
            Text(if (mine) dur(play.positionMs / 1000f) else "0:00", style = t.meta)
            Spacer(Modifier.weight(1f))
            Text(if (mine && play.durationMs > 0) dur(play.durationMs / 1000f) else "0:30", style = t.meta)
        }
        Spacer(Modifier.height(d.gapM))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.gapXL)) {
            IconButton(SyneIcon.Prev, { go(1) }, enabled = idx in 0 until tracks.size - 1)
            PlayButton(mine && play.playing, vm.player.canPlay(tr), { vm.player.play(tr) }, size = 76.dp)
            IconButton(SyneIcon.Next, { go(-1) }, enabled = idx > 0)
        }
        Spacer(Modifier.height(d.gapS))
        Text("도착 직전 30초 하이라이트", style = t.meta)
        Spacer(Modifier.weight(1f))
    }
}

// =====================================================================
//  MOBILE 04 — 주행 기록 / 회고
// =====================================================================

@Composable
private fun RecordScreen(vm: MobileViewModel, uid: String) {
    val tr = vm.track(uid) ?: return
    val d = Syne.dims
    val t = Syne.type
    val stops = tr.timeline.count { it.kind == TimelineEvent.STOP }
    val tags = buildList {
        add(genreLabel(tr.genre)); add(tr.timeOfDay())
        tr.artMedium?.let { add(ART[it] ?: it) }
        if (tr.hardBrakes == 0) add("급제동 없음")
        if (tr.smoothness >= 90) add("아주 부드러움")
        if (tr.tags.any { it == "kind:travel" }) add("여행")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = d.margin)) {
        NavHeader("기록 / 회고", { vm.back() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(vm.store.file(tr.coverFile), Modifier.size(72.dp), targetPx = 256)
            Spacer(Modifier.width(d.gapM))
            Column {
                val e = vm.store.edits.collectAsStateWithLifecycle().value[uid] ?: UserEdit.None
                Text(title(tr, e), style = t.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr.albumName, style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${tr.day()} ${tr.hm()} – ${tr.hm(tr.endedAt)}${tr.zoneNote()}", style = t.meta)
            }
        }
        Spacer(Modifier.height(d.gapM))
        Row(horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            StatTile(SyneIcon.Distance, "거리", km(tr.distanceM), "km", Modifier.weight(1f))
            StatTile(SyneIcon.Clock, "시간", dur(tr.songSec), "", Modifier.weight(1f))
            StatTile(SyneIcon.Smooth, "부드러움", "${tr.smoothness}", "%", Modifier.weight(1f), accent = true)
        }
        Spacer(Modifier.height(d.gapL))
        Text("태그", style = t.title)
        Spacer(Modifier.height(d.gapS))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.gapS)) {
            tags.forEach {
                Text("#$it", style = t.label.copy(color = SynePalette.Focus), modifier = Modifier.clip(RoundedCornerShape(d.radiusS)).background(SynePalette.PrimaryTint).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        Spacer(Modifier.height(d.gapL))
        Text("그날의 드라이브", style = t.title)
        Spacer(Modifier.height(d.gapS))
        // 저장된 값으로만 쓴 요약(지어낸 감상 없음)
        Text(
            "${tr.timeOfDay()} ${tr.hm()}, ${tr.albumName.substringBefore(" →")}에서 출발해 ${dur(tr.songSec)} 동안 ${km(tr.distanceM)}km를 달렸어요. " +
                "정차\u00A0${stops}번, 급제동\u00A0${tr.hardBrakes}\u2060회. 부드러움\u00A0${tr.smoothness}%의 ${genreLabel(tr.genre)} 곡이 되었습니다.",
            style = t.body.copy(color = SynePalette.Text),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(d.radiusM)).background(SynePalette.Surface).padding(d.cardPad),
        )
        Spacer(Modifier.height(d.gapL))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("주행 타임라인", style = t.title)
            Spacer(Modifier.width(d.gapS))
            Text("${tr.hm()} 출발 기준", style = t.meta)
        }
        Spacer(Modifier.height(d.gapS))
        if (tr.timeline.isEmpty()) Text("이 곡에는 1초 기록이 없어요.", style = t.body)
        tr.timeline.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                // 출발 후 경과 시간(같은 분 안에 여러 사건이 있어도 구분되게)
                Text("+" + dur(e.tSec.toFloat()), style = t.meta.copy(fontFeatureSettings = "tnum"), modifier = Modifier.width(52.dp))
                Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(2.dp).fillMaxHeight().background(if (i == 0 || i == tr.timeline.lastIndex) SynePalette.Bg else SynePalette.Line))
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
                            when (e.kind) { TimelineEvent.DEPART, TimelineEvent.ARRIVE -> SynePalette.Primary; TimelineEvent.STOP -> SynePalette.TextTertiary; else -> SynePalette.Gold },
                        ),
                    )
                }
                Spacer(Modifier.width(d.gapS))
                Column {
                    Text(
                        when (e.kind) { TimelineEvent.DEPART -> "출발"; TimelineEvent.ARRIVE -> "도착"; TimelineEvent.STOP -> "정차"; else -> "음악" },
                        style = t.heading,
                    )
                    Text(e.label, style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(d.gapXL))
    }
}
