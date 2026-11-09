package ai.syne.app.debug

import ai.syne.app.SyneLog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * 내비 음성 안내를 흉내 낸다: 포커스 요청(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, GAIN_TRANSIENT_MAY_DUCK)
 * + 같은 용도의 AudioTrack으로 '띵동' 톤을 holdMs 동안 재생 후 반납.
 * (에뮬레이터에선 차량이 경로를 따라 움직이지 않아 실제 안내가 나오지 않음)
 *
 * adb shell am broadcast --user 10 -a ai.syne.app.debug.SIM_NAV_PROMPT -n ai.syne.app/.debug.NavPromptSimReceiver --el holdMs 3000
 */
class NavPromptSimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val holdMs = intent.getLongExtra("holdMs", 3_000L).coerceIn(500L, 8_000L)
        val am = context.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        val pending = goAsync()
        thread(name = "SYNE-sim-nav") {
            val result = am.requestAudioFocus(req)
            SyneLog.i("SIM nav prompt: focus=$result, playing guidance tone ${holdMs}ms")
            val sr = 48_000
            val n = (sr * holdMs / 1000).toInt()
            val pcm = ShortArray(n) { i ->
                val t = i.toDouble() / sr
                val f = if ((t * 2).toInt() % 2 == 0) 660.0 else 520.0 // 띵-동
                (sin(2 * PI * f * t) * 0.25 * 32767).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
            try {
                track.write(pcm, 0, n)
                track.play()
                Thread.sleep(holdMs + 100)
            } finally {
                track.release()
                am.abandonAudioFocusRequest(req)
                SyneLog.i("SIM nav prompt: done (track released, focus abandoned)")
                pending.finish()
            }
        }
    }
}
