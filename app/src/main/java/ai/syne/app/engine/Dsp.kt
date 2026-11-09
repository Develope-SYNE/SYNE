package ai.syne.app.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/*
 * 오디오 스레드용 DSP 부품. 렌더 루프 안에서는 객체 할당·박싱·Math.sin 호출을 하지 않는다.
 */

internal object Wavetable {
    const val SIZE = 2048
    val SINE = FloatArray(SIZE + 1) { sin(2.0 * PI * it / SIZE).toFloat() }

    /** phase 0..1 */
    fun sine(phase: Float): Float {
        val x = phase * SIZE
        val i = x.toInt()
        val f = x - i
        val a = SINE[i]
        return a + (SINE[i + 1] - a) * f
    }
}

internal fun wrap01(x: Float): Float = x - kotlin.math.floor(x)

/** 대역 제한 톱니파 보정 */
internal fun polyBlep(t: Float, dt: Float): Float = when {
    t < dt -> {
        val x = t / dt
        x + x - x * x - 1f
    }
    t > 1f - dt -> {
        val x = (t - 1f) / dt
        x * x + x + x + 1f
    }
    else -> 0f
}

/** 부드러운 포화. |x|가 커져도 ±1 근처로 수렴. */
internal fun softClip(x: Float): Float {
    val c = x.coerceIn(-3f, 3f)
    return c * (27f + c * c) / (27f + 9f * c * c)
}

internal class Pan(var l: Float = 0.707f, var r: Float = 0.707f) {
    /** pan -1..1, 등전력 */
    fun set(pan: Float) {
        val a = (pan.coerceIn(-1f, 1f) + 1f) * (PI.toFloat() / 4f)
        l = cos(a)
        r = sin(a)
    }
}

/** 1차 저역통과(버스 색깔용) */
internal class OnePoleLp(sampleRate: Int, cutoffHz: Float) {
    private val a = (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()
    private var zl = 0f
    private var zr = 0f

    fun process(l: FloatArray, r: FloatArray, len: Int) {
        var sl = zl
        var sr = zr
        for (i in 0 until len) {
            sl += a * (l[i] - sl)
            sr += a * (r[i] - sr)
            l[i] = sl
            r[i] = sr
        }
        zl = sl
        zr = sr
    }
}

/** TPT 상태변수 필터(저역통과). 급제동 스윕용. 계수는 블록 단위로만 갱신. */
internal class SvfLowpass(private val sampleRate: Int) {
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var ic1l = 0f
    private var ic2l = 0f
    private var ic1r = 0f
    private var ic2r = 0f

    fun setCutoff(hz: Float, q: Float = 1.2f) {
        val fc = hz.coerceIn(20f, sampleRate * 0.45f)
        val g = tan(PI * fc / sampleRate).toFloat()
        val k = 1f / q
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun reset() {
        ic1l = 0f; ic2l = 0f; ic1r = 0f; ic2r = 0f
    }

    fun process(l: FloatArray, r: FloatArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val v3l = l[i] - ic2l
            val v1l = a1 * ic1l + a2 * v3l
            val v2l = ic2l + a2 * ic1l + a3 * v3l
            ic1l = 2f * v1l - ic1l
            ic2l = 2f * v2l - ic2l
            l[i] = v2l

            val v3r = r[i] - ic2r
            val v1r = a1 * ic1r + a2 * v3r
            val v2r = ic2r + a2 * ic1r + a3 * v3r
            ic1r = 2f * v1r - ic1r
            ic2r = 2f * v2r - ic2r
            r[i] = v2r
        }
    }
}

/** 음색 정의 */
internal class Patch(
    val wave: Int,
    val attackSec: Float,
    val decaySec: Float,
    val sustain: Float,
    val releaseSec: Float,
    val detuneCents: Float = 0f,
    /** EP(FM) 전용: 변조 지수와 그 감쇠 시간 */
    val fmIndex: Float = 0f,
    val fmDecaySec: Float = 0.3f,
) {
    companion object {
        const val SINE = 0
        const val TRIANGLE = 1
        const val SAW2 = 2 // 디튠된 톱니파 2개
        const val EP = 3 // 2-op FM 일렉트릭 피아노
    }
}

/** 단음 신시사이저 보이스. 게이트 길이가 지나면 스스로 release. */
internal class SynthVoice(private val sampleRate: Int) {
    var active = false
        private set
    var age = 0L
        private set

