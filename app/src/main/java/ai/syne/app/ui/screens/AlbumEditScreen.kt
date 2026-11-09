package ai.syne.app.ui.screens

import ai.syne.app.Genre
import ai.syne.app.ui.MainViewModel
import ai.syne.app.ui.TripCover
import ai.syne.app.ui.formatDate
import ai.syne.core.design.ButtonKind
import ai.syne.core.design.ButtonSize
import ai.syne.core.design.NavHeader
import ai.syne.core.design.StatusIndicator
import ai.syne.core.design.StatusTone
import ai.syne.core.design.Syne
import ai.syne.core.design.SyneButton
import ai.syne.core.design.SyneIcon
import ai.syne.core.design.SynePalette
import ai.syne.core.design.SyneTextField
import ai.syne.core.design.TagChip
import ai.syne.core.share.EditRules
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf

/**
 * 앨범 꾸미기(차량) — 기어 P가 확인될 때만 입력을 받는다.
 * 기어가 P에서 벗어나면 화면 전체가 주행 화면으로 바뀌고(입력 불가), 작성 중인 값은 ViewModel에 남아 다시 P에서 이어 쓸 수 있다.
 * 제목을 비우면 자동 제목(Vol.n · 장르)으로 돌아간다. 주행 기록(거리·시간·급제동·날짜)은 바뀌지 않는다.
 */
@Composable
fun AlbumEditScreen(vm: MainViewModel, tripId: Long) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val parked by vm.parkConfirmed.collectAsStateWithLifecycle()
    val trip by remember(tripId) { vm.track(tripId) }.collectAsState(null)
    val album by remember(trip?.albumId) { trip?.albumId?.let { vm.album(it) } ?: flowOf(null) }.collectAsState(null)
    var error by remember { mutableStateOf<String?>(null) }
    val d = Syne.dims
    val t = Syne.type
    val dr = draft ?: return
    val tr = trip ?: return
    val auto = "Vol.${tr.volume} · ${Genre.fromId(tr.genre)?.label ?: tr.genre}"

    Column(Modifier.fillMaxSize().background(SynePalette.Bg).padding(horizontal = d.margin).padding(top = 40.dp)) {
        NavHeader("앨범 꾸미기", { vm.cancelDraft() }, leading = SyneIcon.Close) {
            SyneButton("취소", vm::cancelDraft, kind = ButtonKind.Secondary, size = ButtonSize.Compact)
            Spacer(Modifier.width(d.gapS))
            SyneButton("저장", { error = vm.saveDraft() }, size = ButtonSize.Compact, enabled = parked)
        }
        Spacer(Modifier.height(d.gapL))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(d.gapXL)) {
            // ---------- 왼쪽: 미리보기(무엇이 바뀌지 않는지도 알려 준다) ----------
            Column(Modifier.width(420.dp)) {
                TripCover(tr.coverPath, Modifier.fillMaxWidth().aspectRatio(1f), targetPx = 1024, corner = d.radiusM)
                Spacer(Modifier.height(d.gapM))
                Text(EditRules.clean(dr.title, EditRules.TITLE_MAX, true) ?: auto, style = t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // 사용자 제목이 있을 때만 자동 제목을 보조 줄에(없으면 위 제목이 곧 자동 제목)
                Text((if (dr.title.isNotBlank()) "$auto · " else "") + (album?.name ?: ""), style = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDate(tr.startedAt), style = t.meta)
                Spacer(Modifier.height(d.gapM))
                Text("음악·앨범 아트·주행 기록은 바뀌지 않아요.", style = t.meta.copy(color = SynePalette.TextTertiary))
            }
            // ---------- 오른쪽: 입력 ----------
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding()) {
                if (!parked) {
                    StatusIndicator(StatusTone.Error, "정차(P)가 확인되지 않아 입력을 잠갔어요 · 휴대폰에서도 편집할 수 있어요", style = t.heading)
                    Spacer(Modifier.height(d.gapM))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("제목", style = t.label, modifier = Modifier.weight(1f))
                    if (dr.title.isNotEmpty()) SyneButton("기본 제목으로", { vm.updateDraft { it.copy(title = "") } }, kind = ButtonKind.Secondary, size = ButtonSize.Compact, enabled = parked)
                }
                Spacer(Modifier.height(d.gapXS))
                SyneTextField(dr.title, { v -> vm.updateDraft { it.copy(title = v.replace("\n", " ")) } }, "비우면 ‘$auto’", Modifier.fillMaxWidth(), maxChars = EditRules.TITLE_MAX, enabled = parked)
                Spacer(Modifier.height(d.gapM))
                Text("설명", style = t.label)
                Spacer(Modifier.height(d.gapXS))
                SyneTextField(dr.description, { v -> vm.updateDraft { it.copy(description = v) } }, "이 앨범을 한두 문장으로", Modifier.fillMaxWidth(), singleLine = false, minLines = 2, maxChars = EditRules.DESCRIPTION_MAX, enabled = parked)
                Spacer(Modifier.height(d.gapM))
                Text("개인 메모 · 나만 보는 기록", style = t.label)
                Spacer(Modifier.height(d.gapXS))
                SyneTextField(dr.memo, { v -> vm.updateDraft { it.copy(memo = v) } }, "그날의 기분이나 있었던 일", Modifier.fillMaxWidth(), singleLine = false, minLines = 3, maxChars = EditRules.MEMO_MAX, enabled = parked)
                Spacer(Modifier.height(d.gapM))
                Text("태그 · 최대 ${EditRules.TAGS_MAX}개", style = t.label)
                Spacer(Modifier.height(d.gapXS))
                if (dr.tags.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(d.gapXS)) {
                        dr.tags.forEach { tag -> TagChip(tag, onRemove = if (parked) ({ vm.updateDraft { it.copy(tags = it.tags - tag) } }) else null) }
                    }
                    Spacer(Modifier.height(d.gapXS))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    fun addTag() = vm.updateDraft { it.copy(tags = EditRules.tags(it.tags + it.tagInput), tagInput = "") }
                    SyneTextField(dr.tagInput, { v -> vm.updateDraft { it.copy(tagInput = v) } }, "#퇴근길", Modifier.weight(1f), imeAction = ImeAction.Done, onDone = ::addTag, enabled = parked && dr.tags.size < EditRules.TAGS_MAX)
                    Spacer(Modifier.width(d.gapS))
                    SyneButton("추가", ::addTag, kind = ButtonKind.Secondary, size = ButtonSize.Compact, icon = SyneIcon.Plus, enabled = parked && dr.tagInput.isNotBlank() && dr.tags.size < EditRules.TAGS_MAX)
                }
                error?.let {
                    Spacer(Modifier.height(d.gapS))
                    StatusIndicator(StatusTone.Error, it, style = t.heading)
                }
                Spacer(Modifier.height(d.gapXL))
            }
        }
    }
}
