package ai.syne.core.share

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 차량 ↔ 모바일 공통 데이터 형식.
 *
 * 차량에서 만든 곡(앨범의 Vol 하나)을 모바일에서도 **같은 곡으로** 알아보도록 전역 고유 ID를 쓴다.
 *  - track uid = "trk-<곡 시작 시각 ms>-<차량 기기 태그>"  (차량 DB id는 기기마다 달라 쓰지 않는다)
 *  - album uid = "alb-<코스 키 해시>"                       (같은 코스 = 같은 앨범)
 * 좌표는 담지 않는다(원칙 3). 장소는 화면에 이미 보이는 이름(코스 이름)만.
 *
 * 번들 파일(.syne) = zip: manifest.json + covers/<uid>.jpg + audio/<uid>.m4a
 * 같은 uid를 다시 가져오면 덮어쓴다(멱등) — 오프라인에서 여러 번 옮겨도 중복되지 않는다.
 * 하위 호환: 'edit'·'timeZone'은 선택 필드라 schema 1 그대로다. 예전 번들(필드 없음)도, 예전 앱(필드 무시)도 그대로 읽는다.
 */
data class SharedTrack(
    val uid: String,
    val albumUid: String,
    val albumName: String,
    val albumKind: String,
    val volume: Int,
    val genre: String,
    val startedAt: Long,
    val endedAt: Long,
    val songSec: Float,
    /** 1초 기록의 속도를 적분한 실제 주행 거리(m) */
    val distanceM: Int,
    val smoothness: Int,
    val hardBrakes: Int,
    val completed: Boolean,
    /** 앨범 아트 정보: medium = 매체 id, source = "local"(기기 안 페인터) | "ai"(생성 서버) */
    val artMedium: String?,
    val artSource: String,
    /** 파형용 멜로디 노트(MIDI, 최대 64개로 줄임) */
    val notes: List<Int>,
    val timeline: List<TimelineEvent>,
    val tags: List<String>,
    /** 번들 안 상대 경로(가져온 뒤엔 기기 안 경로로 바뀐다) */
    val coverFile: String?,
    val audioFile: String?,
    val audioSec: Float?,
    /** 사용자 편집(제목·설명·메모·태그·즐겨찾기). 예전 번들에는 없다(null) */
    val edit: UserEdit? = null,
    /** 주행한 곳의 시간대(예: Asia/Seoul) — 모든 기기에서 '주행한 곳의 현지 시각'으로 표시하기 위해. 예전 번들은 null */
    val timeZone: String? = null,
)

/** 주행 타임라인 한 점 — 저장된 1초 기록에서만 만든다(지어내지 않는다) */
data class TimelineEvent(val tSec: Int, val kind: String, val label: String) {
    companion object {
        const val DEPART = "depart"
        const val SECTION = "section"
        const val STOP = "stop"
        const val ARRIVE = "arrive"
    }
}

data class BundleManifest(val schema: Int, val createdAt: Long, val source: String, val tracks: List<SharedTrack>) {
    companion object { const val SCHEMA = 1 }
}

object AlbumBundle {
    const val EXT = "syne"
    private const val MANIFEST = "manifest.json"

    // ---------------- JSON ----------------

    fun toJson(m: BundleManifest): String = JSONObject().apply {
        put("schema", m.schema); put("createdAt", m.createdAt); put("source", m.source)
        put("tracks", JSONArray().apply { m.tracks.forEach { put(trackJson(it)) } })
    }.toString(2)

