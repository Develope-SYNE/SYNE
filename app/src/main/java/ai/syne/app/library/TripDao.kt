package ai.syne.app.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    // ---- 쓰기 ----
    @Insert suspend fun insertTrip(trip: TripEntity): Long
    @Insert suspend fun insertSamples(rows: List<TripSampleEntity>)
    @Insert suspend fun insertNotes(rows: List<NoteEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTags(rows: List<TagEntity>)
    @Insert suspend fun insertPlace(place: PlaceEntity): Long
    @Update suspend fun updatePlace(place: PlaceEntity)
    @Insert suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("UPDATE trips SET highlightPath = :path WHERE id = :id") suspend fun updateHighlight(id: Long, path: String)
    @Query("UPDATE trips SET coverPath = :path WHERE id = :id") suspend fun updateCover(id: Long, path: String)

    @Query("SELECT t AS tSec, speedKmh, section FROM trip_samples WHERE tripId = :id ORDER BY t") suspend fun samplesLite(id: Long): List<SampleLiteRow>
    @Query("SELECT * FROM trips WHERE albumId IS NOT NULL ORDER BY startedAt") suspend fun allTrips(): List<TripEntity>
    @Query("SELECT tag FROM trip_tags WHERE tripId = :id") suspend fun tags(id: Long): List<String>
    /** 같은 무드에서 최근에 쓴 앨범 아트 매체(반복 방지용) */
    @Query("SELECT g.tag FROM trip_tags g JOIN trips t ON t.id = g.tripId WHERE t.genre = :genre AND g.tag LIKE 'art2:%' ORDER BY t.startedAt DESC LIMIT :n")
    suspend fun recentArtTags(genre: String, n: Int): List<String>

    /** 같은 무드에서 이 시각 이전에 만든 곡 수(앨범 아트 매체 순환용) */
    @Query("SELECT COUNT(*) FROM trips WHERE genre = :genre AND startedAt < :before") suspend fun countGenreBefore(genre: String, before: Long): Int

    /** 현재 버전(art2)의 무드별 앨범 아트가 아직 없는 곡(예전 경로 커버 포함) */
    @Query("SELECT * FROM trips WHERE id NOT IN (SELECT tripId FROM trip_tags WHERE tag LIKE 'art2:%') ORDER BY startedAt")
    suspend fun tripsWithoutArt(): List<TripEntity>

    // ---- 코스 분류 ----
    @Query("SELECT * FROM places") suspend fun places(): List<PlaceEntity>
    @Query("SELECT * FROM places WHERE id = :id") suspend fun place(id: Long): PlaceEntity?
    @Query("SELECT * FROM albums WHERE courseKey = :key") suspend fun albumByKey(key: String): AlbumEntity?
    @Query("SELECT COUNT(*) FROM trips WHERE albumId = :albumId") suspend fun tripCountInAlbum(albumId: Long): Int

    // ---- 요약 ----
    @Query("SELECT * FROM trips WHERE id = :id") suspend fun trip(id: Long): TripEntity?
    @Query("SELECT * FROM albums WHERE id = :id") suspend fun album(id: Long): AlbumEntity?
    @Query("SELECT COUNT(*) FROM trip_samples WHERE tripId = :id") suspend fun sampleCount(id: Long): Int
    @Query("SELECT COUNT(*) FROM trip_notes WHERE tripId = :id") suspend fun noteCount(id: Long): Int

    // ---- 화면 (Flow) ----
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.kind AS kind, COUNT(t.id) AS trackCount, MAX(t.startedAt) AS lastAt,
               SUM(t.songSec) AS totalSec,
               (SELECT coverPath FROM trips WHERE albumId = a.id ORDER BY startedAt DESC LIMIT 1) AS coverPath
        FROM albums a LEFT JOIN trips t ON t.albumId = a.id
        GROUP BY a.id HAVING trackCount > 0
        ORDER BY lastAt DESC
        """,
    )
    fun albumCards(): Flow<List<AlbumCard>>

    @Query("SELECT * FROM albums WHERE id = :id") fun albumFlow(id: Long): Flow<AlbumEntity?>

    /** 최근 주행곡 목록(앨범 이름 포함). genre가 null이면 전체 */
    @Query(
        """
        SELECT t.id AS id, t.albumId AS albumId, a.name AS albumName, t.volume AS volume, t.startedAt AS startedAt,
               t.genre AS genre, t.avgSmoothness AS smoothness, t.coverPath AS coverPath, t.songSec AS songSec,
               e.customTitle AS customTitle, COALESCE(e.favorite, 0) AS favorite
        FROM trips t JOIN albums a ON a.id = t.albumId
        LEFT JOIN track_edits e ON e.tripId = t.id
        WHERE (:genre IS NULL OR t.genre = :genre) AND (:favOnly = 0 OR e.favorite = 1)
        ORDER BY t.startedAt DESC
        """,
    )
    fun trackRows(genre: String?, favOnly: Boolean): Flow<List<TrackRow>>

    // ---- 사용자 편집 ----
    @Query("SELECT * FROM track_edits WHERE tripId = :id") fun editFlow(id: Long): Flow<TrackEditEntity?>
    @Query("SELECT * FROM track_edits WHERE tripId = :id") suspend fun edit(id: Long): TrackEditEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEdit(e: TrackEditEntity)

    @Query("SELECT pitch FROM trip_notes WHERE tripId = :id ORDER BY t") suspend fun notePitches(id: Long): List<Int>
    @Query("SELECT layers FROM trip_samples WHERE tripId = :id") suspend fun layerMasks(id: Long): List<Int>
    @Query("SELECT * FROM trips WHERE albumId = :albumId ORDER BY volume DESC") fun tracksInAlbum(albumId: Long): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips WHERE id = :id") fun tripFlow(id: Long): Flow<TripEntity?>

    /** 장르별 자동 플레이리스트: tag = 'genre:lofi' */
    @Query(
        """
        SELECT t.* FROM trips t JOIN trip_tags g ON g.tripId = t.id
        WHERE g.tag = :tag AND t.albumId IS NOT NULL
        ORDER BY t.startedAt DESC
        """,
    )
    fun playlistByTag(tag: String): Flow<List<TripEntity>>

    @Query("SELECT COUNT(*) FROM trip_tags g JOIN trips t ON t.id = g.tripId WHERE g.tag = :tag AND t.albumId IS NOT NULL")
    fun countByTag(tag: String): Flow<Int>
}
