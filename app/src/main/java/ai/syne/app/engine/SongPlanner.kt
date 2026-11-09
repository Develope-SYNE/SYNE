package ai.syne.app.engine

import ai.syne.app.signals.Gear

enum class Section(val label: String) {
    INTRO("인트로"),
    DEVELOP("전개"),
    CLIMAX("클라이맥스"),
    FINALE("피날레"), // 도착 접근 구간(내비 기반)
    ENDING("엔딩"), // 기어 P 이후 4마디
    END("끝"),
}

/**
 * 곡 구조 계획. 마디 경계마다 호출. 순수 로직.
 *
 * - 내비 정보(remainSec)가 있으면 진행률 p = 경과 / (경과 + 남은 시간)으로 배분:
 *   인트로 < 10% ≤ 전개 < 60% ≤ 클라이맥스 < 85% ≤ 피날레
 * - 없으면 시간 기반 기본 구조: 인트로 8마디 → (전개 16마디 → 클라이맥스 8마디) 반복
 * - 한 번이라도 주행(P 아님)한 뒤 기어 P가 되면 엔딩 4마디 → END
 *
 * 구간은 '각 레이어 안의 밀도'와 상한만 바꾼다. 레이어 수를 늘리는 건 오직 속도 구간(Arranger)이다(원칙 2).
 */
class SongPlanner {

    companion object {
        const val INTRO_BARS_DEFAULT = 8
        const val DEVELOP_BARS_DEFAULT = 16
        const val CLIMAX_BARS_DEFAULT = 8
        const val ENDING_BARS = 4
        const val P_INTRO = 0.10f
        const val P_CLIMAX = 0.60f
        const val P_FINALE = 0.85f
    }

    var section = Section.INTRO
        private set

    /** ENDING 안에서 몇 번째 마디인가(0~3) */
    var endingBar = -1
        private set

    var hasDriven = false
        private set

    /** 내비 기반으로 계획 중인가(디버그 표시용) */
    var navBased = false
        private set

    fun onBar(bar: Int, elapsedSec: Float, remainSec: Int?, gear: Gear): Section {
        if (section == Section.END) return section
        if (section == Section.ENDING) {
            endingBar++
            if (endingBar >= ENDING_BARS) section = Section.END
            return section
        }
        if (gear == Gear.PARK && hasDriven) {
            section = Section.ENDING
            endingBar = 0
            return section
        }
        if (gear == Gear.DRIVE || gear == Gear.REVERSE || gear == Gear.NEUTRAL) hasDriven = true

        navBased = remainSec != null && remainSec > 0
        section = if (navBased) {
            val p = elapsedSec / (elapsedSec + remainSec!!)
            when {
                p < P_INTRO -> Section.INTRO
                p < P_CLIMAX -> Section.DEVELOP
                p < P_FINALE -> Section.CLIMAX
                else -> Section.FINALE
            }
        } else {
            if (bar < INTRO_BARS_DEFAULT) {
                Section.INTRO
            } else {
                val inCycle = (bar - INTRO_BARS_DEFAULT) % (DEVELOP_BARS_DEFAULT + CLIMAX_BARS_DEFAULT)
                if (inCycle < DEVELOP_BARS_DEFAULT) Section.DEVELOP else Section.CLIMAX
            }
        }
        return section
    }

    /** 구간별 레이어 상한(속도가 허락해도 이 이상은 안 쌓음). 인트로: PAD·MELODY·BASS, 피날레: KEYS 제외 */
    fun maxLayers(): Int = when (section) {
        Section.INTRO -> 3
        Section.FINALE -> 4
        else -> 6
    }
}