    private lateinit var patch: Patch
    private var phase = 0f
    private var phase2 = 0f
    private var modPhase = 0f
    private var inc = 0f
    private var inc2 = 0f
    private var vel = 0f
    private val pan = Pan()
    private var delay = 0
    private var gate = 0

    private var stage = 0 // 1 attack, 2 decay/sustain, 3 release
    private var level = 0f
    private var attackInc = 0f
    private var decayCoef = 0f
    private var releaseCoef = 0f
    private var fm = 0f
    private var fmCoef = 0f

    fun noteOn(midi: Float, velocity: Float, panPos: Float, delaySamples: Int, gateSamples: Int, p: Patch, now: Long) {
        patch = p
        val hz = MusicTheory.midiToHz(midi)
        inc = hz / sampleRate
        inc2 = inc * Math.pow(2.0, p.detuneCents / 1200.0).toFloat()
        vel = velocity
        pan.set(panPos)
        delay = delaySamples
        gate = gateSamples
        phase = 0f
        phase2 = 0.37f
        modPhase = 0f
        level = 0f
        stage = 1
        attackInc = 1f / (p.attackSec * sampleRate).coerceAtLeast(1f)
        decayCoef = exp(-1.0 / (p.decaySec * sampleRate).coerceAtLeast(1f)).toFloat()
        releaseCoef = exp(-1.0 / (p.releaseSec * sampleRate).coerceAtLeast(1f)).toFloat()
        fm = p.fmIndex
        fmCoef = exp(-1.0 / (p.fmDecaySec * sampleRate).coerceAtLeast(1f)).toFloat()
        active = true
        age = now
    }

    fun release() {
        if (active) stage = 3
    }

    fun render(outL: FloatArray, outR: FloatArray, off: Int, len: Int) {
        if (!active) return
        val p = patch
        val sustain = p.sustain
        var i = off
        val end = off + len
        while (i < end) {
            if (delay > 0) {
                delay--
                i++
                continue
            }
            if (gate > 0) {
                gate--
                if (gate == 0 && stage < 3) stage = 3
            }
            when (stage) {
                1 -> {
                    level += attackInc
                    if (level >= 1f) {
                        level = 1f
                        stage = 2
                    }
                }
                2 -> level = sustain + (level - sustain) * decayCoef
                3 -> {
                    level *= releaseCoef
                    if (level < 1e-4f) {
                        active = false
                        return
                    }
                }
            }

            val s = when (p.wave) {
                Patch.SINE -> Wavetable.sine(phase)
                Patch.TRIANGLE -> 4f * kotlin.math.abs(phase - 0.5f) - 1f
                Patch.SAW2 -> {
                    val a = 2f * phase - 1f - polyBlep(phase, inc)
                    val b = 2f * phase2 - 1f - polyBlep(phase2, inc2)
                    (a + b) * 0.5f
                }
                else -> { // EP: 반송파 = 사인, 변조파 1:1, 지수는 지수감쇠
                    val m = Wavetable.sine(modPhase) * fm
                    fm *= fmCoef
                    Wavetable.sine(wrap01(phase + m * 0.16f))
                }
            }
            phase += inc
            if (phase >= 1f) phase -= 1f
            phase2 += inc2
            if (phase2 >= 1f) phase2 -= 1f
            modPhase += inc
            if (modPhase >= 1f) modPhase -= 1f

            val v = s * level * vel
            outL[i] += v * pan.l
            outR[i] += v * pan.r
            i++
        }
    }
}

/** 샘플(드럼) 재생 보이스 */
internal class SampleVoice {
    var active = false
        private set
    var age = 0L
        private set
    private var data: FloatArray = FloatArray(0)
    private var pos = 0
    private var gain = 0f
    private var delay = 0
    private val pan = Pan()

