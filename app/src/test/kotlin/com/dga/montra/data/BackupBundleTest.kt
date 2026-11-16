package com.dga.montra.data

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupBundleTest {

    private fun tempDir(): File = Files.createTempDirectory("montra-backup-test").toFile()

    private fun databaseFile(dir: File, contents: ByteArray = sqliteBytes()): File =
        File(dir, "montra.db").apply { writeBytes(contents) }

    private fun sqliteBytes(payload: String = "pages"): ByteArray =
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + payload.toByteArray()

    private val manifest = BackupBundle.Manifest(
        formatVersion = BackupBundle.FORMAT_VERSION,
        schemaVersion = 4,
        createdAtMillis = 1_700_000_000_000L
    )

    @Test
    fun `manifest survives a round trip`() {
        val parsed = BackupBundle.parseManifest(BackupBundle.manifestText(manifest))

        assertEquals(manifest, parsed)
    }

    @Test
    fun `manifest parsing rejects anything it did not write`() {
        assertNull(BackupBundle.parseManifest(""))
        assertNull(BackupBundle.parseManifest("not a manifest"))
        assertNull(BackupBundle.parseManifest("format=1\nschema=4"))  // created missing
        assertNull(BackupBundle.parseManifest("format=1\nschema=four\ncreated=17"))
        assertNull(BackupBundle.parseManifest("format=0\nschema=4\ncreated=17"))
        assertNull(BackupBundle.parseManifest("format=1\nschema=4\ncreated=-5"))
    }

    @Test
    fun `archive holds the manifest and the database`() {
        val dir = tempDir()
        val archive = File(dir, "backup.zip")
        BackupBundle.write(archive, databaseFile(dir), manifest)

        assertEquals(manifest, BackupBundle.readManifest(archive))
        val extracted = File(dir, "restored.db")
        assertTrue(BackupBundle.extractDatabase(archive, extracted))
        assertEquals("SQLite format 3\u0000pages", extracted.readText(Charsets.US_ASCII))
    }

    @Test
    fun `a database with a live write-ahead log is refused`() {
        val dir = tempDir()
        val database = databaseFile(dir)
        File("${database.path}-wal").writeText("uncheckpointed rows")

        val threw = runCatching {
            BackupBundle.write(File(dir, "backup.zip"), database, manifest)
        }.isFailure

        assertTrue(threw, "an uncheckpointed database would produce a stale backup")
    }

    @Test
    fun `extraction refuses an archive that is not a backup`() {
        val dir = tempDir()
        val archive = File(dir, "random.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("photo.jpg"))
            zip.write(ByteArray(64) { 7 })
            zip.closeEntry()
        }

        assertNull(BackupBundle.readManifest(archive))
        assertFalse(BackupBundle.extractDatabase(archive, File(dir, "out.db")))
    }

    @Test
    fun `a truncated download is not accepted as a database`() {
        val dir = tempDir()
        val truncated = File(dir, "truncated.db").apply { writeText("SQLite") }

        assertFalse(BackupBundle.isSqliteDatabase(truncated))
        assertFalse(BackupBundle.isSqliteDatabase(File(dir, "missing.db")))
        assertTrue(BackupBundle.isSqliteDatabase(databaseFile(dir)))
    }
}
