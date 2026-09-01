package com.example.util.simpletimetracker.desktop

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopBackupDomainTest {
    @Test
    fun nativeLogicalBackupRoundTripsRecordsTagsCategoriesAndRunningRecords() {
        val directory = Files.createTempDirectory("desktop-backup")
        val source = directory.resolve("source.sqlite")
        val db = DesktopDatabase(source)
        seed(db)
        val backup = directory.resolve("snapshot.sttb")
        val service = DesktopBackupService(db)

        val created = assertIs<DesktopBackupResult.Success>(service.createBackup(backup, 123L))
        assertTrue(created.summary.entityCounts.getValue("records") >= 1)
        assertIs<DesktopBackupResult.Success>(service.analyzeNative(backup))
        val restored = directory.resolve("restored.sqlite")
        assertIs<DesktopBackupResult.Success>(service.restoreToNewDatabase(backup, restored))

        assertEquals(count(source, "recordTypes"), count(restored, "recordTypes"))
        assertEquals(count(source, "records"), count(restored, "records"))
        assertEquals(count(source, "runningRecords"), count(restored, "runningRecords"))
        assertEquals(count(source, "record_tags"), count(restored, "record_tags"))
        assertEquals(count(source, "record_to_tag"), count(restored, "record_to_tag"))
        assertEquals(count(source, "categories"), count(restored, "categories"))
        assertEquals("comment with \n newline", value(restored, "SELECT comment FROM records WHERE id=101"))
        assertTrue(Files.exists(backup))
    }

    @Test
    fun corruptOrNewerBackupNeverCreatesRestoreTarget() {
        val directory = Files.createTempDirectory("desktop-backup-invalid")
        val db = DesktopDatabase(directory.resolve("source.sqlite")); seed(db)
        val backup = directory.resolve("snapshot.sttb")
        DesktopBackupService(db).createBackup(backup)
        Files.writeString(backup, "not a zip", UTF_8)
        val target = directory.resolve("must-not-exist.sqlite")

        assertIs<DesktopBackupResult.Failure>(DesktopBackupService(db).restoreToNewDatabase(backup, target))
        assertFalse(Files.exists(target))
    }

    @Test
    fun androidImportPreservesSourceIdsAndReportsStaleSoftRelations() {
        val directory = Files.createTempDirectory("android-import")
        val backup = directory.resolve("android.backup")
        Files.writeString(backup, androidFixture(), UTF_8)
        val importer = AndroidBackupImporter()
        val plan = importer.analyze(backup)
        assertTrue(plan.report.successful)
        val target = directory.resolve("candidate.sqlite")

        val report = importer.importInto(plan, target)

        assertTrue(report.successful)
        assertEquals("Spaced  ", value(target, "SELECT name FROM recordTypes WHERE id=42"))
        assertEquals(1L, count(target, "records"))
        assertEquals(1L, count(target, "record_to_tag"))
        assertEquals(1L, count(target, "record_type_goals"))
        assertEquals(1L, count(target, "complex_rules"))
        assertEquals(1L, count(target, "activity_suggestions"))
        assertEquals(1L, count(target, "scheduled_reminders"))
        assertEquals(1L, count(target, "activity_reminder_overrides"))
        assertTrue(report.warnings.any { it.contains("missing") })
        assertEquals(3_600_000L, FileDesktopSemanticPreferences(target.resolveSibling("candidate.sqlite.semantic-preferences.properties")).startOfDayShiftMillis)
        integrity(target)
    }

    @Test
    fun androidImportRejectsBadHeaderAndNeverMutatesTarget() {
        val directory = Files.createTempDirectory("android-import-bad")
        val invalid = directory.resolve("bad.backup")
        Files.writeString(invalid, "wrong\nrecord\t1", UTF_8)
        val plan = AndroidBackupImporter().analyze(invalid)
        assertFalse(plan.report.successful)
        val target = directory.resolve("target.sqlite")
        val result = AndroidBackupImporter().importInto(plan, target)
        assertFalse(result.successful)
        assertFalse(Files.exists(target))
    }

    @Test
    fun androidAnalyzeReportsIgnoredSemanticPreferencesBeforeCandidateImport() {
        val directory = Files.createTempDirectory("android-import-preference-warning")
        val source = directory.resolve("preferences.backup")
        Files.writeString(source, listOf(
            "app simple time tracker",
            "recordType\t42\tActivity\t\t0\t0\t\t\t\t\t\t0\t",
            "prefs\tstartOfDayShift\t3600000",
            "prefs\tunsupportedPreference\tprivate-value-not-needed-for-import",
        ).joinToString("\n", postfix = "\n"), UTF_8)

        val plan = AndroidBackupImporter().analyze(source)

        assertTrue(plan.report.successful)
        assertTrue(plan.report.warnings.any { it.contains("unsupportedPreference") })
    }

    @Test
    fun failedAndroidCandidateImportIsRemovedInsteadOfLeavingPartialDatabase() {
        val directory = Files.createTempDirectory("android-import-rollback")
        val source = directory.resolve("duplicate.backup")
        Files.writeString(source, listOf(
            "app simple time tracker",
            "recordType\t42\tone\t\t0\t0\t\t\t\t\t\t0\t",
            "recordType\t42\ttwo\t\t0\t0\t\t\t\t\t\t0\t",
        ).joinToString("\n", postfix = "\n"), UTF_8)
        val target = directory.resolve("candidate.sqlite")

        val report = AndroidBackupImporter().importInto(AndroidBackupImporter().analyze(source), target)

        assertFalse(report.successful)
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(target.resolveSibling("candidate.sqlite.semantic-preferences.properties")))
    }

    private fun seed(db: DesktopDatabase) {
        connection(db.path) { sql ->
            sql.createStatement().use { statement -> statement.execute("DELETE FROM desktop_id_allocator") }
            sql.prepareStatement("INSERT INTO desktop_id_allocator(id,namespace,next_counter) VALUES(1,1,1000)").use { it.executeUpdate() }
            sql.prepareStatement("INSERT INTO recordTypes VALUES(?,?,?,?,?,?,?,?,?,?)").use { s -> s.setLong(1,1);s.setString(2,"Activity");s.setString(3,"⏱");s.setInt(4,1);s.setString(5,"#123");s.setInt(6,0);s.setInt(7,0);s.setLong(8,0);s.setLong(9,0);s.setString(10,"note");s.executeUpdate() }
            sql.prepareStatement("INSERT INTO records VALUES(?,?,?,?,?,?)").use { s -> s.setLong(1,101);s.setLong(2,1);s.setLong(3,1_000);s.setLong(4,90_000_000);s.setString(5,"comment with \n newline");s.setLong(6,0);s.executeUpdate() }
            sql.prepareStatement("INSERT INTO runningRecords VALUES(?,?,?,?)").use { s -> s.setLong(1,1);s.setLong(2,2_000);s.setString(3,"running");s.setLong(4,0);s.executeUpdate() }
            sql.prepareStatement("INSERT INTO record_tags VALUES(?,?,?,?,?)").use { s -> s.setLong(1,8);s.setString(2,"number");s.setInt(3,0);s.setString(4,"NUMERIC");s.setString(5,"kg");s.executeUpdate() }
            sql.prepareStatement("INSERT INTO record_to_tag VALUES(?,?,?)").use { s -> s.setLong(1,101);s.setLong(2,8);s.setDouble(3,2.5);s.executeUpdate() }
            sql.prepareStatement("INSERT INTO categories VALUES(?,?,?,?,?)").use { s -> s.setLong(1,7);s.setString(2,"Category");s.setInt(3,2);s.setString(4,"#456");s.setString(5,"note");s.executeUpdate() }
            sql.prepareStatement("INSERT INTO record_type_category VALUES(?,?)").use { s -> s.setLong(1,1);s.setLong(2,7);s.executeUpdate() }
        }
    }

    private fun androidFixture() = listOf(
        "app simple time tracker",
        "recordType\t42\tSpaced  \ticon\t3\t1\t\t#abc\t\t\t\t7200\tnote␤line",
        "recordType\t99\tOther\t\t0\t0\t\t\t\t\t\t0\t",
        "category\t5\tWork\t4\t#def\tcategory note",
        "recordTag\t70\t\tWeight\t0\t0\t\t\t\t\t1\tkg",
        "record\t501\t42\t1000\t90000000\tkept␤comment\t",
        "recordToRecordTag\t501\t70\t2.5",
        "typeCategory\t42\t5",
        "typeToRecordTag\t42\t70",
        "typeToDefaultTag\t42\t70",
        "recordTypeGoal\t88\t0\t1\t0\t10800\t5\t1234567\t0\t0",
        "complexRule\t81\t0\t2\t70\t42\t\t1234567\t0\t",
        "activitySuggestion\t82\t42\t99",
        "scheduledReminder\t83\t1\tsoon\t0\t3600000\t123\t\t\t0\t",
        "activityReminderOverride\t42\t1",
        "activityReminderRule\t84\t42\t600\t1\t1234567\t0\t0",
        "activityFilter\t900\t42,777\t0\tStale filter\t0\t\t1",
        "prefs\tstartOfDayShift\t3600000",
        "unknownFutureRow\tdata",
    ).joinToString("\n", postfix="\n")

    private fun connection(path: Path, block: (java.sql.Connection) -> Unit) = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use(block)
    private fun count(path: Path, table: String): Long = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db -> db.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { r -> r.next(); r.getLong(1) } } }
    private fun value(path: Path, query: String): String = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db -> db.createStatement().use { s -> s.executeQuery(query).use { r -> r.next(); r.getString(1) } } }
    private fun integrity(path: Path) = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { db -> db.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { r -> r.next(); assertEquals("ok", r.getString(1)) }; s.executeQuery("PRAGMA foreign_key_check").use { r -> assertFalse(r.next()) } } }
}
