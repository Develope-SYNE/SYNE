package ai.syne.app.engine

import ai.syne.app.SyneLog
import ai.syne.app.signals.Gear
import ai.syne.app.signals.TurnSignal
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln

/** 마디가 바뀔 때 오디오 스레드가 내보내는 정보 */
data class BarInfo(
    val bar: Int,
    val layers: List<Layer>,
    val section: Section,
    val navBased: Boolean,
    val chord: String,
    val smoothness: Int,
    val hardBrakes: Int,
    val melodyTone: MelodyTone?,
)

/** 운전 반응 효과 상태(바뀔 때만 통지) */
data class FxState(
    /** 급제동 테이프 스톱 ~ 재진입 전까지 */
    val tapeStop: Boolean = false,
    /** 신호 정차: 드럼·베이스 빠지고 패드+잔향 */
    val stopHold: Boolean = false,
    /** 제한속도 초과: 먹먹하게 + 멜로디 제거 */
    val overLimit: Boolean = false,
    /** 정지 직전 keys·melody 잦아듦 */
    val settling: Boolean = false,
)

/** 렌더러가 밖으로 내보내는 기록. 모두 오디오 스레드에서 호출된다. */
internal interface RendererSink {
    fun onBar(info: BarInfo)
    fun onFx(fx: FxState)
    fun onSecond(sample: SecondSample, layersMask: Int, section: Section)
    /** 조향 멜로디 노트. 들리지 않는 순간(제한속도 초과 등)에도 선율은 기록한다. */
    fun onMelodyNote(tSec: Float, midi: Int, durSec: Float)
}

/**
 * 시퀀서 + 믹서 + 주행 분석 + 마스터 효과. 오디오 스레드 하나에서만 쓴다(동기화 없음).
 *
 * 편성(어떤 레이어가 연주되는가)은 마디 경계(onBar)에서만 바뀐다.
 * 운전 반응 효과는 심사위원이 1초 안에 알아챌 수 있도록 '즉시' 걸린다:
 * - 급제동: 테이프 스톱(0.6초 피치 하강) → 무음 → 마디 마지막 1박 리버스 심벌 → 다음 마디 첫 박에 테이프 스핀업(-12st→0, 0.25초)으로 전체 동시 진입
 * - 신호 정차: 정지 순간 드럼·베이스·keys·멜로디 OFF, 패드가 숨 쉬듯 + 리버브 꼬리 3초 / 출발(5km/h↑) 시 킥 한 방과 드럼 복귀
 * - 제한속도 초과 = '곡이 낡는다': 0.5초 램프로 마스터 로우패스 450Hz + 테이프 워블 ±0.3%→±2% + 크랙클 +12dB, 멜로디 제거
 *   / 제한속도 이하로 오면 1마디에 걸쳐 원상 복구
 */
