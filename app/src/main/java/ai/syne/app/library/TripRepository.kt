package ai.syne.app.library

import ai.syne.app.SyneLog
import ai.syne.app.cover.ArtBrief
import ai.syne.app.cover.CoverPipeline
import ai.syne.app.cover.DriveFeel
import ai.syne.app.cover.RouteShape
import ai.syne.core.share.AlbumBundle
import ai.syne.core.share.BundleManifest
import ai.syne.core.share.SampleLite
import ai.syne.core.share.SharedTrack
import ai.syne.core.share.Timeline
import ai.syne.core.share.EditRules
import ai.syne.core.share.UserEdit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.OutputStream
import ai.syne.app.engine.TripResult
import ai.syne.app.signals.GeoFix
import android.content.Context
import android.os.Process
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Locale

/** 곡의 결과물(커버·하이라이트) 상태 — 도착 화면이 실제 진행에 맞춰 보여 준다 */
enum class MediaState { PENDING, READY, FAILED, NONE }

/** 앨범 아트가 어디서 왔는가: 기기 안 페인터 / 서버 AI 생성 중 / AI 완료 / AI 실패(기기 아트 유지) */
enum class ArtState { LOCAL, AI_PENDING, AI_DONE, AI_FAILED }

class TripRepository(private val context: Context, private val db: SyneDatabase) {

    private companion object {
        const val REVEAL_GRACE_MS = 1_500L
    }

    private val dao get() = db.trips()
    private val covers = CoverPipeline(context)

    /** 이번 실행 중 만들고 있는 하이라이트 상태(앱을 다시 켜면 DB 값으로 판단) */
    private val _highlight = MutableStateFlow<Map<Long, MediaState>>(emptyMap())
    private val _art = MutableStateFlow<Map<Long, ArtState>>(emptyMap())
    val artRemoteConfigured: Boolean get() = covers.hasRemote

    fun highlightState(trip: TripEntity): Flow<MediaState> = _highlight.map { m ->
        m[trip.id] ?: if (trip.highlightPath != null && file(trip.highlightPath) != null) MediaState.READY else MediaState.NONE
    }

    fun artState(tripId: Long): Flow<ArtState> = _art.map { it[tripId] ?: ArtState.LOCAL }

    /** 기기마다 고정된 짧은 태그(번들 uid가 기기 사이에서 겹치지 않게) */
    private val deviceTag: String by lazy {
        val prefs = context.getSharedPreferences("syne", Context.MODE_PRIVATE)
        prefs.getString("device_tag", null) ?: java.util.UUID.randomUUID().toString().take(6).also { prefs.edit().putString("device_tag", it).apply() }
    }

