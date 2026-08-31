package com.example.util.simpletimetracker.desktop

import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId

data class DayRecordRow(
    val id: Long,
    val activityId: Long,
    val activityName: String,
    val startedAt: Long,
    val endedAt: Long,
    val comment: String,
    val tags: List<DesktopRecordTagView> = emptyList(),
)

internal fun DesktopDatabase.crudConnection(): Connection {
    val db = DriverManager.getConnection(
        "jdbc:sqlite:${path.toAbsolutePath()}",
    )
    db.createStatement().use {
        it.execute("PRAGMA busy_timeout=5000")
        it.execute("PRAGMA foreign_keys=ON")
    }
    return db
}

private fun dayBounds(date: LocalDate): Pair<Long, Long> {
    val zone = ZoneId.systemDefault()
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    return start to end
}

fun DesktopDatabase.historyForDate(
    date: LocalDate,
): List<DayRecordRow> {
    val bounds = dayBounds(date)
    val start = bounds.first
    val end = bounds.second

    val records = crudConnection().use { db ->
        db.prepareStatement(
            """
            SELECT
                r.id,
                r.type_id,
                rt.name,
                r.time_started,
                r.time_ended,
                r.comment
            FROM records r
            JOIN recordTypes rt ON rt.id = r.type_id
            WHERE r.time_started < ?
              AND r.time_ended > ?
            ORDER BY r.time_ended DESC
            """.trimIndent(),
        ).use { query ->
            query.setLong(1, end)
            query.setLong(2, start)

            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            DayRecordRow(
                                id = result.getLong("id"),
                                activityId = result.getLong("type_id"),
                                activityName = result.getString("name"),
                                startedAt = result.getLong("time_started"),
                                endedAt = result.getLong("time_ended"),
                                comment = result.getString("comment"),
                            ),
                        )
                    }
                }
            }
        }
    }
    return records.map { record -> record.copy(tags = recordTagViews(record.id)) }
}

fun DesktopDatabase.archivedActivities(): List<ActivityRow> {
    return crudConnection().use { db ->
        db.prepareStatement(
            """
            SELECT
                rt.id,
                rt.name,
                rr.time_started,
                rt.default_duration
            FROM recordTypes rt
            LEFT JOIN runningRecords rr ON rr.id = rt.id
            WHERE rt.hidden = 1
            ORDER BY rt.name COLLATE NOCASE
            """.trimIndent(),
        ).use { query ->
            query.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        val rawStartedAt =
                            result.getLong("time_started")

                        val startedAt =
                            if (result.wasNull()) null
                            else rawStartedAt

                        add(
                            ActivityRow(
                                id = result.getLong("id"),
                                name = result.getString("name"),
                                startedAt = startedAt,
                                defaultDurationSeconds = result.getLong("default_duration"),
                            ),
                        )
                    }
                }
            }
        }
    }
}

fun DesktopDatabase.setActivityDefaultDuration(
    activityId: Long,
    durationSeconds: Long,
) {
    require(durationSeconds >= 0) { "Длительность не может быть отрицательной" }
    crudConnection().use { db ->
        db.prepareStatement(
            "UPDATE recordTypes SET default_duration = ? WHERE id = ?",
        ).use { update ->
            update.setLong(1, durationSeconds)
            update.setLong(2, activityId)
            check(update.executeUpdate() == 1) { "Активность не найдена" }
        }
    }
}

fun DesktopDatabase.updateActivity(
    activityId: Long,
    name: String,
    defaultDurationSeconds: Long,
) {
    val cleanName = name.trim()
    require(cleanName.isNotEmpty()) { "Название не может быть пустым" }
    require(defaultDurationSeconds >= 0) { "Длительность не может быть отрицательной" }
    crudConnection().use { db ->
        db.prepareStatement(
            "UPDATE recordTypes SET name = ?, default_duration = ? WHERE id = ?",
        ).use { update ->
            update.setString(1, cleanName)
            update.setLong(2, defaultDurationSeconds)
            update.setLong(3, activityId)
            check(update.executeUpdate() == 1) { "Активность не найдена" }
        }
    }
}

fun DesktopDatabase.renameActivity(
    activityId: Long,
    name: String,
) {
    val cleanName = name.trim()

    require(cleanName.isNotEmpty()) {
        "Название не может быть пустым"
    }

    crudConnection().use { db ->
        db.prepareStatement(
            "UPDATE recordTypes SET name = ? WHERE id = ?",
        ).use { update ->
            update.setString(1, cleanName)
            update.setLong(2, activityId)

            check(update.executeUpdate() == 1) {
                "Активность не найдена"
            }
        }
    }
}

fun DesktopDatabase.archiveActivity(
    activityId: Long,
) {
    crudConnection().use { db ->
        db.autoCommit = false

        try {
            val running = db.prepareStatement(
                "SELECT COUNT(*) FROM runningRecords WHERE id = ?",
            ).use { query ->
                query.setLong(1, activityId)

                query.executeQuery().use { result ->
                    result.next()
                    result.getInt(1) != 0
                }
            }

            check(!running) {
                "Сначала остановите таймер"
            }

            db.prepareStatement(
                "UPDATE recordTypes SET hidden = 1 WHERE id = ?",
            ).use { update ->
                update.setLong(1, activityId)

                check(update.executeUpdate() == 1) {
                    "Активность не найдена"
                }
            }

            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        }
    }
}

fun DesktopDatabase.restoreActivity(
    activityId: Long,
) {
    crudConnection().use { db ->
        db.prepareStatement(
            "UPDATE recordTypes SET hidden = 0 WHERE id = ?",
        ).use { update ->
            update.setLong(1, activityId)

            check(update.executeUpdate() == 1) {
                "Активность не найдена"
            }
        }
    }
}