    fun trigger(sample: FloatArray, velocity: Float, panPos: Float, delaySamples: Int, now: Long) {
        data = sample
        pos = 0
        gain = velocity
        delay = delaySamples
        pan.set(panPos)
        active = true
        age = now
    }

    fun render(outL: FloatArray, outR: FloatArray, off: Int, len: Int) {
        if (!active) return
        var i = off
        val end = off + len
        while (i < end) {
            if (delay > 0) {
                delay--
                i++
                continue
            }
            if (pos >= data.size) {
                active = false
                return
            }
            val v = data[pos++] * gain
            outL[i] += v * pan.l
            outR[i] += v * pan.r
            i++
        }
    }
}

// =====================================================================
// 운전 반응을 '귀로' 들리게 하는 마스터 효과들 (오디오 스레드 전용, 할당 없음)
// =====================================================================

/** 빠른 정수 난수(xorshift) — 크랙클·히스용 */
internal class FastRandom(seed: Int) {
    private var s = if (seed == 0) 0x9E3779B9.toInt() else seed
    /** -1..1 */
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return (s and 0xFFFFFF) / 8388608f - 1f
    }
    /** 0..1 */
    fun unit(): Float = (next() + 1f) * 0.5f
}

/** Freeverb 계열 콤 필터(댐핑 포함) */
internal class Comb(size: Int) {
    private val buf = FloatArray(size)
    private var idx = 0
    private var store = 0f
    fun process(x: Float, feedback: Float, damp: Float): Float {
        val out = buf[idx]
        store = out * (1f - damp) + store * damp
        buf[idx] = x + store * feedback
        if (++idx == buf.size) idx = 0
        return out
    }
}

internal class Allpass(size: Int) {
    private val buf = FloatArray(size)
    private var idx = 0
    fun process(x: Float): Float {
        val b = buf[idx]
        buf[idx] = x + b * 0.5f
        if (++idx == buf.size) idx = 0
        return b - x
    }
}

/**
 * 스테레오 리버브(Freeverb 축소판: 채널당 콤 4 + 올패스 2). 꼬리 약 3초(RT60).
 * process는 입력(send) 버퍼를 받아 wet를 out에 '더한다'.
 */
internal class StereoReverb(sr: Int) {
    private val scale = sr / 44_100f
    private val sizes = intArrayOf(1116, 1188, 1277, 1356)
    private val combL = Array(4) { Comb((sizes[it] * scale).toInt()) }
    private val combR = Array(4) { Comb(((sizes[it] + 23) * scale).toInt()) }
    private val apL = arrayOf(Allpass((556 * scale).toInt()), Allpass((441 * scale).toInt()))
    private val apR = arrayOf(Allpass(((556 + 23) * scale).toInt()), Allpass(((441 + 23) * scale).toInt()))
    var feedback = 0.935f
    var damp = 0.35f

    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, wet: Float) {
        for (i in 0 until n) {
            val x = (inL[i] + inR[i]) * 0.035f
            var l = 0f
            var r = 0f
            for (c in combL) l += c.process(x, feedback, damp)
            for (c in combR) r += c.process(x, feedback, damp)
            for (a in apL) l = a.process(l)
            for (a in apR) r = a.process(r)
            outL[i] += l * wet
            outR[i] += r * wet
        }
    }
}

/**
 * 테이프 데크: 마스터 믹스를 링버퍼에 쓰고 '읽기 헤드'로 다시 읽는다.
 * - 평소: 읽기 지연을 천천히 흔들어 피치를 ±0.3% 출렁이게(테이프 워블)
 * - 급제동: 읽기 속도를 0.6초 동안 1 → 0으로 떨어뜨려 피치가 내려가며 멈춤(테이프 스톱) → 무음
 * - resume(): 실시간 위치로 돌아와 짧게 페이드인
 */
