package ai.syne.core.art

import ai.syne.core.design.ArtLight as Syne
import android.graphics.BlendMode
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 한 곡에서 기록된(또는 지금 연주 중인) 음악 데이터. DB(trackWave)와 엔진 상태에서 만든다. */
data class WaveData(
    /** 활성 레이어 수(1~6) */
    val lines: Int,
    /** 부드러움(0~100) */
    val smoothness: Int,
    /** 최근 멜로디 노트(MIDI) */
    val notes: List<Int>,
) {
    companion object {
        val Calm = WaveData(4, 90, listOf(64, 67, 62, 69, 64, 60, 67, 64))
    }
}

/**
 * SYNE 시그니처 오브젝트의 형태를 정하는 값. 모두 0~1(또는 -1~1)로 정규화되어 있다.
 * 속도 자체는 들어오지 않는다(원칙 2): 에너지는 '편성'에서 오고, 편성은 제한속도에서 이미 막힌다.
 */
data class OrbitParams(
    /** 궤도 고리 수(3~7) = 활성 레이어 + 1 */
    val rings: Int = 5,
    /** 팽창·밝기(0~1) = 편성 풍성함 */
    val energy: Float = 0.6f,
    /** 하모니(0~1) = 부드러움. 높을수록 고리가 한 결로 정렬되고 금빛이 늘어난다 */
    val harmony: Float = 0.9f,
    /** 고리의 굴곡 = 멜로디 노트(MIDI) */
    val notes: List<Int> = WaveData.Calm.notes,
    /** 조향(-1 왼쪽 ~ 1 오른쪽) → 궤도면 기울기 */
    val steer: Float = 0f,
    /** 급제동 직후 흐트러짐(0~1) */
    val disruption: Float = 0f,
    /** 방향지시등(-1 왼쪽, 0, 1 오른쪽) → 그쪽 궤도가 밝아짐 */
    val pulse: Int = 0,
    /** 제한속도 초과 → 빛이 바래고 탁해짐(곡이 낡는다) */
    val worn: Boolean = false,
    /** 도착 완성도(0~1) → 고리가 하나의 결로 모이고 중심이 밝아진다 */
    val resolve: Float = 0f,
    /** 같은 곡은 같은 작품 */
    val seed: Int = 7,
) {
    companion object {
        fun from(w: WaveData, seed: Int = 7, resolve: Float = 0f, disruption: Float = 0f) = OrbitParams(
            rings = (w.lines + 1).coerceIn(3, 7),
            energy = ((w.lines - 1) / 5f).coerceIn(0f, 1f),
            harmony = (w.smoothness / 100f).coerceIn(0f, 1f),
            notes = w.notes,
            resolve = resolve,
            disruption = disruption,
            seed = seed,
        )
    }
}

/**
 * SYNE 시그니처 오브젝트 — 빛나는 3차원 궤도 고리들이 하나의 유기적인 형태를 이룬다.
 *
 * 3D 점(고리마다 기울기·비틀림·멜로디 굴곡)을 원근 투영하고, 가산 합성(PLUS)으로 빛 번짐을 쌓는다.
 * 앞쪽(카메라에 가까운) 선은 더 굵고 밝게, 뒤쪽은 가늘고 어둡게 그려 깊이를 만든다.
 *
 * - animated=false: 정지 그래픽(주행 화면). 값이 바뀔 때만 다시 그린다 — 움직임·전환 효과 없음(원칙 1).
 * - animated=true: 정차 화면에서 천천히 돈다(80초에 한 바퀴, 20fps — 느린 회전이라 충분하고 GPU 부담이 적다).
 *
 * @param center 그릴 위치(0~1, 컴포저블 크기 기준)
 * @param scale 1 = 짧은 변의 36% 반지름
 */
@Composable
fun SignatureObject(
    params: OrbitParams,
    animated: Boolean,
    modifier: Modifier = Modifier,
    centerX: Float = 0.5f,
    centerY: Float = 0.5f,
    scale: Float = 1f,
) {
    val time = if (animated) {
        val t by produceState(0f) {
            val start = System.nanoTime()
            while (true) {
                value = ((System.nanoTime() - start) / 1e9).toFloat()
                delay(50)
            }
        }
        t
    } else STILL_TIME
    val renderer = remember { OrbitRenderer() }
    val geo = remember(params) { OrbitGeometry(params) }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        renderer.draw(this, geo, time, centerX, centerY, scale)
    }
}

/** 정지 그래픽의 '시간': 빛꼬리가 고르게 퍼져 보이는 순간 */
private const val STILL_TIME = 11.5f

