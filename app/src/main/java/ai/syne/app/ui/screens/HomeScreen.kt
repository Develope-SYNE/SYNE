package ai.syne.app.ui.screens

import ai.syne.app.Genre
import ai.syne.app.engine.Section
import ai.syne.app.ui.MainViewModel
import ai.syne.app.ui.Route
import ai.syne.core.art.SignatureObject
import ai.syne.core.design.Brand
import ai.syne.core.design.ButtonKind
import ai.syne.core.design.ButtonSize
import ai.syne.core.design.Choice
import ai.syne.core.design.FilterChips
import ai.syne.core.design.GenreSelector
import ai.syne.core.design.HairLine
import ai.syne.core.design.Icon
import ai.syne.core.design.IconButton
import ai.syne.core.design.Syne
import ai.syne.core.design.SyneButton
import ai.syne.core.design.SyneIcon
import ai.syne.core.design.SynePalette
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * SCREEN 01 — 장르 선택 및 주행 시작.
 * 왼쪽 단(폭 고정 640dp) 하나의 순서: 제목 → 장르 → 목적지 → 시작(가장 강한 요소, 파랑).
 * 오른쪽 절반 가운데: 시그니처 오브젝트(장르마다 결이 조금씩 다르다).
 * 머리줄: 로고 / [데모 코스 A·B + 데모 주행] · [보관함] 두 묶음만.
 * 로고 길게 누르기 = 개발자 메뉴.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val genre by vm.genre.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val section by vm.songSection.collectAsStateWithLifecycle()
    val demoCourse by vm.demoCourse.collectAsStateWithLifecycle()
    val orbit by vm.homeOrbit.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type

    Box(Modifier.fillMaxSize().background(SynePalette.Bg)) {
        // 오른쪽 영역(왼쪽 단 끝 ~ 오른쪽 여백)의 가운데, 왼쪽 단 블록의 세로 중심과 같은 높이
        SignatureObject(orbit, animated = true, modifier = Modifier.fillMaxSize(), centerX = 0.705f, centerY = 0.53f, scale = 0.9f)

        Column(Modifier.fillMaxSize().padding(horizontal = d.margin, vertical = 40.dp)) {
            // ---------- 머리줄 ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Brand(Modifier.clip(RoundedCornerShape(d.radiusS)).combinedClickable(onClick = {}, onLongClick = { vm.push(Route.Debug) }))
                Spacer(Modifier.weight(1f))
                Text("데모", style = t.label)
                Spacer(Modifier.width(d.gapS))
                FilterChips(
                    vm.demoCourses.map { Choice(it, "코스 $it", enabled = !playing) },
                    selected = demoCourse, onSelect = { it?.let(vm::selectDemoCourse) },
                )
                Spacer(Modifier.width(d.gapS))
                SyneButton("데모 주행", { vm.startDemo() }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Play, enabled = !playing)
                Spacer(Modifier.width(d.gapL))
                SyneButton("보관함", { vm.push(Route.Library) }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Archive)
            }

            // ---------- 왼쪽 단 ----------
            // 여백 규칙: 제목→설명 S · 설명→첫 조작 XL · 라벨→조작 S · 조작→조작 L · 조작→시작 버튼 XL(시작을 따로 떼어 위계를 준다)
            Column(Modifier.width(600.dp).fillMaxHeight()) {
                Spacer(Modifier.weight(1f))
                Text("오늘의 움직임을\n음악으로.", style = t.display)
                Spacer(Modifier.height(d.gapS))
                Text(
                    when {
                        section == Section.ENDING -> "도착했어요. 곡을 마무리하는 중입니다."
                        playing -> "곡이 흐르고 있어요. 기어를 D로 바꾸면 주행이 곡이 됩니다."
                        else -> "당신의 주행은 매번 다른 사운드가 됩니다."
                    },
                    style = t.body,
                )
                Spacer(Modifier.height(d.gapXL))

                Text("장르", style = t.label)
                Spacer(Modifier.height(d.gapS))
                GenreSelector(
                    // 사운드팩이 있는 장르만 보여 준다(준비 중 장르는 숨김)
                    Genre.entries.filter { it.available }.map { g -> Choice(g.id, g.label, g.bpm) },
                    selected = genre.id,
                    onSelect = { id -> Genre.fromId(id)?.let(vm::selectGenre) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(d.gapL))
                Text("목적지", style = t.label)
                Spacer(Modifier.height(d.gapS))
                DestinationField(vm)
                Spacer(Modifier.height(d.gapXL))
                SyneButton(
                    if (playing) "사운드 멈추기" else "내 사운드 만들기",
                    { vm.toggleSong() },
                    Modifier.fillMaxWidth(),
                    kind = if (playing) ButtonKind.Secondary else ButtonKind.Primary,
                    size = ButtonSize.Large,
                    icon = if (playing) SyneIcon.Pause else SyneIcon.Play,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 목적지(읽기 전용): 내비 경로가 있으면 그 이름과 예상 시간. 경로는 내비 앱이 정한다(직접 입력 없음).
 * 경로가 없으면 안내 문구 — 누르면 내비 앱을 연다(정차 중 홈에서만 보이는 화면).
 */
@Composable
private fun DestinationField(vm: MainViewModel) {
    val navDest by vm.destination.collectAsStateWithLifecycle()
    val remain by vm.remainSec.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) SynePalette.Surface2 else SynePalette.Surface, tween(160), label = "dest")
    val shape = RoundedCornerShape(d.radiusS)
    val dest = navDest
    Row(
        Modifier.fillMaxWidth().height(d.touchL).clip(shape).background(bg).border(1.dp, SynePalette.Line, shape)
            .clickable(src, indication = null) { vm.openNavigation() }.padding(start = d.gapM, end = d.gapS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SyneIcon.Pin, if (dest != null) SynePalette.Primary else SynePalette.TextSecondary)
        Spacer(Modifier.width(d.gapS))
        Column(Modifier.weight(1f)) {
            if (dest != null) {
                Text(dest.name ?: "목적지", style = t.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                remain?.let { Text("내비 경로 · 약 ${(it + 59) / 60}분", style = t.meta) }
            } else {
                Text("내비에서 목적지를 정해 주세요", style = t.heading.copy(color = SynePalette.TextSecondary), maxLines = 1)
                Text("목적지가 없어도 곡은 만들어져요", style = t.meta, maxLines = 1)
            }
        }
        Text("내비 열기", style = t.label.copy(color = SynePalette.Focus))
        Spacer(Modifier.width(d.gapXS))
        Icon(SyneIcon.Chevron, SynePalette.Focus, size = d.icon * 0.8f)
    }
}
