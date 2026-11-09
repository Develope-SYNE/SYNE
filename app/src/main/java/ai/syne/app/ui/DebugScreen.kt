package ai.syne.app.ui

import ai.syne.app.engine.Layer
import ai.syne.app.signals.Channel
import ai.syne.app.signals.ChannelState
import ai.syne.app.signals.Gear
import ai.syne.app.signals.ReplaySignals
import ai.syne.app.signals.SignalHub
import ai.syne.app.signals.SignalSource
import ai.syne.app.signals.TurnSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * 신호·엔진 디버그 화면.
 * 원칙 1: 고정 크기 칸 안에서 글자만 바뀐다(레이아웃 이동 없음). 갱신 빈도는 ViewModel이 P 10Hz / 주행 1Hz로 제한.
 * 신호원 전환·재생 토글은 기어 P에서만.
 */
@Composable
fun DebugScreen(vm: DebugViewModel, onClose: () -> Unit, onArtLab: () -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val overLimit = ui.limitKmh?.let { ui.speedKmh > it } == true
    val engine = ui.engine

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←", color = Dim, fontSize = 28.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(horizontal = 10.dp),
            )
            Text("SYNE · debug", color = Fg, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(24.dp))
            Text(
                "아트 랩", color = Accent, fontSize = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onArtLab).padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = when (ui.source) {
                    SignalSource.REAL -> "REAL"
                    SignalSource.REPLAY -> "REPLAY " + (ui.replayPosSec?.let { fmt("%5.1fs", it) } ?: "")
                    SignalSource.NONE -> "—"
                },
                style = Mono,
                color = if (ui.source == SignalSource.REAL) Live else Accent,
                fontSize = 22.sp,
                modifier = Modifier.width(220.dp),
            )
            Text("${ui.refreshHz}Hz", style = Mono, color = Dim, fontSize = 16.sp, modifier = Modifier.width(60.dp))
            Spacer(Modifier.weight(1f))
            Text("코스", color = Dim, fontSize = 16.sp)
            ReplaySignals.COURSES.keys.forEach { c ->
                FilterChip(
                    selected = ui.course == c,
                    onClick = { vm.setCourse(c) },
                    enabled = ui.parked,
                    label = { Text(c, fontSize = 18.sp) },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            SignalHub.Mode.entries.forEach { m ->
                FilterChip(
                    selected = ui.mode == m,
                    onClick = { vm.setMode(m) },
                    enabled = ui.parked,
                    label = { Text(m.name, fontSize = 18.sp) },
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Box(Modifier.height(24.dp)) {
            if (!ui.parked) Text("조작은 기어 P에서만", color = Dim, fontSize = 14.sp)
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile("속도", fmt("%5.1f", ui.speedKmh), "km/h", Modifier.weight(1f), if (overLimit) Warn else Fg)
            Tile("조향", fmt("%+6.1f", ui.steeringDeg), "deg", Modifier.weight(1f))
            Tile("기어", ui.gear.label(), "", Modifier.weight(1f), if (ui.gear == Gear.PARK) Accent else Fg)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Tile("브레이크", if (ui.brake) "ON" else "off", "", Modifier.weight(1f), if (ui.brake) Warn else Dim)
            Tile("방향지시등", ui.turn.label(), "", Modifier.weight(1f), if (ui.turn != TurnSignal.OFF) Accent else Dim)
            Tile("남은 시간", ui.remainSec?.let { fmt("%d:%02d", it / 60, it % 60) } ?: "—", "", Modifier.weight(1f))
            Tile("제한속도", ui.limitKmh?.toString() ?: "—", "km/h", Modifier.weight(1f), if (overLimit) Warn else Fg)
        }

        Spacer(Modifier.height(24.dp))
        Text("채널", color = Dim, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Channel.entries.forEach { ch -> ChannelBadge(ch, ui.channels[ch], Modifier.weight(1f)) }
        }

        Spacer(Modifier.height(24.dp))
        Text("사운드 엔진", color = Dim, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::togglePlayback, enabled = ui.parked, modifier = Modifier.width(140.dp)) {
                Text(if (engine.playing) "정지" else "재생", fontSize = 18.sp)
            }
            Spacer(Modifier.width(24.dp))
            Text(
                text = if (engine.playing) fmt("마디 %4d", engine.bar + 1) else "마디    —",
                style = Mono, color = Fg, fontSize = 26.sp, modifier = Modifier.width(190.dp),
            )
            Text(engine.chord.ifEmpty { "—" }, style = Mono, color = Dim, fontSize = 22.sp, modifier = Modifier.width(100.dp))
            Text(
                text = engine.section?.let { it.label + if (engine.navBased) " · 내비" else " · 시간" } ?: "—",
                color = Accent, fontSize = 22.sp, modifier = Modifier.width(220.dp),
            )
            Text("부드러움", color = Dim, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (engine.playing) fmt("%3d", engine.smoothness) else "  —",
                style = Mono, color = Fg, fontSize = 26.sp, modifier = Modifier.width(70.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text("급제동", color = Dim, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (engine.playing) fmt("%2d", engine.hardBrakes) else " —",
                style = Mono, color = if (engine.hardBrakes > 0) Warn else Fg, fontSize = 26.sp,
                modifier = Modifier.width(50.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Layer.entries.forEach { layer ->
                LayerChip(layer.name, active = engine.playing && layer in engine.layers)
                Spacer(Modifier.width(10.dp))
            }
            Spacer(Modifier.width(14.dp))
            Flag("TAPE", engine.tapeStop, Warn)
            Flag("STOP", engine.stopHold, Accent)
            Flag("SETTLE", engine.settling, Accent)
            Flag("LIMIT", engine.overLimit, Warn)
            Flag("DUCK", engine.ducked, Accent)
            Flag("MUTE", engine.muted, Dim)
        }
    }
}

@Composable
private fun Tile(title: String, value: String, unit: String, modifier: Modifier, color: Color = Fg) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .height(104.dp),
    ) {
        Text(title, color = Dim, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Mono, color = color, fontSize = 44.sp, maxLines = 1)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(unit, color = Dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}

@Composable
private fun LayerChip(name: String, active: Boolean) {
    Box(
        Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Live.copy(alpha = 0.18f) else Card)
            .border(1.dp, if (active) Live else Card, RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, color = if (active) Live else Dim, fontSize = 15.sp, style = Mono)
    }
}

@Composable
private fun Flag(text: String, on: Boolean, color: Color) {
    Text(
        text,
        color = if (on) color else Card,
        fontSize = 15.sp,
        style = Mono,
        modifier = Modifier.width(110.dp),
    )
}

@Composable
private fun ChannelBadge(channel: Channel, state: ChannelState?, modifier: Modifier) {
    val color = when (state) {
        ChannelState.LIVE -> Live
        ChannelState.PENDING -> Accent
        ChannelState.UNSUPPORTED -> Dim
        ChannelState.FAILED -> Warn
        null -> Dim
    }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(channel.name, color = Fg, fontSize = 14.sp)
            Text(state?.name ?: "—", color = color, fontSize = 13.sp, style = Mono)
        }
    }
}

private fun Gear.label() = when (this) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.UNKNOWN -> "?"
}

private fun TurnSignal.label() = when (this) {
    TurnSignal.OFF -> "off"
    TurnSignal.LEFT -> "◀ L"
    TurnSignal.RIGHT -> "R ▶"
}

private fun fmt(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum")
private val Bg = Color(0xFF0E0F12)
private val Card = Color(0xFF1A1C21)
private val Fg = Color(0xFFECEDEF)
private val Dim = Color(0xFF8A8F98)
private val Accent = Color(0xFF7AA2FF)
private val Live = Color(0xFF5BD69B)
private val Warn = Color(0xFFFF7A6B)
