package ai.syne.app.cover

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * 그날 경로의 '모양'(좌표 아님): 1초 기록의 속도 × 조향을 적분(자전거 모델)해 형태만 다시 만든다.
 * 실제 위경도를 쓰지 않으므로 커버·프롬프트에 위치 정보가 없다.
 *
 * @param points 정규화된 형태(x0,y0,x1,y1…, 0~1 상자 안, 가로세로 비율 유지), 호 길이 기준 등간격 [N]점
 * @param profile 출발→도착을 가로로 눕혔을 때 경로가 그 선에서 벗어난 정도(-1~1, [N]점) — 능선·지평선 실루엣에 쓴다
 */
class RouteShape(val points: FloatArray, val profile: FloatArray) {
    companion object {
        const val N = 48
        private const val WHEELBASE_M = 2.9
        private const val STEER_RATIO = 15.0

        /** 1초 간격 (속도 km/h, 조향 deg). 움직인 거리가 너무 짧으면 null */
        fun from(speedsKmh: List<Float>, steeringDeg: List<Float>): RouteShape? {
            if (speedsKmh.size < 5) return null
            var x = 0.0; var y = 0.0; var heading = Math.PI / 2
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            xs += x; ys += y
            for (i in speedsKmh.indices) {
                val v = speedsKmh[i] / 3.6
                if (v < 0.2) continue
                heading -= v / WHEELBASE_M * tan(Math.toRadians(steeringDeg.getOrElse(i) { 0f } / STEER_RATIO))
                x += v * cos(heading); y += v * sin(heading)
                xs += x; ys += y
            }
            val seg = DoubleArray(xs.size)
            for (i in 1 until xs.size) seg[i] = seg[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            val total = seg.last()
            if (total < 50.0) return null

            // 호 길이 등간격으로 N점
            val rx = DoubleArray(N); val ry = DoubleArray(N)
            var j = 0
            for (k in 0 until N) {
                val d = total * k / (N - 1)
                while (j < seg.size - 2 && seg[j + 1] < d) j++
                val t = if (seg[j + 1] > seg[j]) (d - seg[j]) / (seg[j + 1] - seg[j]) else 0.0
                rx[k] = xs[j] + (xs[j + 1] - xs[j]) * t
                ry[k] = ys[j] + (ys[j + 1] - ys[j]) * t
            }
            // 형태: 0~1 상자에 비율 유지로 맞춤(화면 위쪽이 +y가 되도록 뒤집음)
            val minX = rx.min(); val maxX = rx.max(); val minY = ry.min(); val maxY = ry.max()
            val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
            val ox = (span - (maxX - minX)) / 2; val oy = (span - (maxY - minY)) / 2
            val pts = FloatArray(N * 2)
            for (k in 0 until N) {
                pts[2 * k] = ((rx[k] - minX + ox) / span).toFloat()
                pts[2 * k + 1] = (1 - (ry[k] - minY + oy) / span).toFloat()
            }
            // 실루엣: 출발→도착 방향을 가로축으로 돌렸을 때의 수직 편차
            val dx = rx[N - 1] - rx[0]; val dy = ry[N - 1] - ry[0]
            val ang = if (hypot(dx, dy) > total * 0.05) atan2(dy, dx) else 0.0
            val ca = cos(-ang); val sa = sin(-ang)
            val dev = DoubleArray(N) { k -> (rx[k] - rx[0]) * sa + (ry[k] - ry[0]) * ca }
            val m = dev.maxOf { abs(it) }.coerceAtLeast(total * 0.02)
            val prof = FloatArray(N) { (dev[it] / m).toFloat().coerceIn(-1f, 1f) }
            return RouteShape(pts, prof)
        }
    }
}
