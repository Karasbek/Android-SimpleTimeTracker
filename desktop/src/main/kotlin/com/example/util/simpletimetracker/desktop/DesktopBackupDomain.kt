package com.example.util.simpletimetracker.desktop

import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Versioned logical desktop backup. The database payload is deliberately not a raw SQLite file:
 * it is a stable table/row stream with typed cells, wrapped with an integrity manifest.
 */
data class DesktopBackupSummary(
    val formatVersion: Int,
    val createdAt: Long,
    val logicalSchemaVersion: Int,
    val entityCounts: Map<String, Long>,
)

sealed interface DesktopBackupResult {
    data class Success(val summary: DesktopBackupSummary) : DesktopBackupResult
    data class Failure(val message: String) : DesktopBackupResult
}

data class AndroidImportReport(
    val sourceRows: Int = 0,
    val counts: Map<String, Long> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val unsupportedRows: Set<String> = emptySet(),
    val fatalErrors: List<String> = emptyList(),
) {
    val successful: Boolean get() = fatalErrors.isEmpty()
}

/** A parsed Android backup is intentionally separate from applying it to a database. */
data class AndroidBackupPlan(
    val rows: List<List<String>>,
    val report: AndroidImportReport,
)

class DesktopBackupService(
    private val database: DesktopDatabase,
    private val semanticPreferences: DesktopSemanticPreferences? = null,
    private val pomodoroConfigStore: DesktopPomodoroConfigStore? = null,
) {
    fun createBackup(target: Path, now: Long = System.currentTimeMillis()): DesktopBackupResult = runCatching {
        Files.createDirectories(target.parent ?: Path.of("."))
        val payload = Files.createTempFile(target.parent ?: Path.of("."), "desktop-backup-", ".tsv")
        try {
            val counts = writeDatabasePayload(payload)
            val manifest = Properties().apply {
                setProperty("format", FORMAT)
                setProperty("formatVersion", FORMAT_VERSION.toString())
                setProperty("sourcePlatform", "desktop")
                setProperty("createdAt", now.toString())
                setProperty("logicalSchemaVersion", DesktopDatabaseSchema.CURRENT_VERSION.toString())
                setProperty("payloadSha256", sha256(payload))
                counts.forEach { (name, count) -> setProperty("count.$name", count.toString()) }
            }
            val temporary = target.resolveSibling("${target.fileName}.part")
            try {
                ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.properties")); manifest.store(zip, null); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("database.tsv")); Files.copy(payload, zip); zip.closeEntry()
                    semanticPreferences?.let { prefs ->
                        zip.putNextEntry(ZipEntry("semantic-preferences.properties"))
                        semanticProperties(prefs).store(zip, null)
                        zip.closeEntry()
                    }
                    pomodoroConfigStore?.let { store ->
                        zip.putNextEntry(ZipEntry("pomodoro.properties"))
                        pomodoroProperties(store.load()).store(zip, null)
                        zip.closeEntry()
                    }
                }
                atomicMove(temporary, target)
            } finally { Files.deleteIfExists(temporary) }
            DesktopBackupResult.Success(DesktopBackupSummary(FORMAT_VERSION, now, DesktopDatabaseSchema.CURRENT_VERSION, counts))
        } finally { Files.deleteIfExists(payload) }
    }.getOrElse { DesktopBackupResult.Failure(it.message ?: "Unable to create desktop backup") }

    /** Validates every byte before any target path is opened for writing. */
    fun analyzeNative(backup: Path): DesktopBackupResult = runCatching {
        ZipFile(backup.toFile()).use { zip ->
            val manifest = zip.getEntry("manifest.properties") ?: error("Missing backup manifest")
            val payload = zip.getEntry("database.tsv") ?: error("Missing logical database payload")
            val properties = Properties().also { zip.getInputStream(manifest).use(it::load) }
            require(properties.getProperty("format") == FORMAT) { "Unsupported desktop backup format" }
            val version = properties.getProperty("formatVersion")?.toIntOrNull() ?: error("Invalid backup version")
            require(version <= FORMAT_VERSION) { "Backup format $version is newer than this desktop app" }
            val temporary = Files.createTempFile("desktop-backup-verify-", ".tsv")
            try {
                zip.getInputStream(payload).use { input -> Files.newOutputStream(temporary).use { input.copyTo(it) } }
                require(properties.getProperty("payloadSha256") == sha256(temporary)) { "Desktop backup checksum mismatch" }
                val counts = validatePayload(temporary)
                DesktopBackupResult.Success(DesktopBackupSummary(version, properties.getProperty("createdAt")?.toLongOrNull() ?: 0L, properties.getProperty("logicalSchemaVersion")?.toIntOrNull() ?: 0, counts))
            } finally { Files.deleteIfExists(temporary) }
        }
    }.getOrElse { DesktopBackupResult.Failure(it.message ?: "Invalid desktop backup") }

    /** Restores into a fresh path only. A live DB is never silently replaced. */
    fun restoreToNewDatabase(backup: Path, target: Path): DesktopBackupResult {
        val analysis = analyzeNative(backup)
        if (analysis !is DesktopBackupResult.Success) return analysis
        if (Files.exists(target)) return DesktopBackupResult.Failure("Restore target already exists: $target")
        return runCatching {
            val temporary = target.resolveSibling("${target.fileName}.restore-part")
            Files.deleteIfExists(temporary)
            try {
                ZipFile(backup.toFile()).use { zip ->
                    val payload = zip.getEntry("database.tsv") ?: error("Missing logical database payload")
                    val extracted = Files.createTempFile("desktop-restore-", ".tsv")
                    try {
                        zip.getInputStream(payload).use { input -> Files.newOutputStream(extracted).use { input.copyTo(it) } }
                        restorePayload(extracted, temporary)
                    } finally { Files.deleteIfExists(extracted) }
                }
                integrityCheck(temporary)
                atomicMove(temporary, target)
            } finally { Files.deleteIfExists(temporary) }
            analysis
        }.getOrElse { DesktopBackupResult.Failure(it.message ?: "Restore failed") }
    }

    fun exportCsv(target: Path, zone: ZoneId = ZoneId.systemDefault()): DesktopBackupResult = runCatching {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val temporary = target.resolveSibling("${target.fileName}.part")
        Files.newBufferedWriter(temporary, UTF_8).use { out ->
            out.appendLine("activity,start,end,duration_seconds,comment,tags,categories")
            database.crudConnection().use { db ->
                db.prepareStatement("SELECT r.id,rt.name,r.time_started,r.time_ended,r.comment FROM records r JOIN recordTypes rt ON rt.id=r.type_id ORDER BY r.time_started,r.id").use { query ->
                    query.executeQuery().use { result -> while (result.next()) {
                        val id = result.getLong("id")
                        val tags = textList(db, "SELECT t.name || CASE WHEN rtt.numeric_value IS NULL THEN '' ELSE '=' || rtt.numeric_value END FROM record_to_tag rtt JOIN record_tags t ON t.id=rtt.tag_id WHERE rtt.record_id=? ORDER BY t.name", id)
                        val categories = textList(db, "SELECT c.name FROM record_type_category rtc JOIN categories c ON c.id=rtc.category_id WHERE rtc.record_type_id=(SELECT type_id FROM records WHERE id=?) ORDER BY c.name", id)
                        val start = Instant.ofEpochMilli(result.getLong("time_started")).atZone(zone).format(formatter)
                        val end = Instant.ofEpochMilli(result.getLong("time_ended")).atZone(zone).format(formatter)
                        out.append(csv(result.getString("name"))).append(',').append(csv(start)).append(',').append(csv(end)).append(',')
                            .append(((result.getLong("time_ended") - result.getLong("time_started")) / 1000).toString()).append(',')
                            .append(csv(result.getString("comment"))).append(',').append(csv(tags.joinToString("; "))).append(',').append(csv(categories.joinToString("; "))).appendLine()
                    } }
                }
            }
        }
        atomicMove(temporary, target)
        val recordCount = database.crudConnection().use { db ->
            db.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM records").use { result -> result.next(); result.getLong(1) } }
        }
        DesktopBackupResult.Success(DesktopBackupSummary(0, System.currentTimeMillis(), DesktopDatabaseSchema.CURRENT_VERSION, mapOf("records" to recordCount)))
    }.getOrElse { DesktopBackupResult.Failure(it.message ?: "CSV export failed") }

    private fun writeDatabasePayload(target: Path): Map<String, Long> = database.crudConnection().use { db ->
        Files.newBufferedWriter(target, UTF_8).use { out ->
            val tables = tables(db)
            val counts = linkedMapOf<String, Long>()
            tables.forEach { table ->
                val columns = columns(db, table)
                out.append("T\t").append(enc(table)).append('\t').append(enc(columns.joinToString("\u001f"))).appendLine()
                var count = 0L
                db.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"$table\"").use { rows ->
                    while (rows.next()) {
                        out.append("R\t").append(enc(table))
                        columns.forEachIndexed { index, _ -> out.append('\t').append(cell(rows.getObject(index + 1))) }
                        out.appendLine(); count++
                    }
                } }
                counts[table] = count
            }
            counts
        }
    }

    private fun validatePayload(payload: Path): Map<String, Long> {
        val counts = linkedMapOf<String, Long>(); val known = mutableSetOf<String>()
        Files.newBufferedReader(payload, UTF_8).useLines { lines -> lines.forEach { line ->
            val p = line.split('\t'); require(p.isNotEmpty()) { "Malformed logical payload" }
            when (p[0]) {
                "T" -> { require(p.size == 3); known += dec(p[1]); dec(p[2]) }
                "R" -> { require(p.size >= 2); val table=dec(p[1]); require(table in known) { "Row before table declaration" }; counts[table]=(counts[table] ?: 0)+1; p.drop(2).forEach(::readCell) }
                else -> error("Unknown payload row")
            }
        } }
        return counts
    }

    private fun restorePayload(payload: Path, target: Path) {
        val layout = linkedMapOf<String, List<String>>(); val rows = linkedMapOf<String, MutableList<List<String>>>()
        Files.newBufferedReader(payload, UTF_8).useLines { lines -> lines.forEach { line ->
            val p=line.split('\t'); when(p[0]) { "T" -> layout[dec(p[1])] = dec(p[2]).split("\u001f"); "R" -> rows.getOrPut(dec(p[1])){mutableListOf()}.add(p.drop(2)); else -> error("Unknown payload row") }
        } }
        DesktopDatabase(target) // creates current schema and allocator
        DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}").use { db ->
            db.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }; db.autoCommit=false
            try {
                tables(db).forEach { table -> db.createStatement().use { it.executeUpdate("DELETE FROM \"$table\"") } }
                // Payload table order is dependency order from sqlite; parent records precede relationships in schema.
                layout.forEach { (table, cols) ->
                    require(table in tables(db)) { "Backup contains unknown required table: $table" }
                    rows[table].orEmpty().forEach { values ->
                        require(values.size == cols.size) { "Invalid cell count for $table" }
                        db.prepareStatement("INSERT INTO \"$table\" (${cols.joinToString(",") { "\"$it\"" }}) VALUES (${cols.joinToString(",") { "?" }})").use { insert ->
                            values.forEachIndexed { i, value -> bindCell(insert, i + 1, value) }; insert.executeUpdate()
                        }
                    }
                }
                db.commit()
            } catch (e: Throwable) { db.rollback(); throw e } finally { db.autoCommit=true; db.createStatement().use { it.execute("PRAGMA foreign_keys=ON") } }
        }
    }

    companion object {
        const val FORMAT = "simple-time-tracker-desktop-logical-backup"
        const val FORMAT_VERSION = 1
    }
}