internal class TapeDeck(private val sr: Int) {
    private val size = 1 shl 16
    private val mask = size - 1
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var w = 0L

    // 워블: 0.55Hz, 기본 ±0.3%(wobble=1). 과속 '낡음'에서는 wobble≈6.7(±2%). 0이면 워블 없음.
    // 기본 지연은 ±2% 깊이(≈278샘플)보다 커야 미래를 읽지 않는다.
    private val baseDelay = 320.0
    private val depth1 = 0.003 * sr / (2 * Math.PI * 0.55)

    /** 워블 깊이 배율(1 = ±0.3%) */
    var wobble = 1f
    private val depth2 = 0.0008 * sr / (2 * Math.PI * 0.13)
    private var ph1 = 0.0
    private var ph2 = 0.25
    private val inc1 = 0.55 / sr
    private val inc2 = 0.13 / sr

    /** 0 평소, 1 멈추는 중, 2 무음, 3 스핀업 */
    var mode = 0
        private set
    private var readPos = 0.0
    private var stopT = 0
    private val stopLen = (0.6 * sr).toInt()
    var silentSamples = 0
        private set
    private var fadeIn = 1f
    private val fadeStep = 1f / (0.012f * sr)

    private fun delayNow(): Double =
        baseDelay + wobble * (depth1 * Math.sin(2 * Math.PI * ph1) + depth2 * Math.sin(2 * Math.PI * ph2))

    // 스핀업: 무음 상태에서 피치 -12st → 0을 spinLen 동안 올리며 실시간 위치에 정확히 도착
    private val spinLen = (0.25 * sr).toInt()
    private var spinT = 0

    /** 무음(2)에서 호출: 0.25초 동안 피치가 한 옥타브 아래에서 원래대로 올라오며 진입(3) */
    fun spinUp() {
        if (mode != 2) return
        // 읽기 속도 합만큼 뒤에서 출발해야 끝날 때 실시간 위치와 맞는다(이음새 없음)
        var sum = 0.0
        for (i in 0 until spinLen) sum += spinRate(i)
        readPos = (w - 1).toDouble() - delayNow() - (spinLen - sum)
        spinT = 0
        mode = 3
    }

    private fun spinRate(i: Int): Double = Math.pow(2.0, -(1.0 - i.toDouble() / spinLen)) // 0.5 → 1

    fun startStop() {
        if (mode != 0) return
        readPos = (w - 1).toDouble() - delayNow()
        stopT = 0
        mode = 1
    }

    fun resume() {
        mode = 0
        fadeIn = 0f
        silentSamples = 0
    }

    private fun read(buf: FloatArray, pos: Double): Float {
        val i = Math.floor(pos).toLong()
        val f = (pos - i).toFloat()
        val a = buf[(i and mask.toLong()).toInt()]
        val b = buf[((i + 1) and mask.toLong()).toInt()]
        return a + (b - a) * f
    }

    fun process(l: FloatArray, r: FloatArray, n: Int) {
        for (k in 0 until n) {
            val wi = (w and mask.toLong()).toInt()
            bufL[wi] = l[k]
            bufR[wi] = r[k]
            w++
            ph1 += inc1; if (ph1 >= 1.0) ph1 -= 1.0
            ph2 += inc2; if (ph2 >= 1.0) ph2 -= 1.0
            when (mode) {
                0 -> {
                    val pos = (w - 1).toDouble() - delayNow()
                    if (fadeIn < 1f) fadeIn = (fadeIn + fadeStep).coerceAtMost(1f)
                    l[k] = read(bufL, pos) * fadeIn
                    r[k] = read(bufR, pos) * fadeIn
                }
                1 -> {
                    val p = stopT.toFloat() / stopLen
                    val rate = Math.pow((1.0 - p), 1.6)
                    readPos += rate
                    val g = 1f - p * p * p
                    l[k] = read(bufL, readPos) * g
                    r[k] = read(bufR, readPos) * g
                    if (++stopT >= stopLen) {
                        mode = 2
                        silentSamples = 0
                    }
                }
                3 -> {
                    readPos += spinRate(spinT)
                    l[k] = read(bufL, readPos)
                    r[k] = read(bufR, readPos)
                    if (++spinT >= spinLen) mode = 0
                }
                else -> {
                    l[k] = 0f
                    r[k] = 0f
                    silentSamples++
                }
            }
        }
    }
}

