package ai.syne.app.ui.screens

import ai.syne.app.cover.ArtBrief
import ai.syne.app.cover.ArtDirections
import ai.syne.app.cover.ProceduralCoverGenerator
import ai.syne.core.design.NavHeader
import ai.syne.core.design.Syne
import ai.syne.core.design.SynePalette
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val MOOD_NAME = mapOf("lofi" to "Lo-fi", "citypop" to "City Pop", "ambient" to "Ambient", "hiphop" to "Hip-hop")

/**
 * 아트 랩(개발자 메뉴): 무드 4종 × 가상의 주행 6개로 기기 안 앨범 아트를 한 번에 그려 본다.
 * 같은 무드 안에서도 매체·구도·색이 달라지는지, 무드끼리는 구분되는지 확인하는 용도.
 */
@Composable
fun ArtLabScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(SynePalette.Bg).padding(horizontal = 48.dp, vertical = 32.dp)) {
        NavHeader("아트 랩 · 무드 4종 × 가상 주행 6개", onBack)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            ArtDirections.moods.forEach { mood ->
                Text(MOOD_NAME[mood] ?: mood, style = Syne.type.heading)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(6) { i -> Sample(mood, i, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 같은 무드의 앞 칸 매체를 recent로 넘겨 실제 저장 흐름(반복 방지)을 흉내 낸다 */
private fun recentFor(mood: String, i: Int): List<String> {
    val made = ArrayList<String>()
    for (k in 0 until i) {
        val start = 1_762_000_000_000L + k * 5L * 3_600_000L + mood.hashCode() * 1000L
        val pitches = List(48) { n -> 60 + ((n * (k + 3) + mood.length * 5) % 14) }
        made += ArtBrief.from(mood, start, 90f + k * 70f, 60f + k * 7f, k % 3, pitches, k, made.asReversed().take(3)).medium.id
    }
    return made.asReversed().take(3)
}

@Composable
private fun Sample(mood: String, i: Int, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, mood, i) {
        value = withContext(Dispatchers.Default) {
            // 가상의 주행: 시각·길이·부드러움·노트를 바꿔 가며
            val start = 1_762_000_000_000L + i * 5L * 3_600_000L + mood.hashCode() * 1000L
            val pitches = List(48) { k -> 60 + ((k * (i + 3) + mood.length * 5) % 14) }
            // 가상 경로: 곡선이 다른 주행 6가지(조향을 사인파로)
            val speeds = List(150) { 36f }
            val steer = List(150) { k -> (40f + i * 25f) * kotlin.math.sin(k / (12f + i * 6f)).toFloat() + (if (i % 2 == 0) 12f else -8f) }
            val route = ai.syne.app.cover.RouteShape.from(speeds, steer)
            val brief = ArtBrief.from(mood, start, 90f + i * 70f, 60f + i * 7f, i % 3, pitches, moodIndex = i, recent = recentFor(mood, i), route = route)
            ai.syne.app.cover.CoverSignature.apply(ProceduralCoverGenerator.generate(brief, 384), brief.route, i + 1).asImageBitmap()
        }
    }
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(Syne.dims.radiusS)).background(SynePalette.Surface)) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
