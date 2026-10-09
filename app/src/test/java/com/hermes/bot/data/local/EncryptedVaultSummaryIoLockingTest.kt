package com.hermes.bot.data.local

import com.hermes.bot.data.gateway.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PR-1 (review P3-4): session-summary I/O is serialized under the vault's
 * lifecycle mutex — the same lock every other vault mutation takes. The
 * PR-1 overrides (read/save/clear summary) must not run summary I/O
 * unlocked beneath it: an in-flight write blocks a concurrent clear, and
 * an in-flight read blocks a concurrent write (deterministic end state).
 */
class EncryptedVaultSummaryIoLockingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var engine: LatchingCipherEngine
    private lateinit var vault: EncryptedVault

    private val origin = "http://127.0.0.1:9123"

    /** Gates the FIRST schema-3 summary-record cipher operation per mode. */
    private class LatchingCipherEngine(
        private val delegate: VaultCipherEngine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    ) : VaultCipherEngine {
        @Volatile
        var blockNextSummaryEncrypt = false

        @Volatile
        var blockNextSummaryDecrypt = false

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        private fun isSummaryV3(associatedData: ByteArray?): Boolean =
            associatedData != null &&
                String(associatedData, Charsets.UTF_8).startsWith("SESSION_SUMMARY_CACHE:3:")

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (blockNextSummaryEncrypt && isSummaryV3(associatedData)) {
                blockNextSummaryEncrypt = false
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(ciphertextWithIv: ByteArray, associatedData: ByteArray?): Result<ByteArray> {
            if (blockNextSummaryDecrypt && isSummaryV3(associatedData)) {
                blockNextSummaryDecrypt = false
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            return delegate.decrypt(ciphertextWithIv, associatedData)
        }
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("summary_lock_test")
        engine = LatchingCipherEngine()
        vault = EncryptedVault(storageDir = storageDir, cipherEngine = engine)
    }

    private suspend fun seedActiveConnection() {
        vault.saveConnectionProfile(
            ConnectionProfile(localId = "conn-1", normalizedOrigin = origin, displayLabel = "Test Server")
        )
        vault.setActiveProfileId("conn-1")
        vault.saveAuthCredential(
            AuthCredential(
                driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
                providerId = "password",
                cookies = mapOf("session_id" to "tok"),
                canonicalOrigin = origin
            ),
            profileLocalId = "conn-1"
        )
    }

    private suspend fun requirePartition(): CacheAccessPartition =
        (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition

    private fun cache(id: String) = SessionSummaryCache(
        sessions = listOf(SessionSummary(id, "T", null, 1.0, 1, "android")),
        syncTimeEpochSeconds = System.currentTimeMillis() / 1000,
        profileLocalId = "conn-1"
    )

    @Test
    fun clearSessionSummaryCache_waitsForInFlightSummaryWrite() = runBlocking {
        seedActiveConnection()
        val partition = requirePartition()

        engine.blockNextSummaryEncrypt = true
        val writeJob = launch(Dispatchers.IO) {
            vault.saveSessionSummaryCache(cache("s-inflight"), partition)
        }
        assertTrue("Write must reach its summary cipher step", engine.entered.await(10, TimeUnit.SECONDS))

        val clearJob = launch { vault.clearSessionSummaryCache() }
        val clearFinishedDuringWrite = withTimeoutOrNull(1_500) {
            clearJob.join()
            true
        }
        assertNull(
            "clearSessionSummaryCache must serialize behind the in-flight summary write",
            clearFinishedDuringWrite
        )

        engine.release.countDown()
        writeJob.join()
        clearJob.join()

        assertTrue(
            "A clear issued during a write must win — no record may survive beneath the lock",
            vault.readSessionSummaryCache(partition) is CacheReadResult.Absent
        )
    }

    @Test
    fun saveSessionSummaryCache_waitsForInFlightSummaryRead() = runBlocking {
        seedActiveConnection()
        val partition = requirePartition()
        vault.saveSessionSummaryCache(cache("s-before"), partition)

        engine.blockNextSummaryDecrypt = true
        val readJob = launch(Dispatchers.IO) { vault.readSessionSummaryCache(partition) }
        assertTrue("Read must reach its summary cipher step", engine.entered.await(10, TimeUnit.SECONDS))

        val writeJob = launch { vault.saveSessionSummaryCache(cache("s-after"), partition) }
        val writeFinishedDuringRead = withTimeoutOrNull(1_500) {
            writeJob.join()
            true
        }
        assertNull(
            "saveSessionSummaryCache must serialize behind the in-flight summary read",
            writeFinishedDuringRead
        )

        engine.release.countDown()
        readJob.join()
        writeJob.join()

        val read = vault.readSessionSummaryCache(partition)
        assertTrue("Post-lock write must be readable: $read", read is CacheReadResult.Available)
        assertEquals("s-after", (read as CacheReadResult.Available).value.sessions.first().id)
    }
}
