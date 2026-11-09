package ai.syne.app.engine

import ai.syne.app.SyneLog
import ai.syne.app.signals.DestinationRef
import ai.syne.app.signals.GeoFix
import ai.syne.app.signals.VehicleSignals
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class EngineState(
    val playing: Boolean = false,
    val bar: Int = 0,
    val chord: String = "",
    val layers: List<Layer> = emptyList(),
    val section: Section? = null,
    val navBased: Boolean = false,
    /** 급제동 테이프 스톱 중 */
    val tapeStop: Boolean = false,
    /** 신호 정차(패드+잔향) 중 */
    val stopHold: Boolean = false,
    /** 제한속도 초과(먹먹함) 중 */
    val overLimit: Boolean = false,
    val smoothness: Int = 100,
    val hardBrakes: Int = 0,
    val settling: Boolean = false,
    val melodyTone: MelodyTone? = null,
    /** 최근 멜로디 노트(MIDI, 최대 16개) — 화면 파동의 굴곡. 마디마다 갱신 */
    val recentNotes: List<Int> = emptyList(),
    val ducked: Boolean = false,
    val muted: Boolean = false,
    val sampleRate: Int = 0,
)

/**
 * AudioTrack 기반 실시간 합성 엔진(Lo-fi 팩).
 * 전용 오디오 스레드가 블록(1024프레임)마다 차량 신호의 최신값(StateFlow.value)을 읽어
 * 주행 분석(DriveState) → 곡 구조(SongPlanner) → 편성·합성(Renderer) → 기록(TripRecorder)을 한 번에 돈다.
 *
 * 곡은 기어 P 이후 엔딩 4마디로 끝나며, 끝나면 [onSongFinished]로 TripResult를 넘긴다.
 * 상태(state)는 마디 경계에서만 갱신된다(약 0.35Hz).
 */
class SoundEngine(context: Context) {

    private val assets = context.applicationContext.assets
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** 곡이 끝났을 때(완결 또는 중단) 오디오 스레드에서 호출. 주행한 적이 없는 곡은 넘기지 않는다. */
    @Volatile var onSongFinished: ((TripResult) -> Unit)? = null

    @Volatile private var signals: VehicleSignals? = null
    @Volatile private var genre: String = "lofi"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopRequested = false
    @Volatile private var ducked = false
    @Volatile private var muted = false
    private var thread: Thread? = null

    @Synchronized
    fun start(signals: VehicleSignals, genre: String = "lofi") {
        this.signals = signals
        this.genre = genre
        if (thread?.isAlive == true && !stopRequested) return
        thread?.join(500)
        stopRequested = false
        thread = Thread(::runLoop, "SYNE-audio").also { it.start() }
    }

    /** 짧게 페이드아웃한 뒤 오디오 스레드가 스스로 종료한다(중단 곡으로 저장). 호출 스레드를 막지 않는다. */
    @Synchronized
    fun stop() {
        stopRequested = true
    }

    fun setDucked(value: Boolean) {
        ducked = value
        _state.update { it.copy(ducked = value) }
    }

    fun setMuted(value: Boolean) {
        muted = value
        _state.update { it.copy(muted = value) }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val sr = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).takeIf { it in 22_050..96_000 } ?: 48_000
        val block = Renderer.BLOCK
        val track = try {
            buildTrack(sr, block)
        } catch (t: Throwable) {
            SyneLog.e("AudioTrack init failed", t)
            return
        }

        val recorder = TripRecorder(sr)
        val recent = ArrayDeque<Int>(16)
        val startedAt = System.currentTimeMillis()
        val songGenre = genre
        // 위치는 폴링하지 않는다: 곡 시작 1회, 엔딩 진입(P) 1회만 요청 (SDK 호출은 메인 스레드에서)
        signals?.let { s -> main.post { s.requestPosition() } }
        var startFix: GeoFix? = null
        var endFix: GeoFix? = null
        var endRequestedAt = Long.MAX_VALUE
        var lastDestination: DestinationRef? = null
        val source = signals?.source?.value?.name ?: "NONE"
        val pack = GenrePack.of(songGenre)
        val renderer = Renderer(sr, DrumKit.load(assets, sr, pack), object : RendererSink {
            override fun onBar(info: BarInfo) {
                _state.update {
                    it.copy(
                        bar = info.bar, chord = info.chord, layers = info.layers, section = info.section,
                        navBased = info.navBased, smoothness = info.smoothness, hardBrakes = info.hardBrakes,
                        melodyTone = info.melodyTone, recentNotes = recent.toList(),
                    )
                }
            }

            override fun onFx(fx: FxState) {
                _state.update {
                    it.copy(tapeStop = fx.tapeStop, stopHold = fx.stopHold, overLimit = fx.overLimit, settling = fx.settling)
                }
            }

            override fun onSecond(sample: SecondSample, layersMask: Int, section: Section) {
                recorder.addSample(
                    SampleRow(sample.t, sample.speedKmh, sample.steeringDeg, sample.smoothness, layersMask, section),
                )
            }

            override fun onMelodyNote(tSec: Float, midi: Int, durSec: Float) {
                if (recent.size == 16) recent.removeFirst()
                recent.addLast(midi)
                recorder.addNote(NoteRow(tSec, midi, durSec))
            }
        }, pack)
        val out = FloatArray(block * 2)
        val dt = block.toFloat() / sr
        SyneLog.i("SoundEngine start: ${sr}Hz, block=$block, ${pack.id} ${pack.bpm}BPM swing ${pack.swingPercent}%, source=$source")
        _state.value = EngineState(playing = true, sampleRate = sr, ducked = ducked, muted = muted)