    fun trackJson(t: SharedTrack) = JSONObject().apply {
        put("uid", t.uid); put("albumUid", t.albumUid); put("albumName", t.albumName); put("albumKind", t.albumKind)
        put("volume", t.volume); put("genre", t.genre); put("startedAt", t.startedAt); put("endedAt", t.endedAt)
        put("songSec", t.songSec.toDouble()); put("distanceM", t.distanceM); put("smoothness", t.smoothness)
        put("hardBrakes", t.hardBrakes); put("completed", t.completed)
        put("artMedium", t.artMedium ?: JSONObject.NULL); put("artSource", t.artSource)
        put("notes", JSONArray(t.notes))
        put("timeline", JSONArray().apply { t.timeline.forEach { e -> put(JSONObject().put("t", e.tSec).put("kind", e.kind).put("label", e.label)) } })
        put("tags", JSONArray(t.tags))
        put("cover", t.coverFile ?: JSONObject.NULL); put("audio", t.audioFile ?: JSONObject.NULL)
        put("audioSec", t.audioSec?.toDouble() ?: JSONObject.NULL)
        t.timeZone?.let { put("timeZone", it) }
        t.edit?.takeIf { it.updatedAt > 0 }?.let { put("edit", editJson(it)) }
    }

    fun editJson(e: UserEdit) = JSONObject().apply {
        put("customTitle", e.customTitle ?: JSONObject.NULL); put("description", e.description ?: JSONObject.NULL)
        put("memo", e.memo ?: JSONObject.NULL); put("tags", JSONArray(e.tags)); put("favorite", e.favorite); put("updatedAt", e.updatedAt)
    }

    fun editFrom(j: JSONObject): UserEdit {
        fun optStr(k: String) = if (!j.has(k) || j.isNull(k)) null else j.optString(k).ifEmpty { null }
        val tags = j.optJSONArray("tags")
        return UserEdit(
            customTitle = EditRules.clean(optStr("customTitle"), EditRules.TITLE_MAX, singleLine = true),
            description = EditRules.clean(optStr("description"), EditRules.DESCRIPTION_MAX),
            memo = EditRules.clean(optStr("memo"), EditRules.MEMO_MAX),
            tags = EditRules.tags(if (tags == null) emptyList() else List(tags.length()) { tags.getString(it) }),
            favorite = j.optBoolean("favorite"),
            updatedAt = j.optLong("updatedAt"),
        )
    }

    fun fromJson(s: String): BundleManifest {
        val o = JSONObject(s)
        val schema = o.getInt("schema")
        require(schema <= BundleManifest.SCHEMA) { "지원하지 않는 번들 버전($schema)" }
        val arr = o.getJSONArray("tracks")
        return BundleManifest(schema, o.optLong("createdAt"), o.optString("source"), List(arr.length()) { trackFrom(arr.getJSONObject(it)) })
    }