/** Production parser for the official Android UTF-8 logical backup. */
class AndroidBackupImporter {
    fun analyze(source: Path): AndroidBackupPlan {
        val rows = mutableListOf<List<String>>(); val warnings=mutableListOf<String>(); val unsupported=linkedSetOf<String>(); val counts=linkedMapOf<String,Long>()
        runCatching {
            Files.newBufferedReader(source, UTF_8).use { reader ->
                require(reader.readLine() == ANDROID_HEADER) { "Not an Android Simple Time Tracker backup" }
                var line: String?
                while (reader.readLine().also { line=it } != null) {
                    val parts=line!!.split('\t'); require(parts.isNotEmpty() && parts[0].isNotEmpty()) { "Malformed Android backup row" }
                    rows += parts; counts[parts[0]]=(counts[parts[0]] ?: 0)+1
                    if (parts[0] !in SUPPORTED_ROWS) unsupported += parts[0]
                }
            }
        }.onFailure { return AndroidBackupPlan(emptyList(), AndroidImportReport(fatalErrors=listOf(it.message ?: "Unable to parse Android backup"))) }
        if (unsupported.isNotEmpty()) warnings += "Unsupported Android rows are preserved in the report and not imported: ${unsupported.joinToString()}"
        val unsupportedPreferences = rows.asSequence()
            .filter { it.first() == "prefs" }
            .map { it.text(1) }
            .filterNot { it in ANDROID_SEMANTIC_PREFERENCE_KEYS }
            .distinct()
            .toList()
        if (unsupportedPreferences.isNotEmpty()) {
            warnings += "Android preferences not supported on desktop are ignored: ${unsupportedPreferences.joinToString()}"
        }
        if (rows.none { it.first() == "recordType" }) warnings += "Backup contains no activities"
        return AndroidBackupPlan(rows, AndroidImportReport(rows.size, counts, warnings, unsupported))
    }

