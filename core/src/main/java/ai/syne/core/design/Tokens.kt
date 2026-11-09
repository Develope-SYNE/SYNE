package ai.syne.core.design

import ai.syne.core.R
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * SYNE 디자인 시스템 — "Digital Precision, Analog Memory".
 * 차량(:app)과 모바일(:mobile)이 같은 토큰을 쓰고, 크기만 환경별 스케일(Car/Mobile)로 다르다.
 *
 * 색의 의미(UI 전용 — 앨범 아트에는 강제하지 않는다)
 *  - Primary(파랑): 누를 수 있는 것·선택된 것·진행 중인 조작
 *  - Gold(웜 골드): 음악·보상 — 부드러움 점수, 재생 진행, 완성
 *  - Error: 실패·재시도 필요
 *  - 보라 계열은 쓰지 않는다.
 */
object SynePalette {
    val Bg = Color(0xFF0B0D10)            // 거의 검정
    val Surface = Color(0xFF151920)       // 차콜
    val Surface2 = Color(0xFF1E2530)      // 한 단계 위(선택·눌림)
    val Line = Color(0xFF232A35)
    val LineStrong = Color(0xFF313A47)
    val Text = Color(0xFFEEF1F5)          // 소프트 화이트
    val TextSecondary = Color(0xFFA3ABB8) // 밝은 회색
    val TextTertiary = Color(0xFF6B7482)  // 비활성·준비 중
    val Primary = Color(0xFF3B82F6)
    val PrimaryPressed = Color(0xFF2563EB)
    val PrimaryTint = Color(0x293B82F6)   // 선택된 칸의 바탕
    val Focus = Color(0xFF60A5FA)
    val Gold = Color(0xFFF6C356)
    val GoldTint = Color(0x24F6C356)
    val Error = Color(0xFFF26B5B)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnLight = Color(0xFF0B0D10)
    val Scrim = Color(0xB30B0D10)
}

/** SignatureObject의 빛 색(작품 전용 — 값 바꾸지 말 것) */
object ArtLight {
    val Blue = Color(0xFF176BFF)
    val Blue2 = Color(0xFF35C9FF)
    val Yellow = Color(0xFFFFD04A)
    val Orange = Color(0xFFFF852B)
}

/** Pretendard: 한글·영문·숫자를 한 가족으로(SIL OFL 1.1, assets/licenses/OFL-Pretendard.txt) */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 한글이 글자 단위로 끊기지 않게 어절 단위 줄바꿈(API 33+) */
private val KoBreak = LineBreak.Heading.copy(wordBreak = LineBreak.WordBreak.Phrase)
private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

private fun style(size: TextUnit, line: TextUnit, weight: FontWeight, color: Color = SynePalette.Text, tracking: TextUnit = 0.sp, breakKo: Boolean = false, tnum: Boolean = false) =
    TextStyle(
        fontFamily = Pretendard, fontSize = size, lineHeight = line, fontWeight = weight, color = color, letterSpacing = tracking,
        lineHeightStyle = Trim, lineBreak = if (breakKo) KoBreak else LineBreak.Simple, fontFeatureSettings = if (tnum) "tnum" else null,
        // 어절 단위 줄바꿈은 글자의 언어가 한국어일 때만 동작한다(기기 언어가 영어여도 한국어로 줄바꿈)
        localeList = Ko,
    )

private val Ko = androidx.compose.ui.text.intl.LocaleList("ko-KR")

/**
 * 의미 기반 글자 체계. 제목 Bold/SemiBold, 주요 정보 Medium, 메타데이터 Regular.
 * Numeric은 고정폭 숫자(tnum) — 시각·거리·점수가 줄마다 흔들리지 않게.
 */