        try {
            track.play()
            val wallStart = System.nanoTime()
            lastControlNs = 0L
            var renderedFrames = 0L
            var throttles = 0
            while (true) {
                renderBlock(renderer, recorder, out, block, dt)
                renderedFrames += block
                signals?.let { s ->
                    val fix = s.position.value
                    if (startFix == null && fix != null && fix.atMs >= startedAt) startFix = fix
                    if (fix != null && fix.atMs >= endRequestedAt) endFix = fix
                    s.destination.value?.let { lastDestination = it }
                    if (endRequestedAt == Long.MAX_VALUE && renderer.planner.section == Section.ENDING) {
                        endRequestedAt = System.currentTimeMillis()
                        main.post { s.requestPosition() }
                    }
                }
                val written = track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    SyneLog.e("AudioTrack write error $written")
                    break
                }
                if (renderer.finished) break // 엔딩 + 리버브 꼬리 3초까지
                if (stopRequested && renderer.masterGain < 1e-3f) break

                // 벽시계 보정: 곡 시간은 주행 시간과 맞아야 한다. (에뮬레이터 오디오 HAL이 10초씩 멈추거나
                // 반대로 실시간보다 빨리 소비하는 현상 실측 — 2026-11-02 'write blocked for 9918 msecs')
                val ahead = renderedFrames.toDouble() / sr - (System.nanoTime() - wallStart) / 1e9
                if (ahead > CLOCK_TOLERANCE_SEC) {
                    if (throttles++ % 60 == 0) {
                        SyneLog.w("오디오 출력이 실시간보다 빠름(HAL) → 벽시계에 맞춰 조절 중 (누적 ${throttles}회)")
                    }
                    Thread.sleep(((ahead - CLOCK_TARGET_LEAD_SEC) * 1000).toLong())
                } else if (ahead < -CLOCK_TOLERANCE_SEC) {
                    // 멈춰 있던 만큼 소리 없이 곡을 진행시켜 주행과 다시 맞춘다
                    var skipped = 0
                    while (renderedFrames.toDouble() / sr - (System.nanoTime() - wallStart) / 1e9 < 0 && !renderer.finished) {
                        renderBlock(renderer, recorder, out, block, dt)
                        renderedFrames += block
                        skipped++
                    }
                    SyneLog.w("audio output stalled ${"%.2f".format(-ahead)}s → fast-forward $skipped blocks")
                    if (renderer.finished) break
                }
            }
        } catch (t: Throwable) {
            SyneLog.e("audio loop crashed", t)
        } finally {
            runCatching { track.stop() }
            track.release()
            _state.value = EngineState(ducked = ducked, muted = muted)
            SyneLog.i("SoundEngine stopped (${if (renderer.ended) "엔딩 완결" else "중단"}, ${if (renderer.ended) renderer.bar else renderer.bar + 1}마디)")
        }

        if (renderer.planner.hasDriven) {
            val result = TripResult(
                startedAtMs = startedAt,
                endedAtMs = System.currentTimeMillis(),
                source = source,
                songSec = (renderer.clock.toDouble() / sr).toFloat(),
                bars = if (renderer.ended) renderer.bar else renderer.bar + 1,
                completed = renderer.ended,
                samples = recorder.samples,
                notes = recorder.notes,
                avgSmoothness = renderer.drive.averageSmoothness,
                hardBrakes = renderer.drive.hardBrakeCount,
                maxSpeedKmh = renderer.drive.maxSpeedKmh,
                highlightPcm = recorder.highlight(),
                sampleRate = sr,
                genre = songGenre,
                startFix = startFix,
                // 엔딩 없이 중단됐으면 마지막으로 받은 위치(시작 위치보다 새것일 때만)
                endFix = endFix ?: signals?.position?.value?.takeIf { it.atMs > (startFix?.atMs ?: Long.MAX_VALUE) },
                destination = lastDestination,
            )
            onSongFinished?.invoke(result)
        } else {
            SyneLog.i("주행 없이 끝난 곡 — 기록하지 않음")
        }
    }

    private var lastControlNs = 0L

    /**
     * 주행 분석(가감속·급제동·부드러움)은 '실제 시간' 기준이어야 한다. 오디오 블록 길이로 dt를 잡으면
     * HAL 보정(쉬었다 몰아서 렌더) 때 Δv/Δt가 튀어 가짜 급제동이 난다(실측). 그래서 벽시계 dt를 쓴다.
     */
    private fun renderBlock(renderer: Renderer, recorder: TripRecorder, out: FloatArray, block: Int, blockSec: Float) {
        val now = System.nanoTime()
        val dt = if (lastControlNs == 0L) blockSec else ((now - lastControlNs) / 1e9).toFloat().coerceIn(0f, 1f)
        lastControlNs = now
        signals?.let { s ->
            renderer.updateControls(
                speedKmh = s.speedKmh.value,
                steeringDeg = s.steeringDeg.value,
                brakePressed = s.brakePressed.value,
                turnSignal = s.turnSignal.value,
                gear = s.gear.value,
                speedLimitKmh = s.speedLimitKmh.value,
                remainSec = s.remainSec.value,
                dtSec = dt,
            )
        }
        renderer.masterTarget = when {
            stopRequested || muted -> 0f
            ducked -> DUCK_GAIN
            else -> 1f
        }
        renderer.render(out, block)
        recorder.capture(out, block)
    }

    private fun buildTrack(sr: Int, block: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bytes = maxOf(minBuf, block * 2 * 4 * 3)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private companion object {
        const val DUCK_GAIN = 0.22f
        const val CLOCK_TOLERANCE_SEC = 0.5
        const val CLOCK_TARGET_LEAD_SEC = 0.1
    }
}