/** LP 레코드 질감: 드문 크랙클(틱) + 아주 작은 히스. 마스터에 더한다. */
internal class Crackle(private val sr: Int, seed: Int) {
    private val rnd = FastRandom(seed)
    private val clickProb = 7f / sr // 초당 약 7개
    private var clickL = 0f
    private var clickR = 0f
    private var hissL = 0f
    private var hissR = 0f
    /** 사용자·테스트용 양(0~1) */
    var level = 1f

    /** 효과용 배율(과속 '낡음' 시 4 = +12dB, 엔딩 꼬리에서 0으로) */
    var boost = 1f

    fun add(l: FloatArray, r: FloatArray, n: Int) {
        val g = level * boost
        for (k in 0 until n) {
            if (rnd.unit() < clickProb) clickL = rnd.next() * 0.03f
            if (rnd.unit() < clickProb) clickR = rnd.next() * 0.03f
            clickL *= 0.55f
            clickR *= 0.55f
            hissL += 0.08f * (rnd.next() - hissL)
            hissR += 0.08f * (rnd.next() - hissR)
            l[k] += (clickL + hissL * 0.004f) * g
            r[k] += (clickR + hissR * 0.004f) * g
        }
    }
}

/** 블록 단위 선형 램프(효과 파라미터용) */
internal class LinRamp(var value: Float) {
    private var target = value
    private var step = 0f

    fun set(to: Float, seconds: Float, sr: Int) {
        target = to
        val samples = (seconds * sr).coerceAtLeast(1f)
        step = (to - value) / samples
    }

    /** n샘플 진행 후 현재 값 */
    fun advance(n: Int): Float {
        if (value != target) {
            value += step * n
            if ((step > 0 && value > target) || (step < 0 && value < target) || step == 0f) value = target
        }
        return value
    }
}

/**
 * 비트크러셔(힙합 과속 '깨짐'): 다운샘플(샘플 앤 홀드) + 6bit 양자화. [amount] 0 = 원음, 1 = 완전히 깨진 소리.
 */
internal class Crusher(sampleRate: Int, targetRate: Int = 7_000, bits: Int = 6) {
    private val hold = (sampleRate / targetRate).coerceAtLeast(1)
    private val levels = (1 shl (bits - 1)).toFloat()
    private var count = 0
    private var hl = 0f
    private var hr = 0f

    fun process(l: FloatArray, r: FloatArray, n: Int, amount: Float) {
        if (amount <= 0f) return
        for (k in 0 until n) {
            if (count == 0) {
                hl = Math.round(l[k] * levels) / levels
                hr = Math.round(r[k] * levels) / levels
            }
            count = (count + 1) % hold
            l[k] += (hl - l[k]) * amount
            r[k] += (hr - r[k]) * amount
        }
    }
}

/**
 * 808 모노 보이스(글라이드): 위상이 끊기지 않는 사인 + 포화.
 *  - 새 음(slide=false): 1옥타브 위에서 목표 음으로 떨어지는 '붐'(약 40ms) + 3ms 어택
 *  - 슬라이드(slide=true, 앞 음이 아직 울릴 때): 현재 음높이에서 목표로 미끄러진다(로그 주파수, 약 70ms)
 * 길게 울리고(시상수 [decaySec]) 다음 음이 오면 같은 보이스가 이어받아 겹침·클릭이 없다.
 * [drive]: 포화 세기. 1.35 = 서브가 주인공, 3 이상 = 배음이 거칠게 살아나는 공격적인 808(작은 스피커에서도 들린다).
 */