    /** Applies only to an empty/new database. Source IDs are retained; no fuzzy merge is attempted. */
    fun importInto(plan: AndroidBackupPlan, target: Path): AndroidImportReport {
        if (!plan.report.successful) return plan.report
        if (Files.exists(target)) return plan.report.copy(fatalErrors=listOf("Android import target already exists: $target"))
        val preferencesTarget = target.resolveSibling("${target.fileName}.semantic-preferences.properties")
        return runCatching {
            DesktopDatabase(target)
            // Candidate imports receive their own portable semantic preferences sidecar. Never touch
            // the current application's profile while a user is only preparing an imported dataset.
            val candidatePreferences = FileDesktopSemanticPreferences(
                preferencesTarget,
            )
            val stale=mutableListOf<String>(); val imported=linkedMapOf<String,Long>()
            DriverManager.getConnection("jdbc:sqlite:${target.toAbsolutePath()}").use { db ->
                db.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }; db.autoCommit=false
                try {
                    fun exists(table:String,id:Long)=db.prepareStatement("SELECT 1 FROM $table WHERE id=?").use { q->q.setLong(1,id);q.executeQuery().use{it.next()} }
                    fun add(name:String) { imported[name]=(imported[name] ?: 0)+1 }
                    plan.rows.filter { it[0] == "recordType" }.forEach { p ->
                        val id=p.long(1); if (exists("recordTypes",id)) error("Duplicate Android activity ID: $id")
                        db.prepareStatement("INSERT INTO recordTypes(id,name,icon,color,color_int,hidden,instant,instantDuration,default_duration,note) VALUES(?,?,?,?,?,?,?,?,?,?)").use { s ->
                            s.setLong(1,id); s.setString(2,p.text(2));s.setString(3,p.text(3));s.setInt(4,p.int(4));s.setString(5,p.text(7));s.setInt(6,p.bool(5));s.setInt(7,0);s.setLong(8,0);s.setLong(9,p.long(11));s.setString(10,p.text(12).restoreAndroidNewline());s.executeUpdate()
                        }; add("activities")
                    }
                    plan.rows.filter { it[0] == "category" }.forEach { p -> db.prepareStatement("INSERT INTO categories(id,name,color,color_int,note) VALUES(?,?,?,?,?)").use {s->s.setLong(1,p.long(1));s.setString(2,p.text(2));s.setInt(3,p.int(3));s.setString(4,p.text(4));s.setString(5,p.text(5).restoreAndroidNewline());s.executeUpdate()};add("categories") }
                    plan.rows.filter { it[0] == "recordTag" }.forEach { p -> db.prepareStatement("INSERT INTO record_tags(id,name,archived,value_type,value_suffix) VALUES(?,?,?,?,?)").use{s->s.setLong(1,p.long(1));s.setString(2,p.text(3));s.setInt(3,p.bool(4));s.setString(4,if(p.long(10)==1L)"NUMERIC" else "NONE");s.setString(5,p.text(11));s.executeUpdate()};add("tags") }
                    plan.rows.filter { it[0] == "record" }.forEach { p -> if (!exists("recordTypes",p.long(2))) stale += "record ${p.long(1)} references missing activity ${p.long(2)}" else { db.prepareStatement("INSERT INTO records(id,type_id,time_started,time_ended,comment,tag_id) VALUES(?,?,?,?,?,0)").use{s->s.setLong(1,p.long(1));s.setLong(2,p.long(2));s.setLong(3,p.long(3));s.setLong(4,p.long(4));s.setString(5,p.text(5).restoreAndroidNewline());s.executeUpdate()};add("records") } }
                    plan.rows.filter { it[0] == "typeCategory" }.forEach { p -> relation(db,"record_type_category","record_type_id",p.long(1),"category_id",p.long(2),stale);add("activityCategoryRelations") }
                    plan.rows.filter { it[0] == "recordToRecordTag" }.forEach { p -> relation(db,"record_to_tag","record_id",p.long(1),"tag_id",p.long(2),stale,p.text(3).toDoubleOrNull());add("recordTagRelations") }
                    plan.rows.filter { it[0] == "typeToRecordTag" }.forEach { p -> relation(db,"record_type_to_tag","record_type_id",p.long(1),"tag_id",p.long(2),stale);add("activityTagRelations") }
                    plan.rows.filter { it[0] == "typeToDefaultTag" }.forEach { p -> relation(db,"record_type_to_default_tag","record_type_id",p.long(1),"tag_id",p.long(2),stale);add("activityDefaultTagRelations") }
                    plan.rows.filter { it[0] == "recordTypeGoal" }.forEach { p ->
                        val owner = when { p.long(2)!=0L -> "ACTIVITY" to p.long(2); p.long(6)!=0L -> "CATEGORY" to p.long(6); else -> "TAG" to p.long(9) }
                        if (owner.second == 0L) stale += "goal ${p.long(1)} has no owner" else db.prepareStatement("INSERT INTO record_type_goals(id,owner_id,owner_type,goal_range,measure,subtype,value,days_of_week) VALUES(?,?,?,?,?,?,?,?)").use{s->s.setLong(1,p.long(1));s.setLong(2,owner.second);s.setString(3,owner.first);s.setString(4,listOf("SESSION","DAILY","WEEKLY","MONTHLY").getOrElse(p.int(3)){"SESSION"});s.setString(5,if(p.int(4)==1)"COUNT" else "DURATION");s.setString(6,if(p.int(8)==1)"LIMIT" else "GOAL");s.setLong(7,p.long(5));s.setString(8,p.text(7));s.executeUpdate()};add("goals")
                    }
                    plan.rows.filter { it[0] == "complexRule" }.forEach { p ->
                        db.prepareStatement("INSERT INTO complex_rules(id,disabled,action,disallow_only_previous,starting_activity_ids,current_activity_ids,days_of_week) VALUES(?,?,?,?,?,?,?)").use { s ->
                            s.setLong(1,p.long(1)); s.setInt(2,p.bool(2)); s.setString(3,listOf("ALLOW_MULTITASKING","DISALLOW_MULTITASKING","ASSIGN_TAG").getOrElse(p.int(3)){"ALLOW_MULTITASKING"}); s.setInt(4,p.bool(8)); s.setString(5,p.text(5)); s.setString(6,p.text(6)); s.setString(7,p.text(7)); s.executeUpdate()
                        }
                        parseRuleValues(p.text(4), p.text(9)).forEach { value ->
                            db.prepareStatement("INSERT OR IGNORE INTO complex_rule_tags(rule_id,tag_id,numeric_value,select_value_on_start) VALUES(?,?,?,?)").use { s ->
                                s.setLong(1, p.long(1)); s.setLong(2, value.id)
                                if (value.numeric == null) s.setObject(3, null) else s.setDouble(3, value.numeric)
                                s.setInt(4, if (value.select) 1 else 0); s.executeUpdate()
                            }
                        }; add("complexRules")
                    }
                    plan.rows.filter { it[0] == "activitySuggestion" }.forEach { p ->
                        db.prepareStatement("INSERT INTO activity_suggestions(id,activity_id) VALUES(?,?)").use{s->s.setLong(1,p.long(1));s.setLong(2,p.long(2));s.executeUpdate()}
                        p.text(3).split(',').mapNotNull(String::toLongOrNull).forEach { item -> db.prepareStatement("INSERT OR IGNORE INTO activity_suggestion_items(suggestion_id,activity_id) VALUES(?,?)").use{s->s.setLong(1,p.long(1));s.setLong(2,item);s.executeUpdate()} }; add("activitySuggestions")
                    }
                    plan.rows.filter { it[0] == "scheduledReminder" }.forEach { p ->
                        val type = listOf("WEEKLY","ONE_TIME","MONTHLY").getOrElse(p.int(4)){"WEEKLY"}; val condition=if(p.int(9)==1)"ACTIVITY_NOT_TRACKED_TODAY" else "ALWAYS"
                        db.prepareStatement("INSERT INTO scheduled_reminders(id,enabled,text,schedule_type,days_of_week,one_time_epoch_day,day_of_month,time_of_day_millis,condition_type,condition_activity_id) VALUES(?,?,?,?,?,?,?,?,?,?)").use{s->s.setLong(1,p.long(1));s.setInt(2,p.bool(2));s.setString(3,p.text(3).restoreAndroidNewline());s.setString(4,type);s.setString(5,p.text(6));if(p.text(7).isEmpty())s.setObject(6,null) else s.setLong(6,p.long(7));if(p.text(8).isEmpty())s.setObject(7,null) else s.setInt(7,p.int(8));s.setLong(8,p.long(5));s.setString(9,condition);if(p.text(10).isEmpty())s.setObject(10,null) else s.setLong(10,p.long(10));s.executeUpdate()};add("scheduledReminders")
                    }
                    val overrideModes = plan.rows.filter { it[0] == "activityReminderOverride" }.associateBy { it.long(1) }
                    plan.rows.filter { it[0] == "activityReminderRule" }.forEach { p ->
                        val mode = if (overrideModes[p.long(2)]?.int(2) == 1) "CUSTOM" else "DISABLED"
                        db.prepareStatement("INSERT OR REPLACE INTO activity_reminder_overrides(activity_id,mode,duration_seconds,recurrent,days_of_week,dnd_start_millis,dnd_end_millis) VALUES(?,?,?,?,?,?,?)").use{s->s.setLong(1,p.long(2));s.setString(2,mode);s.setLong(3,p.long(3));s.setInt(4,p.bool(4));s.setString(5,p.text(5));s.setLong(6,p.long(6));s.setLong(7,p.long(7));s.executeUpdate()};add("activityReminderOverrides")
                    }
                    overrideModes.filterKeys { activityId -> plan.rows.none { it[0] == "activityReminderRule" && it.long(2) == activityId } }.forEach { (activityId, p) -> db.prepareStatement("INSERT OR REPLACE INTO activity_reminder_overrides(activity_id,mode,duration_seconds,recurrent,days_of_week,dnd_start_millis,dnd_end_millis) VALUES(?, ?, 0, 0, '', 0, 0)").use{s->s.setLong(1,activityId);s.setString(2,if(p.int(2)==1)"CUSTOM" else "DISABLED");s.executeUpdate()};add("activityReminderOverrides") }
                    plan.rows.filter { it[0] == "activityFilter" }.forEach { p ->
                        val kind=if(p.int(3)==1)"categories" else "activities"; val ids=p.text(2).split(',').mapNotNull(String::toLongOrNull)
                        db.prepareStatement("INSERT INTO saved_record_filters(id,name,include_uncategorized,exclude_uncategorized,include_untagged,exclude_untagged) VALUES(?,?,?,?,?,?)").use{s->s.setLong(1,p.long(1));s.setString(2,p.text(4));repeat(4){i->s.setInt(i+3,0)};s.executeUpdate()}
                        ids.forEach { id ->
                            val entityTable = if (kind == "activities") "recordTypes" else "categories"
                            if (!exists(entityTable, id)) stale += "filter ${p.long(1)} references missing $kind entity $id"
                            db.prepareStatement("INSERT OR IGNORE INTO saved_record_filter_$kind(filter_id,entity_id,mode) VALUES(?,?, 'INCLUDE')").use{s->s.setLong(1,p.long(1));s.setLong(2,id);s.executeUpdate()}
                        }; add("filters")
                    }
                    plan.rows.filter { it[0] == "prefs" }.forEach { p -> mapPreference(p.text(1),p.text(2),candidatePreferences,stale) }
                    db.commit()
                } catch(e:Throwable) { db.rollback(); throw e } finally { db.autoCommit=true;db.createStatement().use{it.execute("PRAGMA foreign_keys=ON")} }
            }
            integrityCheck(target)
            plan.report.copy(counts=plan.report.counts + imported, warnings=plan.report.warnings + stale)
        }.getOrElse {
            // A failed candidate must not be mistaken for a successfully imported profile.
            Files.deleteIfExists(target)
            Files.deleteIfExists(preferencesTarget)
            plan.report.copy(fatalErrors=plan.report.fatalErrors + (it.message ?: "Android import failed"))
        }
    }

    companion object { const val ANDROID_HEADER="app simple time tracker"; private val SUPPORTED_ROWS=setOf("recordType","record","category","typeCategory","recordTag","recordToRecordTag","typeToRecordTag","typeToDefaultTag","recordTypeGoal","activityFilter","complexRule","activitySuggestion","scheduledReminder","activityReminderOverride","activityReminderRule","prefs") }
}

