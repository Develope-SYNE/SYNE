package ai.syne.app.service

import ai.syne.app.MainActivity
import ai.syne.app.R
import ai.syne.app.SyneLog
import ai.syne.app.syneApp
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 음악 재생을 소유하는 포그라운드 서비스(mediaPlayback). 화면이 가려져도 음악이 이어진다.
 *
 * 내비 안내 덕킹 (2026-11-02 Pleos 에뮬레이터 실측):
 * - setWillPauseWhenDucked(true)면 AAOS는 안내 시 LOSS_TRANSIENT(-2)를 보내 음악이 '뮤트'됨 → false 사용
 * - false면 미디어와 안내가 동시 재생(concurrent)되고 앱엔 콜백이 없으며, 이 이미지는 HAL 덕킹도 꺼져 있음
 * - 덕킹 이벤트 수신 권한(RECEIVE_CAR_AUDIO_DUCKING_EVENTS)은 signature|privileged라 서드파티 불가
 * → 그래서 AudioPlaybackCallback으로 '안내 음성 재생 중'(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)을 직접 감지해 엔진이 덕킹한다.
 *   실차에서 LOSS_TRANSIENT_CAN_DUCK가 오면 그것도 덕킹으로 처리.
 * - 통화 등(LOSS_TRANSIENT) → 뮤트(시퀀서 시간은 계속), 다른 미디어 앱(LOSS) → 정지
 */
class PlaybackService : Service() {

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var playing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var focusDuck = false
    private var guidanceDuck = false

    private fun applyDuck() = syneApp.engine.setDucked(focusDuck || guidanceDuck)

    /** 다른 앱의 재생 중인 플레이어 목록에서 내비 안내 음성을 찾는다(권한 불필요). */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val guidance = configs.any {
                val u = it.audioAttributes.usage
                u == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE || u == AudioAttributes.USAGE_ASSISTANT
            }
            if (guidance != guidanceDuck) {
                guidanceDuck = guidance
                SyneLog.d("guidance playback ${if (guidance) "started → duck" else "ended → unduck"}")
                applyDuck()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val engine = syneApp.engine
        SyneLog.d("audio focus change=$change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusDuck = false
                applyDuck()
                engine.setMuted(false)
                if (!playing) startEngine()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusDuck = true
                applyDuck()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.setMuted(true)
            AudioManager.AUDIOFOCUS_LOSS -> stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                return START_NOT_STICKY
            }
            else -> {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, buildNotification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
                syneApp.signals.start()
                requestFocusAndPlay()
            }
        }
        return START_NOT_STICKY
    }

    private fun requestFocusAndPlay() {
        if (playing) return
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(false) // true면 AAOS가 안내 시 LOSS_TRANSIENT(뮤트)를 보냄
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }

        when (val result = audioManager.requestAudioFocus(req)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> startEngine()
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> SyneLog.i("audio focus delayed — GAIN 시 재생")
            else -> {
                SyneLog.w("audio focus denied ($result)")
                stopPlayback()
            }
        }
    }

    private fun startEngine() {
        playing = true
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)
        val engine = syneApp.engine
        engine.start(syneApp.signals, syneApp.genre.value.id)
        // 엔딩까지 끝나 엔진이 스스로 멈추면 서비스도 정리(저장은 SyneApp이 맡음)
        scope.launch {
            engine.state.map { it.playing }.filter { it }.first()
            engine.state.map { it.playing }.drop(1).filter { !it }.first()
            if (playing) {
                SyneLog.i("곡 종료 → 재생 서비스 정리")
                stopPlayback()
            }
        }
    }

    private fun stopPlayback() {
        playing = false
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
        syneApp.engine.stop()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
        if (playing) {
            syneApp.engine.stop()
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SYNE 재생", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_syne)
            .setContentTitle("SYNE")
            .setContentText("See Your Sound — 주행이 곡이 되는 중")
            .setContentIntent(open)
            .addAction(0, "정지", stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "ai.syne.app.action.PLAY"
        const val ACTION_STOP = "ai.syne.app.action.STOP"

        fun play(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
