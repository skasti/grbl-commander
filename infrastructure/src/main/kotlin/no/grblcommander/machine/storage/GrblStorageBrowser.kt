package no.grblcommander.machine.storage

import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import no.grblcommander.machine.protocol.GrblProtocol
import no.grblcommander.machine.session.CommandAcknowledgment

/** Read-only VFS requests. The caller reserves one session for the entire request. */
class GrblStorageBrowser(private val execute: suspend (String) -> CommandAcknowledgment) {
    data class Directory(
        val path: String,
        val entries: List<GrblStorageReports.Entry>,
        /** Recursive firmware listings omit directories without visible files. */
        val mayOmitEmptyDirectories: Boolean,
    )

    suspend fun mounts(): List<GrblStorageReports.Mount> =
        GrblStorageReports.mounts(request("\$FI"))

    suspend fun list(path: String): Directory {
        val target = path.trimEnd('/').ifEmpty { if (path.startsWith('/')) "/" else "" }
        val targetCommand = changeDirectory(target)
        val original = GrblStorageReports.workingDirectory(request("\$PWD"))
        val restore = if (target == original) null else changeDirectory(original)
        val options = execute("\$650")
        val hierarchical = when (options.errorCode) {
            null -> options.responseLines.singleOrNull { it.startsWith("\$650=") }
                ?.substringAfter('=')?.toIntOrNull()?.let { it and 4 != 0 }
            3, 53 -> null // Older firmware can list files without exposing this setting.
            else -> reject("\$650", options.errorCode)
        }
        var failure: Throwable? = null
        var restoreRequired = false
        try {
            if (restore != null) {
                restoreRequired = true
                val response = execute(targetCommand)
                response.errorCode?.let { code ->
                    // Unsupported firmware cannot have applied the directory change.
                    if (code == 3 || code == 53) restoreRequired = false
                    reject(targetCommand, code)
                }
            }
            val cwd = if (restore == null) original else GrblStorageReports.workingDirectory(request("\$PWD"))
            val entries = children(cwd, GrblStorageReports.entries(request("\$F+")))
            return Directory(cwd, entries, hierarchical != true)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (restoreRequired && restore != null) {
                try {
                    // The caller's session/epoch guard still applies during cancellation cleanup.
                    withContext(NonCancellable) { request(restore) }
                } catch (error: Exception) {
                    val restoreFailure = IOException("Could not restore controller working directory to $original", error)
                    if (failure == null) throw restoreFailure
                    failure.addSuppressed(restoreFailure)
                }
            }
        }
    }

    private suspend fun request(command: String): List<String> {
        val response = execute(command)
        response.errorCode?.let { reject(command, it) }
        return response.responseLines
    }

    private fun changeDirectory(path: String): String {
        val command = "\$CWD=$path"
        val relative = path.removePrefix("/")
        if (!path.startsWith('/') || '\\' in path ||
            (relative.isNotEmpty() && relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) ||
            GrblProtocol.commandValidationError(command) != null
        ) {
            throw ControllerStorageException(ControllerStorageException.Kind.INVALID_PATH, "Cannot send controller path: $path")
        }
        return command
    }

    private fun children(cwd: String, records: List<GrblStorageReports.Entry>): List<GrblStorageReports.Entry> {
        val prefix = cwd.trimEnd('/') + "/"
        val entries = linkedMapOf<String, GrblStorageReports.Entry>()
        for (record in records) {
            val relative = if (record.path.startsWith('/')) {
                if (!record.path.startsWith(prefix)) throw IOException("Storage entry outside $cwd: ${record.path}")
                record.path.removePrefix(prefix)
            } else record.path
            if (record.directory && relative == "..") continue // Firmware's parent navigation marker.
            if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
                throw IOException("Invalid path in controller listing: ${record.path}")
            }
            val name = relative.substringBefore('/')
            val path = prefix + name
            if ('/' in relative) {
                entries.putIfAbsent(path, GrblStorageReports.Entry(path, null, directory = true, usable = true))
            } else {
                entries[path] = record.copy(path = path)
            }
        }
        return entries.values.toList()
    }

    private fun reject(command: String, code: Int): Nothing {
        val kind = when (code) {
            3, 53 -> ControllerStorageException.Kind.UNSUPPORTED
            62, 84 -> ControllerStorageException.Kind.MEDIA_ERROR
            63 -> ControllerStorageException.Kind.DIRECTORY_UNAVAILABLE
            65 -> ControllerStorageException.Kind.NOT_MOUNTED
            78 -> ControllerStorageException.Kind.ACCESS_DENIED
            else -> ControllerStorageException.Kind.REJECTED
        }
        throw ControllerStorageException(kind, "$command rejected with error:$code", code)
    }
}