    fun trackFrom(j: JSONObject): SharedTrack {
        fun ints(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getInt(it) }
        fun strs(a: JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        fun optStr(k: String) = if (j.isNull(k)) null else j.optString(k, null)
        val tl = j.optJSONArray("timeline")
        return SharedTrack(
            uid = j.getString("uid"), albumUid = j.getString("albumUid"), albumName = j.getString("albumName"),
            albumKind = j.optString("albumKind", "COURSE"), volume = j.getInt("volume"), genre = j.getString("genre"),
            startedAt = j.getLong("startedAt"), endedAt = j.getLong("endedAt"), songSec = j.getDouble("songSec").toFloat(),
            distanceM = j.optInt("distanceM"), smoothness = j.optInt("smoothness"), hardBrakes = j.optInt("hardBrakes"),
            completed = j.optBoolean("completed"), artMedium = optStr("artMedium"), artSource = j.optString("artSource", "local"),
            notes = ints(j.optJSONArray("notes")),
            timeline = if (tl == null) emptyList() else List(tl.length()) { i -> tl.getJSONObject(i).let { TimelineEvent(it.getInt("t"), it.getString("kind"), it.getString("label")) } },
            tags = strs(j.optJSONArray("tags")),
            coverFile = optStr("cover"), audioFile = optStr("audio"),
            audioSec = if (j.isNull("audioSec")) null else j.optDouble("audioSec").toFloat(),
            edit = j.optJSONObject("edit")?.let(::editFrom),
            timeZone = if (j.has("timeZone")) j.optString("timeZone").ifEmpty { null } else null,
        )
    }

    // ---------------- zip ----------------

    /** 번들 쓰기. [files]: 번들 안 경로 → 실제 파일 */
    fun write(out: OutputStream, manifest: BundleManifest, files: Map<String, File>) {
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(toJson(manifest).toByteArray()); zip.closeEntry()
            files.forEach { (name, f) ->
                if (!f.exists()) return@forEach
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    /**
     * 번들 읽기: 파일은 [destDir] 아래 같은 상대 경로로 풀고 manifest를 돌려준다.
     * 경로 탈출(../) 항목과 manifest 밖의 낯선 경로는 무시한다.
     */
    fun read(input: InputStream, destDir: File): BundleManifest {
        var manifest: BundleManifest? = null
        val root = destDir.canonicalFile
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name == MANIFEST) {
                    manifest = fromJson(zip.readBytes().decodeToString())
                } else if (e.name.startsWith("covers/") || e.name.startsWith("audio/")) {
                    val out = File(root, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) continue
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        return manifest ?: error("manifest.json이 없는 번들")
    }
}

/** 1초 기록 한 줄(타임라인 계산용) */
data class SampleLite(val tSec: Int, val speedKmh: Float, val section: String)

object Timeline {
    private val SECTION_LABEL = mapOf(
        "DEVELOP" to "리듬이 붙고 곡이 펼쳐지기 시작",
        "CLIMAX" to "곡이 가장 풍성해진 구간",
        "FINALE" to "도착을 향해 마무리되는 선율",
        "ENDING" to "기어 P · 엔딩 연주",
    )

    /**
     * 저장된 1초 기록 → 타임라인. 출발 · 곡 구간이 바뀐 순간 · 5초 이상 정차 · 도착.
     * 기록에 없는 사건은 만들지 않는다.
     */
    fun build(samples: List<SampleLite>, songSec: Float): List<TimelineEvent> {
        if (samples.isEmpty()) return emptyList()
        val out = ArrayList<TimelineEvent>()
        val firstMove = samples.firstOrNull { it.speedKmh > 1f }
        out += TimelineEvent(firstMove?.tSec ?: 0, TimelineEvent.DEPART, "출발 · 첫 소리가 겹쳐지기 시작")
        var lastSection = samples.first().section
        var stopStart = -1
        var moved = false
        for (s in samples) {
            if (s.section != lastSection) {
                SECTION_LABEL[s.section]?.let { out += TimelineEvent(s.tSec, TimelineEvent.SECTION, it) }
                lastSection = s.section
            }
            if (s.speedKmh > 1f) {
                if (moved && stopStart >= 0 && s.tSec - stopStart >= 5 && s.section != "ENDING") {
                    out += TimelineEvent(stopStart, TimelineEvent.STOP, "${s.tSec - stopStart}초 정차 · 패드만 남아 숨 고르기")
                }
                moved = true; stopStart = -1
            } else if (moved && stopStart < 0) stopStart = s.tSec
        }
        out += TimelineEvent(songSec.toInt(), TimelineEvent.ARRIVE, "도착 · 곡 완성")
        return out.sortedBy { it.tSec }
    }
}

// ---------------- 동기화 ----------------

enum class SyncState { NOT_CONFIGURED, IDLE, WORKING, DONE, ERROR }

data class SyncStatus(val channel: String, val state: SyncState, val lastAt: Long? = null, val message: String? = null)

/**
 * 동기화 통로. 지금 실제로 동작하는 것은 '번들 파일'(차량 내보내기 → 휴대폰 가져오기)뿐이다.
 * 실시간 서버 동기화는 백엔드가 생기면 이 인터페이스로 붙인다 — 그전까지는 [RemoteSync]가
 * NOT_CONFIGURED를 돌려주고, 화면은 '연결 안 됨'으로 정직하게 표시한다.
 */
interface SyncChannel {
    val name: String
    fun status(): SyncStatus
}

object RemoteSync : SyncChannel {
    override val name = "SYNE 클라우드"
    override fun status() = SyncStatus(name, SyncState.NOT_CONFIGURED, message = "서버가 아직 없어 실시간 동기화는 꺼져 있어요")
}
