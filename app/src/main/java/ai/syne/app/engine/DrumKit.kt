package ai.syne.app.engine

import ai.syne.app.SyneLog
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 드럼 샘플 3종(kick·snare·hat) + 방향지시등용 오픈햇.
 * assets/drums/{kick,snare,hat,openhat}.wav (CC0 권장, 16bit PCM 또는 32bit float)가 있으면 그것을,
 * 없으면 합성한 샘플을 쓴다. 엔진 시작 시 1회 준비하고 렌더 루프에서는 읽기만 한다.
 * 힙합은 assets/drums/hiphop/ 를 먼저 보고, 없으면 짧고 단단한 펀치 킥·크게 터지는 스네어·클랩·크래시를 합성한다.
 */
internal class DrumKit(
    val kick: FloatArray,
    val snare: FloatArray,
    val hat: FloatArray,
    val openHat: FloatArray,
    /** 스네어에 겹치는 클랩(힙합). 비어 있으면 겹치지 않는다 */
    val clap: FloatArray = FloatArray(0),
    /** 4마디마다 치는 크래시(힙합). 비어 있으면 치지 않는다 */
    val crash: FloatArray = FloatArray(0),
) {

    /** 리버스 심벌(라이저) — 테이프 스톱 뒤 재시작 직전 1박. 길이는 엔진이 박 길이로 만든다. */
    fun reverseCymbal(sampleRate: Int, seconds: Float): FloatArray = synthReverseCymbal(sampleRate, seconds)

    companion object {
        fun load(assets: AssetManager, sampleRate: Int, pack: GenrePack = GenrePack.LOFI): DrumKit {
            val dir = if (pack == GenrePack.HIPHOP) "drums/hiphop" else "drums"
            fun pick(name: String, synth: () -> FloatArray): FloatArray {
                val loaded = runCatching {
                    assets.open("$dir/$name.wav").use { WavReader.read(it.readBytes(), sampleRate) }
                }.getOrNull()
                if (loaded != null) SyneLog.i("drum '$dir/$name' ← assets (${loaded.size} samples)")
                return loaded ?: synth()
            }
            val base = synth(sampleRate, pack)
            return DrumKit(
                kick = pick("kick") { base.kick },
                snare = pick("snare") { base.snare },
                hat = pick("hat") { base.hat },
                openHat = pick("openhat") { base.openHat },
                clap = if (base.clap.isEmpty()) base.clap else pick("clap") { base.clap },
                crash = if (base.crash.isEmpty()) base.crash else pick("crash") { base.crash },
            )
        }

        /** 에셋 없이 합성 드럼만으로 만든 킷(테스트·오프라인 렌더용) */
        fun synth(sampleRate: Int, pack: GenrePack = GenrePack.LOFI) = when (pack) {
            GenrePack.LOFI -> DrumKit(synthKick(sampleRate), synthSnare(sampleRate), synthHat(sampleRate, 0.05f), synthHat(sampleRate, 0.28f))
            GenrePack.HIPHOP -> DrumKit(
                synthPunchKick(sampleRate), synthHardSnare(sampleRate), synthHat(sampleRate, 0.035f), synthHat(sampleRate, 0.32f),
                clap = synthClap(sampleRate), crash = synthCrash(sampleRate),
            )
        }

        /** 클랩: 10ms 간격의 노이즈 버스트 3번 + 짧은 꼬리(밴드패스 느낌) */
        private fun synthClap(sr: Int): FloatArray {
            val n = (0.28f * sr).toInt()
            val rnd = Random(31)
            var hp = 0f
            var prev = 0f
            var lp = 0f
            val gap = 0.011f
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.82f * (hp + noise - prev)
                prev = noise
                lp += 0.55f * (hp - lp)
                val burst = (0 until 3).sumOf { k ->
                    val tk = t - k * gap
                    if (tk >= 0f && tk < gap) Math.exp(-tk * 180.0) else 0.0
                }.toFloat()
                val tail = if (t >= 2 * gap) exp(-(t - 2 * gap) * 16f) else 0f
                lp * (burst * 0.9f + tail * 0.75f)
            }
        }

        /**
         * 펀치 킥: 짧고 단단하게(0.3초). 어택 강조 = 2ms 노이즈 클릭 + 빠르게 떨어지는 피치(260→52Hz, 약 15ms)의 '탁' +
         * 짧은 몸통. 꼬리가 짧아 808과 부딪히지 않는다. 세게 포화시켜 단단하게
         */
        private fun synthPunchKick(sr: Int): FloatArray {
            val n = (0.3f * sr).toInt()
            var phase = 0.0
            val rnd = Random(3)
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val f = 52f + 210f * exp(-t * 70f)
                phase += f / sr
                val body = sin(2 * PI * phase).toFloat() * exp(-t * 11f)
                val punch = sin(2 * PI * phase * 2).toFloat() * exp(-t * 60f) * 0.5f
                val click = if (i < sr / 500) (rnd.nextFloat() * 2f - 1f) * 0.8f * (1f - i * 500f / sr) else 0f
                val tail = if (t > 0.26f) 1f - (t - 0.26f) / 0.04f else 1f // 끝 40ms 페이드(클릭 없음)
                softClip((body + punch + click) * 2.6f) * tail
            }
        }

        /** 하드 스네어: 크게 터지는 밝은 노이즈 크랙 + 210Hz 몸통, 강한 포화. 잔향은 짧은 룸 리버브가 맡는다 */
        private fun synthHardSnare(sr: Int): FloatArray {
            val n = (0.26f * sr).toInt()
            val rnd = Random(19)
            var hp = 0f
            var prev = 0f
            var lp = 0f
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.92f * (hp + noise - prev)
                prev = noise
                lp += 0.8f * (hp - lp)
                val crack = lp * (exp(-t * 22f) * 1.1f + exp(-t * 8f) * 0.15f)
                val body = sin(2 * PI * 210.0 * t).toFloat() * exp(-t * 30f) * 0.75f +
                    sin(2 * PI * 340.0 * t).toFloat() * exp(-t * 45f) * 0.3f
                softClip((crack + body) * 1.7f)
            }
        }

        /** 크래시: 밝은 노이즈 + 금속성 링(비화성 배음 3개), 1.6초 감쇠 */
        private fun synthCrash(sr: Int): FloatArray {
            val n = (1.6f * sr).toInt()
            val rnd = Random(41)
            var hp = 0f
            var prev = 0f
            val ring = doubleArrayOf(3_150.0, 4_870.0, 6_920.0)
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.95f * (hp + noise - prev)
                prev = noise
                var m = 0f
                for (f in ring) m += sin(2 * PI * f * t).toFloat()
                val env = exp(-t * 2.6f) * (if (t < 0.002f) t / 0.002f else 1f)
                (hp * 0.75f + m * 0.06f) * env * (1f + 1.2f * exp(-t * 30f)) * 0.5f
            }
        }

        private fun synthReverseCymbal(sr: Int, seconds: Float): FloatArray {
            val n = (seconds * sr).toInt()
            val rnd = Random(23)
            var hp = 0f
            var prev = 0f
            var shimmer = 0.0
            return FloatArray(n) { i ->
                val p = i.toFloat() / n // 0 → 1
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.92f * (hp + noise - prev) // 밝은 노이즈(심벌)
                prev = noise
                shimmer += (6_000.0 + 2_000.0 * p) / sr
                val ring = sin(2 * PI * shimmer).toFloat() * 0.15f
                val env = Math.pow(p.toDouble(), 2.6).toFloat() // 거꾸로 된 감쇠 = 점점 커짐
                (hp * 0.85f + ring) * env * 0.55f
            }
        }

        private fun synthKick(sr: Int): FloatArray {
            val n = (0.45f * sr).toInt()
            var phase = 0.0
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val f = 46f + 110f * exp(-t * 30f)
                phase += f / sr
                val body = sin(2 * PI * phase).toFloat() * exp(-t * 7f)
                val click = if (i < sr / 400) (Random.nextFloat() * 2f - 1f) * 0.3f * (1f - i * 400f / sr) else 0f
                softClip((body + click) * 1.4f)
            }
        }

        private fun synthSnare(sr: Int): FloatArray {
            val n = (0.25f * sr).toInt()
            val rnd = Random(7)
            var hp = 0f
            var prev = 0f
            var lp = 0f
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.85f * (hp + noise - prev) // 고역 통과
                prev = noise
                lp += 0.45f * (hp - lp) // 너무 날카롭지 않게 (lo-fi)
                val tone = sin(2 * PI * 185.0 * t).toFloat() * exp(-t * 32f) * 0.45f
                (lp * exp(-t * 17f) * 0.9f + tone)
            }
        }

        private fun synthHat(sr: Int, lengthSec: Float): FloatArray {
            val n = (lengthSec * sr).toInt()
            val rnd = Random(11)
            var hp = 0f
            var prev = 0f
            val decay = 4.6f / lengthSec
            return FloatArray(n) { i ->
                val t = i.toFloat() / sr
                val noise = rnd.nextFloat() * 2f - 1f
                hp = 0.6f * (hp + noise - prev)
                prev = noise
                hp * exp(-t * decay) * 0.8f
            }
        }
    }
}

/** 최소 WAV 파서: PCM 16bit / IEEE float 32bit, 모노·스테레오(모노로 합침), 선형 리샘플 */
internal object WavReader {
    fun read(bytes: ByteArray, targetRate: Int): FloatArray? {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt()
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOff = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1)
        }
        if (dataOff < 0 || channels <= 0 || rate <= 0) return null
        val pcm16 = format == 1 && bits == 16
        val float32 = format == 3 && bits == 32
        if (!pcm16 && !float32) return null
        val frameBytes = channels * bits / 8
        val frames = dataLen / frameBytes
        val mono = FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) {
                val at = dataOff + f * frameBytes + c * bits / 8
                sum += if (pcm16) bb.getShort(at) / 32768f else bb.getFloat(at)
            }
            sum / channels
        }
        if (rate == targetRate) return mono
        val ratio = rate.toDouble() / targetRate
        val outLen = (frames / ratio).toInt()
        return FloatArray(outLen) { i ->
            val x = i * ratio
            val i0 = x.toInt().coerceAtMost(frames - 1)
            val i1 = (i0 + 1).coerceAtMost(frames - 1)
            val fr = (x - i0).toFloat()
            mono[i0] + (mono[i1] - mono[i0]) * fr
        }
    }
}
