package ai.syne.app.library

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 코스 분류 규칙(순수 로직). */
object CourseLogic {
    const val CLUSTER_RADIUS_M = 300.0
    const val TRAVEL_MIN_M = 50_000

    const val KIND_COURSE = "COURSE"
    const val KIND_TRAVEL = "TRAVEL"
    const val KEY_UNKNOWN = "unknown"

    /** 대권 거리(m) */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** 반경 안에서 가장 가까운 장소. 없으면 null(새 장소) */
    fun nearestPlace(places: List<PlaceEntity>, lat: Double, lon: Double, radiusM: Double = CLUSTER_RADIUS_M): PlaceEntity? =
        places.map { it to distanceM(it.lat, it.lon, lat, lon) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }
            ?.first

    /** 방문 수 가중 평균으로 중심을 옮긴다(반복 주행에서 GPS 오차를 흡수) */
    fun merge(place: PlaceEntity, lat: Double, lon: Double): PlaceEntity {
        val n = place.visits
        return place.copy(
            lat = (place.lat * n + lat) / (n + 1),
            lon = (place.lon * n + lon) / (n + 1),
            visits = n + 1,
        )
    }

    /** 경로 거리(없으면 직선거리)가 50km 이상이면 여행 앨범 */
    fun isTravel(routeDistanceM: Int?, straightM: Double): Boolean =
        (routeDistanceM?.takeIf { it > 0 }?.toDouble() ?: straightM) >= TRAVEL_MIN_M

    /** 방향 있는 코스: 집→회사와 회사→집은 다른 앨범 */
    fun courseKey(kind: String, startPlaceId: Long, endPlaceId: Long): String =
        "${if (kind == KIND_TRAVEL) "travel" else "course"}:$startPlaceId>$endPlaceId"

    fun albumName(kind: String, startName: String, endName: String): String = when {
        kind == KIND_TRAVEL -> "여행 · $endName"
        startName == endName -> "$startName 한 바퀴"
        else -> "$startName → $endName"
    }
}