private const val TWO_PI = (2 * PI).toFloat()

/** 파라미터에서 미리 정해 두는 고리별 형태(매 프레임 다시 계산하지 않는다) */
private class OrbitGeometry(val p: OrbitParams) {
    val n = p.rings.coerceIn(3, 7)
    private val rnd = Random(p.seed)
    private val calm = 1f - p.resolve * 0.75f
    private val spread = ((1f - p.harmony) * 0.55f + p.disruption * 0.7f) * calm

    // 멜로디 노트 → 하모닉 계수(굴곡의 모양). 이산 푸리에의 k=1..4 성분
    private val notes = (if (p.notes.size >= 4) p.notes.takeLast(12) else WaveData.Calm.notes)
        .map { ((it - 66f) / 10f).coerceIn(-1f, 1f) }
    val harmAmp = FloatArray(4)
    val harmPhase = FloatArray(4)

    init {
        for (k in 0 until 4) {
            var c = 0f; var s = 0f
            notes.forEachIndexed { j, v ->
                val a = TWO_PI * (k + 1) * j / notes.size
                c += v * cos(a); s += v * sin(a)
            }
            harmAmp[k] = (hypot(c, s) / notes.size).coerceAtMost(0.6f)
            harmPhase[k] = atan2(s, c)
        }
    }

    val radius = FloatArray(n)
    val tiltX = FloatArray(n)
    val tiltY = FloatArray(n)
    val yaw = FloatArray(n)
    val offX = FloatArray(n)
    val offY = FloatArray(n)
    val zRipple = FloatArray(n)
    val zPhase = FloatArray(n)
    val warmAt = FloatArray(n)
    val headAt = FloatArray(n)
    val headSpeed = FloatArray(n)
    val deform = 0.05f + 0.09f * (1f - p.harmony) * calm + 0.16f * p.disruption

    init {
        for (i in 0 until n) {
            val t = i / (n - 1f)
            radius[i] = (0.40f + 0.60f * t) * (0.86f + 0.16f * p.energy)
            // ORBE처럼 대부분 비슷한 면에 포개지되, 하모니가 낮으면(또는 급제동) 면이 흩어진다
            tiltX[i] = 1.12f + (t - 0.5f) * 0.28f + spread * (rnd.nextFloat() * 2 - 1)
            tiltY[i] = (rnd.nextFloat() * 2 - 1) * (0.18f + spread)
            yaw[i] = -0.5f + i * 0.62f + rnd.nextFloat() * 0.4f
            offX[i] = (t - 0.5f) * 0.10f * calm
            offY[i] = (0.5f - t) * 0.05f * calm
            zRipple[i] = 0.06f + 0.10f * rnd.nextFloat() * calm
            zPhase[i] = rnd.nextFloat() * TWO_PI
            warmAt[i] = PI.toFloat() * (0.85f + 0.3f * rnd.nextFloat()) // 왼쪽이 따뜻한 빛(주황·금)
            headAt[i] = rnd.nextFloat() * TWO_PI
            headSpeed[i] = (0.10f + 0.10f * rnd.nextFloat()) * (if (i % 2 == 0) 1 else -1)
        }
    }

    /** 금빛 비율: 부드러울수록, 도착할수록 늘어난다 */
    val warmShare = (0.30f + 0.55f * p.harmony + 0.2f * p.resolve).coerceAtMost(1f)
}

/**
 * 버퍼를 재사용하는 그리기 담당. 선분을 (색 단계 × 밝기 단계 × 굵기 단계) 버킷으로 모아
 * 버킷마다 drawLines 한 번으로 그린다 — 수천 개 선분을 수십 번의 호출로.
 */
private class OrbitRenderer {
    private val segs = 180
    private val strands = 4
    private val hueSteps = 14
    private val lumSteps = 9
    private val widthSteps = 3
    private val buckets = hueSteps * lumSteps * widthSteps
    private val maxSegs = 7 * strands * segs
    private val raw = FloatArray(maxSegs * 4)
    private val keyOf = IntArray(maxSegs)
    private val sorted = FloatArray(maxSegs * 4)
    private val counts = IntArray(buckets)
    private val starts = IntArray(buckets)
    private val px = FloatArray(segs + 1)
    private val py = FloatArray(segs + 1)
    private val pz = FloatArray(segs + 1)
    private val sparkPts = FloatArray(160 * 2)

