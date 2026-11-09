package ai.syne.app.engine

import ai.syne.app.signals.Gear
import ai.syne.app.signals.TurnSignal
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 엔진 전체(Renderer)를 시뮬레이션 주행으로 오프라인 렌더해 '귀로 들려야 하는' 반응을 수치로 확인한다.
 * 결과 WAV는 app/build/syne-audio/ 에 남겨 직접 들어볼 수 있다.
 */
class OfflineRenderTest {
    private val sr = 48_000
    private val block = Renderer.BLOCK

    private class Frame(
        val speed: Float, val brake: Boolean, val gear: Gear = Gear.DRIVE, val limit: Int? = null, val turn: TurnSignal = TurnSignal.OFF,
        val steer: Float = 0f, val remainSec: Int? = null,
    )

    /** 마지막 render에서 마디가 시작된 샘플 위치들 */
    private val barStarts = ArrayList<Long>()

    /** 마지막 render에서 곡이 끝난(finished) 샘플 위치 */
    private var finishedAt = -1

    private fun render(
        name: String, seconds: Float, drive: (Float) -> Frame, vinyl: Float? = null, pack: GenrePack = GenrePack.LOFI, stopWhenFinished: Boolean = false,
    ): FloatArray {
        finishedAt = -1
        barStarts.clear()
        var ref: Renderer? = null
        val r = Renderer(sr, DrumKit.synth(sr, pack), object : RendererSink {
            override fun onBar(info: BarInfo) { ref?.let { barStarts += it.clock } }
            override fun onFx(fx: FxState) {}
            override fun onSecond(sample: SecondSample, layersMask: Int, section: Section) {}
            override fun onMelodyNote(tSec: Float, midi: Int, durSec: Float) {}
        }, pack).also { if (vinyl != null) it.vinyl = vinyl }
        ref = r
        val frames = (seconds * sr).toInt() / block
        val out = FloatArray(frames * block * 2)
        val buf = FloatArray(block * 2)
        val dt = block.toFloat() / sr
        for (f in 0 until frames) {
            val t = f * dt
            val q = (t * 10).toInt() / 10f // 신호는 10Hz 계단
            val fr = drive(q)
            r.updateControls(fr.speed, fr.steer, fr.brake, fr.turn, fr.gear, fr.limit, fr.remainSec, dt)
            r.render(buf, block)
            System.arraycopy(buf, 0, out, f * block * 2, block * 2)
            if (r.finished && finishedAt < 0) {
                finishedAt = (f + 1) * block
                if (stopWhenFinished) {
                    val cut = out.copyOf(finishedAt * 2)
                    writeWav(File("build/syne-audio/$name.wav"), cut)
                    return cut
                }
            }
        }
        writeWav(File("build/syne-audio/$name.wav"), out)
        return out
    }

    /** 데모 코스(assets JSON)를 그대로 재생해 렌더 — 조향·방향지시등·제한속도·남은 시간까지 실제 값 */
    private fun renderDemo(name: String, asset: String, pack: GenrePack): FloatArray {
        val trip = ai.syne.app.signals.ReplayTrip.parse(File("src/main/assets/$asset").readText())
        val last = trip.frames.last()
        return render(name, trip.durationSec + 25f, pack = pack, stopWhenFinished = true, drive = { t ->
            val fr = trip.frames.getOrNull((t * trip.hz).toInt()) ?: last
            Frame(fr.speedKmh, fr.brake, fr.gear, fr.speedLimitKmh, fr.turn, fr.steeringDeg, fr.remainSec)
        })
    }