private data class AndroidRuleTagValue(val id: Long, val numeric: Double?, val select: Boolean)
private fun parseRuleValues(ids: String, values: String): List<AndroidRuleTagValue> {
    val parsed = values.split(',').mapNotNull { entry ->
        val parts = entry.split(':', limit = 2)
        parts.firstOrNull()?.toLongOrNull()?.let { id -> id to parts.getOrNull(1).orEmpty() }
    }.toMap()
    return ids.split(',').mapNotNull { id -> id.toLongOrNull()?.let { tagId ->
        val encoded = parsed[tagId]
        AndroidRuleTagValue(tagId, encoded?.toDoubleOrNull(), encoded == "later")
    } }
}

private fun relation(db: Connection, table:String, left:String, leftId:Long, right:String, rightId:Long, stale:MutableList<String>, numeric:Double?=null) {
    val present={ tableName:String,id:Long -> db.prepareStatement("SELECT 1 FROM $tableName WHERE id=?").use{q->q.setLong(1,id);q.executeQuery().use{it.next()}} }
    val lTable=if(left.contains("record")) if(left=="record_id") "records" else "recordTypes" else "recordTypes"; val rTable=if(right=="tag_id")"record_tags" else "categories"
    if(!present(lTable,leftId)||!present(rTable,rightId)){stale += "$table references missing entity";return}
    val sql=if(numeric==null)"INSERT OR IGNORE INTO $table($left,$right) VALUES(?,?)" else "INSERT OR REPLACE INTO $table($left,$right,numeric_value) VALUES(?,?,?)"
    db.prepareStatement(sql).use{s->s.setLong(1,leftId);s.setLong(2,rightId);if(numeric!=null)s.setDouble(3,numeric);s.executeUpdate()}
}

