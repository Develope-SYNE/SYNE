package ai.syne.app.ui

import ai.syne.app.Genre
import ai.syne.app.SyneLog
import ai.syne.app.engine.Section
import ai.syne.app.library.AlbumCard
import ai.syne.app.library.AlbumEntity
import ai.syne.app.library.TrackRow
import ai.syne.app.library.TripEntity
import ai.syne.app.service.PlaybackService
import ai.syne.app.signals.DestinationRef
import ai.syne.app.signals.Gear
import ai.syne.app.signals.TurnSignal
import ai.syne.core.art.OrbitParams
import ai.syne.core.art.WaveData
import ai.syne.core.art.decay
import ai.syne.app.signals.ReplaySignals
import ai.syne.app.signals.SignalHub
import ai.syne.app.syneApp
import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Library : Route
    data class Album(val id: Long) : Route
    /** fresh = 방금 도착해서 열린 화면(‘주행 완료’ 상태로 보여 준다) */
    data class Track(val id: Long, val fresh: Boolean = false) : Route
    data object Debug : Route
    data object ArtLab : Route
    /** 앨범 꾸미기(제목·설명·메모·태그) — 기어 P가 확인될 때만 */
    data class Edit(val id: Long) : Route
}

/** 편집 중인 값(기어가 바뀌어 화면이 잠시 주행 화면으로 넘어가도 지워지지 않게 ViewModel에 둔다) */
data class EditDraft(val tripId: Long, val title: String, val description: String, val memo: String, val tags: List<String>, val tagInput: String = "")

/**
 * 주행 화면 스냅샷(4초마다 갱신, 원칙 1): 정지 그래픽 + 코스 이름 + "Vol.n · 장르" 뿐.
 * 부드러움·도착 시각·생성 상태는 띄우지 않는다 — 부드러움은 소리로만 전달하고 도착 화면에서 공개한다.
 */
data class DrivingUi(
    /** 코스 앨범 이름(예측) — 없으면 null */
    val course: String?,
    /** "Vol.9" */
    val volume: String?,
    val genre: String,
    val orbit: OrbitParams,
)


