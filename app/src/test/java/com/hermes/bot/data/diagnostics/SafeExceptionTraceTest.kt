package com.hermes.bot.data.diagnostics

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SafeExceptionTraceTest {
    private fun failure(): Throwable = IllegalStateException("synthetic-content-canary", IllegalArgumentException("synthetic-cause-canary")).apply {
        stackTrace = arrayOf(StackTraceElement("com.hermes.bot.Example", "load", "synthetic-path-canary", 42))
        cause!!.stackTrace = arrayOf(StackTraceElement("java.lang.Thread", "run", "synthetic-source-canary", 12))
    }

    @Test fun tracePreservesCoordinatesAndCausesWithoutMessagesOrPaths() {
        val trace = SafeExceptionTrace.capture(failure())
        assertTrue(trace.contains("com.hermes.bot.Example#load:42"))
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException"))
        assertFalse(trace.contains("canary"))
        assertEquals(trace, SafeExceptionTrace.validate(trace))
        assertNull(SafeExceptionTrace.validate(trace + "\nsynthetic-content-canary"))
    }

    @Test fun hostileCoordinatesAndCyclesAreOmittedAndLargeTracesRemainValid() {
        val error = failure().apply { stackTrace = Array(500) { StackTraceElement("untrusted.canary", "canary", "canary", 1) } }
        assertFalse(SafeExceptionTrace.capture(error).contains("canary"))
        val first = IllegalStateException()
        val second = IllegalStateException(first)
        first.initCause(second)
        assertTrue(SafeExceptionTrace.capture(first).contains("cycle omitted"))
        val long = "com.hermes.bot." + "A".repeat(220)
        var chain: Throwable? = null
        repeat(6) { chain = IllegalStateException("canary", chain).apply {
            stackTrace = Array(100) { StackTraceElement(long, "m".repeat(128), "canary", 1) }
        } }
        val trace = SafeExceptionTrace.capture(chain!!)
        assertTrue(trace.length <= 12_000)
        assertNotNull(SafeExceptionTrace.validate(trace))
    }

    @Test fun crashPersistsSynchronouslyAndSurvivesRepositoryRecreation() = runBlocking {
        val directory = Files.createTempDirectory("synthetic-diagnostics").toFile()
        try {
            PrivateDiagnosticsRepository.forTesting(directory).captureCrash(failure())
            val result = PrivateDiagnosticsRepository.forTesting(directory).read(DiagnosticSeverity.ERROR) as PrivateLogResult.Success
            val record = DiagnosticJsonl.parseComplete(result.value.linesNewestFirst.single())!!
            assertEquals("app_crash", record.code)
            assertTrue(record.structuredDisplay().contains("#load:42"))
            assertFalse(record.canonicalJsonLine().contains("canary"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun crashCaptureSkipsBusyFileLockAndDoesNotCorruptQueuedRecords() = runBlocking {
        val directory = Files.createTempDirectory("synthetic-diagnostics").toFile()
        val release = java.util.concurrent.CountDownLatch(1)
        val ready = java.util.concurrent.CountDownLatch(1)
        val repository = PrivateDiagnosticsRepository.forTesting(directory)
        val field = repository.javaClass.getDeclaredField("fileLock").apply { isAccessible = true }
        val lock = field.get(repository) as java.util.concurrent.locks.ReentrantLock
        val holder = Thread {
            lock.lock()
            try { ready.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
            finally { lock.unlock() }
        }
        try {
            holder.start()
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            repository.captureCrash(failure())
            release.countDown()
            holder.join(5_000)
            repository.emit(CatalogEvent.COMPOSITION_FAILED)
            val result = repository.read(DiagnosticSeverity.ERROR) as PrivateLogResult.Success
            assertEquals(0, result.value.malformedCount)
            assertEquals(listOf("composition_failed"), result.value.linesNewestFirst.map { DiagnosticJsonl.parseComplete(it)!!.code })
        } finally { release.countDown(); holder.join(5_000); directory.deleteRecursively() }
    }

    @Test fun persistedTraceRejectsFreeTextBeforeItCanReachTheReport() {
        val json = DiagnosticRecordEncoder.encode(CatalogEvent.APP_CRASH,
            DiagnosticMetadata.forEvent(CatalogEvent.APP_CRASH, DiagnosticField.ExceptionClass.from(failure())))
        val hostile = json.replace("com.hermes.bot.Example#load:42", "synthetic-content-canary")
        val parsed = DiagnosticJsonl.parseComplete(hostile)!!
        assertNull(parsed.trace)
        assertFalse(parsed.canonicalJsonLine().contains("canary"))
        assertFalse(parsed.structuredDisplay().contains("canary"))
    }

    @Test fun previousHandlerRunsEvenWhenCaptureFails() {
        val error = failure()
        var delivered: Throwable? = null
        val handler = DiagnosticCrashHandler({ throw IllegalStateException() }, Thread.UncaughtExceptionHandler { _, item -> delivered = item })
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, delivered)
    }
}