internal class Renderer(
    private val sr: Int,
    private val kit: DrumKit,
    private val sink: RendererSink,
    /** 장르 팩: 템포·스윙·리듬 섹션·음색·믹스(사이드체인·룸)가 다르다. 선율 체계·화성·곡 구조·운전 반응 규칙은 공통 */
    val pack: GenrePack = GenrePack.LOFI,
) {
    companion object {
        const val BLOCK = 1024

        private const val B_PAD = 0
        private const val B_BASS = 1
        private const val B_DRUMS = 2
        private const val B_KEYS = 3
        private const val B_MELODY = 4
        private const val B_RICH = 5
        private const val B_ACCENT = 6 // 방향지시등 하이햇. 편성과 무관
        private const val B_STATE = 7 // 힙합 상태 소리(어두운 벨 스탭 루프 + 1/8 에코). Lo-fi는 쓰지 않음
        private const val NB = 8

        //                                       pad   bass  drums keys  melody rich  accent
        private val BUS_LEVEL = floatArrayOf(0.24f, 0.30f, 0.55f, 0.40f, 0.19f, 0.13f, 0.40f, 0.42f)
        private val SEND = floatArrayOf(0.30f, 0.00f, 0.10f, 0.30f, 0.25f, 0.35f, 0.15f, 0.25f)

        /** 잦아듦(정지 직전 2마디): keys·melody만. 드럼·베이스는 정지 '순간'에 뚜렷하게 빠진다. */
        private const val SETTLE_BARS = 2f

        private const val STOP_KMH = 1f
        private const val GO_KMH = 5f
        /** 요청은 약 700Hz였으나 이 믹스는 에너지가 100~500Hz에 몰려 700Hz로는 체감이 약해 450Hz로 (오프라인 측정) */
        private const val OVER_LP_HZ = 450f
        private const val OPEN_HZ = 18_000f
        private const val OVER_RAMP_SEC = 0.5f
        private const val TAIL_FADE_SEC = 2f // 엔딩 뒤 리버브·패드·크랙클·워블이 함께 0으로
        private const val TAIL_SILENCE_SEC = 0.5f // 마지막은 완전 무음
        /** 테이프 스톱 뒤 리버스 심벌 전 최소 무음(박). 부족하면 다음 마디 4박까지 기다린다. */
        private const val MIN_SILENCE_BEATS = 0.5f
        private const val WORN_WOBBLE = 6.67f // ±0.3% × 6.67 ≈ ±2%
        private const val WORN_CRACKLE = 4f // +12dB
        /** 힙합 과속 '깨짐': 비트크러시 뒤 로우패스 */
        private const val CRUSH_LP_HZ = 900f
        private const val REVERB_WET = 0.55f
        /** 엔딩 꼬리용 긴 잔향 피드백(짧은 룸을 쓰는 장르도 엔딩은 길게) */
        private const val LONG_ROOM = 0.935f
        /** 사이드체인 덕킹 모양: 4ms 만에 내려가고 약 0.11초 시상수로 돌아온다 */
        private const val DUCK_ATTACK_SEC = 0.004f
        private const val DUCK_RELEASE_SEC = 0.11f
    }

    // ---------------- 제어 입력 ----------------
    private var speed = 0f
    private var steering = 0f
    private var turn = TurnSignal.OFF
    private var gear = Gear.UNKNOWN
    private var limit: Int? = null
    private var remainSec: Int? = null

    val drive = DriveState()
    val planner = SongPlanner()

    /** 0 무음 ~ 1. SoundEngine이 덕킹·뮤트·정지 페이드에 쓴다. */
    var masterTarget = 1f
    var masterGain = 0f
        private set

    /** 엔딩 4마디까지 연주를 마쳤는가(곡 완결) */
    var ended = false
        private set

    /** 엔딩 뒤 꼬리(2초 페이드 + 0.5초 무음)까지 끝났는가(오디오 종료 가능) */
    var finished = false
        private set

    // ---------------- 시간 ----------------
    private val samplesPerStep = pack.samplesPerStep(sr)
    private val samplesPerBar = samplesPerStep * MusicTheory.STEPS_PER_BAR
    private val beatSamples = (samplesPerStep * MusicTheory.STEPS_PER_BEAT).toInt()
    private val barSec = (samplesPerBar / sr).toFloat()
    var clock = 0L
        private set
    private var nextStepAt = 0.0
    private var step = -1L
    var bar = -1
        private set
    private val swingSamples = (samplesPerStep * pack.swing).toInt()

    // ---------------- 편성 ----------------
    private val arranger = Arranger()
    var layers: List<Layer> = listOf(Layer.PAD)
        private set
    private var chord = MusicTheory.PROGRESSION[0]
    var melodyTone: MelodyTone? = null
        private set

    // ---------------- 운전 반응 상태 ----------------
    private var moving = false
    private var stopHold = false
    private var restartPending = false
    private var restartForce = false // 출발 직후 다음 마디까지 드럼·베이스 강제 ON

    // 힙합(beatDrop) 상태: 그루브가 켜지기 전·정차·엔딩엔 벨 스탭 루프만
    private var grooveOn = false
    private var dropArmed = false // 출발 감지 → 마디 4박에 리버스 심벌을 걸 차례
    private var dropRiser = false // 리버스 심벌을 걸었다 → 다음 마디 첫 박에 비트 드롭
    private var overNow = false
    private var settleLatched = 1f
    private var riserArmed = false // 테이프 무음 중 리버스 심벌을 걸어 두었는가(다음 마디 첫 박에 스핀업)
    private var sendBoost = 1f
    private var sendBoostTarget = 1f
    private var tailLeft = -1
    private val tailFadeLen = (TAIL_FADE_SEC * sr).toInt()
    private val tailLen = tailFadeLen + (TAIL_SILENCE_SEC * sr).toInt()
    private val wobbleRamp = LinRamp(1f)
    private val crackleRamp = LinRamp(1f)
    private var lastFx = FxState()

    private val settling: Boolean get() = settleLatched < 0.999f

    // ---------------- 음색 ----------------
    private val padPatch = Patch(Patch.SAW2, attackSec = 0.6f, decaySec = 1.5f, sustain = 0.8f, releaseSec = 1.4f, detuneCents = 9f)
    private val richPatch = Patch(Patch.SAW2, attackSec = 1.2f, decaySec = 2.0f, sustain = 0.85f, releaseSec = 1.8f, detuneCents = 14f)
    private val bassPatch = Patch(Patch.SINE, attackSec = 0.005f, decaySec = 0.35f, sustain = 0.6f, releaseSec = 0.08f)

    /** 로즈 풍 전자피아노: 사인 + 약한 벨(낮은 FM 지수, 빠른 감쇠). 트레몰로는 버스에서 */
    private val rhodesPatch = Patch(Patch.EP, attackSec = 0.004f, decaySec = 1.8f, sustain = 0.35f, releaseSec = 0.45f, fmIndex = 0.7f, fmDecaySec = 0.15f)

    // 조향 멜로디 음색 3종 (속도 구간별)
    private val pluckPatch = Patch(Patch.EP, attackSec = 0.002f, decaySec = 0.22f, sustain = 0f, releaseSec = 0.12f, fmIndex = 2.2f, fmDecaySec = 0.04f)
    private val melodyKeysPatch = Patch(Patch.EP, attackSec = 0.003f, decaySec = 0.5f, sustain = 0.25f, releaseSec = 0.25f, fmIndex = 1.1f, fmDecaySec = 0.3f)
    private val leadPatch = Patch(Patch.SAW2, attackSec = 0.012f, decaySec = 0.3f, sustain = 0.55f, releaseSec = 0.2f, detuneCents = 7f)

    // 힙합(STABS · SYNTH_BELL) 음색
    /** 현악 스탭: 디튠된 톱니 2개, 짧은 어택·빠른 감쇠(마디당 1~2회, pad 버스 자리) */
    private val stringPatch = Patch(Patch.SAW2, attackSec = 0.012f, decaySec = 0.45f, sustain = 0.3f, releaseSec = 0.3f, detuneCents = 12f)
    /** 어두운 벨/피아노 스탭(keys 버스): FM 벨, 서스테인 없음 */
    private val bellStabPatch = Patch(Patch.EP, attackSec = 0.002f, decaySec = 0.7f, sustain = 0f, releaseSec = 0.25f, fmIndex = 2.4f, fmDecaySec = 0.12f)
    private val synthPluckPatch = Patch(Patch.SAW2, attackSec = 0.002f, decaySec = 0.16f, sustain = 0f, releaseSec = 0.1f, detuneCents = 6f)
    private val bellPatch = Patch(Patch.EP, attackSec = 0.002f, decaySec = 1.1f, sustain = 0f, releaseSec = 0.4f, fmIndex = 3.2f, fmDecaySec = 0.5f)
    /** 신스 브라스 스탭(힙합 훅): 디튠 톱니 2개, 빠른 어택·짧은 감쇠로 '빰!' */
    private val brassPatch = Patch(Patch.SAW2, attackSec = 0.006f, decaySec = 0.16f, sustain = 0.35f, releaseSec = 0.07f, detuneCents = 11f)
    private val keysPatch get() = if (pack.harmony == Harmony.PAD_RHODES) rhodesPatch else brassPatch

    private val padVoices = Array(10) { SynthVoice(sr) }
    private val richVoices = Array(8) { SynthVoice(sr) }
    private val bassVoices = Array(4) { SynthVoice(sr) }
    private val keysVoices = Array(16) { SynthVoice(sr) }
    private val melodyVoices = Array(10) { SynthVoice(sr) }
    private val drumVoices = Array(24) { SampleVoice() } // 하이햇 롤(최대 8타)까지
    private val accentVoices = Array(8) { SampleVoice() }
    private val stateVoices = Array(12) { SynthVoice(sr) }
    private val stateDelay = StereoDelay(sr) // 1/8박 에코(최대 1초)

    /** 808(힙합): 글라이드 모노 보이스 하나 */
    private val bass808 = Bass808Voice(sr, drive = pack.bassDrive)

    // 킥 사이드체인(힙합): 킥이 울리는 시각들 → 블록마다 덕킹 곡선
    private val kickAt = LongArray(8) { Long.MAX_VALUE }
    private var lastKickAt = Long.MIN_VALUE / 2
    private val duck = FloatArray(BLOCK) { 1f }
    private val duckAttack = (DUCK_ATTACK_SEC * sr).toInt()
    private val duckRelease = 1f / (DUCK_RELEASE_SEC * sr)

    /** 테이프 '뒤'에 더하는 소리(무음 중에도 들려야 하는 리버스 심벌) */
    private val riser = kit.reverseCymbal(sr, beatSamples.toFloat() / sr)
    private val postVoice = SampleVoice()
    private val postL = FloatArray(BLOCK)
    private val postR = FloatArray(BLOCK)

    // ---------------- 버스·마스터 ----------------
    private val busL = Array(NB) { FloatArray(BLOCK) }
    private val busR = Array(NB) { FloatArray(BLOCK) }
    private val busGain = FloatArray(NB)
    private val busBase = FloatArray(NB).also { it[B_PAD] = 1f; it[B_ACCENT] = 1f }
    private val busLp = Array(NB) { b -> pack.busCutHz[b].takeIf { it > 0f }?.let { OnePoleLp(sr, it) } }
    private val busK = (1.0 - exp(-1.0 / (0.12 * sr))).toFloat() // 레이어 페이드 ~120ms
    private val masterK = (1.0 - exp(-1.0 / (0.08 * sr))).toFloat()
    private val mixL = FloatArray(BLOCK)
    private val mixR = FloatArray(BLOCK)
    private val sendL = FloatArray(BLOCK)
    private val sendR = FloatArray(BLOCK)

    private val reverb = StereoReverb(sr).also { it.feedback = pack.roomFeedback }
    private val tape = TapeDeck(sr)
    private val crackle = Crackle(sr, seed = 0x5EED).also { it.level = pack.texture.crackle }
    private val crusher = Crusher(sr)
    private val crushRamp = LinRamp(0f)

    /** LP 크랙클 양(0~1). 기본 1, 측정 테스트에서는 0 */
    var vinyl: Float
        get() = crackle.level
        set(v) { crackle.level = v }
    private val hiCut = pack.texture.hiCutHz?.let { hz -> SvfLowpass(sr).apply { setCutoff(hz, 0.707f) } } // Lo-fi 마스터 하이컷
    private val overLp = SvfLowpass(sr)
    private var lpActive = false
    private var lpFromLog = ln(OPEN_HZ)
    private var lpToLog = ln(OPEN_HZ)
    private var lpNowLog = ln(OPEN_HZ)
    private var lpT = 0
    private var lpLen = 1

    private var tremPhase = 0f
    private val tremInc = 4.6f / sr
    private var breathPhase = 0f
    private val breathInc = 0.22f / sr

    fun updateControls(
        speedKmh: Float,
        steeringDeg: Float,
        brakePressed: Boolean,
        turnSignal: TurnSignal,
        gear: Gear,
        speedLimitKmh: Int?,
        remainSec: Int?,
        dtSec: Float,
    ) {
        speed = speedKmh
        steering = steeringDeg
        turn = turnSignal
        this.gear = gear
        limit = speedLimitKmh
        this.remainSec = remainSec
        val driving = gear != Gear.PARK && gear != Gear.UNKNOWN

        val hard = drive.update(speedKmh, steeringDeg, brakePressed, driving, dtSec) { s ->
            sink.onSecond(s, layers.toMask(), planner.section)
        }

        // 1) 급제동 → 즉시 테이프 스톱
        if (hard && tape.mode == 0 && !ended && planner.section != Section.ENDING) {
            tape.startStop()
            SyneLog.d("HARD BRAKE #${drive.hardBrakeCount} (${"%.1f".format(drive.decelMs2)} m/s²) → TAPE STOP")
        }

        // 2) 신호 정차 / 출발
        if (driving) {
            if (moving && speedKmh < STOP_KMH) {
                moving = false
                stopHold = true
                restartForce = false
                grooveOn = false
                dropArmed = false
                dropRiser = false
                sendBoost = 3f // 빠지는 드럼·베이스의 꼬리를 리버브로 길게
                SyneLog.d(if (pack.beatDrop) "STOP → 드럼·808 OFF, 벨 루프+1/8 에코 (bar $bar)" else "STOP → 드럼·베이스 OFF, 패드+잔향 (bar $bar)")
            } else if (!moving && speedKmh > GO_KMH) {
                moving = true
                if (!ended && planner.section != Section.ENDING) {
                    if (pack.beatDrop) {
                        // 힙합: 바로 치지 않고 마디 4박 리버스 심벌 → 다음 마디 첫 박에 킥·808·드럼 동시 진입
                        dropArmed = true
                        SyneLog.d("GO → 비트 드롭 대기 (bar $bar)")
                    } else {
                        stopHold = false
                        restartPending = true
                        restartForce = true
                        SyneLog.d("GO → 킥과 함께 드럼 재시작 (bar $bar)")
                    }
                }
            }
        } else {
            moving = false
            stopHold = false
            restartForce = false
            grooveOn = false
            dropArmed = false
            dropRiser = false
        }

        // 3) 제한속도 초과 → 즉시 먹먹하게 / 복귀 시 1마디에 걸쳐 맑게
        val over = driving && speedLimitKmh != null && speedLimitKmh > 0 && speedKmh > speedLimitKmh
        if (over != overNow) {
            overNow = over
            if (over) {
                when (pack.overLimit) {
                    OverLimitFx.WORN -> {
                        rampLowpass(OVER_LP_HZ, OVER_RAMP_SEC)
                        wobbleRamp.set(WORN_WOBBLE, OVER_RAMP_SEC, sr)
                        crackleRamp.set(WORN_CRACKLE, OVER_RAMP_SEC, sr)
                    }
                    OverLimitFx.CRUSH -> {
                        rampLowpass(CRUSH_LP_HZ, OVER_RAMP_SEC)
                        crushRamp.set(1f, OVER_RAMP_SEC, sr)
                    }
                }
                SyneLog.d("OVER LIMIT ${"%.0f".format(speedKmh)}>$speedLimitKmh → ${pack.overLimit}·멜로디 OFF")
            } else {
                rampLowpass(OPEN_HZ, barSec)
                wobbleRamp.set(1f, barSec, sr)
                crackleRamp.set(1f, barSec, sr)
                crushRamp.set(0f, barSec, sr)
                SyneLog.d("UNDER LIMIT → 1마디에 걸쳐 원상 복구")
            }
        }

        // 4) 잦아듦(keys·melody): 줄어드는 쪽만 즉시 반영
        val settleNow = if (!driving) 1f else (drive.timeToStopSec / (SETTLE_BARS * barSec)).coerceIn(0f, 1f)
        if (settleNow < settleLatched) settleLatched = settleNow
    }

    private fun rampLowpass(toHz: Float, sec: Float) {
        lpFromLog = if (lpActive) lpNowLog else ln(OPEN_HZ)
        lpToLog = ln(toHz)
        lpT = 0
        lpLen = (sec * sr).toInt().coerceAtLeast(1)
        if (!lpActive) overLp.reset()
        lpActive = true
    }

    /** 인터리브 스테레오 float(L,R,L,R…)로 n프레임 렌더 */
    fun render(out: FloatArray, n: Int) {
        require(n <= BLOCK)
        for (b in 0 until NB) {
            busL[b].fill(0f, 0, n)
            busR[b].fill(0f, 0, n)
        }
        postL.fill(0f, 0, n)
        postR.fill(0f, 0, n)
        val blockStart = clock
        var i = 0
        while (i < n) {
            val until = ceil(nextStepAt - clock).toInt()
            if (until <= 0) {
                onStep()
                nextStepAt += samplesPerStep
                continue
            }
            val len = minOf(n - i, until)
            renderVoices(i, len)
            i += len
            clock += len
        }

        val ducking = pack.duckDepth > 0f && sidechainCurve(blockStart, n)

        // 버스 → 믹스 + 리버브 센드
        mixL.fill(0f, 0, n); mixR.fill(0f, 0, n)
        sendL.fill(0f, 0, n); sendR.fill(0f, 0, n)
        for (b in 0 until NB) {
            val t = busTarget(b)
            var g = busGain[b]
            if (g < 1e-4f && t == 0f && !busHasVoices(b)) {
                busGain[b] = 0f
                continue
            }
            val l = busL[b]
            val r = busR[b]
            busLp[b]?.process(l, r, n)
            if (b == B_STATE) {
                // 정차·엔딩은 에코를 길게(잦아드는 메아리), 출발 전·인트로는 짧게
                val tail = stopHold || planner.section == Section.ENDING || ended
                stateDelay.process(l, r, n, beatSamples / 2, if (tail) 0.55f else 0.3f, if (tail) 0.6f else 0.22f)
            }
            val level = BUS_LEVEL[b] * pack.busScale[b]
            val send = SEND[b]
            // 사이드체인: 킥·808·드럼·방향지시등·상태 소리 말고 나머지(현악·브라스·멜로디·풍성함)가 킥마다 숨을 쉰다
            val ducked = ducking && (b == B_PAD || b == B_KEYS || b == B_MELODY || b == B_RICH)
            for (k in 0 until n) {
                g += (t - g) * busK
                var a = if (ducked) g * level * duck[k] else g * level
                var al = a
                var ar = a
                if (b == B_KEYS && pack.harmony == Harmony.PAD_RHODES) { // 로즈 트레몰로(스테레오 오토팬)
                    val m = Wavetable.sine(tremPhase) * 0.3f
                    tremPhase += tremInc; if (tremPhase >= 1f) tremPhase -= 1f
                    al = a * (1f + m); ar = a * (1f - m)
                } else if (b == B_PAD && stopHold) { // 정차 중 패드가 숨 쉬듯
                    a *= 0.72f + 0.28f * Wavetable.sine(breathPhase)
                    breathPhase += breathInc; if (breathPhase >= 1f) breathPhase -= 1f
                    al = a; ar = a
                }
                val vl = l[k] * al
                val vr = r[k] * ar
                mixL[k] += vl; mixR[k] += vr
                sendL[k] += vl * send; sendR[k] += vr * send
            }
            busGain[b] = g
        }

        // 리버브 (정차·엔딩에서 센드를 키워 꼬리를 길게)
        sendBoost += (sendBoostTarget - sendBoost) * (n / (1.2f * sr)).coerceAtMost(1f)
        // 짧은 룸을 쓰는 장르(힙합)도 엔딩은 긴 꼬리로
        reverb.feedback = if (ended || planner.section == Section.ENDING) LONG_ROOM else pack.roomFeedback
        reverb.process(sendL, sendR, mixL, mixR, n, REVERB_WET * sendBoost)

        hiCut?.process(mixL, mixR, 0, n)
        tape.wobble = wobbleRamp.advance(n) * pack.texture.wobble
        tape.process(mixL, mixR, n) // 워블 · 테이프 스톱 · 무음 · 스핀업
        for (k in 0 until n) { // 리버스 심벌은 테이프 무음 중에도 들리도록 테이프 뒤에
            mixL[k] += postL[k]
            mixR[k] += postR[k]
        }

        // 마스터 게인 · 소프트 클립
        var mg = masterGain
        val mt = masterTarget
        for (k in 0 until n) {
            mg += (mt - mg) * masterK
            mixL[k] = softClip(mixL[k] * mg)
            mixR[k] = softClip(mixR[k] * mg)
        }
        masterGain = mg

        // 힙합 과속 '깨짐': 비트크러시(6bit + 다운샘플) → 아래 로우패스
        crusher.process(mixL, mixR, n, crushRamp.advance(n))

        // 과속 로우패스는 클리핑 '뒤'에: 클립이 만든 고역까지 확실히 먹먹하게 (램프 중이거나 닫혀 있을 때만)
        if (lpActive) {
            var k = 0
            while (k < n) {
                val len = minOf(32, n - k)
                val p = (lpT.toFloat() / lpLen).coerceAtMost(1f)
                lpNowLog = lpFromLog + (lpToLog - lpFromLog) * p
                overLp.setCutoff(exp(lpNowLog), 0.9f)
                overLp.process(mixL, mixR, k, len)
                lpT += len
                k += len
            }
            if (lpT >= lpLen && lpToLog >= ln(OPEN_HZ) - 0.01f) lpActive = false // 다 열렸으면 우회
        }
        crackle.boost = crackleRamp.advance(n)
        crackle.add(mixL, mixR, n)

        // 엔딩 꼬리: 리버브·패드·크랙클·워블 모두 함께 2초에 걸쳐 0 → 마지막 0.5초 완전 무음
        for (k in 0 until n) {
            val f = if (finished) 0f else if (tailLeft >= 0) {
                val elapsed = tailLen - (tailLeft - k)
                if (elapsed >= tailFadeLen) 0f else (1f - elapsed.toFloat() / tailFadeLen).let { it * it }
            } else 1f
            out[2 * k] = (mixL[k] * f).coerceIn(-1f, 1f)
            out[2 * k + 1] = (mixR[k] * f).coerceIn(-1f, 1f)
        }

        if (tailLeft >= 0) {
            tailLeft -= n
            if (tailLeft <= 0) finished = true
        }

        val fx = FxState(tapeStop = tape.mode != 0, stopHold = stopHold, overLimit = overNow, settling = settling)
        if (fx != lastFx) {
            lastFx = fx
            sink.onFx(fx)
        }
    }

    /** 이 블록의 덕킹 곡선을 [duck]에 채운다. 블록 안에 덕킹이 전혀 없으면 false */
    private fun sidechainCurve(blockStart: Long, n: Int): Boolean {
        val depth = pack.duckDepth
        var any = false
        for (k in 0 until n) {
            val t = blockStart + k
            for (j in kickAt.indices) {
                if (kickAt[j] <= t) {
                    lastKickAt = kickAt[j]
                    kickAt[j] = Long.MAX_VALUE
                }
            }
            val x = t - lastKickAt
            val d = when {
                x < 0 -> 0f
                x < duckAttack -> x.toFloat() / duckAttack
                else -> exp(-(x - duckAttack) * duckRelease).let { if (it < 1e-3f) 0f else it }
            }
            duck[k] = 1f - depth * d
            if (d > 0f) any = true
        }
        return any
    }

    private fun busTarget(b: Int): Float {
        if (b == B_STATE) {
            if (!pack.beatDrop) return 0f
            return when {
                planner.section == Section.ENDING || ended -> 1f
                grooveOn -> 0f
                stopHold -> 0.6f // 정차: 루프가 낮게 남아 에코로 잦아듦
                else -> 1f
            }
        }
        // 힙합: 그루브가 켜지기 전·정차 중엔 벨 루프 말고는 아무것도(패드 드론 없음). 엔딩은 따로
        if (pack.beatDrop && !grooveOn && planner.section != Section.ENDING && !ended && b != B_ACCENT) return 0f
        if (b == B_ACCENT) return 1f
        var t = if (restartForce && (b == B_DRUMS || b == B_BASS)) 1f else busBase[b]
        if (t == 0f) return 0f
        if (stopHold && (b == B_BASS || b == B_DRUMS || b == B_KEYS || b == B_MELODY)) return 0f
        if (overNow && b == B_MELODY) return 0f
        if ((b == B_KEYS || b == B_MELODY) && settleLatched < 1f) {
            val lo = if (b == B_KEYS) 0.45f else 0.7f
            val x = ((settleLatched - lo) / (1f - lo)).coerceIn(0f, 1f)
            t *= x * x * (3f - 2f * x)
        }
        return t
    }

    private fun renderVoices(off: Int, len: Int) {
        for (v in padVoices) v.render(busL[B_PAD], busR[B_PAD], off, len)
        for (v in richVoices) v.render(busL[B_RICH], busR[B_RICH], off, len)
        for (v in bassVoices) v.render(busL[B_BASS], busR[B_BASS], off, len)
        bass808.render(busL[B_BASS], busR[B_BASS], off, len)
        for (v in drumVoices) v.render(busL[B_DRUMS], busR[B_DRUMS], off, len)
        for (v in keysVoices) v.render(busL[B_KEYS], busR[B_KEYS], off, len)
        for (v in melodyVoices) v.render(busL[B_MELODY], busR[B_MELODY], off, len)
        for (v in accentVoices) v.render(busL[B_ACCENT], busR[B_ACCENT], off, len)
        for (v in stateVoices) v.render(busL[B_STATE], busR[B_STATE], off, len)
        postVoice.render(postL, postR, off, len)
    }

    private fun busHasVoices(b: Int): Boolean = when (b) {
        B_PAD -> padVoices.any { it.active }
        B_RICH -> richVoices.any { it.active }
        B_BASS -> bassVoices.any { it.active } || bass808.active
        B_DRUMS -> drumVoices.any { it.active }
        B_KEYS -> keysVoices.any { it.active }
        B_MELODY -> melodyVoices.any { it.active }
        B_STATE -> pack.beatDrop // 힙합만: 에코 꼬리가 남아 있을 수 있다
        else -> accentVoices.any { it.active }
    }

    // ------------------------------------------------------------------
    // 시퀀서
    // ------------------------------------------------------------------

    private fun onBar() {
        bar++
        val section = planner.onBar(bar, (clock.toDouble() / sr).toFloat(), remainSec, gear)
        if (section == Section.END) {
            if (!ended) {
                ended = true
                tailLeft = tailLen
                sendBoostTarget = 2.5f
                wobbleRamp.set(0f, TAIL_FADE_SEC, sr)
                crackleRamp.set(0f, TAIL_FADE_SEC, sr)
                crushRamp.set(0f, TAIL_FADE_SEC, sr)
                SyneLog.d("bar $bar: 엔딩 완결 → 꼬리 ${TAIL_FADE_SEC.toInt()}초 페이드 + ${TAIL_SILENCE_SEC}초 무음")
            }
            return
        }
        restartForce = false // 이제 편성은 Arranger가 정한다

        settleLatched = if (gear == Gear.PARK || gear == Gear.UNKNOWN) 1f
        else (drive.timeToStopSec / (SETTLE_BARS * barSec)).coerceIn(0f, 1f)

        val prev = layers
        val prevTone = melodyTone
        var keysGain = 1f
        if (section == Section.ENDING) {
            chord = MusicTheory.PROGRESSION[MusicTheory.ENDING_CHORDS[planner.endingBar]]
            layers = buildList {
                add(Layer.PAD); add(Layer.MELODY); add(Layer.BASS); add(Layer.KEYS)
                if (drive.richEligible) add(Layer.RICH)
            }
            settleLatched = 1f
            stopHold = false
            melodyTone = MelodyTone.KEYS
            // 마지막 Am 마디부터 리버브를 키워 꼬리로 자연스럽게 이어지게
            sendBoostTarget = if (planner.endingBar == SongPlanner.ENDING_BARS - 1) 2.5f else 1f
        } else {
            chord = MusicTheory.PROGRESSION[bar % MusicTheory.PROGRESSION.size]
            val base = arranger.onBar(speed, limit, parked = gear == Gear.PARK, maxLayers = planner.maxLayers())
            val rich = drive.richEligible && !arranger.overLimit
            layers = if (rich) base + Layer.RICH else base
            // 힙합: 비트가 떨어진 뒤엔 인트로 구간이라도 킥·808이 곡의 몸통(편성 상한과 무관)
            if (pack.beatDrop && grooveOn && arranger.level >= 1) layers = (layers + Layer.DRUMS + Layer.BASS).distinct()
            melodyTone = arranger.melodyTone
            keysGain = arranger.keysGain
            sendBoostTarget = 1f
        }
        busBase[B_PAD] = 1f
        busBase[B_BASS] = if (Layer.BASS in layers) 1f else 0f
        busBase[B_DRUMS] = if (Layer.DRUMS in layers || section == Section.ENDING) 1f else 0f
        busBase[B_KEYS] = if (Layer.KEYS in layers) keysGain else 0f
        busBase[B_MELODY] = if (Layer.MELODY in layers) 1f else 0f
        busBase[B_RICH] = if (Layer.RICH in layers) 1f else 0f
        busBase[B_ACCENT] = 1f

        // Lo-fi: 패드(3화음)와 풍성함(7th·9th)을 마디마다 새로 깐다. 엔딩 마지막 Am은 끝까지 울려 꼬리로.
        // Hip-hop: 길게 깔리는 pad 없음 — 현악 스탭은 onStep에서 마디당 1~2회
        if (pack.harmony == Harmony.PAD_RHODES) {
            val gate = samplesPerBar.toInt()
            chord.triad.forEachIndexed { idx, midi ->
                alloc(padVoices).noteOn(midi.toFloat(), 0.55f, -0.3f + 0.3f * idx, 0, gate, padPatch, clock)
            }
            if (Layer.RICH in layers) {
                chord.ext.forEachIndexed { idx, midi ->
                    alloc(richVoices).noteOn(midi.toFloat(), 0.5f, if (idx == 0) -0.5f else 0.5f, 0, gate, richPatch, clock)
                }
            }
        }

        if (prev != layers || prevTone != melodyTone || bar == 0 || section != lastLoggedSection) {
            SyneLog.d(
                "bar $bar [${section.label}${if (planner.navBased) "·nav" else ""}] ${chord.name} layers=$layers " +
                    "melody=${melodyTone?.label ?: "-"} speed=${"%.0f".format(speed)} limit=$limit " +
                    "over=${arranger.overLimit} smooth=${drive.smoothness}",
            )
            lastLoggedSection = section
        }
        sink.onBar(
            BarInfo(
                bar = bar, layers = layers, section = section, navBased = planner.navBased, chord = chord.name,
                smoothness = drive.smoothness, hardBrakes = drive.hardBrakeCount, melodyTone = melodyTone,
            ),
        )
    }

    private var lastLoggedSection: Section? = null

    private fun onStep() {
        step++
        val s = (step % MusicTheory.STEPS_PER_BAR).toInt()
        if (s == 0) onBar()
        if (ended) return

        // 테이프 스톱 후 무음: 마디 마지막 박에 리버스 심벌 → 다음 마디 첫 박에 스핀업으로 전체 동시 진입
        if (tape.mode == 2) {
            if (s == 0 && riserArmed) {
                riserArmed = false
                tape.spinUp()
                SyneLog.d("TAPE spin-up → 전체 레이어 동시 진입 (bar $bar)")
            } else if (s == MusicTheory.STEPS_PER_BAR - MusicTheory.STEPS_PER_BEAT && !riserArmed &&
                tape.silentSamples >= MIN_SILENCE_BEATS * beatSamples
            ) {
                riserArmed = true
                postVoice.trigger(riser, 1f, 0f, 0, clock)
                SyneLog.d("TAPE riser (bar $bar 4박)")
            }
        }

        if (planner.section == Section.ENDING) {
            endingStep(s)
            return
        }

        val swing = if (s % 2 == 1) swingSamples else 0
        val stepLen = samplesPerStep.toInt()
        val climax = planner.section == Section.CLIMAX

        // 힙합 비트 드롭: 출발 → 마디 4박에 리버스 심벌 → 다음 마디 첫 박에 킥·808·드럼 동시 진입
        if (pack.beatDrop) {
            if (s == 0 && dropRiser) {
                dropRiser = false
                grooveOn = true
                stopHold = false
                restartForce = true // 이번 마디는 편성과 무관하게 드럼·808
                if (kit.crash.isNotEmpty()) drum(kit.crash, 0.9f, pan = -0.25f)
                SyneLog.d("BEAT DROP (bar $bar)")
            }
            if (dropArmed && s == MusicTheory.STEPS_PER_BAR - MusicTheory.STEPS_PER_BEAT && tape.mode == 0) {
                dropArmed = false
                dropRiser = true
                postVoice.trigger(riser, 0.9f, 0f, 0, clock)
            }
            if (!grooveOn) stateStep(s, swing)
        }
        val groove = !pack.beatDrop || grooveOn
        val drumsOn = (Layer.DRUMS in layers || restartForce) && !stopHold && groove
        val bassOn = (Layer.BASS in layers || restartForce) && !stopHold && groove

        // 출발 '재시작': 다음 16분에 킥 한 방
        if (restartPending) {
            restartPending = false
            drum(kit.kick, 1f)
        }

        if (bassOn) bassStep(s, stepLen, swing)
        if (drumsOn) drumStep(s, swing, climax)

        // 방향지시등: 16분 하이햇 + 오프비트 오픈햇, 깜빡이 방향으로 좌우 100%
        if (turn != TurnSignal.OFF) {
            val pan = if (turn == TurnSignal.LEFT) -1f else 1f
            if (s % 2 == 1) accent(kit.hat, 0.42f, pan, swing)
            if (s % 4 == 2) accent(kit.openHat, 0.55f, pan, 0)
        }

        // keys: Lo-fi는 길게 눌러 주는 로즈 화음, 힙합은 신스 브라스 스탭 훅(2마디 리프)
        if (Layer.KEYS in layers && !stopHold && groove) {
            if (pack.harmony == Harmony.STABS) brassHook(s, stepLen, climax)
            else {
                val (gateSteps, vel) = keysHit(s, climax)
                if (gateSteps > 0) {
                    chord.keys.forEachIndexed { idx, midi ->
                        alloc(keysVoices).noteOn(
                            midi.toFloat(), vel, -0.15f + 0.1f * idx, (if (s % 2 == 1) swing else 0) + idx * 60, stepLen * gateSteps, keysPatch, clock,
                        )
                    }
                }
            }
        }

        // 힙합 현악 스탭(pad 버스 자리 — 정차 중에도 남아 잔향과 함께 숨 쉰다)
        if (pack.harmony == Harmony.STABS && groove) stringStep(s, stepLen, swing, gateBars = 0)

        // 조향 멜로디: 움직이는 동안 선율은 항상 진행(기록), 소리는 MELODY 레이어가 있을 때만
        if (arranger.melodyLine) {
            val audible = Layer.MELODY in layers && !overNow && groove
            val motif = MusicTheory.LEAD_MOTIF[bar % MusicTheory.LEAD_MOTIF.size]
            val offset = MusicTheory.steeringToDegreeOffset(steering)
            val idx = MusicTheory.LEAD_STEPS.indexOf(s)
            if (idx >= 0) melody(motif[idx] + offset, swing, stepLen * 2, audible = audible)
            if (climax) {
                val e = MusicTheory.LEAD_CLIMAX_EXTRA_STEPS.indexOf(s)
                if (e >= 0) melody(motif[MusicTheory.LEAD_CLIMAX_EXTRA_FROM[e]] + 2 + offset, swing, stepLen, vel = 0.45f, audible = audible)
            }
        }
    }

    /** 엔딩 4마디: F – G – Am – Am. 리드가 A4로 내려앉고 마지막 Am은 끝까지 울려 리버브 꼬리로 이어진다. */
    private fun endingStep(s: Int) {
        val eb = planner.endingBar
        val stepLen = samplesPerStep.toInt()
        if (pack.beatDrop) {
            // 힙합 엔딩: 벨 스탭 루프 + 엔딩 리드 → 마지막 마디 첫 박에 808 한 방과 벨 스탭 → 에코 꼬리 2초 후 무음
            if (eb < SongPlanner.ENDING_BARS - 1) stateStep(s, if (s % 2 == 1) swingSamples else 0)
            else if (s == 0) {
                bass(chord.bassMidi, 1f, 0)
                stateStab(0.6f, 0)
            }
            endingLead(s, eb, stepLen)
            return
        }
        if (s == 0) {
            bass(chord.bassMidi, 0.85f, stepLen * 15)
            chord.keys.forEachIndexed { idx, midi ->
                alloc(keysVoices).noteOn(midi.toFloat(), 0.5f, -0.15f + 0.1f * idx, idx * 140, stepLen * if (eb == 3) 16 else 12, keysPatch, clock)
            }
        }
        when {
            eb == 0 && (s == 0 || s == 8) -> drum(kit.kick, 0.7f)
            eb == 0 && s == 12 -> drum(kit.snare, 0.5f)
            (eb == 1 || eb == 2) && s == 0 -> drum(kit.kick, 0.8f)
        }
        endingLead(s, eb, stepLen)
    }

    /** 엔딩 리드: A4로 내려앉는 선율(모든 장르 공통) */
    private fun endingLead(s: Int, eb: Int, stepLen: Int) {
        if (eb < MusicTheory.ENDING_LEAD_STEPS.size) {
            val i = MusicTheory.ENDING_LEAD_STEPS[eb].indexOf(s)
            if (i >= 0) {
                val last = eb == MusicTheory.ENDING_LEAD_STEPS.size - 1
                melody(MusicTheory.ENDING_LEAD_DEGREES[eb][i], 0, if (last) stepLen * 28 else stepLen * 3, vel = 0.55f, steer = false)
            }
        }
    }

    /** 힙합 상태 소리: 어두운 벨 스탭 2마디 루프(상태 버스 = 800Hz 로우패스 + 1/8 에코) */
    private fun stateStep(s: Int, swing: Int) {
        val hit = if (bar % 2 == 0) when (s) { 0 -> 0.6f; 6 -> 0.42f; 10 -> 0.5f; else -> 0f }
        else when (s) { 0 -> 0.55f; 3 -> 0.36f; 8 -> 0.48f; 14 -> 0.32f; else -> 0f }
        if (hit > 0f) stateStab(hit, if (s % 2 == 1) swing else 0)
    }

    private fun stateStab(vel: Float, delay: Int) {
        val stepLen = samplesPerStep.toInt()
        chord.triad.forEachIndexed { idx, midi ->
            alloc(stateVoices).noteOn(midi.toFloat(), vel, -0.3f + 0.3f * idx, delay + idx * 40, stepLen * 2, bellStabPatch, clock)
        }
    }

    private fun melody(degree: Int, delay: Int, gate: Int, vel: Float = 0.6f, steer: Boolean = true, audible: Boolean = true) {
        val d = degree.coerceIn(MusicTheory.LEAD_MIN_DEGREE, MusicTheory.LEAD_MAX_DEGREE)
        val midi = MusicTheory.pentatonicMidi(d)
        sink.onMelodyNote(((clock + delay).toDouble() / sr).toFloat(), midi, gate.toFloat() / sr)
        if (!audible) return
        val pan = if (steer) MusicTheory.steeringToPan(steering) else 0f
        val tone = melodyTone ?: MelodyTone.PLUCK
        when (pack.melodyVoice) {
            MelodyVoice.EP -> when (tone) {
                MelodyTone.PLUCK ->
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel * 0.95f, pan, delay, gate, pluckPatch, clock)
                MelodyTone.KEYS ->
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel, pan, delay, gate, melodyKeysPatch, clock)
                MelodyTone.LEAD -> { // 밝은 lead: 한 옥타브 위 + 원음 살짝
                    alloc(melodyVoices).noteOn((midi + 12).toFloat(), vel * 0.55f, pan, delay, gate, leadPatch, clock)
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel * 0.35f, pan, delay, gate, leadPatch, clock)
                }
            }
            // 힙합: 같은 선율, 음색만 신스 플럭 / 벨 / 벨(+1옥타브)+플럭
            MelodyVoice.SYNTH_BELL -> when (tone) {
                MelodyTone.PLUCK ->
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel * 0.8f, pan, delay, gate, synthPluckPatch, clock)
                MelodyTone.KEYS ->
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel * 0.9f, pan, delay, gate, bellPatch, clock)
                MelodyTone.LEAD -> {
                    alloc(melodyVoices).noteOn((midi + 12).toFloat(), vel * 0.5f, pan, delay, gate, bellPatch, clock)
                    alloc(melodyVoices).noteOn(midi.toFloat(), vel * 0.4f, pan, delay, gate, synthPluckPatch, clock)
                }
            }
        }
    }

    private fun bass(midi: Int, vel: Float, gate: Int, delay: Int = 0, slide: Boolean = false) {
        if (pack.bass808) {
            bass808.trigger(midi - 12, vel, delay, slide) // 808: 한 옥타브 아래, 모노(이어받기·글라이드)
        } else {
            alloc(bassVoices).noteOn(midi.toFloat(), vel, 0f, delay, gate, bassPatch, clock)
        }
    }

    // ---------------- 장르별 리듬 섹션 ----------------

    /** 베이스 리듬 */
    private fun bassStep(s: Int, stepLen: Int, swing: Int) {
        val root = chord.bassMidi
        when (pack) {
            GenrePack.LOFI -> when (s) {
                0 -> bass(root, 0.95f, stepLen * 5)
                7 -> bass(root, 0.65f, stepLen * 2, swing)
                10 -> bass(root + 7, 0.8f, stepLen * 3)
                14 -> bass(root + 12, 0.5f, stepLen)
            }
            // 808은 킥을 따라 치고(새 음 = '붐'), 그 사이를 미끄러진다:
            // 1박 루트 · 2박 엇박 루트 · 3박 엇박 루트 · 4박에서 옥타브 위로 슬라이드 · 홀수 마디 끝에서 다음 코드 루트로 슬라이드
            GenrePack.HIPHOP -> when (s) {
                0 -> bass(root, 1f, 0)
                7 -> bass(root, 0.8f, 0)
                10 -> bass(root, 0.9f, 0)
                12 -> if (bar % 2 == 0) bass(root + 12, 0.75f, 0, slide = true)
                13 -> if (bar % 2 == 1) bass(root + 7, 0.75f, 0, slide = true)
                14 -> if (bar % 2 == 0) bass(root, 0.7f, 0, slide = true)
                15 -> if (bar % 2 == 1) {
                    val next = MusicTheory.PROGRESSION[(bar + 1) % MusicTheory.PROGRESSION.size].bassMidi
                    bass(next, 0.7f, 0, slide = true)
                }
            }
        }
    }

    /** 드럼 패턴 */
    private fun drumStep(s: Int, swing: Int, climax: Boolean) {
        when (pack) {
            GenrePack.LOFI -> {
                when (s) {
                    0 -> drum(kit.kick, 0.95f)
                    7 -> drum(kit.kick, 0.6f, delay = swing)
                    10 -> drum(kit.kick, 0.85f)
                    4, 12 -> drum(kit.snare, 0.85f)
                    15 -> drum(kit.snare, 0.15f, delay = swing)
                }
                if (s % 2 == 0) drum(kit.hat, if (s % 4 == 0) 0.75f else 0.55f, pan = 0.15f)
                else if (climax) drum(kit.hat, 0.3f, pan = -0.15f, delay = swing)
            }
            // 하드 힙합(정박): 펀치 킥(1박 · 2박 엇박 · 3박 엇박, 홀수 마디는 4박 엇박 하나 더), 2·4박에 크게 겹친 스네어+클랩,
            // 16분 하이햇 상시 + 매 마디 4박은 하이햇 롤, 4마디마다 첫 박 크래시
            GenrePack.HIPHOP -> {
                when (s) {
                    0 -> {
                        drum(kit.kick, 1f)
                        if (bar % 4 == 0 && kit.crash.isNotEmpty()) drum(kit.crash, if (climax) 0.85f else 0.7f, pan = -0.25f)
                    }
                    7 -> drum(kit.kick, 0.8f)
                    10 -> drum(kit.kick, 0.95f)
                    13 -> if (bar % 2 == 1) drum(kit.kick, 0.7f)
                    4, 12 -> { // 스네어 + 클랩 레이어(클랩은 살짝 늦고 넓게)
                        drum(kit.snare, 1f)
                        if (kit.clap.isNotEmpty()) drum(kit.clap, 1.1f, pan = 0.05f, delay = (sr * 0.004f).toInt())
                    }
                }
                // 매 마디 4박 하이햇 롤: 1/32(8타)와 셋잇단(1/24, 6타)을 마디마다 번갈아. 그 밖엔 16분 햇 상시
                if (s >= 12) {
                    if (s == 12) hatRoll(triplet = bar % 2 == 1)
                } else {
                    val vel = when { s % 4 == 0 -> 0.62f; s % 2 == 0 -> 0.46f; else -> if (climax) 0.38f else 0.3f }
                    drum(kit.hat, vel, pan = if (s % 2 == 0) 0.2f else -0.15f)
                }
            }
        }
    }

    /** 하이햇 롤: 4박 한 박 안에 1/32 8타 또는 셋잇단 6타, 점점 세게 */
    private fun hatRoll(triplet: Boolean) {
        val hits = if (triplet) 6 else 8
        val interval = beatSamples.toFloat() / hits
        for (k in 0 until hits) {
            drum(kit.hat, 0.22f + 0.4f * k / (hits - 1), pan = 0.2f - 0.4f * k / hits, delay = (k * interval).toInt())
        }
    }

    /**
     * 힙합 현악 스탭: 매 마디 1박 + 짝수 번째 마디 3박 엇박(마디당 1~2회). RICH(부드러운 주행 보상)면 7th·9th를 얹는다.
     * gateBars > 0이면 그 마디 수만큼 길게(엔딩 마지막 화음).
     */
    private fun stringStep(s: Int, stepLen: Int, swing: Int, gateBars: Int) {
        val hit = s == 0 || (s == 11 && bar % 2 == 1 && gateBars == 0)
        if (!hit) return
        val gate = if (gateBars > 0) (samplesPerBar * gateBars).toInt() else stepLen * (if (s == 0) 4 else 2)
        val vel = if (s == 0) 0.5f else 0.38f
        val delay = if (s % 2 == 1) swing else 0
        chord.triad.forEachIndexed { idx, midi ->
            alloc(padVoices).noteOn(midi.toFloat(), vel, -0.4f + 0.4f * idx, delay, gate, stringPatch, clock)
        }
        if (Layer.RICH in layers) {
            chord.ext.forEachIndexed { idx, midi ->
                alloc(richVoices).noteOn(midi.toFloat(), vel * 0.8f, if (idx == 0) -0.6f else 0.6f, delay, gate, stringPatch, clock)
            }
        }
    }

    /** keys 화음: (길이(16분 수), 세기). 0이면 치지 않는다 */
    private fun keysHit(s: Int, climax: Boolean): Pair<Int, Float> = when (pack) {
        GenrePack.LOFI -> when (s) { // 1박, 2박 엇박, 3박 엇박
            0 -> 6 to 0.5f
            6 -> 3 to 0.38f
            10 -> 5 to 0.44f
            14 -> if (climax) 2 to 0.32f else 0 to 0f
            else -> 0 to 0f
        }
        GenrePack.HIPHOP -> 0 to 0f // 힙합 keys는 brassHook
    }

    /**
     * 힙합 훅: 신스 브라스 스탭 2마디 리프(keys 버스). 화음은 그 마디 코드(3화음) + 맨 위 한 음이 리프 선율을 만든다.
     * A마디: 1박 · 1박 엇박(16분 3) · 2박 엇박 · 3박 엇박 · 4박 / B마디: 1박 · 1박 2(16분 2) · 2박 엇박 · 3박(16분 9) · 3박 엇박 길게.
     */
    private fun brassHook(s: Int, stepLen: Int, climax: Boolean) {
        val a = bar % 2 == 0
        // (16분 길이, 세기, 맨 위 음: 0 = 근음 옥타브, 1 = 3음 옥타브, 2 = 5음 옥타브)
        val hit: Triple<Int, Float, Int>? = if (a) when (s) {
            0 -> Triple(2, 0.62f, 0)
            3 -> Triple(1, 0.46f, 0)
            6 -> Triple(2, 0.55f, 1)
            10 -> Triple(1, 0.5f, 1)
            12 -> Triple(2, 0.58f, 2)
            14 -> if (climax) Triple(1, 0.42f, 2) else null
            else -> null
        } else when (s) {
            0 -> Triple(1, 0.6f, 2)
            2 -> Triple(1, 0.44f, 2)
            6 -> Triple(2, 0.55f, 1)
            9 -> Triple(1, 0.48f, 1)
            11 -> Triple(3, 0.6f, 0)
            else -> null
        }
        hit ?: return
        val (gateSteps, vel, top) = hit
        val gate = stepLen * gateSteps
        chord.triad.forEachIndexed { idx, midi ->
            alloc(keysVoices).noteOn(midi.toFloat(), vel * 0.75f, -0.3f + 0.3f * idx, 0, gate, brassPatch, clock)
        }
        val lead = chord.triad[top.coerceAtMost(chord.triad.size - 1)] + 12
        alloc(keysVoices).noteOn(lead.toFloat(), vel, 0.1f, 0, gate, brassPatch, clock)
    }

    private fun drum(sample: FloatArray, vel: Float, pan: Float = 0f, delay: Int = 0) {
        alloc(drumVoices).trigger(sample, vel, pan, delay, clock)
        if (sample === kit.kick && pack.duckDepth > 0f) { // 사이드체인 예약
            val j = kickAt.indices.firstOrNull { kickAt[it] == Long.MAX_VALUE } ?: kickAt.indices.minBy { kickAt[it] }
            kickAt[j] = clock + delay
        }
    }

    private fun accent(sample: FloatArray, vel: Float, pan: Float, delay: Int) {
        alloc(accentVoices).trigger(sample, vel, pan, delay, clock)
    }

    private fun alloc(pool: Array<SynthVoice>): SynthVoice {
        var oldest = pool[0]
        for (v in pool) {
            if (!v.active) return v
            if (v.age < oldest.age) oldest = v
        }
        return oldest
    }

    private fun alloc(pool: Array<SampleVoice>): SampleVoice {
        var oldest = pool[0]
        for (v in pool) {
            if (!v.active) return v
            if (v.age < oldest.age) oldest = v
        }
        return oldest
    }
}
