package io.github.anshuman2401.hermesmobile.data.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

sealed interface PrivateLogResult<out T> {
    data class Success<T>(val value: T) : PrivateLogResult<T>
    data object Unavailable : PrivateLogResult<Nothing>
    data class IoFailure(val exceptionClass: String) : PrivateLogResult<Nothing>
}

data class PrivateLogRead(
    val linesNewestFirst: List<String>,
    val malformedCount: Int,
    val bounded: Boolean
)

interface PrivateDiagnosticLogStore {
    suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead>
}

/**
 * App-private, best-effort diagnostics. Callers only offer immutable catalog records;
 * one IO actor owns every directory, file, append, flush, and read operation.
 */
class PrivateDiagnosticsRepository private constructor(
    private val filesDir: File,
    private val scope: CoroutineScope
) : Diagnostics, PrivateDiagnosticLogStore {
    private sealed interface Command {
        data class Append(val severity: DiagnosticSeverity, val record: String) : Command
        data class Read(val severity: DiagnosticSeverity, val reply: CompletableDeferred<PrivateLogResult<PrivateLogRead>>) : Command
    }

    private val fileLock = ReentrantLock()
    private val mailbox = Channel<Command>(capacity = QUEUE_CAPACITY)
    @Volatile private var droppedCount = 0L
    @Volatile private var degraded = false

    init {
        scope.launch(Dispatchers.IO) {
            for (command in mailbox) {
                when (command) {
                    is Command.Append -> append(command)
                    is Command.Read -> command.reply.complete(readInternal(command.severity))
                }
            }
        }
    }

    override fun emit(event: CatalogEvent, metadata: DiagnosticMetadata) {
        val command = Command.Append(event.severity, DiagnosticRecordEncoder.encode(event, metadata))
        if (mailbox.trySend(command).isFailure) droppedCount++
    }

    override suspend fun read(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> {
        val reply = CompletableDeferred<PrivateLogResult<PrivateLogRead>>()
        mailbox.send(Command.Read(severity, reply))
        return reply.await()
    }

    internal fun degradedStateForTest(): Pair<Long, Boolean> = droppedCount to degraded

    /** Fatal path bypasses the actor and never waits for its file lock. */
    fun captureCrash(error: Throwable) {
        if (!fileLock.tryLock()) return
        try {
            append(Command.Append(DiagnosticSeverity.ERROR, DiagnosticRecordEncoder.encode(
                CatalogEvent.APP_CRASH, DiagnosticMetadata.forEvent(CatalogEvent.APP_CRASH, DiagnosticField.ExceptionClass.from(error))
            )))
        } finally { fileLock.unlock() }
    }

    private fun append(command: Command.Append) = fileLock.withLock {
        runCatching {
            val directory = diagnosticDirectory()
            if (!directory.exists() && !directory.mkdirs()) error("DiagnosticsDirectoryUnavailable")
            DiagnosticSeverity.entries.forEach { severity ->
                val file = File(directory, severity.fileName)
                if (!file.exists() && !file.createNewFile()) error("DiagnosticsFileUnavailable")
            }
            FileOutputStream(File(directory, command.severity.fileName), true).use { output ->
                output.write(command.record.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }.onFailure { degraded = true }
    }

    private fun readInternal(severity: DiagnosticSeverity): PrivateLogResult<PrivateLogRead> = fileLock.withLock {
        try {
        val file = File(diagnosticDirectory(), severity.fileName)
        if (!file.isFile) return@withLock PrivateLogResult.Success(PrivateLogRead(emptyList(), 0, false))
        val window = readFinalWindow(file, MAX_DISPLAY_BYTES)
        val parsed = DiagnosticJsonl.readComplete(ByteArrayInputStream(window.bytes), DiagnosticRecordEncoder.MAX_RECORD_BYTES)
        val retained = parsed.records.takeLast(MAX_DISPLAY_LINES)
        PrivateLogResult.Success(
            PrivateLogRead(
                linesNewestFirst = retained.asReversed(),
                malformedCount = parsed.rejectedCount + window.leadingPartialLineCount,
                bounded = window.truncated || parsed.records.size > MAX_DISPLAY_LINES
            )
        )
    } catch (exception: Exception) {
        degraded = true
        PrivateLogResult.IoFailure(exception.javaClass.simpleName)
    }
    }

    private fun diagnosticDirectory(): File = File(filesDir, DIAGNOSTICS_DIRECTORY).canonicalFile

    private data class FinalWindow(val bytes: ByteArray, val truncated: Boolean, val leadingPartialLineCount: Int)

    private fun readFinalWindow(file: File, maxBytes: Long): FinalWindow {
        val size = file.length()
        val truncated = size > maxBytes
        val start = (size - maxBytes).coerceAtLeast(0L)
        FileInputStream(file).use { input ->
            if (start > 0) input.channel.position((start - 1).coerceAtLeast(0L))
            val limit = (maxBytes + if (start > 0) 1 else 0).toInt()
            val output = ByteArrayOutputStream(limit)
            val buffer = ByteArray(minOf(8 * 1024, limit))
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count == -1) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            val raw = output.toByteArray()
            if (start == 0L) return FinalWindow(raw, false, 0)
            val newline = raw.indexOf('\n'.code.toByte())
            return if (newline >= 0) {
                FinalWindow(raw.copyOfRange(newline + 1, raw.size), true, 1)
            } else {
                FinalWindow(ByteArray(0), true, if (raw.isEmpty()) 0 else 1)
            }
        }
    }

    companion object {
        const val QUEUE_CAPACITY = 256
        const val MAX_DISPLAY_LINES = 2_000
        const val MAX_DISPLAY_BYTES = 1L * 1024 * 1024
        private const val DIAGNOSTICS_DIRECTORY = "diagnostics"

        fun fromContext(filesDir: File): PrivateDiagnosticsRepository =
            PrivateDiagnosticsRepository(filesDir, CoroutineScope(SupervisorJob() + Dispatchers.IO))

        internal fun forTesting(filesDir: File): PrivateDiagnosticsRepository =
            PrivateDiagnosticsRepository(filesDir, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
