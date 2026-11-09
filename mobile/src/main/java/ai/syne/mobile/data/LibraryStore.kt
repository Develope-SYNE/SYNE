package ai.syne.mobile.data

import ai.syne.core.share.AlbumBundle
import ai.syne.core.share.EditRules
import ai.syne.core.share.UserEdit
import ai.syne.core.share.RemoteSync
import ai.syne.core.share.SharedTrack
import ai.syne.core.share.SyncState
import ai.syne.core.share.SyncStatus
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 모바일 보관함(오프라인 우선). 차량에서 내보낸 번들(.syne)을 가져와 filesDir/library 아래에 둔다.
 *  - 같은 uid(= 같은 곡)를 다시 가져오면 덮어쓴다 → 몇 번을 옮겨도 중복되지 않는다.
 *  - 앨범 아트·하이라이트는 차량이 만든 파일 그대로(다시 그리지 않는다) — 차량과 같은 작품.
 *  - 실시간 서버 동기화는 없다(RemoteSync = NOT_CONFIGURED). 화면은 이를 그대로 표시한다.
 */
class LibraryStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "library").apply { mkdirs() }
    private val index = File(root, "index.json")
    /** 사용자 편집은 가져온 주행 기록(index.json)과 따로 둔다 — 다시 가져와도 기록만 갱신되고 편집은 병합 규칙을 따른다 */
    private val editsFile = File(root, "edits.json")

    private val _edits = MutableStateFlow<Map<String, UserEdit>>(emptyMap())
    /** uid → 사용자 편집 */
    val edits: StateFlow<Map<String, UserEdit>> = _edits.asStateFlow()

    private val _tracks = MutableStateFlow<List<SharedTrack>>(emptyList())
    /** 최신 곡이 먼저 */
    val tracks: StateFlow<List<SharedTrack>> = _tracks.asStateFlow()

    private val _bundle = MutableStateFlow(SyncStatus("번들 파일", SyncState.IDLE))
    /** 파일 가져오기 상태(실제로 동작하는 유일한 통로) */
    val bundleStatus: StateFlow<SyncStatus> = _bundle.asStateFlow()
    val remoteStatus: SyncStatus = RemoteSync.status()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (editsFile.exists()) runCatching {
            val o = JSONObject(editsFile.readText())
            _edits.value = o.keys().asSequence().associateWith { AlbumBundle.editFrom(o.getJSONObject(it)) }
        }
        if (!index.exists()) return@withContext
        runCatching {
            val o = JSONObject(index.readText())
            val arr = o.getJSONArray("tracks")
            _tracks.value = List(arr.length()) { AlbumBundle.trackFrom(arr.getJSONObject(it)) }.sortedByDescending { it.startedAt }
            _bundle.value = SyncStatus("번들 파일", SyncState.DONE, o.optLong("lastImportAt").takeIf { it > 0 }, o.optString("source", null))
        }
    }

    /** 번들 가져오기. @return 새로 들어온 곡 수(덮어쓴 곡 제외) */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        _bundle.value = _bundle.value.copy(state = SyncState.WORKING, message = null)
        runCatching {
            val tmp = File(app.cacheDir, "import-${System.currentTimeMillis()}").apply { mkdirs() }
            val manifest = app.contentResolver.openInputStream(uri)?.use { AlbumBundle.read(it, tmp) } ?: error("파일을 열 수 없어요")
            val known = _tracks.value.associateBy { it.uid }.toMutableMap()
            val edits = _edits.value.toMutableMap()
            var added = 0
            manifest.tracks.forEach { t ->
                fun move(rel: String?): String? {
                    rel ?: return null
                    val src = File(tmp, rel).takeIf { it.exists() } ?: return null
                    val dst = File(root, rel).apply { parentFile?.mkdirs() }
                    src.copyTo(dst, overwrite = true)
                    return rel
                }
                if (t.uid !in known) added++
                // 편집: 더 나중에 고친 쪽을 쓴다(휴대폰에서 더 최근에 고친 메모·즐겨찾기는 지켜진다)
                EditRules.merge(edits[t.uid], t.edit)?.let { edits[t.uid] = it }
                known[t.uid] = t.copy(coverFile = move(t.coverFile), audioFile = move(t.audioFile), edit = null)
            }
            tmp.deleteRecursively()
            val list = known.values.sortedByDescending { it.startedAt }
            val now = System.currentTimeMillis()
            index.writeText(
                JSONObject().put("lastImportAt", now).put("source", manifest.source)
                    .put("tracks", JSONArray().apply { list.forEach { put(AlbumBundle.trackJson(it)) } }).toString(),
            )
            _tracks.value = list
            writeEdits(edits)
            _bundle.value = SyncStatus("번들 파일", SyncState.DONE, now, manifest.source)
            added
        }.onFailure { _bundle.value = SyncStatus("번들 파일", SyncState.ERROR, _bundle.value.lastAt, it.message ?: "가져오기 실패") }
    }

    fun file(rel: String?): File? = rel?.let { File(root, it) }?.takeIf { it.exists() }

    fun edit(uid: String): UserEdit = _edits.value[uid] ?: UserEdit.None

    /** 사용자 편집 저장(규칙으로 정리, updatedAt = 지금). 주행 기록·파일은 건드리지 않는다 */
    suspend fun saveEdit(uid: String, title: String, description: String, memo: String, tags: List<String>) = withContext(Dispatchers.IO) {
        val prev = edit(uid)
        val next = UserEdit(
            EditRules.clean(title, EditRules.TITLE_MAX, singleLine = true),
            EditRules.clean(description, EditRules.DESCRIPTION_MAX),
            EditRules.clean(memo, EditRules.MEMO_MAX),
            EditRules.tags(tags), prev.favorite, System.currentTimeMillis(),
        )
        writeEdits(_edits.value + (uid to next))
    }

    suspend fun toggleFavorite(uid: String) = withContext(Dispatchers.IO) {
        val prev = edit(uid)
        writeEdits(_edits.value + (uid to prev.copy(favorite = !prev.favorite, updatedAt = System.currentTimeMillis())))
    }

    private fun writeEdits(m: Map<String, UserEdit>) {
        val o = JSONObject()
        m.forEach { (uid, e) -> o.put(uid, AlbumBundle.editJson(e)) }
        // 임시 파일에 쓴 뒤 바꿔치기(쓰는 도중 앱이 꺼져도 기존 편집이 깨지지 않게)
        val tmp = File(root, "edits.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(editsFile)
        _edits.value = m
    }
}