@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val syne = app.syneApp
    private val trips = syne.trips

    // ---------- 내비게이션 (정차 중에만 의미 있음) ----------
    private val stack = MutableStateFlow<List<Route>>(listOf(Route.Home))
    val route: StateFlow<Route> = stack.map { it.last() }.stateIn(viewModelScope, SharingStarted.Eagerly, Route.Home)

    fun push(r: Route) {
        stack.value = stack.value + r
    }

    /** 홈으로(스택 비우기) */
    fun goHome() { stack.value = listOf(Route.Home) }

    /** 보관함으로 — 방금 본 곡이 선택된 상태로 */
    fun openLibrary(selectTrackId: Long? = null) {
        selectTrackId?.let { _selected.value = it }
        stack.value = listOf(Route.Home, Route.Library)
    }

    fun pop(): Boolean {
        if (stack.value.size <= 1) return false
        stack.value = stack.value.dropLast(1)
        return true
    }

    // ---------- 주행 여부: P 이외 기어면 주행 화면 강제 ----------
    val driving: StateFlow<Boolean> = syne.signals.gear
        .map { it != Gear.PARK && it != Gear.UNKNOWN }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)


    /** 지금 만들고 있는 코스 앨범(예측) — 곡 시작 위치·내비 목적지로 계산, 좌표는 화면에 나가지 않음 */
    val courseLine: StateFlow<String?> = combine(
        syne.engine.state.map { it.playing }.distinctUntilChanged(), syne.signals.position, syne.signals.destination,
    ) { p, pos, dest -> Triple(p, pos, dest) }
        .mapLatest { (p, pos, dest) -> if (p) trips.predictCourse(pos, dest) else null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 주행 화면 상태: 4초에 한 번만 스냅샷(원칙 1·5). 애니메이션·전환 효과 없음 */
    val drivingUi: StateFlow<DrivingUi> = driving.flatMapLatest { d ->
        if (!d) flowOf(snapshot()) else flow {
            while (true) {
                emit(snapshot())
                delay(4_000)
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), snapshot())

    /** 마지막 급제동 시각(오브젝트가 잠깐 흐트러졌다가 다음 스냅샷들에서 풀린다) */
    @Volatile private var lastHardBrakeMs = 0L

    private fun snapshot(): DrivingUi {
        val e = syne.engine.state.value
        val sig = syne.signals
        val genre = syne.genre.value.label
        val line = courseLine.value
        val cut = line?.lastIndexOf(" Vol.") ?: -1
        val course = if (cut > 0) line!!.substring(0, cut) else line
        val volume = if (cut > 0) line!!.substring(cut + 1) else null
        if (!e.playing) return DrivingUi(course, volume, genre, OrbitParams.from(WaveData.Calm))
        val sinceBrake = (System.currentTimeMillis() - lastHardBrakeMs) / 1000f
        // 에너지 = 편성(레이어 수·멜로디 음색). 속도는 직접 넣지 않는다 — 제한속도 초과 시 편성이 이미 막혀 있다(원칙 2)
        val toneLevel = e.melodyTone?.ordinal?.plus(1) ?: 0
        val energy = (((e.layers.size - 1) / 5f) * 0.7f + toneLevel / 3f * 0.3f).coerceIn(0f, 1f)
        val orbit = OrbitParams(
            rings = (e.layers.size + 1).coerceIn(3, 7),
            energy = energy,
            // 부드러움은 화면에 드러내지 않는다(소리로만): 하모니는 고정값
            harmony = 0.9f,
            notes = e.recentNotes,
            steer = (sig.steeringDeg.value / 90f).coerceIn(-1f, 1f),
            disruption = if (e.tapeStop) 1f else decay(sinceBrake, 3f).let { if (it < 0.05f) 0f else it },
            pulse = when (sig.turnSignal.value) { TurnSignal.LEFT -> -1; TurnSignal.RIGHT -> 1; else -> 0 },
            worn = e.overLimit,
            resolve = when (e.section) { Section.FINALE -> 0.45f; Section.ENDING, Section.END -> 1f; else -> 0f },
        )
        return DrivingUi(course, volume, genre, orbit)
    }

    // ---------- 장르 · 재생 ----------
    val genre: StateFlow<Genre> = syne.genre
    fun selectGenre(g: Genre) = syne.selectGenre(g)

    val playing: StateFlow<Boolean> = syne.engine.state.map { it.playing }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 실차 출발: Replay로 두었으면 실차(AUTO: 실패 시 Replay 자동 전환)로 되돌린 뒤 재생 */
    fun toggleSong() {
        val ctx = getApplication<Application>()
        if (playing.value) {
            PlaybackService.stop(ctx)
            return
        }
        if (syne.signals.mode.value == SignalHub.Mode.REPLAY) syne.signals.setMode(SignalHub.Mode.AUTO)
        PlaybackService.play(ctx)
    }

    // ---------- 데모 주행(시연용): 개발자 메뉴 없이 홈에서 한 번에 ----------
    val demoCourses: List<String> = ReplaySignals.COURSES.keys.toList()
    val demoCourse: StateFlow<String> = syne.signals.replayCourse

    fun selectDemoCourse(course: String) {
        // 재생 중이 아닐 때 고르기만 한다(Replay 중이면 허브가 처음부터 다시 재생)
        if (!playing.value) syne.signals.setReplayCourse(course)
    }

    /** 데모 주행 시작: Replay 모드로 바꾸고 선택한 코스를 처음(P)부터 재생하면서 곡을 시작 */
    fun startDemo() {
        if (playing.value) return
        val hub = syne.signals
        if (hub.mode.value == SignalHub.Mode.REPLAY) hub.setReplayCourse(hub.replayCourse.value) // 처음부터
        else hub.setMode(SignalHub.Mode.REPLAY)
        SyneLog.i("데모 주행 시작: 코스 ${hub.replayCourse.value}")
        PlaybackService.play(getApplication())
    }

    // ---------- 보관함 ----------
    val albums: StateFlow<List<AlbumCard>> = trips.albumCards()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 장르별 자동 플레이리스트 곡 수 */
    val playlistCount: StateFlow<Int> = genre.flatMapLatest { trips.playlistCount(it.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 보관함 장르 필터(null = ALL) */
    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()
    fun setFilter(genreId: String?) { _filter.value = genreId }

    /** 필터 키: null 전체 · FAVORITES 즐겨찾기 · 그 밖엔 장르 id */
    val trackRows: StateFlow<List<TrackRow>> = _filter.flatMapLatest { f -> if (f == FAVORITES) trips.trackRows(null, favOnly = true) else trips.trackRows(f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 내비 목적지(홈 목적지 칸, 읽기 전용 — 경로는 내비 앱이 정한다) */
    val destination = syne.signals.destination

    /**
     * 내비 앱 열기(정차 중 홈에서만). Pleos 내비 → 없으면 시스템 지도 앱.
     * @return 열었으면 true
     */
    fun openNavigation(): Boolean {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(NAVI_PACKAGE)
            ?: android.content.Intent.makeMainSelectorActivity(android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_MAPS)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.onFailure { SyneLog.w("내비 앱 열기 실패: ${it.message}") }.isSuccess
    }
    val remainSec = syne.signals.remainSec

    /** 홈 시그니처 오브젝트: 최근 트랙의 음악 데이터로(없으면 잔잔한 기본) */
    val homeOrbit: StateFlow<OrbitParams> = combine(trackRows, syne.genre) { rows, g -> rows.firstOrNull() to g }
        .mapLatest { (last, g) ->
            val base = OrbitParams.from(last?.let { trips.trackWave(it.id) } ?: WaveData.Calm, seed = (last?.id ?: 7L).toInt())
            // 장르별 결: Hip-hop은 고리가 많고 에너지가 크게, Ambient는 적고 고요하게(SignatureObject는 그대로, 값만 다르게)
            when (g) {
                Genre.HIPHOP -> base.copy(rings = (base.rings + 1).coerceAtMost(7), energy = 0.9f, harmony = 0.65f)
                Genre.AMBIENT -> base.copy(rings = 3, energy = 0.3f, harmony = 1f, resolve = 0.5f)
                else -> base
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrbitParams.from(WaveData.Calm))

    // ---------- 사용자 편집 · 즐겨찾기 ----------
    /** 편집은 기어 P가 '확인'될 때만(UNKNOWN이면 정차를 믿을 수 없으므로 막는다) */
    val parkConfirmed: StateFlow<Boolean> = syne.signals.gear.map { it == Gear.PARK }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun edit(tripId: Long) = trips.edit(tripId)
    fun toggleFavorite(tripId: Long) { viewModelScope.launch { trips.toggleFavorite(tripId) } }

    private val _draft = MutableStateFlow<EditDraft?>(null)
    val draft: StateFlow<EditDraft?> = _draft.asStateFlow()

    /** 편집 화면 열기: 같은 곡의 작성 중인 값이 있으면 그대로 이어서 */
    fun openEdit(tripId: Long) {
        if (!parkConfirmed.value) return
        if (_draft.value?.tripId != tripId) {
            viewModelScope.launch {
                val e = trips.edit(tripId).first()
                _draft.value = EditDraft(tripId, e.customTitle ?: "", e.description ?: "", e.memo ?: "", e.tags)
                push(Route.Edit(tripId))
            }
        } else push(Route.Edit(tripId))
    }

    fun updateDraft(f: (EditDraft) -> EditDraft) { _draft.value?.let { _draft.value = f(it) } }

    /** 저장. 문제가 있으면 문구를 돌려주고 저장하지 않는다 */
    fun saveDraft(): String? {
        val d = _draft.value ?: return null
        ai.syne.core.share.EditRules.problem(d.title, d.description, d.memo)?.let { return it }
        val tags = d.tags + listOfNotNull(ai.syne.core.share.EditRules.tag(d.tagInput))
        viewModelScope.launch { trips.saveEdit(d.tripId, d.title, d.description, d.memo, tags) }
        _draft.value = null
        pop()
        return null
    }

    fun cancelDraft() { _draft.value = null; pop() }

    // ---------- 보관함 선택 · 결과물 상태 ----------
    private val _selected = MutableStateFlow<Long?>(null)
    /** 보관함에서 고른 곡(없으면 가장 최근 곡) */
    val selectedTrack: StateFlow<Long?> = combine(_selected, trackRows) { sel, rows -> sel?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun select(id: Long) { _selected.value = id }

    fun highlightState(trip: TripEntity) = trips.highlightState(trip)
    fun artState(id: Long) = trips.artState(id)
    val artRemoteConfigured: Boolean get() = trips.artRemoteConfigured
    suspend fun drivenMeters(id: Long) = trips.drivenMeters(id)
    fun file(rel: String?) = trips.file(rel)

    // ---------- 휴대폰용 파일 만들기(번들 파일, 직접 전송 아님) ----------
    companion object {
        const val FAVORITES = "fav"
        /** Pleos Connect 내비 앱(docs/PLEOS_NOTES.md) */
        const val NAVI_PACKAGE = "ai.umos.maps.android.navigation.app"
    }

    sealed interface ExportState {
        data object Idle : ExportState
        data object Working : ExportState
        data class Done(val count: Int, val where: String) : ExportState
        data class Failed(val reason: String) : ExportState
    }
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    /**
     * 보관함 전체를 .syne 번들로 내보낸다 — 다운로드/SYNE 폴더(USB·파일 공유로 휴대폰에 옮겨 SYNE 모바일에서 가져오기).
     * 서버 동기화가 아니라 실제 파일이다. 정차 중에만 버튼이 보인다.
     */
    fun exportToPhone() {
        if (_export.value is ExportState.Working) return
        _export.value = ExportState.Working
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = "SYNE-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".syne"
            _export.value = runCatching {
                withContext(Dispatchers.IO) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream") // zip이지만 확장자 .syne를 유지
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/SYNE")
                        }
                        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("저장 위치를 만들 수 없어요")
                        val n = ctx.contentResolver.openOutputStream(uri)!!.use { trips.exportBundle(it) }
                        ExportState.Done(n, "다운로드/SYNE/$name")
                    } else {
                        val f = java.io.File(ctx.getExternalFilesDir("exports"), name)
                        val n = f.outputStream().use { trips.exportBundle(it) }
                        ExportState.Done(n, f.absolutePath)
                    }
                }
            }.getOrElse { ExportState.Failed(it.message ?: "알 수 없는 오류") }
            SyneLog.i("번들 내보내기: ${_export.value}")
        }
    }

    suspend fun trackWave(id: Long): WaveData = trips.trackWave(id) ?: WaveData.Calm

    fun album(id: Long): Flow<AlbumEntity?> = trips.album(id)
    fun tracks(albumId: Long): Flow<List<TripEntity>> = trips.tracks(albumId)
    fun track(id: Long): Flow<TripEntity?> = trips.track(id)

    // ---------- 하이라이트 재생 ----------
    private var player: MediaPlayer? = null
    private val _highlightOf = MutableStateFlow<Long?>(null)
    val highlightOf: StateFlow<Long?> = _highlightOf.asStateFlow()

    /** 하이라이트 재생 위치(0~1). 정차 화면에서만 재생되므로 0.15초 간격 갱신 */
    private val _highlightProgress = MutableStateFlow(0f)
    val highlightProgress: StateFlow<Float> = _highlightProgress.asStateFlow()
    private var progressJob: kotlinx.coroutines.Job? = null

    fun toggleHighlight(trip: TripEntity) {
        if (_highlightOf.value == trip.id) {
            stopHighlight()
            return
        }
        stopHighlight()
        val f = trips.file(trip.highlightPath) ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            setDataSource(f.absolutePath)
            setOnCompletionListener { stopHighlight() }
            prepare()
            start()
        }
        _highlightOf.value = trip.id
        progressJob = viewModelScope.launch {
            while (true) {
                val p = player ?: break
                _highlightProgress.value = runCatching { p.currentPosition.toFloat() / p.duration.coerceAtLeast(1) }.getOrDefault(0f)
                delay(150)
            }
        }
    }

    fun stopHighlight() {
        progressJob?.cancel()
        progressJob = null
        _highlightProgress.value = 0f
        player?.runCatching { stop(); release() }
        player = null
        _highlightOf.value = null
    }

    /** 지금 곡의 구간(홈에서 '엔딩 연주 중' 안내용) */
    val songSection: StateFlow<Section?> = syne.engine.state.map { if (it.playing) it.section else null }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), null)

    init {
        // 예전 경로 커버를 쓰는 곡에 무드별 앨범 아트를 한 번만 만들어 준다
        viewModelScope.launch { trips.backfillArt() }
        // 급제동 횟수가 늘면 그 시각을 기억(주행 스냅샷이 오브젝트를 잠깐 흐트러뜨린다)
        viewModelScope.launch {
            syne.engine.state.map { it.hardBrakes }.distinctUntilChanged().collect { if (it > 0) lastHardBrakeMs = System.currentTimeMillis() }
        }
        // 주행이 시작되면 하이라이트 재생은 멈춘다
        viewModelScope.launch {
            driving.collect { if (it) stopHighlight() }
        }
        // 도착(엔딩 완결) → 방금 만든 트랙 상세로 바로 이동. 뒤로 가면 그 앨범 → 홈.
        viewModelScope.launch {
            syne.arrivals.collect { tripId ->
                val albumId = trips.track(tripId).first()?.albumId
                stack.value = buildList {
                    add(Route.Home)
                    if (albumId != null) add(Route.Album(albumId))
                    add(Route.Track(tripId, fresh = true))
                }
            }
        }
        SyneLog.d("MainViewModel ready")
    }

    override fun onCleared() {
        stopHighlight()
    }
}
