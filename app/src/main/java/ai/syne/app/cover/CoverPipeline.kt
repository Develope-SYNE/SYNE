package ai.syne.app.cover

import ai.syne.app.BuildConfig
import ai.syne.app.SyneLog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앨범 아트 파이프라인 — 단계마다 책임을 나눈다.
 *
 *   ArtDirections(무드 → 매체 목록)  →  ArtBrief.from(주행 기록 → 설계서·프롬프트)
 *     →  CoverGenerator(이미지 생성: 서버 AI 또는 기기 안 페인터)  →  CoverStore(파일 저장·재사용)
 *
 * 앨범 메타데이터(제목·무드·길이)는 이미지에 넣지 않고 앱이 따로 그린다.
 * 한 곡의 커버는 곡이 끝날 때 한 번만 만들고, 보관함은 저장된 파일을 읽기만 한다.
 */
interface CoverGenerator {
    /** 실패하면 null(다음 생성기로 넘어간다) */
    fun generate(brief: ArtBrief, sizePx: Int): Bitmap?
}

/** 기기 안 페인터 — 항상 성공하는 기본 경로 */
object ProceduralCoverGenerator : CoverGenerator {
    override fun generate(brief: ArtBrief, sizePx: Int): Bitmap = ArtPainters.paint(brief, sizePx)
}

/**
 * 이미지 생성 서버(선택). gradle 속성 `syne.coverServer`로 주소를 넣었을 때만 쓴다.
 * 보내는 것은 프롬프트·시드·크기뿐 — 좌표·장소 이름은 보내지 않는다(원칙 3).
 * 요청: POST {prompt, negative_prompt, seed, width, height} → 응답: PNG/JPEG 바이트.
 * 호출 시점은 곡이 끝난 뒤(기어 P 이후)뿐이다.
 */
class RemoteCoverGenerator(private val endpoint: String) : CoverGenerator {
    override fun generate(brief: ArtBrief, sizePx: Int): Bitmap? = runCatching {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("prompt", brief.prompt)
            .put("negative_prompt", brief.negativePrompt)
            .put("seed", brief.seed)
            .put("width", sizePx)
            .put("height", sizePx)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    }.onFailure { SyneLog.w("remote cover failed: ${it.message}") }.getOrNull()
}

/** 커버 파일 저장소: files/art/trip_<시작시각>.jpg */
class CoverStore(private val context: Context) {
    fun relPath(startedAtMs: Long) = "art/trip_$startedAtMs.jpg"

    fun save(startedAtMs: Long, bmp: Bitmap): String {
        val rel = relPath(startedAtMs)
        File(context.filesDir, rel).apply { parentFile?.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return rel
    }
}

class CoverPipeline(context: Context) {
    private val store = CoverStore(context)
    private val markFont = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, ai.syne.core.R.font.pretendard_semibold) }.getOrNull()
    private val remote = BuildConfig.COVER_SERVER.takeIf { it.isNotBlank() }?.let { RemoteCoverGenerator(it) }

    /**
     * 설계서 → 이미지 → 파일. 서버가 설정돼 있고 [allowRemote]면 먼저 시도, 실패하면 기기 안에서 그린다.
     * @return 저장된 상대 경로
     */
    fun create(brief: ArtBrief, startedAtMs: Long, allowRemote: Boolean, volume: Int? = null): String {
        val raw = (if (allowRemote) remote?.generate(brief, SIZE) else null) ?: ProceduralCoverGenerator.generate(brief, SIZE)
        val bmp = CoverSignature.apply(raw, brief.route, volume, markFont) // 모든 커버에 같은 서명(경로 선 + SYNE · Vol.n)
        return try {
            store.save(startedAtMs, bmp)
        } finally {
            bmp.recycle()
        }
    }

    val hasRemote get() = remote != null

    /** 서버 AI로만 만들기. 실패하면 null(기존 커버 유지). 기존 파일을 같은 이름으로 덮어쓴다 */
    fun createRemote(brief: ArtBrief, startedAtMs: Long, volume: Int? = null): String? {
        val bmp = CoverSignature.apply(remote?.generate(brief, SIZE) ?: return null, brief.route, volume, markFont)
        return try { store.save(startedAtMs, bmp) } finally { bmp.recycle() }
    }

    companion object {
        const val SIZE = 1024
    }
}