internal class Bass808Voice(private val sr: Int, private val decaySec: Float = 0.55f, private val drive: Float = 1.35f) {
    /** 드라이브를 올려도 최대 크기가 비슷하도록 맞추는 출력 배율 */
    private val outGain = 0.81f / softClip(drive)
    var active = false
        private set
    private val pan = Pan().also { it.set(0f) }
    private var phase = 0.0
    private var logHz = 0f
    private var logTarget = 0f
    private var glideK = 0f
    private var drop = 0f
    private val dropK = (1.0 - Math.exp(-1.0 / (0.012 * sr))).toFloat()
    private var env = 0f
    private var peak = 0f
    private var atkFrom = 0f
    private var atk = 1f
    private val atkInc = 1f / (0.003f * sr)
    private val decayMul = Math.exp(-1.0 / (decaySec * sr)).toFloat()
    private var delay = 0
    private var pendingHz = 0f
    private var pendingVel = 0f
    private var pendingSlide = false
    private var hasPending = false

    fun trigger(midi: Int, velocity: Float, delaySamples: Int, slide: Boolean) {
        pendingHz = MusicTheory.midiToHz(midi.toFloat())
        pendingVel = velocity
        pendingSlide = slide
        delay = delaySamples
        hasPending = true
        active = true
    }

    private fun start() {
        hasPending = false
        val target = kotlin.math.ln(pendingHz)
        atkFrom = env // 앞 음이 울리고 있으면 그 크기에서 3ms 동안 이어 붙인다(클릭 없음)
        atk = 0f
        if (pendingSlide && env > 0.06f) {
            logTarget = target
            glideK = (1.0 - Math.exp(-1.0 / (0.07 * sr))).toFloat()
            peak = maxOf(env, pendingVel * 0.85f)
        } else {
            logHz = target
            logTarget = target
            glideK = 0f
            drop = 1f // +1옥타브에서 떨어지는 '붐'
            peak = pendingVel
        }
    }

    fun render(outL: FloatArray, outR: FloatArray, off: Int, len: Int) {
        if (!active) return
        for (i in off until off + len) {
            if (hasPending) {
                if (delay > 0) delay-- else start()
            }
            if (atk < 1f) {
                atk = (atk + atkInc).coerceAtMost(1f)
                env = atkFrom + (peak - atkFrom) * atk
            } else {
                env *= decayMul
            }
            if (glideK > 0f) logHz += (logTarget - logHz) * glideK
            drop -= drop * dropK
            val hz = Math.exp(logHz.toDouble()) * (1.0 + drop)
            phase += hz / sr
            if (phase >= 1.0) phase -= 1.0
            // 포화: 약하면 기음이 주인공, 세면(힙합) 사각파에 가까워져 배음이 거칠게 선다. 살짝 비대칭(짝수 배음)
            val x = kotlin.math.sin(2 * Math.PI * phase).toFloat() * env
            val v = softClip(drive * (x + 0.12f * (x * x - 0.5f * env * env))) * outGain // x² 평균을 빼서 DC 없이
            outL[i] += v * pan.l
            outR[i] += v * pan.r
        }
        if (env < 1e-4f && !hasPending) active = false
    }
}

/** 스테레오 피드백 딜레이(힙합 벨 스탭 에코). 딜레이 길이는 호출마다 지정(1/8박 등) */
internal class StereoDelay(maxSamples: Int) {
    private val bl = FloatArray(maxSamples)
    private val br = FloatArray(maxSamples)
    private var w = 0

    fun process(l: FloatArray, r: FloatArray, n: Int, delaySamples: Int, feedback: Float, wet: Float) {
        val d = delaySamples.coerceIn(1, bl.size - 1)
        for (k in 0 until n) {
            val rd = (w - d + bl.size) % bl.size
            val yl = bl[rd]; val yr = br[rd]
            // 핑퐁: 왼쪽 메아리가 오른쪽으로 넘어간다
            bl[w] = l[k] + yr * feedback
            br[w] = r[k] + yl * feedback
            l[k] += yl * wet
            r[k] += yr * wet
            w = (w + 1) % bl.size
        }
    }
}