    // 빛 번짐(넓은 획)은 짧은 선분을 겹쳐 그리면 이음매에 빗금이 생긴다 → 리본 중심선을 조각 경로로 이어 그린다
    private val chunkSegs = 4
    private val maxChunks = 7 * (segs / chunkSegs + 1)
    private val chunkPaths = Array(maxChunks) { android.graphics.Path() }
    private val chunkHue = FloatArray(maxChunks)
    private val chunkLum = FloatArray(maxChunks)
    private val chunkDepth = FloatArray(maxChunks)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        if (Build.VERSION.SDK_INT >= 29) blendMode = BlendMode.PLUS else xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    fun draw(scope: DrawScope, g: OrbitGeometry, time: Float, centerX: Float, centerY: Float, scale: Float) = with(scope) {
        val p = g.p
        val w = size.width
        val h = size.height
        val cx = w * centerX
        val cy = h * centerY
        val R = min(w, h) * 0.36f * scale
        val focal = 3.4f
        val unit = R / 360f // 선 굵기 기준

        // 전체 회전: 천천히 돌고(애니메이션), 조향이 궤도면을 기울인다
        val gYaw = time * TWO_PI / 80f + p.steer * 0.55f
        val gRoll = -0.22f + p.steer * 0.18f
        val breathe = 1f + 0.015f * sin(time * TWO_PI / 9f)

        // 색 팔레트: 차가운 쪽(파랑→시안) ↔ 따뜻한 쪽(주황→금)
        val wornMix = if (p.worn) 0.55f else 0f
        val pal = IntArray(hueSteps) { q ->
            val t = q / (hueSteps - 1f)
            val c = if (t < 0.5f) lerp(Syne.Blue, Syne.Blue2, t * 2f) else lerp(Syne.Orange, Syne.Yellow, (t - 0.5f) * 2f)
            lerp(c, Color(0xFF8A8F99), wornMix).toArgb() and 0x00FFFFFF
        }
        val lumGain = (if (p.worn) 0.55f else 1f) * (0.75f + 0.25f * p.energy)

        // ---- 1) 배경 빛 무리(atmospheric glow) ----
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val haloCool = lerp(Syne.Blue, Color(0xFF8A8F99), wornMix)
            val haloWarm = lerp(Syne.Orange, Color(0xFF8A8F99), wornMix)
            fill.shader = RadialGradient(
                cx - R * 0.25f, cy, R * 1.25f,
                intArrayOf(haloWarm.copy(alpha = 0.07f * g.warmShare).toArgb(), 0), null, Shader.TileMode.CLAMP,
            )
            nc.drawCircle(cx - R * 0.25f, cy, R * 1.25f, fill)
            fill.shader = RadialGradient(
                cx + R * 0.25f, cy, R * 1.35f,
                intArrayOf(haloCool.copy(alpha = 0.10f).toArgb(), 0), null, Shader.TileMode.CLAMP,
            )
            nc.drawCircle(cx + R * 0.25f, cy, R * 1.35f, fill)
            // 도착할수록 밝아지는 중심
            val core = 0.03f + 0.07f * p.resolve
            val coreColor = lerp(Color(0xFF9DB8FF), Syne.Yellow, 0.35f * p.resolve)
            fill.shader = RadialGradient(
                cx, cy, R * 0.38f,
                intArrayOf(coreColor.copy(alpha = core * lumGain).toArgb(), 0), null, Shader.TileMode.CLAMP,
            )
            nc.drawCircle(cx, cy, R * 0.38f, fill)
            fill.shader = null
        }

