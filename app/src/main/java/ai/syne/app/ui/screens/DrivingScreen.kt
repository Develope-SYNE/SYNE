package ai.syne.app.ui.screens

import ai.syne.app.ui.MainViewModel
import ai.syne.core.art.SignatureObject
import ai.syne.core.design.Syne
import ai.syne.core.design.SynePalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * SCREEN 02 — 주행 중. 운전자 주의 분산 기준(CLAUDE.md 원칙 1·2)이 디자인보다 우선.
 *  - 그래픽: 시그니처 오브젝트 정지 그래픽(4초마다 다시 그림, 움직임·전환·깜빡임 없음)
 *  - 글자: 오른쪽 아래 두 줄뿐 — 코스 이름 / "Vol.n · 장르"
 *  - 숫자·점수·상태·버튼 없음. 부드러움은 소리로만 전달하고 도착 화면에서 공개한다.
 *  - 왼쪽 상단 45%×35%(사각지대 카메라 팝업 자리)는 비운다.
 */
@Composable
fun DrivingScreen(vm: MainViewModel) {
    val ui by vm.drivingUi.collectAsStateWithLifecycle()
    val d = Syne.dims
    val t = Syne.type

    BoxWithConstraints(Modifier.fillMaxSize().background(SynePalette.Bg)) {
        // 오른쪽 아래로: 왼쪽 상단 45%×35%(사각지대 카메라 팝업)에 걸리지 않고, 오른쪽 아래 두 줄 글자와도 겹치지 않는 자리
        SignatureObject(ui.orbit, animated = false, modifier = Modifier.fillMaxSize(), centerX = 0.65f, centerY = 0.59f, scale = 0.86f)

        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = d.margin, bottom = 64.dp).widthIn(max = maxWidth * 0.6f),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                ui.course ?: "SYNE",
                style = t.title.copy(fontSize = 44.sp, lineHeight = 54.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            )
            Spacer(Modifier.height(d.gapXS))
            Text(
                listOfNotNull(ui.volume, ui.genre).joinToString(" · "),
                style = t.title.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = SynePalette.TextSecondary),
                maxLines = 1, textAlign = TextAlign.End,
            )
        }
    }
}
