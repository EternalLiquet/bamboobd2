package io.github.eternalliquet.bamboobd2.core.storage

import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface SaveResult {
    data class Saved(val fileName: String, val sequence: Int) : SaveResult
    data class Failed(val reason: String) : SaveResult
}

sealed interface StoredSession {
    val fileName: String

    data class Readable(override val fileName: String, val record: SessionRecord) : StoredSession
    data class Unreadable(override val fileName: String, val reason: String) : StoredSession
}

interface SessionStore {
    fun save(record: SessionRecord): SaveResult

    /** Latest readable save of each session, plus any files that could not be read. */
    fun list(): List<StoredSession>

    /** Latest readable save of one session, or null. */
    fun load(id: String): SessionRecord?
}

/**
 * Write-once session storage. Each save goes to a temporary file in the same
 * directory, is flushed to disk, then moved atomically to `<id>.<seq>.json`, a
 * name that has never existed. Nothing is overwritten in place. One writer per
 * session is assumed, so sequence numbers cannot collide. If the platform cannot
 * move atomically the save fails visibly; there is no non-atomic fallback.
 * Requires java.nio.file, available on Android from API 26.
 */
class FileSessionStore(
    private val directory: File,
    /** Test hook: runs after the temporary file is written, before the move. */
    private val beforeMove: (tempFile: File) -> Unit = {},
) : SessionStore {

    init {
        directory.mkdirs()
        // Leftovers from an interrupted save are never valid sessions.
        directory.listFiles { f -> f.name.contains(TEMP_MARKER) }?.forEach { it.delete() }
    }

    override fun save(record: SessionRecord): SaveResult {
        if (!ID_PATTERN.matches(record.id)) return SaveResult.Failed("invalid session id")
        val text = try {
            SessionCodec.encode(record)
        } catch (e: IllegalArgumentException) {
            return SaveResult.Failed(e.message ?: "record rejected")
        }
        val sequence = (savedFiles()[record.id]?.maxOf { it.first } ?: 0) + 1
        val target = File(directory, "${record.id}.$sequence.json")
        if (target.exists()) return SaveResult.Failed("save target already exists")
        val temp = File(directory, "${record.id}.$sequence.json$TEMP_MARKER")
        return try {
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.channel.force(true)
            }
            beforeMove(temp)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            SaveResult.Saved(target.name, sequence)
        } catch (e: AtomicMoveNotSupportedException) {
            temp.delete()
            SaveResult.Failed("atomic move not supported on this file system")
        } catch (e: IOException) {
            temp.delete()
            SaveResult.Failed("could not write session: ${e.message}")
        }
    }

    override fun list(): List<StoredSession> {
        val result = mutableListOf<StoredSession>()
        for ((id, files) in savedFiles()) {
            var found: StoredSession.Readable? = null
            for ((_, file) in files.sortedByDescending { it.first }) {
                when (val decoded = read(file, id)) {
                    is StoredSession.Readable -> if (found == null) found = decoded
                    is StoredSession.Unreadable -> result += decoded
                }
                if (found != null) break
            }
            found?.let { result += it }
        }
        return result.sortedByDescending { (it as? StoredSession.Readable)?.record?.startedAt ?: Long.MIN_VALUE }
    }

    override fun load(id: String): SessionRecord? {
        if (!ID_PATTERN.matches(id)) return null
        return savedFiles()[id].orEmpty()
            .sortedByDescending { it.first }
            .firstNotNullOfOrNull { (_, file) -> (read(file, id) as? StoredSession.Readable)?.record }
    }

    private fun read(file: File, expectedId: String): StoredSession = try {
        when (val decoded = SessionCodec.decode(file.readText(Charsets.UTF_8))) {
            is DecodeResult.Ok ->
                if (decoded.record.id == expectedId) {
                    StoredSession.Readable(file.name, decoded.record)
                } else {
                    StoredSession.Unreadable(file.name, "session id does not match file name")
                }
            is DecodeResult.Invalid -> StoredSession.Unreadable(file.name, decoded.reason)
        }
    } catch (e: IOException) {
        StoredSession.Unreadable(file.name, "could not read file")
    }

    /** Final save files grouped by session id, with their sequence numbers. */
    private fun savedFiles(): Map<String, List<Pair<Int, File>>> =
        directory.listFiles().orEmpty()
            .mapNotNull { f -> FILE_PATTERN.matchEntire(f.name)?.let { m -> Triple(m.groupValues[1], m.groupValues[2].toInt(), f) } }
            .groupBy({ it.first }, { it.second to it.third })

    private companion object {
        const val TEMP_MARKER = ".tmp"
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        val FILE_PATTERN = Regex("([A-Za-z0-9-]{1,64})\\.([1-9][0-9]{0,8})\\.json")
    }
}
