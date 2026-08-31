package com.example.util.simpletimetracker.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ActivityRow(
    val id: Long,
    val name: String,
    val startedAt: Long?,
)

data class HistoryRow(
    val id: Long,
    val activityName: String,
    val startedAt: Long,
    val endedAt: Long,
)

class DesktopDatabase(
    databasePath: Path? = null,
) : DesktopTimerRepository {

    val path: Path

    init {
        Class.forName("org.sqlite.JDBC")

        path = databasePath ?: run {
            val dataHome = System.getenv("XDG_DATA_HOME")
                ?.takeIf { it.isNotBlank() }
                ?.let(Paths::get)
                ?: Paths.get(System.getProperty("user.home"), ".local", "share")
            dataHome.resolve("simple-time-tracker").resolve("tracker.sqlite3")
        }
        Files.createDirectories(path.parent)

        connection().use { db ->
            db.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA busy_timeout=5000")
            }
            DesktopDatabaseSchema.initialize(db)

            db.prepareStatement(
                "SELECT COUNT(*) FROM desktop_id_allocator WHERE id = 1",
            ).use { query ->
                query.executeQuery().use { result ->
                    result.next()
                    if (result.getInt(1) == 0) {
                        val namespace = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
                        db.prepareStatement(
                            "INSERT INTO desktop_id_allocator(id, namespace, next_counter) VALUES(1, ?, 1)",
                        ).use { insert ->
                            insert.setLong(1, namespace.toLong())
                            insert.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    private fun connection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
    }

    internal fun nextId(db: Connection): Long {
        val values = db.prepareStatement(
            "SELECT namespace, next_counter FROM desktop_id_allocator WHERE id = 1",
        ).use { query ->
            query.executeQuery().use { result ->
                check(result.next())
                result.getLong("namespace") to result.getLong("next_counter")
            }
        }

        val namespace = values.first
        val counter = values.second
        check(counter in 1..0xffffffffL) { "ID counter exhausted" }

        db.prepareStatement(
            "UPDATE desktop_id_allocator SET next_counter = ? WHERE id = 1",
        ).use { update ->
            update.setLong(1, counter + 1)
            update.executeUpdate()
        }

        return (namespace shl 32) or counter
    }

    fun addActivity(name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return

        connection().use { db ->
            db.autoCommit = false
            try {
                val id = nextId(db)
                db.prepareStatement(
                    """
                    INSERT INTO recordTypes(
                        id, name, icon, color, color_int, hidden,
                        instant, instantDuration, note
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { insert ->
                    insert.setLong(1, id)
                    insert.setString(2, cleanName)
                    insert.setString(3, "")
                    insert.setInt(4, 0)
                    insert.setString(5, "")
                    insert.setInt(6, 0)
                    insert.setInt(7, 0)
                    insert.setLong(8, 0)
                    insert.setString(9, "")
                    insert.executeUpdate()
                }
                db.commit()
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun activities(): List<ActivityRow> {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT
                    rt.id,
                    rt.name,
                    rr.time_started
                FROM recordTypes rt
                LEFT JOIN runningRecords rr ON rr.id = rt.id
                WHERE rt.hidden = 0
                ORDER BY rt.name COLLATE NOCASE
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            val rawStartedAt = result.getLong("time_started")
                            val startedAt = if (result.wasNull()) null else rawStartedAt
                            add(
                                ActivityRow(
                                    id = result.getLong("id"),
                                    name = result.getString("name"),
                                    startedAt = startedAt,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun runningRecords(): List<DesktopRunningRecord> {
        return connection().use { db ->
            db.prepareStatement(
                "SELECT id, time_started, comment, tag_id FROM runningRecords ORDER BY time_started, id",
            ).use { query ->
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                DesktopRunningRecord(
                                    activityId = result.getLong("id"),
                                    startedAt = result.getLong("time_started"),
                                    comment = result.getString("comment"),
                                    tagId = result.getLong("tag_id"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun addRunningRecord(record: DesktopRunningRecord): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val available = db.prepareStatement(
                    "SELECT COUNT(*) FROM recordTypes WHERE id = ? AND hidden = 0",
                ).use { query ->
                    query.setLong(1, record.activityId)
                    query.executeQuery().use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                }
                if (!available) {
                    db.rollback()
                    return false
                }
                val inserted = db.prepareStatement(
                    "INSERT OR IGNORE INTO runningRecords(id, time_started, comment, tag_id) VALUES(?, ?, ?, ?)",
                ).use { insert ->
                    insert.setLong(1, record.activityId)
                    insert.setLong(2, record.startedAt)
                    insert.setString(3, record.comment)
                    insert.setLong(4, record.tagId)
                    insert.executeUpdate() == 1
                }
                db.commit()
                return inserted
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun completeRunningRecord(activityId: Long, endedAt: Long): Boolean {
        connection().use { db ->
            db.autoCommit = false
            try {
                val running = db.prepareStatement(
                    "SELECT time_started, comment, tag_id FROM runningRecords WHERE id = ?",
                ).use { query ->
                    query.setLong(1, activityId)
                    query.executeQuery().use { result ->
                        if (result.next()) {
                            Triple(
                                result.getLong("time_started"),
                                result.getString("comment"),
                                result.getLong("tag_id"),
                            )
                        } else {
                            null
                        }
                    }
                } ?: run {
                    db.rollback()
                    return false
                }
                val actualEndedAt = endedAt.coerceAtLeast(running.first)
                db.prepareStatement(
                    """
                    INSERT INTO records(id, type_id, time_started, time_ended, comment, tag_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { insert ->
                    insert.setLong(1, nextId(db))
                    insert.setLong(2, activityId)
                    insert.setLong(3, running.first)
                    insert.setLong(4, actualEndedAt)
                    insert.setString(5, running.second)
                    insert.setLong(6, running.third)
                    insert.executeUpdate()
                }
                db.prepareStatement("DELETE FROM runningRecords WHERE id = ?").use { delete ->
                    delete.setLong(1, activityId)
                    check(delete.executeUpdate() == 1)
                }
                db.commit()
                return true
            } catch (e: Throwable) {
                db.rollback()
                throw e
            }
        }
    }

    override fun previousCompletedActivityId(): Long? {
        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT r.type_id
                FROM records r
                JOIN recordTypes rt ON rt.id = r.type_id
                WHERE rt.hidden = 0 AND rt.instantDuration = 0
                ORDER BY r.time_ended DESC, r.id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { query ->
                query.executeQuery().use { result ->
                    if (result.next()) result.getLong(1) else null
                }
            }
        }
    }

    fun historyToday(): List<HistoryRow> {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        return connection().use { db ->
            db.prepareStatement(
                """
                SELECT
                    r.id,
                    rt.name,
                    r.time_started,
                    r.time_ended
                FROM records r
                JOIN recordTypes rt ON rt.id = r.type_id
                WHERE r.time_ended >= ?
                ORDER BY r.time_ended DESC
                LIMIT 100
                """.trimIndent(),
            ).use { query ->
                query.setLong(1, start)
                query.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                HistoryRow(
                                    id = result.getLong("id"),
                                    activityName = result.getString("name"),
                                    startedAt = result.getLong("time_started"),
                                    endedAt = result.getLong("time_ended"),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
