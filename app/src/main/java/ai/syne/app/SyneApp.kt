package ai.syne.app

import ai.syne.app.engine.SoundEngine
import ai.syne.app.library.SyneDatabase
import ai.syne.app.library.TripRepository
import ai.syne.app.signals.SignalHub
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 프로세스 수명 동안 하나만 있는 것들. 화면(Activity)과 재생(PlaybackService)이 같은 인스턴스를 본다.
 * 화면이 꺼져도 서비스가 살아 있는 한 신호·음악은 계속된다.
 */
class SyneApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val signals: SignalHub by lazy { SignalHub(this, appScope) }
    val db: SyneDatabase by lazy { SyneDatabase.create(this) }
    val trips: TripRepository by lazy { TripRepository(this, db) }
    private val prefs by lazy { getSharedPreferences("syne", MODE_PRIVATE) }
    private val _genre by lazy { MutableStateFlow(Genre.fromId(prefs.getString("genre", null)) ?: Genre.LOFI) }

    /** 정차 화면에서 고른 장르. 다음 곡부터 적용 */
    val genre: StateFlow<Genre> get() = _genre.asStateFlow()

    fun selectGenre(g: Genre) {
        if (!g.available) return
        _genre.value = g
        prefs.edit().putString("genre", g.id).apply()
    }

    private val _arrivals = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /** 엔딩까지 완결된 곡이 DB에 들어간 순간의 trip id. 화면이 이걸 받아 새 트랙 상세로 이동한다. */
    val arrivals: SharedFlow<Long> = _arrivals.asSharedFlow()

    val engine: SoundEngine by lazy {
        SoundEngine(this).also { e ->
            // 곡이 끝나면(완결·중단 모두) 서비스 수명과 무관하게 저장
            e.onSongFinished = { result ->
                appScope.launch {
                    trips.save(result) { tripId ->
                        if (result.completed) {
                            SyneLog.i("도착 → 새 트랙 #$tripId 열기")
                            _arrivals.tryEmit(tripId)
                        }
                    }
                }
            }
        }
    }
}

/** 장르 팩 */
enum class Genre(val id: String, val label: String, val mood: String, val bpm: String, val available: Boolean) {
    LOFI("lofi", "Lo-fi", "Warm · Laid-back", "84 BPM", true),
    HIPHOP("hiphop", "Hip-hop", "Hard · 808", "96 BPM", true),
    AMBIENT("ambient", "Ambient", "Airy · Cinematic", "준비 중", false),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

val android.content.Context.syneApp: SyneApp get() = applicationContext as SyneApp