data class SyneType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
    val control: TextStyle,
    val numeric: TextStyle,
    val numericL: TextStyle,
) {
    companion object {
        /** 차량: 운전석 시거리 기준(레퍼런스 Bold 64 / SemiBold 32 / Regular 24) */
        val Car = SyneType(
            display = style(60.sp, 74.sp, FontWeight.Bold, tracking = (-0.02).em, breakKo = true),
            title = style(34.sp, 44.sp, FontWeight.SemiBold, tracking = (-0.01).em, breakKo = true),
            heading = style(26.sp, 34.sp, FontWeight.SemiBold, breakKo = true),
            body = style(24.sp, 34.sp, FontWeight.Normal, SynePalette.TextSecondary, breakKo = true),
            meta = style(20.sp, 28.sp, FontWeight.Normal, SynePalette.TextSecondary),
            label = style(20.sp, 26.sp, FontWeight.Medium, SynePalette.TextSecondary),
            control = style(22.sp, 28.sp, FontWeight.SemiBold),
            numeric = style(40.sp, 46.sp, FontWeight.SemiBold, tnum = true),
            numericL = style(88.sp, 96.sp, FontWeight.Bold, tracking = (-0.02).em, tnum = true),
        )

        /** 모바일: 레퍼런스 Bold 28 / SemiBold 20 / Regular 16 */
        val Mobile = SyneType(
            display = style(28.sp, 36.sp, FontWeight.Bold, tracking = (-0.02).em, breakKo = true),
            title = style(22.sp, 30.sp, FontWeight.Bold, tracking = (-0.01).em, breakKo = true),
            heading = style(17.sp, 24.sp, FontWeight.SemiBold, breakKo = true),
            body = style(15.sp, 23.sp, FontWeight.Normal, SynePalette.TextSecondary, breakKo = true),
            meta = style(13.sp, 18.sp, FontWeight.Normal, SynePalette.TextSecondary),
            label = style(13.sp, 18.sp, FontWeight.Medium, SynePalette.TextSecondary),
            control = style(16.sp, 22.sp, FontWeight.SemiBold),
            numeric = style(22.sp, 28.sp, FontWeight.SemiBold, tnum = true),
            numericL = style(32.sp, 38.sp, FontWeight.Bold, tnum = true),
        )
    }
}

/** 간격·모양·터치 규칙. 화면마다 숫자를 새로 쓰지 않고 여기 값을 쓴다 */
data class SyneDims(
    val margin: Dp,
    val gapXS: Dp,
    val gapS: Dp,
    val gapM: Dp,
    val gapL: Dp,
    val gapXL: Dp,
    val radiusS: Dp,
    val radiusM: Dp,
    val touch: Dp,
    val touchL: Dp,
    val icon: Dp,
    val cardPad: Dp,
) {
    companion object {
        val Car = SyneDims(margin = 72.dp, gapXS = 8.dp, gapS = 12.dp, gapM = 20.dp, gapL = 32.dp, gapXL = 48.dp,
            radiusS = 10.dp, radiusM = 14.dp, touch = 64.dp, touchL = 88.dp, icon = 30.dp, cardPad = 24.dp)
        val Mobile = SyneDims(margin = 20.dp, gapXS = 4.dp, gapS = 8.dp, gapM = 12.dp, gapL = 20.dp, gapXL = 28.dp,
            radiusS = 8.dp, radiusM = 14.dp, touch = 48.dp, touchL = 54.dp, icon = 22.dp, cardPad = 16.dp)
    }
}

val LocalSyneType = staticCompositionLocalOf { SyneType.Car }
val LocalSyneDims = staticCompositionLocalOf { SyneDims.Car }

/** 현재 환경의 글자·간격 */
object Syne {
    val type: SyneType @Composable get() = LocalSyneType.current
    val dims: SyneDims @Composable get() = LocalSyneDims.current
}

@Composable
fun SyneDesign(type: SyneType, dims: SyneDims, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = SynePalette.Primary, onPrimary = SynePalette.OnPrimary, secondary = SynePalette.Gold,
            background = SynePalette.Bg, surface = SynePalette.Surface, onBackground = SynePalette.Text, onSurface = SynePalette.Text,
            error = SynePalette.Error,
        ),
    ) {
        CompositionLocalProvider(LocalSyneType provides type, LocalSyneDims provides dims, content = content)
    }
}