    /** 구간 RMS(dB) */
    private fun rmsDb(x: FloatArray, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * sr).toInt() * 2; val b = (toSec * sr).toInt() * 2
        var s = 0.0
        for (i in a until b) s += x[i] * x[i]
        return 20 * log10(sqrt(s / (b - a)) + 1e-9)
    }

    /** 밝기: 700Hz 위(RC 고역통과 4단, 24dB/oct) 에너지가 전체에서 차지하는 비율(dB) */
    private fun brightDb(x: FloatArray, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * sr).toInt() * 2; val b = (toSec * sr).toInt() * 2
        val rc = 1.0 / (2 * Math.PI * 700); val dt = 1.0 / sr; val al = rc / (rc + dt)
        val h = DoubleArray(4); val p = DoubleArray(4)
        var eHi = 0.0; var eAll = 0.0; var i = a
        while (i < b) {
            var v = (x[i] + x[i + 1]) * 0.5
            eAll += v * v
            for (st in 0 until 4) { val y = al * (h[st] + v - p[st]); p[st] = v; h[st] = y; v = y }
            eHi += v * v; i += 2
        }
        return 10 * log10(eHi / (eAll + 1e-12) + 1e-12)
    }

    /** 저역(킥·베이스) 에너지: 150Hz 1차 저역통과 후 RMS(dB) */
    private fun lfDb(x: FloatArray, fromSec: Float, toSec: Float): Double {
        val a = (fromSec * sr).toInt() * 2; val b = (toSec * sr).toInt() * 2
        val c = (1 - Math.exp(-2 * Math.PI * 150 / sr)).toFloat()
        var z = 0f; var s = 0.0; var i = a
        while (i < b) { z += c * ((x[i] + x[i + 1]) * 0.5f - z); s += z * z; i += 2 }
        return 20 * log10(sqrt(s / ((b - a) / 2)) + 1e-9)
    }

    private fun cruiseThen(at: Float, after: (Float) -> Frame): (Float) -> Frame = { t ->
        when {
            t < 3f -> Frame(0f, true, Gear.PARK)
            t < 5f -> Frame(0f, true)
            t < 12f -> Frame(((t - 5f) / 7f * 50f).coerceAtMost(50f), false)
            t < at -> Frame(50f, false)
            else -> after(t - at)
        }
    }

    @Test fun `급제동은 1초 안에 테이프 스톱으로 거의 무음이 된다`() {
        val x = render("hard_brake", 28f, cruiseThen(18f) { t ->
            Frame((50f - 22f * t).coerceAtLeast(10f), true) // 약 6 m/s²
        })
        val before = rmsDb(x, 15f, 17.5f)
        val gap = (0..30).minOf { rmsDb(x, 18f + it * 0.05f, 18.25f + it * 0.05f) } // 18~19.75초 사이 가장 조용한 0.25초
        val back = rmsDb(x, 23f, 26f) // 리버스 심벌 → 다음 마디 첫 박 스핀업 이후
        println("hard brake: before=%.1f dB, quietest=%.1f dB, after restart=%.1f dB".format(before, gap, back))
        assertTrue("before=$before gap=$gap", gap < before - 25)
        assertTrue("back=$back gap=$gap", back > gap + 25)
    }

    @Test fun `과속은 즉시 먹먹해지고 제한속도 아래로 오면 다시 맑아진다`() {
        val x = render("over_limit_measure", 34f, vinyl = 0f, drive = cruiseThen(18f) { t ->
            when {
                t < 8f -> Frame(62f, false, limit = 50)
                else -> Frame(45f, false, limit = 50)
            }
        })
        val clear = brightDb(x, 15f, 17.5f)
        val muffled = brightDb(x, 19.2f, 25.5f)
        val reopened = brightDb(x, 30f, 33.5f)
        println("over limit: >700Hz share clear=%.1f muffled=%.1f reopened=%.1f dB".format(clear, muffled, reopened))
        assertTrue("clear=$clear muffled=$muffled", muffled < clear - 10)
        assertTrue("reopened=$reopened muffled=$muffled", reopened > muffled + 6)
    }

    @Test fun `신호 정차 순간 비트가 사라지고 출발하면 돌아온다`() {
        val x = render("signal_stop", 40f, cruiseThen(16f) { t ->
            when {
                t < 8f -> Frame((50f * (1 - t / 8f)).coerceAtLeast(0f), true) // 일반 감속 정차
                t < 16f -> Frame(0f, true)
                else -> Frame(((t - 16f) * 6f).coerceAtMost(40f), false)
            }
        })
        val moving = lfDb(x, 13f, 16f)
        val stopped = lfDb(x, 26f, 31f)
        val again = lfDb(x, 34f, 39f)
        println("signal stop: LF(kick·bass) dB moving=%.1f stopped=%.1f again=%.1f".format(moving, stopped, again))
        assertTrue("moving=$moving stopped=$stopped", stopped < moving - 10)
        assertTrue("again=$again stopped=$stopped", again > stopped + 10)
    }

    @Test fun `과속은 낡은 테이프처럼 - 들어보기용 WAV`() {
        render("over_limit_worn", 34f, cruiseThen(18f) { t ->
            if (t < 8f) Frame(62f, false, limit = 50) else Frame(45f, false, limit = 50)
        })
    }

    @Test fun `파일로 들어보기 - 방향지시등 좌우와 엔딩 꼬리`() {
        render("turn_signal", 20f, cruiseThen(12f) { t ->
            Frame(50f, false, turn = if (t < 4f) TurnSignal.LEFT else TurnSignal.RIGHT)
        })
        val x = render("ending_tail", 44f, cruiseThen(14f) { t ->
            if (t < 6f) Frame((50f * (1 - t / 6f)).coerceAtLeast(0f), true) else Frame(0f, true, Gear.PARK)
        })
        assertTrue("곡이 끝나야 함", finishedAt > 0)
        // 끝나기 직전 0.5초는 크랙클까지 포함해 완전 무음
        val from = (finishedAt - (0.5f * sr).toInt()) * 2
        val maxAbs = (from until finishedAt * 2).maxOf { abs(x[it]) }
        println("ending: finished at %.2fs, max |x| in last 0.5s = %.6f".format(finishedAt.toFloat() / sr, maxAbs))
        assertTrue("maxAbs=$maxAbs", maxAbs == 0f)
    }

    // ---------------- 힙합 팩 ----------------

    @Test fun `힙합 - 마디 길이는 96BPM으로 고정, 속도와 무관`() {
        render("hiphop_tempo", 30f, pack = GenrePack.HIPHOP, drive = cruiseThen(18f) { t -> Frame(if (t < 6f) 80f else 20f, false) })
        val bars = barStarts.zipWithNext { a, b -> (b - a).toDouble() / sr }
        val expected = 4 * 60.0 / GenrePack.HIPHOP.bpm
        println("hiphop bars: n=%d, %.4f..%.4f s (expected %.4f)".format(bars.size, bars.min(), bars.max(), expected))
        assertTrue(bars.size >= 8)
        bars.forEach { assertTrue("bar=$it", abs(it - expected) < 1.0 / sr * block + 1e-3) }
    }

    @Test fun `힙합 - 급제동 테이프 스톱 규칙은 Lo-fi와 같다`() {
        val x = render("hiphop_hard_brake", 28f, pack = GenrePack.HIPHOP, drive = cruiseThen(18f) { t ->
            Frame((50f - 22f * t).coerceAtLeast(10f), true)
        })
        val before = rmsDb(x, 15f, 17.5f)
        val gap = (0..30).minOf { rmsDb(x, 18f + it * 0.05f, 18.25f + it * 0.05f) }
        val back = rmsDb(x, 23f, 26f)
        println("hiphop hard brake: before=%.1f quietest=%.1f after=%.1f dB".format(before, gap, back))
        assertTrue("before=$before gap=$gap", gap < before - 25)
        assertTrue("back=$back gap=$gap", back > gap + 25)
    }

    @Test fun `힙합 - 정차하면 킥과 808이 빠지고 출발하면 돌아온다`() {
        val x = render("hiphop_signal_stop", 40f, pack = GenrePack.HIPHOP, drive = cruiseThen(16f) { t ->
            when {
                t < 8f -> Frame((50f * (1 - t / 8f)).coerceAtLeast(0f), true)
                t < 16f -> Frame(0f, true)
                else -> Frame(((t - 16f) * 6f).coerceAtMost(40f), false)
            }
        })
        val moving = lfDb(x, 13f, 16f)
        val stopped = lfDb(x, 26f, 31f)
        val again = lfDb(x, 34f, 39f)
        println("hiphop signal stop: LF dB moving=%.1f stopped=%.1f again=%.1f".format(moving, stopped, again))
        assertTrue("moving=$moving stopped=$stopped", stopped < moving - 10)
        assertTrue("again=$again stopped=$stopped", again > stopped + 10)
    }

    @Test fun `힙합 - 과속은 즉시 깨지고(비트크러시) 제한속도 아래로 오면 돌아온다`() {
        val x = render("hiphop_over_limit", 34f, pack = GenrePack.HIPHOP, drive = cruiseThen(18f) { t ->
            if (t < 8f) Frame(62f, false, limit = 50) else Frame(45f, false, limit = 50)
        })
        val clear = brightDb(x, 15f, 17.5f)
        val crushed = brightDb(x, 19.2f, 25.5f)
        val reopened = brightDb(x, 30f, 33.5f)
        println("hiphop over limit: >700Hz clear=%.1f crushed=%.1f reopened=%.1f dB".format(clear, crushed, reopened))
        assertTrue("clear=$clear crushed=$crushed", crushed < clear - 6) // 즉시, 뚜렷하게 바뀐다
        assertTrue("reopened=$reopened crushed=$crushed", reopened > crushed + 6) // 제한속도 아래로 오면 돌아온다
    }

    @Test fun `크러셔 - 6bit 양자화와 다운샘플(샘플 앤 홀드)`() {
        val n = 4_800
        val l = FloatArray(n) { (0.8 * Math.sin(2 * Math.PI * 440 * it / sr)).toFloat() }
        val r = l.copyOf()
        Crusher(sr).process(l, r, n, 1f)
        val levels = l.map { Math.round(it * 32) }.toSet()
        val held = (1 until n).count { l[it] == l[it - 1] }.toFloat() / n
        println("crusher: distinct levels=%d, held ratio=%.2f".format(levels.size, held))
        assertTrue("6bit → 최대 65단계: ${levels.size}", levels.size <= 65)
        assertTrue("값이 모두 1/32 격자 위", l.all { abs(it * 32 - Math.round(it * 32)) < 1e-4 })
        assertTrue("48k → 7k 다운샘플이면 대부분의 샘플이 직전 값과 같다: $held", held > 0.8f)
    }

    @Test fun `힙합 - 출발 전엔 벨 루프만, 출발하면 다음 마디 첫 박에 비트 드롭`() {
        // P 0~3초, D 정지 3~5초, 5초부터 가속(약 5.7초에 5km/h 통과)
        val x = render("hiphop_beat_drop", 16f, pack = GenrePack.HIPHOP, drive = cruiseThen(30f) { Frame(50f, false) })
        val goSec = 5f + 5f / 50f * 7f
        val firstAfterGo = barStarts.map { it.toFloat() / sr }.first { it > goSec }
        val dropSec = barStarts.map { it.toFloat() / sr }.first { it > firstAfterGo } // 다음 마디 4박 심벌 → 그다음 첫 박
            .let { candidate -> if (firstAfterGo - goSec > 4 * 60f / GenrePack.HIPHOP.bpm / 4 * 4 * 0.25f + 0.02f) firstAfterGo else candidate }
        val introRms = rmsDb(x, 0.5f, 5f)
        val introLf = lfDb(x, 0.5f, 5f)
        val dropLf = lfDb(x, dropSec + 0.05f, dropSec + 2.5f)
        println("hiphop beat drop: go=%.2fs drop=%.2fs intro RMS=%.1f LF=%.1f → after drop LF=%.1f dB".format(goSec, dropSec, introRms, introLf, dropLf))
        assertTrue("벨 루프는 들려야 함: $introRms", introRms > -45)
        // 벨 루프도 200Hz 근처 성분이 있어 150Hz 저역 측정에 조금 잡힌다 — 킥·808이 들어오면 그보다 8dB 이상 커진다
        assertTrue("드롭 뒤 킥·808: introLf=$introLf dropLf=$dropLf", dropLf > introLf + 8)
        // 드롭 직전(마지막 박 직전까지)엔 아직 킥·808이 없다
        val preLf = lfDb(x, dropSec - 1.2f, dropSec - 0.75f)
        assertTrue("드롭 전 preLf=$preLf dropLf=$dropLf", dropLf > preLf + 8)
    }

    @Test fun `힙합 엔딩 - 808 한 방과 벨 에코 꼬리 뒤 완전 무음`() {
        val x = render("hiphop_ending", 50f, pack = GenrePack.HIPHOP, drive = cruiseThen(14f) { t ->
            if (t < 6f) Frame((50f * (1 - t / 6f)).coerceAtLeast(0f), true) else Frame(0f, true, Gear.PARK)
        })
        assertTrue("곡이 끝나야 함", finishedAt > 0)
        val from = (finishedAt - (0.5f * sr).toInt()) * 2
        val maxAbs = (from until finishedAt * 2).maxOf { abs(x[it]) }
        val endSec = finishedAt.toFloat() / sr
        // 마지막 엔딩 마디 = 꼬리(2초 + 0.5초) 바로 앞 한 마디. 그 첫 박에 808 한 방
        val lastBarStart = endSec - 2.5f - 4 * 60f / GenrePack.HIPHOP.bpm
        val boom = lfDb(x, lastBarStart, lastBarStart + 0.6f)
        val before = lfDb(x, lastBarStart - 1.2f, lastBarStart - 0.1f)
        println("hiphop ending: finished %.2fs, last bar LF before=%.1f 808=%.1f dB, max |x| last 0.5s=%.6f".format(endSec, before, boom, maxAbs))
        assertTrue("808 한 방: before=$before boom=$boom", boom > before + 8)
        assertTrue("maxAbs=$maxAbs", maxAbs == 0f)
    }

    @Test fun `데모 코스 A를 힙합으로 - 끝까지 완결되고 Lo-fi보다 저역이 묵직하다`() {
        val hip = renderDemo("demo_A_hiphop", "trip_demo.json", GenrePack.HIPHOP)
        val hipFinished = finishedAt
        val hipBars = barStarts.size
        val lofi = renderDemo("demo_A_lofi", "trip_demo.json", GenrePack.LOFI)
        val lofiFinished = finishedAt
        println("demo A: hiphop %d bars, ends %.1fs / lofi ends %.1fs".format(hipBars, hipFinished.toFloat() / sr, lofiFinished.toFloat() / sr))
        assertTrue("힙합 곡이 엔딩까지 완결돼야 함", hipFinished > 0)
        assertTrue("Lo-fi 곡이 엔딩까지 완결돼야 함", lofiFinished > 0)
        // 순항 구간(40~70초): 킥·808 저역이 Lo-fi보다 크고, 전체 크기는 클리핑 없이 비슷한 수준
        val lfHip = lfDb(hip, 40f, 70f); val lfLofi = lfDb(lofi, 40f, 70f)
        val rmsHip = rmsDb(hip, 40f, 70f); val rmsLofi = rmsDb(lofi, 40f, 70f)
        val peak = hip.maxOf { abs(it) }
        println("demo A cruise: LF hiphop=%.1f lofi=%.1f dB · RMS hiphop=%.1f lofi=%.1f dB · peak %.3f".format(lfHip, lfLofi, rmsHip, rmsLofi, peak))
        assertTrue("lfHip=$lfHip lfLofi=$lfLofi", lfHip > lfLofi + 2)
        // 텍스처 체인 없음(하이컷·워블·크랙클 off) + 밝은 믹스: 700Hz 위 비중이 Lo-fi보다 크다
        val brHip = brightDb(hip, 40f, 70f); val brLofi = brightDb(lofi, 40f, 70f)
        println("demo A cruise: >700Hz share hiphop=%.1f lofi=%.1f dB".format(brHip, brLofi))
        assertTrue("brHip=$brHip brLofi=$brLofi", brHip > brLofi + 2)
        assertTrue("rmsHip=$rmsHip rmsLofi=$rmsLofi", abs(rmsHip - rmsLofi) < 6)
        assertTrue("peak=$peak", peak <= 1f)
    }

    private fun writeWav(f: File, pcm: FloatArray) {
        f.parentFile?.mkdirs()
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) data.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(sr); putInt(sr * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(pcm.size * 2)
        }
        f.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }
}
