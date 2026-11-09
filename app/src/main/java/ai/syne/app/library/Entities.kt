package ai.syne.app.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 한 번의 주행 = 한 곡(트랙). 앨범 안에서 Vol.n */
@Entity(tableName = "trips", indices = [Index("albumId")])
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    /** REAL / REPLAY */
    val source: String,
    val genre: String,
    val songSec: Float,
    val bars: Int,
    /** 엔딩 4마디까지 마쳤는가 */
    val completed: Boolean,
    val noteCount: Int,
    val avgSmoothness: Float,
    val hardBrakes: Int,
    val maxSpeedKmh: Float,
    /** filesDir 기준 상대 경로 (m4a) */
    val highlightPath: String?,
    @ColumnInfo(defaultValue = "NULL") val albumId: Long? = null,
    @ColumnInfo(defaultValue = "0") val volume: Int = 0,
    /** 경로 총 거리(m). 내비가 없으면 출발·도착 직선거리 */
    @ColumnInfo(defaultValue = "0") val routeDistanceM: Int = 0,
    /** filesDir 기준 상대 경로 (png) */
    @ColumnInfo(defaultValue = "NULL") val coverPath: String? = null,
)

/** 1초 샘플 */
@Entity(
    tableName = "trip_samples",
    primaryKeys = ["tripId", "t"],
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TripSampleEntity(
    val tripId: Long,
    val t: Int,
    val speedKmh: Float,
    val steeringDeg: Float,
    val smoothness: Int,
    /** Layer.ordinal 비트마스크 */
    val layers: Int,
    val section: String,
)

/** 조향 멜로디 노트 로그 (P1 AI 완성곡의 입력) */
@Entity(
    tableName = "trip_notes",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    /** 곡 시작 기준 초 */
    val t: Float,
    /** MIDI 음높이 */
    val pitch: Int,
    /** 초 */
    val dur: Float,
)

/**
 * 출발·도착 지점 클러스터(반경 300m). **좌표는 앱 내부 전용** — 화면엔 name만, 서버로는 보내지 않는다.
 * name: 내비 목적지 이름 또는 행정동 주소(예: '분당구 삼평동').
 */
@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val name: String,
    val visits: Int,
)

/** 코스(출발 장소 → 도착 장소) 기준 앨범. 같은 코스의 주행이 Vol.1, Vol.2…로 쌓인다. */
@Entity(tableName = "albums", indices = [Index(value = ["courseKey"], unique = true)])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseKey: String,
    /** COURSE / TRAVEL */
    val kind: String,
    val name: String,
    val startPlaceId: Long?,
    val endPlaceId: Long?,
    val createdAt: Long,
)

/** 트랙 태그. 장르는 'genre:lofi' 형태 → 장르별 자동 플레이리스트 */
@Entity(
    tableName = "trip_tags",
    primaryKeys = ["tripId", "tag"],
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tag")],
)
data class TagEntity(val tripId: Long, val tag: String)

/** 보관함 그리드 한 칸 */
data class AlbumCard(
    val id: Long,
    val name: String,
    val kind: String,
    val trackCount: Int,
    val lastAt: Long?,
    val totalSec: Float?,
    val coverPath: String?,
)

/** 보관함 '최근 주행곡' 한 줄 */
data class TrackRow(
    val id: Long,
    val albumId: Long,
    val albumName: String,
    val volume: Int,
    val startedAt: Long,
    val genre: String,
    val smoothness: Float,
    val coverPath: String?,
    val songSec: Float,
    /** 사용자 제목(없으면 null) */
    val customTitle: String? = null,
    val favorite: Boolean = false,
)

/** 타임라인·거리 계산용 1초 기록 요약 */
data class SampleLiteRow(val tSec: Int, val speedKmh: Float, val section: String)

/**
 * 사용자가 직접 쓴 곡 정보(제목·설명·메모·태그·즐겨찾기) — 자동 주행 기록(trips)과 분리된 테이블.
 * 행이 없으면 '편집 안 함'. 곡을 지우면 함께 지워진다(CASCADE). 곡 ID·파일은 건드리지 않는다.
 */
@Entity(
    tableName = "track_edits",
    foreignKeys = [ForeignKey(TripEntity::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
)
data class TrackEditEntity(
    @PrimaryKey val tripId: Long,
    val customTitle: String?,
    val description: String?,
    val memo: String?,
    /** JSON 배열 문자열 */
    val tagsJson: String,
    val favorite: Boolean,
    val updatedAt: Long,
)
