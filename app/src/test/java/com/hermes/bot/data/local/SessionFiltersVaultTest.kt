package com.hermes.bot.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SessionFiltersVaultTest {
    @get:Rule val temp = TemporaryFolder()
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private fun vault(dir: File) = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)
    private suspend fun prepare(vault: EncryptedVault, id: String = "profile-a", origin: String = "http://example.invalid:8080") {
        vault.saveConnectionProfile(ConnectionProfile(id, origin, "Synthetic"))
        vault.setActiveProfileId(id)
    }

    @Test fun survivesRestartAndCacheClear_butFullResetRemovesRecord() = runBlocking {
        val dir = temp.newFolder()
        val first = vault(dir)
        prepare(first)
        val scope = first.getSessionFilters()!!.scope
        first.saveSessionFilters(SessionFilters(scope, true, emptySet()))
        first.clearAuthCredential()
        first.clearCache()
        assertEquals(SessionFilters(scope, true, emptySet()), vault(dir).getSessionFilters())
        first.clearAll()
        prepare(first)
        assertEquals(SessionFilters(scope), first.getSessionFilters())
    }

    @Test fun scopeSeparatesWorkspacesProfilesAndOrigins_andRejectsStaleWrite() = runBlocking {
        val dir = temp.newFolder()
        val v = vault(dir)
        prepare(v)
        val unscoped = v.getSessionFilters()!!.scope
        v.saveSessionFilters(SessionFilters(unscoped, true, setOf("android")))
        v.saveSelectedContextProfile("workspace-a")
        val workspace = v.getSessionFilters()!!.scope
        assertEquals(SessionFilters(workspace), v.getSessionFilters())
        assertTrue(runCatching { v.saveSessionFilters(SessionFilters(unscoped)) }.isFailure)
        v.saveSessionFilters(SessionFilters(workspace, false, setOf("tui")))
        v.clearSelectedContextProfile()
        assertEquals(setOf("android"), v.getSessionFilters()!!.selectedSources)
        prepare(v, "profile-b")
        assertNull(v.getSessionFilters()!!.selectedSources)
        prepare(v, "profile-a", "http://other.example.invalid:8080")
        assertNull(v.getSessionFilters()!!.selectedSources)
        prepare(v)
        assertEquals(setOf("android"), v.getSessionFilters()!!.selectedSources)
    }

    @Test fun failedAtomicWritePreservesPriorAndUnreadableRecordIsNotOverwritten() = runBlocking {
        val dir = temp.newFolder()
        val v = vault(dir)
        prepare(v)
        val scope = v.getSessionFilters()!!.scope
        v.saveSessionFilters(SessionFilters(scope, true, setOf("synthetic-source")))
        val file = dir.listFiles()!!.single { it.name.startsWith("session_filters_") && it.name.endsWith(".enc") }
        assertFalse(String(file.readBytes()).contains("synthetic-source"))
        val failing = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined,
            atomicMove = { _, _ -> throw IOException("Synthetic failure") })
        assertTrue(runCatching { failing.saveSessionFilters(SessionFilters(scope)) }.isFailure)
        assertEquals(setOf("synthetic-source"), v.getSessionFilters()!!.selectedSources)
        file.writeBytes(byteArrayOf(1, 2, 3))
        val prior = file.readBytes()
        assertTrue(runCatching { v.getSessionFilters() }.isFailure)
        assertTrue(runCatching { v.saveSessionFilters(SessionFilters(scope)) }.isFailure)
        assertArrayEquals(prior, file.readBytes())
    }
}