        // ---- 2) 고리 선분을 버킷으로 모으기 ----
        var count = 0
        var sparks = 0
        var chunks = 0
        val sparkRnd = Random(p.seed * 31 + 5)
        for (i in 0 until g.n) {
            val ri = g.radius[i] * breathe
            val cTx = cos(g.tiltX[i]); val sTx = sin(g.tiltX[i])
            val cTy = cos(g.tiltY[i]); val sTy = sin(g.tiltY[i])
            val yaw = g.yaw[i] + time * 0.03f * (if (i % 2 == 0) 1 else -1)
            val cYw = cos(yaw); val sYw = sin(yaw)
            val cGy = cos(gYaw); val sGy = sin(gYaw)
            val cGr = cos(gRoll); val sGr = sin(gRoll)
            val head = g.headAt[i] + time * g.headSpeed[i] * TWO_PI / 6f

            for (s in 0 until strands) {
                val ds = (s - (strands - 1) / 2f) * (0.0075f + 0.012f * (1f - p.harmony) + 0.03f * p.disruption)
                val strandPhase = s * 0.35f
                for (k in 0..segs) {
                    val th = k * TWO_PI / segs
                    // 멜로디 굴곡(하모닉 합) — 소리 파형 같은 유기적 변형
                    var d = 0f
                    for (m in 0 until 4) d += g.harmAmp[m] * sin((m + 2) * th + g.harmPhase[m] + strandPhase * 0.2f + time * 0.04f * (m + 1))
                    val r = ri * (1f + g.deform * d + ds)
                    // 고리 자체 평면 좌표 + 살짝 출렁이는 z
                    var x = r * cos(th)
                    var y = r * sin(th)
                    var z = ri * g.zRipple[i] * sin(2 * th + g.zPhase[i] + strandPhase)
                    // 고리 회전: 평면 안(yaw) → 기울기 X → 기울기 Y
                    var t1 = x * cYw - y * sYw; y = x * sYw + y * cYw; x = t1
                    t1 = y * cTx - z * sTx; z = y * sTx + z * cTx; y = t1
                    t1 = x * cTy + z * sTy; z = -x * sTy + z * cTy; x = t1
                    x += g.offX[i]; y += g.offY[i]
                    // 전체: 롤(Z) → 요(Y)
                    t1 = x * cGr - y * sGr; y = x * sGr + y * cGr; x = t1
                    t1 = x * cGy + z * sGy; z = -x * sGy + z * cGy; x = t1
                    val persp = focal / (focal + z)
                    px[k] = cx + x * R * persp
                    py[k] = cy + y * R * persp
                    pz[k] = z
                }
                for (k in 0 until segs) {
                    val th = (k + 0.5f) * TWO_PI / segs
                    // 색: 왼쪽(warmAt) 따뜻한 빛, 반대편 차가운 빛
                    val warm = (0.5f + 0.5f * cos(th - g.warmAt[i])).pow(1.6f) * g.warmShare
                    val coolT = 0.5f * (0.5f + 0.5f * sin(th * 2 + i))
                    val hueT = if (warm > 0.35f) 0.5f + 0.5f * ((warm - 0.35f) / 0.65f).coerceIn(0f, 1f) else coolT
                    // 빛꼬리: 머리(head)에서 가장 밝고 뒤로 갈수록 옅어진다
                    val since = (((head - th) % TWO_PI) + TWO_PI) % TWO_PI / TWO_PI
                    var lum = 0.22f + 0.78f * (1f - since).pow(2.4f)
                    // 깊이: 앞쪽 밝게
                    val z = (pz[k] + pz[k + 1]) * 0.5f
                    val depth = ((0.9f - z) / 1.8f).coerceIn(0f, 1f)
                    lum *= 0.45f + 0.75f * depth
                    if (s == 1) lum *= 1.25f
                    // 방향지시등: 그쪽 반쪽이 은은하게 밝아짐
                    if (p.pulse != 0) {
                        val side = ((px[k] - cx) / R) * p.pulse
                        if (side > 0f) lum *= 1f + 0.7f * side.coerceAtMost(1f)
                    }
                    // 급제동: 선이 끊겨 흩어진다
                    if (p.disruption > 0f) {
                        val cut = sin(th * 23f + i * 1.7f + s)
                        if (cut > 1f - p.disruption * 0.9f) lum *= 0.08f
                    }
                    lum *= lumGain
                    val q = (hueT * (hueSteps - 1)).roundToInt().coerceIn(0, hueSteps - 1)
                    val l = (lum * lumSteps).roundToInt().coerceIn(0, lumSteps - 1)
                    val wd = (depth * widthSteps).toInt().coerceIn(0, widthSteps - 1)
                    val o = count * 4
                    raw[o] = px[k]; raw[o + 1] = py[k]; raw[o + 2] = px[k + 1]; raw[o + 3] = py[k + 1]
                    keyOf[count] = (q * lumSteps + l) * widthSteps + wd
                    count++
                    // 빛 번짐용 조각 경로(중심 가닥만)
                    if (s == 1) {
                        if (k % chunkSegs == 0) {
                            if (chunks < maxChunks) chunks++
                            val path = chunkPaths[chunks - 1]
                            path.rewind()
                            path.moveTo(px[k], py[k])
                            chunkLum[chunks - 1] = 0f
                            chunkHue[chunks - 1] = 0f
                            chunkDepth[chunks - 1] = 0f
                        }
                        val ci = chunks - 1
                        chunkPaths[ci].lineTo(px[k + 1], py[k + 1])
                        chunkLum[ci] += lum / chunkSegs
                        chunkDepth[ci] += depth / chunkSegs
                        chunkHue[ci] += hueT / chunkSegs
                    }
                    // 반짝임: 빛꼬리 머리 근처에 작은 입자
                    if (s == 1 && since < 0.12f && sparks < 160 && sparkRnd.nextFloat() < 0.5f) {
                        sparkPts[sparks * 2] = px[k] + (sparkRnd.nextFloat() - 0.5f) * 18f * unit
                        sparkPts[sparks * 2 + 1] = py[k] + (sparkRnd.nextFloat() - 0.5f) * 18f * unit
                        sparks++
                    }
                }
            }
        }

