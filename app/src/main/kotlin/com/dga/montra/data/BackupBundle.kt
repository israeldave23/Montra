package com.dga.montra.data

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The backup file: one zip holding the database and a small text manifest.
 *
 * The previous backup was the raw `.db` file copied while Room had it open. Three things were wrong
 * with that, and all three could lose money:
 *
 *  * **The write-ahead log was left behind.** Room runs in WAL mode, so a committed transaction can
 *    live in `montra.db-wal` and not in `montra.db` at all. The old backup was missing whatever the
 *    user did most recently. The caller now checkpoints first (see
 *    `RoomFinanceRepository.checkpointForBackup`) and this class refuses to write a database whose
 *    sidecars still exist.
 *  * **Nothing identified the file.** A file picked at random could be written over the live
 *    database. The manifest says what the file is, which format version wrote it and which schema
 *    it holds, so a mismatch is refused before anything is replaced.
 *  * **Nothing was validated on the way back in.** [extractDatabase] checks the SQLite header, so a
 *    truncated download cannot become the database.
 *
 * Plain text rather than JSON on purpose: no `org.json` (an Android class that returns nothing in a
 * JVM unit test), and a manifest a person can read in a zip viewer.
 */
internal object BackupBundle {

    /** Bumped only when the archive layout changes; the manifest is checked against it. */
    const val FORMAT_VERSION = 1

    const val DATABASE_ENTRY = "montra.db"
    const val MANIFEST_ENTRY = "manifest.txt"

    /** Every SQLite database file starts with these 15 bytes. */
    private const val SQLITE_HEADER = "SQLite format 3"
    private const val HEADER_BYTES = 16

    data class Manifest(
        val formatVersion: Int,
        val schemaVersion: Int,
        val createdAtMillis: Long
    )

    fun manifestText(manifest: Manifest): String = buildString {
        appendLine("format=${manifest.formatVersion}")
        appendLine("schema=${manifest.schemaVersion}")
        appendLine("created=${manifest.createdAtMillis}")
    }

    /** Null when [text] is not a manifest this build understands. */
    fun parseManifest(text: String): Manifest? {
        val values = text.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.take(split).trim() to line.substring(split + 1).trim()
            }
            .toMap()
        val format = values["format"]?.toIntOrNull() ?: return null
        val schema = values["schema"]?.toIntOrNull() ?: return null
        val created = values["created"]?.toLongOrNull() ?: return null
        if (format <= 0 || schema <= 0 || created <= 0L) return null
        return Manifest(format, schema, created)
    }

    /**
     * Writes [database] and [manifest] into [target].
     *
     * A database with a `-wal` sidecar is rejected: that means the checkpoint did not run, and
     * archiving it alone would silently drop the newest transactions.
     */
    fun write(target: File, database: File, manifest: Manifest) {
        require(database.isFile) { "No database file to archive" }
        require(!File("${database.path}-wal").exists()) {
            "Database still has a write-ahead log; checkpoint before archiving"
        }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestText(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            database.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Reads the manifest without extracting the database. Null for anything else. */
    fun readManifest(archive: File): Manifest? = runCatching {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(MANIFEST_ENTRY) ?: return null
            zip.getInputStream(entry).bufferedReader().use { parseManifest(it.readText()) }
        }
    }.getOrNull()

    /**
     * Extracts the database entry into [target]. False when the archive has no database entry, or
     * when what came out does not start with the SQLite header.
     */
    fun extractDatabase(archive: File, target: File): Boolean = runCatching {
        val found = ZipFile(archive).use { zip ->
            val entry = zip.getEntry(DATABASE_ENTRY) ?: return false
            zip.getInputStream(entry).use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            true
        }
        found && isSqliteDatabase(target)
    }.getOrElse {
        target.delete()
        false
    }

    /** True when the file begins with the SQLite header, i.e. it is a database and not a stray file. */
    fun isSqliteDatabase(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_BYTES) return false
        val header = ByteArray(HEADER_BYTES)
        try {
            file.inputStream().use { input ->
                var read = 0
                while (read < HEADER_BYTES) {
                    val count = input.read(header, read, HEADER_BYTES - read)
                    if (count <= 0) return false
                    read += count
                }
            }
        } catch (error: Exception) {
            return false
        }
        return String(header, 0, SQLITE_HEADER.length, Charsets.US_ASCII) == SQLITE_HEADER
    }
}