private val ANDROID_SEMANTIC_PREFERENCE_KEYS = setOf(
    "startOfDayShift",
    "allowMultitasking",
    "ignoreShortRecordsDuration",
    "showUntrackedInRecords",
)

private fun mapPreference(key:String,value:String,prefs:DesktopSemanticPreferences?, warnings:MutableList<String>) { if(prefs==null)return; when(key) { "startOfDayShift" -> value.toLongOrNull()?.let{prefs.startOfDayShiftMillis=it}; "allowMultitasking" -> prefs.allowMultitasking=value=="1"; "ignoreShortRecordsDuration" -> value.toLongOrNull()?.let{prefs.ignoreShortRecordsDurationSeconds=it}; "showUntrackedInRecords" -> prefs.showUntrackedInRecords=value=="1"; else -> warnings += "Android preference $key is not supported on desktop" } }
private fun List<String>.text(index:Int)=getOrNull(index).orEmpty()
private fun List<String>.long(index:Int)=text(index).toLongOrNull() ?: 0L
private fun List<String>.int(index:Int)=text(index).toIntOrNull() ?: 0
private fun List<String>.bool(index:Int)=if(int(index)==1)1 else 0
private fun String.restoreAndroidNewline()=replace("␤","\n")
private fun enc(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(UTF_8))
private fun dec(value:String)=String(Base64.getDecoder().decode(value),UTF_8)
private fun cell(value:Any?)=when(value){null->"N";is ByteArray->"B"+Base64.getEncoder().encodeToString(value);is Long,is Int,is Short->"I"+value.toString();is Double,is Float->"F"+value.toString();else->"S"+enc(value.toString())}
private fun readCell(value:String){require(value.isNotEmpty() && value[0] in "NBIFS")}
private fun bindCell(statement:java.sql.PreparedStatement,index:Int,value:String){readCell(value);when(value[0]){'N'->statement.setObject(index,null);'B'->statement.setBytes(index,Base64.getDecoder().decode(value.substring(1)));'I'->statement.setLong(index,value.substring(1).toLong());'F'->statement.setDouble(index,value.substring(1).toDouble());else->statement.setString(index,dec(value.substring(1)))}}
private fun tables(db:Connection)=db.createStatement().use{s->s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY CASE name WHEN 'recordTypes' THEN 1 WHEN 'records' THEN 2 ELSE 3 END,name").use{r->buildList{while(r.next())add(r.getString(1))}}}
private fun columns(db:Connection,table:String)=db.createStatement().use{s->s.executeQuery("PRAGMA table_info(\"$table\")").use{r->buildList{while(r.next())add(r.getString("name"))}}}
private fun sha256(path:Path)=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString(""){"%02x".format(it)}
private fun atomicMove(from:Path,to:Path){try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}catch(_:AtomicMoveNotSupportedException){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING)}}
private fun integrityCheck(path:Path){DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use{db->db.createStatement().use{s->s.executeQuery("PRAGMA integrity_check").use{r->check(r.next()&&r.getString(1)=="ok")};s.executeQuery("PRAGMA foreign_key_check").use{r->check(!r.next()){ "Foreign key check failed" }}}}}
private fun semanticProperties(p:DesktopSemanticPreferences)=Properties().apply{setProperty("allowMultitasking",p.allowMultitasking.toString());setProperty("ignoreShortRecordsDurationSeconds",p.ignoreShortRecordsDurationSeconds.toString());setProperty("startOfDayShiftMillis",p.startOfDayShiftMillis.toString());setProperty("firstDayOfWeek",p.firstDayOfWeek.name);setProperty("ignoreShortUntrackedDurationSeconds",p.ignoreShortUntrackedDurationSeconds.toString());setProperty("showUntrackedInRecords",p.showUntrackedInRecords.toString())}
private fun pomodoroProperties(p:DesktopPomodoroConfig)=Properties().apply{setProperty("focus",p.focusMillis.toString());setProperty("break",p.breakMillis.toString());setProperty("longBreak",p.longBreakMillis.toString());setProperty("periods",p.periodsUntilLongBreak.toString())}
private fun textList(db:Connection,sql:String,id:Long)=db.prepareStatement(sql).use{q->q.setLong(1,id);q.executeQuery().use{r->buildList{while(r.next())add(r.getString(1))}}}
private fun csv(value:String)="\""+value.replace("\"","\"\"")+"\""
