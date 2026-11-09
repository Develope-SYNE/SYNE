package ai.syne.app.ui

import ai.syne.app.engine.EngineState
import ai.syne.app.service.PlaybackService
import ai.syne.app.signals.Channel
import ai.syne.app.signals.ChannelState
import ai.syne.app.signals.Gear
import ai.syne.app.signals.SignalHub
import ai.syne.app.signals.SignalSource
import ai.syne.app.signals.TurnSignal
import ai.syne.app.syneApp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 화면에 그릴 값 한 벌. 이 객체가 바뀔 때만 재구성된다. */
data class DebugUi(
    val source: SignalSource,
    val mode: SignalHub.Mode,
    val speedKmh: Float,
    val steeringDeg: Float,
    val brake: Boolean,
    val turn: TurnSignal,
    val gear: Gear,
    val remainSec: Int?,
    val limitKmh: Int?,
    val channels: Map<Channel, ChannelState>,
    val replayPosSec: Float?,
    val engine: EngineState,
    val refreshHz: Int,
    val course: String,
) {
    val parked get() = gear == Gear.PARK || gear == Gear.UNKNOWN
}

/**
 * 신호·엔진은 SyneApp이 소유(서비스와 공유). 이 ViewModel은 화면 갱신 빈도만 책임진다.
 *
 * 원칙 1·5: 주행 중(P 이외)에는 1Hz, 정차(P) 중에만 10Hz로 스냅샷을 찍는다.
 * 신호가 10Hz로 바뀌어도 화면 재구성은 이 주기를 넘지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModel(app: Application) : AndroidViewModel(app) {

    private val syne = app.syneApp
    private val signals = syne.signals
    private val engine = syne.engine

    val ui: StateFlow<DebugUi> = signals.gear
        .map { it == Gear.PARK || it == Gear.UNKNOWN }
        .distinctUntilChanged()
        .flatMapLatest { parked ->
            val hz = if (parked) PARKED_HZ else DRIVING_HZ
            flow {
                while (true) {
                    emit(snapshot(hz))
                    delay(1000L / hz)
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot(PARKED_HZ))

    private fun snapshot(hz: Int) = DebugUi(
        source = signals.source.value,
        mode = signals.mode.value,
        speedKmh = signals.speedKmh.value,
        steeringDeg = signals.steeringDeg.value,
        brake = signals.brakePressed.value,
        turn = signals.turnSignal.value,
        gear = signals.gear.value,
        remainSec = signals.remainSec.value,
        limitKmh = signals.speedLimitKmh.value,
        channels = signals.channels.value,
        replayPosSec = signals.replayPositionSec.value,
        engine = engine.state.value,
        refreshHz = hz,
        course = signals.replayCourse.value,
    )

    /** 권한 요청 결과 이후 호출. 중복 호출 무시. */
    fun startSignals() = signals.start()

    fun setMode(mode: SignalHub.Mode) = signals.setMode(mode)

    /** 데모 코스 선택(Replay면 처음부터 다시) */
    fun setCourse(course: String) = signals.setReplayCourse(course)

    fun togglePlayback() {
        val ctx = getApplication<Application>()
        if (engine.state.value.playing) PlaybackService.stop(ctx) else PlaybackService.play(ctx)
    }

    private companion object {
        const val PARKED_HZ = 10
        const val DRIVING_HZ = 1
    }
}
