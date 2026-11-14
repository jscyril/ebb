package com.jscyril.ebb.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

enum class Kind { SESSION, BLOCKED_ATTEMPT, EMERGENCY, FOREGROUND }

@Entity(tableName = "usage_event")
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val start: Long,
    val end: Long,
    val plannedMin: Int,
    val kind: Kind,
)

@Entity(tableName = "day_aggregate", primaryKeys = ["dayKey", "pkg"])
data class DayAggregate(val dayKey: Int, val pkg: String, val seconds: Long, val opens: Int, val blocked: Int)

data class Interval(val start: Long, val end: Long)

@Dao
abstract class UsageDao {
    @Insert abstract suspend fun insert(e: UsageEvent)

    @Query("INSERT OR IGNORE INTO day_aggregate (dayKey, pkg, seconds, opens, blocked) VALUES (:dayKey, :pkg, 0, 0, 0)")
    abstract suspend fun ensureRow(dayKey: Int, pkg: String)

    @Query("UPDATE day_aggregate SET seconds = seconds + :s, opens = opens + :o, blocked = blocked + :b WHERE dayKey = :dayKey AND pkg = :pkg")
    abstract suspend fun bump(dayKey: Int, pkg: String, s: Long, o: Int, b: Int)

    /** Event insert and all aggregate bumps commit together. */
    @Transaction
    open suspend fun log(e: UsageEvent, bumps: List<Bump>) {
        insert(e)
        for (b in bumps) { ensureRow(b.dayKey, e.pkg); bump(b.dayKey, e.pkg, b.seconds, b.opens, b.blocked) }
    }

    @Query("SELECT dayKey, SUM(seconds) AS seconds, SUM(opens) AS opens, SUM(blocked) AS blocked FROM day_aggregate WHERE dayKey >= :from GROUP BY dayKey")
    abstract fun totals(from: Int): Flow<List<DayTotal>>

    @Query("SELECT pkg, seconds, opens, blocked FROM day_aggregate WHERE dayKey = :dayKey ORDER BY seconds DESC")
    abstract fun perApp(dayKey: Int): Flow<List<AppTotal>>

    @Query("SELECT start, `end` AS `end` FROM usage_event WHERE kind = 'FOREGROUND' AND `end` > :since")
    abstract fun foreground(since: Long): Flow<List<Interval>>

    @Query("DELETE FROM usage_event WHERE `end` < :before")
    abstract suspend fun deleteEventsBefore(before: Long)
}

@Database(entities = [UsageEvent::class, DayAggregate::class], version = 1, exportSchema = true)
abstract class EbbDb : RoomDatabase() {
    abstract fun usage(): UsageDao
}