        // 버킷 정렬(계수 정렬)
        counts.fill(0)
        for (c in 0 until count) counts[keyOf[c]]++
        var acc = 0
        for (b in 0 until buckets) { starts[b] = acc; acc += counts[b] }
        val fillPos = starts.copyOf()
        for (c in 0 until count) {
            val dst = fillPos[keyOf[c]]++ * 4
            val src = c * 4
            sorted[dst] = raw[src]; sorted[dst + 1] = raw[src + 1]; sorted[dst + 2] = raw[src + 2]; sorted[dst + 3] = raw[src + 3]
        }

        // ---- 3) 세 겹으로 그리기: 넓은 빛 번짐 → 중간 → 가는 심선 ----
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // 넓은 빛 번짐 두 겹(리본 중심선 조각 경로)
            val glowPasses = arrayOf(floatArrayOf(64f, 0.05f), floatArrayOf(20f, 0.16f))
            for (pass in glowPasses) {
                for (c in 0 until chunks) {
                    val a = (pass[1] * chunkLum[c].coerceAtMost(1f) * 255f).roundToInt().coerceIn(0, 255)
                    if (a == 0) continue
                    glowPaint.strokeWidth = pass[0] * unit * (0.55f + 0.6f * chunkDepth[c])
                    glowPaint.color = (a shl 24) or (lerp(colorAt(chunkHue[c]), Color(0xFF8A8F99), wornMix).toArgb() and 0x00FFFFFF)
                    nc.drawPath(chunkPaths[c], glowPaint)
                }
            }
            // 가는 빛줄기(모든 가닥): 살짝 번진 선 + 심선
            val passes = arrayOf(
                floatArrayOf(6f, 0.10f),
                floatArrayOf(2.4f, 0.75f),
            )
            for (pass in passes) {
                for (b in 0 until buckets) {
                    val n = counts[b]
                    if (n == 0) continue
                    val wd = b % widthSteps
                    val l = (b / widthSteps) % lumSteps
                    val q = b / (widthSteps * lumSteps)
                    val lum = (l + 0.5f) / lumSteps
                    val widthMul = 0.55f + 0.45f * wd
                    paint.strokeWidth = pass[0] * unit * widthMul
                    val a = (pass[1] * lum * 255f).roundToInt().coerceIn(0, 255)
                    if (a == 0) continue
                    paint.color = (a shl 24) or pal[q]
                    nc.drawLines(sorted, starts[b] * 4, n * 4, paint)
                }
            }
            // 가장 밝은 빛꼬리는 심이 하얗게 타오른다
            val hot = 0x00FFF6E8
            for (b in 0 until buckets) {
                val n = counts[b]
                if (n == 0) continue
                val l = (b / widthSteps) % lumSteps
                if (l < lumSteps - 3) continue
                val wd = b % widthSteps
                paint.strokeWidth = 1.2f * unit * (0.55f + 0.45f * wd)
                paint.color = (((l - (lumSteps - 4)) * 0.16f * 255f).roundToInt().coerceIn(0, 255) shl 24) or hot
                nc.drawLines(sorted, starts[b] * 4, n * 4, paint)
            }
            // 반짝임
            if (sparks > 0) {
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 3.2f * unit
                paint.color = (((0.7f * lumGain) * 255).toInt().coerceIn(0, 255) shl 24) or (Color(0xFFFFF3D6).toArgb() and 0x00FFFFFF)
                nc.drawPoints(sparkPts, 0, sparks * 2, paint)
                paint.strokeCap = Paint.Cap.BUTT
            }
        }
    }
}

/** 빛의 색(연속): 0~0.5 파랑→시안, 0.5~1 주황→금 */
private fun colorAt(t: Float): Color =
    if (t < 0.5f) lerp(Syne.Blue, Syne.Blue2, t * 2f) else lerp(Syne.Orange, Syne.Yellow, (t - 0.5f) * 2f)

private fun lerp(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * u, a.green + (b.green - a.green) * u, a.blue + (b.blue - a.blue) * u, 1f)
}

/** 부드러운 감쇠(급제동 흐트러짐이 다음 스냅샷에서 풀릴 때 사용) */
fun decay(secondsSince: Float, halfLife: Float): Float = exp(-secondsSince * 0.693f / halfLife).coerceIn(0f, 1f)
