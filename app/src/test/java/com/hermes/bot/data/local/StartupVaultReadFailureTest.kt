package com.hermes.bot.data.local

import com.hermes.bot.data.gateway.SessionSummary
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Injects an unavailable cipher category; does not emulate or certify Android Keystore. */
class StartupVaultReadFailureTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun unavailableKeyPreservesEncryptedStartupRecordsAndRetryRecovers() = runBlocking {
        val directory = temp.newFolder()
        val software = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        var unavailable = false
        val cipher = object : VaultCipherEngine by software {
            override fun decrypt(ciphertextWithIv: ByteArray, associatedData: ByteArray?): Result<ByteArray> =
                if (unavailable) Result.failure(KeyStoreUnavailableException("synthetic unavailable"))
                else software.decrypt(ciphertextWithIv, associatedData)
        }
        val vault = EncryptedVault(directory, cipher, main.testDispatcher)
        vault.saveConnectionProfile(ConnectionProfile("synthetic-profile", "http://fixture.invalid:9119", "Hermes"))
        vault.setActiveProfileId("synthetic-profile")
        vault.saveAuthCredential(AuthCredential("cookie_session", "basic", mapOf("session" to "synthetic"), "http://fixture.invalid:9119"))
        val partition = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        vault.saveSessionSummaryCache(SessionSummaryCache(
            listOf(SessionSummary("synthetic-session", "Saved", "", 50.0, 1, "android")),
            profileLocalId = "synthetic-profile"), partition)
        fun bytes() = directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).path to it.readBytes() }
        val before = bytes()
        unavailable = true
        suspend fun expectUnavailable(block: suspend () -> Any?) {
            try { block(); fail("Must fail closed while the cipher is unavailable") }
            catch (_: KeyStoreUnavailableException) { }
        }
        expectUnavailable { vault.listConnectionProfiles() }
        expectUnavailable { vault.getConnectionProfile() }
        expectUnavailable { vault.getAuthCredential() }
        expectUnavailable { vault.readSessionSummaryCache(partition) }
        val after = bytes()
        assertEquals(before.keys, after.keys)
        before.forEach { (name, value) -> assertArrayEquals(value, after[name]) }
        unavailable = false
        assertNotNull(vault.getConnectionProfile())
        assertNotNull(vault.getAuthCredential())
        assertTrue(vault.readSessionSummaryCache(partition) is CacheReadResult.Available)
    }
}