    /**
     * 한 곡 결과 저장: 커버 PNG → DB(코스 분류·앨범·Vol.n) → [onInserted] → 하이라이트 AAC → DB 갱신.
     * 도착하자마자 트랙 화면을 열 수 있게, 오래 걸리는 AAC 인코딩(에뮬레이터 약 12초)은 DB 저장 뒤에 한다.
     * 저장 후 DB에서 다시 읽어 요약을 로그로 남긴다(좌표는 로그에도 남기지 않는다).
     */
    suspend fun save(result: TripResult, onInserted: (tripId: Long) -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val stamp = result.startedAtMs

        // 무드별 앨범 아트: 기기 안에서 먼저 그려 도착 화면에 바로 보이게 한다(서버 생성은 아래에서 뒤이어)
        val brief = ArtBrief.from(
            result.genre, stamp, result.songSec, result.avgSmoothness, result.hardBrakes,
            result.notes.map { it.pitch }, dao.countGenreBefore(result.genre, stamp),
            recent = dao.recentArtTags(result.genre, 3).map { it.removePrefix("art2:") },
            drive = DriveFeel.of(result.samples.map { it.speedKmh }),
            route = RouteShape.from(result.samples.map { it.speedKmh }, result.samples.map { it.steeringDeg }),
        )
        var volume = 0
        val id = db.withTransaction {
            val course = assignAlbum(result)
            val tripId = dao.insertTrip(
                TripEntity(
                    startedAt = result.startedAtMs,
                    endedAt = result.endedAtMs,
                    source = result.source,
                    genre = result.genre,
                    songSec = result.songSec,
                    bars = result.bars,
                    completed = result.completed,
                    noteCount = result.notes.size,
                    avgSmoothness = result.avgSmoothness,
                    hardBrakes = result.hardBrakes,
                    maxSpeedKmh = result.maxSpeedKmh,
                    highlightPath = null, // 아래에서 인코딩 후 채운다
                    albumId = course.albumId,
                    volume = course.volume,
                    routeDistanceM = course.routeDistanceM,
                    coverPath = null, // 아래에서 Vol 번호를 서명에 넣어 그린 뒤 채운다
                ),
            )
            volume = course.volume
            dao.insertSamples(
                result.samples.map {
                    TripSampleEntity(tripId, it.t, it.speedKmh, it.steeringDeg, it.smoothness, it.layersMask, it.section.name)
                },
            )
            dao.insertNotes(result.notes.map { NoteEntity(tripId = tripId, t = it.t, pitch = it.pitch, dur = it.dur) })
            dao.insertTags(
                listOf(
                    TagEntity(tripId, "genre:${result.genre}"),
                    TagEntity(tripId, "kind:${course.kind.lowercase()}"),
                    TagEntity(tripId, "source:${result.source.lowercase()}"),
                    TagEntity(tripId, "art2:${brief.medium.id}"),
                ),
            )
            tripId
        }
        // 앨범 아트: 기기 안에서 그려 도착 화면이 열리기 전에 채운다(서버 생성은 아래에서 뒤이어). 서명에 Vol 번호
        runCatching { covers.create(brief, stamp, allowRemote = false, volume = volume) }
            .onFailure { SyneLog.e("cover render failed", it) }.getOrNull()?.let { dao.updateCover(id, it) }
        _highlight.update { it + (id to MediaState.PENDING) }
        if (covers.hasRemote) _art.update { it + (id to ArtState.AI_PENDING) }
        onInserted(id)

        // 도착 화면(커버·제목)이 먼저 그려지도록 잠깐 양보한 뒤, 낮은 우선순위로 인코딩
        delay(REVEAL_GRACE_MS)
        val hlRel = "highlights/trip_$stamp.m4a"
        runCatching {
            val prev = Process.getThreadPriority(Process.myTid())
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                AacEncoder.encode(result.highlightPcm, result.sampleRate, channels = 2, out = File(context.filesDir, hlRel))
            } finally {
                Process.setThreadPriority(prev)
            }
            dao.updateHighlight(id, hlRel)
            _highlight.update { it + (id to MediaState.READY) }
        }.onFailure {
            SyneLog.e("highlight AAC encode failed", it)
            _highlight.update { m -> m + (id to MediaState.FAILED) }
        }

        // 서버 AI 커버(설정돼 있을 때만, 곡이 끝난 뒤=P 이후). 실패하면 기기 커버를 그대로 둔다
        if (covers.hasRemote) {
            val ok = runCatching { covers.createRemote(brief, stamp, volume)?.let { dao.updateCover(id, it) } != null }.getOrDefault(false)
            if (ok) dao.insertTags(listOf(TagEntity(id, "artsrc:ai")))
            _art.update { it + (id to if (ok) ArtState.AI_DONE else ArtState.AI_FAILED) }
        }

