package ai.syne.app.engine

import ai.syne.app.signals.DestinationRef
import ai.syne.app.signals.GeoFix

/** 1초 기록 한 줄(저장용) */
class SampleRow(
    val t: Int,
    val speedKmh: Float,
    val steeringDeg: Float,
    val smoothness: Int,
    val layersMask: Int,
    val section: Section,
)

/** 리드 멜로디 노트(MIDI 유사): 곡 시작 기준 초, MIDI 음높이, 길이(초) */
class NoteRow(val t: Float, val pitch: Int, val dur: Float)

/** 한 곡(=한 주행) 결과. 오디오 스레드가 끝난 뒤 저장 쪽으로 넘어간다. */
class TripResult(
    val startedAtMs: Long,
    val endedAtMs: Long,
    val source: String,
    val songSec: Float,
    val bars: Int,
    /** 엔딩 4마디까지 마쳤는가(중간 정지면 false) */
    val completed: Boolean,
    val samples: List<SampleRow>,
    val notes: List<NoteRow>,
    val avgSmoothness: Float,
    val hardBrakes: Int,
    val maxSpeedKmh: Float,
    /** 마지막 30초(피날레 포함) 인터리브 스테레오 16bit */
    val highlightPcm: ShortArray,
    val sampleRate: Int,
    val genre: String,
    /** 곡 시작 직후 받은 위치 (앱 내부 전용) */
    val startFix: GeoFix?,
    /** 엔딩 진입(P) 직후 받은 위치 (앱 내부 전용) */
    val endFix: GeoFix?,
    /** 곡 중 마지막으로 본 목적지 */
    val destination: DestinationRef?,
)

/**
 * 오디오 스레드 전용 기록기. 곡이 끝날 때까지 메모리에만 쌓는다(1시간 주행 ≈ 샘플 3,600줄 + 노트 수천 개).
 * 하이라이트는 최근 30초 링버퍼(48kHz 스테레오 16bit ≈ 5.8MB)로 계속 덮어쓴다.
 */
internal class TripRecorder(sampleRate: Int, highlightSec: Int = 30) {
    val samples = ArrayList<SampleRow>(1024)
    val notes = ArrayList<NoteRow>(2048)

    private val ring = ShortArray(sampleRate * highlightSec * 2)
    private var ringPos = 0
    private var ringFilled = false

    fun addSample(row: SampleRow) {
        samples += row
    }

    fun addNote(row: NoteRow) {
        notes += row
    }

    /** 렌더된 인터리브 float 블록을 링버퍼에 복사 */
    fun capture(interleaved: FloatArray, frames: Int) {
        val n = frames * 2
        var p = ringPos
        for (i in 0 until n) {
            val v = (interleaved[i].coerceIn(-1f, 1f) * 32767f).toInt()
            ring[p] = v.toShort()
            p++
            if (p == ring.size) {
                p = 0
                ringFilled = true
            }
        }
        ringPos = p
    }

    /** 시간 순서로 펼친 하이라이트 */
    fun highlight(): ShortArray {
        if (!ringFilled) return ring.copyOfRange(0, ringPos)
        val out = ShortArray(ring.size)
        val tail = ring.size - ringPos
        System.arraycopy(ring, ringPos, out, 0, tail)
        System.arraycopy(ring, 0, out, tail, ringPos)
        return out
    }
}
