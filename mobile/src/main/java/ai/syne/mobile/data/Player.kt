package ai.syne.mobile.data

import ai.syne.core.share.SharedTrack
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 재생 상태: 지금 곡 · 재생 중 · 위치/길이(ms) */
data class PlayState(val uid: String? = null, val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0) {
    val progress: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
}

/**
 * 하이라이트(차량이 저장한 도착 직전 30초) 재생. 실제 파일만 재생하고, 위치 이동(seek)을 지원한다.
 * 완성곡 전체는 차량에도 저장하지 않으므로 여기서도 다루지 않는다.
 */
class Player(private val store: LibraryStore, private val scope: CoroutineScope) {
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    fun canPlay(t: SharedTrack) = store.file(t.audioFile) != null

    fun play(t: SharedTrack) {
        if (_state.value.uid == t.uid && mp != null) { toggle(); return }
        release()
        val f = store.file(t.audioFile) ?: return
        mp = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(f.absolutePath)
            setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs) }
            prepare()
            start()
        }
        _state.value = PlayState(t.uid, true, 0, mp?.duration ?: 0)
        startTicker()
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else {
            if (_state.value.positionMs >= _state.value.durationMs - 50) p.seekTo(0)
            p.start()
        }
        _state.value = _state.value.copy(playing = p.isPlaying)
    }

    fun seek(fraction: Float) {
        val p = mp ?: return
        val ms = (fraction.coerceIn(0f, 1f) * p.duration).toInt()
        p.seekTo(ms)
        _state.value = _state.value.copy(positionMs = ms)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                mp?.let { runCatching { _state.value = _state.value.copy(positionMs = it.currentPosition, playing = it.isPlaying) } }
                delay(200)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        mp?.runCatching { stop(); release() }
        mp = null
        _state.value = PlayState()
    }
}