        logSummary(id)
        id
    }

    /**
     * 예전 경로 커버를 쓰는 곡에 무드별 앨범 아트를 한 번만 만들어 준다(이미 'art2:' 태그가 있으면 건너뜀. 아트 규칙이 바뀌면 태그 버전을 올려 한 번만 다시 그린다).
     * 기기 안 페인터만 쓴다 — 서버 호출 없음. 보관함을 열 때마다 다시 그리지 않는다.
     */
    suspend fun backfillArt() = withContext(Dispatchers.IO) {
        val todo = dao.tripsWithoutArt()
        if (todo.isEmpty()) return@withContext
        SyneLog.i("앨범 아트 만들기: ${todo.size}곡")
        val prev = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            for (t in todo) runCatching {
                val brief = ArtBrief.from(
                    t.genre, t.startedAt, t.songSec, t.avgSmoothness, t.hardBrakes,
                    dao.notePitches(t.id), dao.countGenreBefore(t.genre, t.startedAt),
                )
                val rel = covers.create(brief, t.startedAt, allowRemote = false, volume = t.volume)
                val old = t.coverPath
                dao.updateCover(t.id, rel)
                dao.insertTags(listOf(TagEntity(t.id, "art2:${brief.medium.id}")))
                if (old != null && old != rel && old.startsWith("covers/")) File(context.filesDir, old).delete()
            }.onFailure { SyneLog.e("backfill art failed #${t.id}", it) }
        } finally {
            Process.setThreadPriority(prev)
        }
    }

    private class CourseAssignment(val albumId: Long, val volume: Int, val kind: String, val routeDistanceM: Int)

    /** 출발·도착을 반경 300m 장소로 묶고, (출발 장소 → 도착 장소) 코스의 앨범과 다음 Vol 번호를 정한다. */
    private suspend fun assignAlbum(r: TripResult): CourseAssignment {
        val start = r.startFix
        val end = r.endFix ?: r.destination?.let { GeoFix(it.lat, it.lon, it.name, r.endedAtMs) }
        if (start == null || end == null) {
            val album = dao.albumByKey(CourseLogic.KEY_UNKNOWN)
                ?: dao.album(dao.insertAlbum(AlbumEntity(0, CourseLogic.KEY_UNKNOWN, CourseLogic.KIND_COURSE, "위치 정보 없는 주행", null, null, System.currentTimeMillis())))!!
            return CourseAssignment(album.id, dao.tripCountInAlbum(album.id) + 1, album.kind, r.destination?.routeDistanceM ?: 0)
        }

        val startPlace = matchOrCreatePlace(start.lat, start.lon, start.label ?: "출발지")
        // 도착 장소 이름은 내비 목적지 이름 우선
        val endName = r.destination?.name ?: end.label ?: "도착지"
        val endPlace = matchOrCreatePlace(end.lat, end.lon, endName)

        val straight = CourseLogic.distanceM(start.lat, start.lon, end.lat, end.lon)
        val routeM = r.destination?.routeDistanceM?.takeIf { it > 0 } ?: straight.toInt()
        val kind = if (CourseLogic.isTravel(r.destination?.routeDistanceM, straight)) CourseLogic.KIND_TRAVEL else CourseLogic.KIND_COURSE
        val key = CourseLogic.courseKey(kind, startPlace.id, endPlace.id)
        val album = dao.albumByKey(key) ?: run {
            val name = CourseLogic.albumName(kind, startPlace.name, endPlace.name)
            dao.album(dao.insertAlbum(AlbumEntity(0, key, kind, name, startPlace.id, endPlace.id, System.currentTimeMillis())))!!
        }
        return CourseAssignment(album.id, dao.tripCountInAlbum(album.id) + 1, kind, routeM)
    }

    private suspend fun matchOrCreatePlace(lat: Double, lon: Double, name: String): PlaceEntity {
        val near = CourseLogic.nearestPlace(dao.places(), lat, lon)
        return if (near != null) {
            CourseLogic.merge(near, lat, lon).also { dao.updatePlace(it) }
        } else {
            val id = dao.insertPlace(PlaceEntity(lat = lat, lon = lon, name = name, visits = 1))
            dao.place(id)!!
        }
    }

    /** DB에 실제로 들어간 값으로 요약 */
    suspend fun logSummary(id: Long) {
        val t = dao.trip(id) ?: return
        val album = t.albumId?.let { dao.album(it) }
        val notes = dao.noteCount(id)
        val samples = dao.sampleCount(id)
        val sec = t.songSec.toInt()
        val hl = t.highlightPath?.let { File(context.filesDir, it) }?.takeIf { it.exists() }
        val cover = t.coverPath?.let { File(context.filesDir, it) }?.takeIf { it.exists() }
        SyneLog.i(
            String.format(
                Locale.US,
                "TRIP #%d 저장 → 앨범 '%s' Vol.%d [%s] · 곡 %d:%02d (%d마디%s) · 노트 %d · 부드러움 %.0f · 급제동 %d · " +
                    "거리 %.1fkm · 샘플 %d · 커버 %s · 하이라이트 %s",
                t.id, album?.name ?: "-", t.volume, album?.kind ?: "-", sec / 60, sec % 60, t.bars,
                if (t.completed) ", 완결" else ", 중단", notes, t.avgSmoothness, t.hardBrakes, t.routeDistanceM / 1000.0, samples,
                cover?.let { "${it.name} (${it.length() / 1024}KB)" } ?: "없음",
                hl?.let { "${it.name} (${it.length() / 1024}KB)" } ?: "없음",
            ),
        )
    }

    /**
     * 주행 중 화면에 보여 줄 '지금 만들고 있는 앨범' 이름(읽기 전용 예측).
     * 출발 위치와 내비 목적지로 같은 코스를 찾고, 있으면 다음 Vol 번호를 붙인다. 좌표는 밖으로 나가지 않는다.
     */
    suspend fun predictCourse(start: GeoFix?, dest: ai.syne.app.signals.DestinationRef?): String? = withContext(Dispatchers.IO) {
        if (start == null) return@withContext null
        val places = dao.places()
        val sp = CourseLogic.nearestPlace(places, start.lat, start.lon)
        val startName = sp?.name ?: start.label ?: "새 출발지"
        if (dest == null) return@withContext "${startName}에서 출발"
        val ep = CourseLogic.nearestPlace(places, dest.lat, dest.lon)
        val endName = ep?.name ?: dest.name ?: "목적지"
        val straight = CourseLogic.distanceM(start.lat, start.lon, dest.lat, dest.lon)
        val kind = if (CourseLogic.isTravel(dest.routeDistanceM, straight)) CourseLogic.KIND_TRAVEL else CourseLogic.KIND_COURSE
        val album = if (sp != null && ep != null) dao.albumByKey(CourseLogic.courseKey(kind, sp.id, ep.id)) else null
        val vol = (album?.let { dao.tripCountInAlbum(it.id) } ?: 0) + 1
        "${album?.name ?: CourseLogic.albumName(kind, startName, endName)} Vol.$vol"
    }

    /** 트랙의 파동: 멜로디 노트 굴곡 + 가장 많았던 레이어 수 + 평균 부드러움 */

    suspend fun trackWave(id: Long): ai.syne.core.art.WaveData? = withContext(Dispatchers.IO) {
        val t = dao.trip(id) ?: return@withContext null
        val pitches = dao.notePitches(id)
        val step = (pitches.size / 12).coerceAtLeast(1)
        val notes = pitches.filterIndexed { i, _ -> i % step == 0 }.take(12)
        val lines = dao.layerMasks(id).maxOfOrNull { Integer.bitCount(it) } ?: 4
        ai.syne.core.art.WaveData(lines, t.avgSmoothness.toInt(), notes)
    }

    fun trackRows(genre: String?, favOnly: Boolean = false) = dao.trackRows(genre, favOnly)

    // ---- 사용자 편집(제목·설명·메모·태그·즐겨찾기) — 주행 기록과 분리 저장 ----
    fun edit(tripId: Long): Flow<UserEdit> = dao.editFlow(tripId).map { it?.toEdit() ?: UserEdit.None }

    /** 편집 저장. 규칙(EditRules)으로 정리한 값만 저장하고, updatedAt을 지금으로 바꾼다 */
    suspend fun saveEdit(tripId: Long, title: String?, description: String?, memo: String?, tags: List<String>) = withContext(Dispatchers.IO) {
        val prev = dao.edit(tripId)
        dao.upsertEdit(
            TrackEditEntity(
                tripId,
                EditRules.clean(title, EditRules.TITLE_MAX, singleLine = true),
                EditRules.clean(description, EditRules.DESCRIPTION_MAX),
                EditRules.clean(memo, EditRules.MEMO_MAX),
                org.json.JSONArray(EditRules.tags(tags)).toString(),
                prev?.favorite ?: false,
                System.currentTimeMillis(),
            ),
        )
    }

    suspend fun toggleFavorite(tripId: Long) = withContext(Dispatchers.IO) {
        val prev = dao.edit(tripId)
        dao.upsertEdit(
            (prev ?: TrackEditEntity(tripId, null, null, null, "[]", false, 0)).let { it.copy(favorite = !it.favorite, updatedAt = System.currentTimeMillis()) },
        )
    }

    private fun TrackEditEntity.toEdit(): UserEdit {
        val arr = runCatching { org.json.JSONArray(tagsJson) }.getOrNull()
        return UserEdit(customTitle, description, memo, if (arr == null) emptyList() else List(arr.length()) { arr.getString(it) }, favorite, updatedAt)
    }

    // ---- 화면용 ----
    fun albumCards() = dao.albumCards()
    fun album(id: Long) = dao.albumFlow(id)
    fun tracks(albumId: Long) = dao.tracksInAlbum(albumId)
    fun track(id: Long) = dao.tripFlow(id)
    fun playlist(genre: String) = dao.playlistByTag("genre:$genre")
    fun playlistCount(genre: String) = dao.countByTag("genre:$genre")

    fun file(rel: String?): File? = rel?.let { File(context.filesDir, it) }?.takeIf { it.exists() }

    /** 1초 기록의 속도를 적분한 실제 주행 거리(m) */
    suspend fun drivenMeters(id: Long): Int = withContext(Dispatchers.IO) {
        dao.samplesLite(id).sumOf { it.speedKmh / 3.6 }.toInt()
    }

    /**
     * 휴대폰으로 보낼 번들(.syne) 쓰기 — 보관함의 모든 곡(커버·하이라이트·기록·타임라인). 좌표는 넣지 않는다.
     * @return 담은 곡 수
     */
    suspend fun exportBundle(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, File>()
        val tracks = dao.allTrips().mapNotNull { t ->
            val album = t.albumId?.let { dao.album(it) } ?: return@mapNotNull null
            val uid = "trk-${t.startedAt}-$deviceTag"
            val samples = dao.samplesLite(t.id)
            val tags = dao.tags(t.id)
            val pitches = dao.notePitches(t.id)
            val step = (pitches.size / 64).coerceAtLeast(1)
            val cover = file(t.coverPath)?.also { files["covers/$uid.jpg"] = it }
            val audio = file(t.highlightPath)?.also { files["audio/$uid.m4a"] = it }
            SharedTrack(
                uid = uid, albumUid = "alb-" + Integer.toHexString(album.courseKey.hashCode()), albumName = album.name, albumKind = album.kind,
                volume = t.volume, genre = t.genre, startedAt = t.startedAt, endedAt = t.endedAt, songSec = t.songSec,
                distanceM = samples.sumOf { it.speedKmh / 3.6 }.toInt(), smoothness = t.avgSmoothness.toInt(), hardBrakes = t.hardBrakes,
                completed = t.completed,
                artMedium = tags.firstOrNull { it.startsWith("art2:") }?.removePrefix("art2:"),
                artSource = if ("artsrc:ai" in tags) "ai" else "local",
                notes = pitches.filterIndexed { i, _ -> i % step == 0 }.take(64),
                timeline = Timeline.build(samples.map { SampleLite(it.tSec, it.speedKmh, it.section) }, t.songSec),
                tags = tags.filter { it.startsWith("genre:") || it.startsWith("kind:") || it.startsWith("art2:") },
                coverFile = cover?.let { "covers/$uid.jpg" }, audioFile = audio?.let { "audio/$uid.m4a" }, audioSec = audio?.let { 30f },
                edit = dao.edit(t.id)?.toEdit(),
                timeZone = java.util.TimeZone.getDefault().id,
            )
        }
        AlbumBundle.write(out, BundleManifest(BundleManifest.SCHEMA, System.currentTimeMillis(), "car:$deviceTag", tracks), files)
        tracks.size
    }
}
