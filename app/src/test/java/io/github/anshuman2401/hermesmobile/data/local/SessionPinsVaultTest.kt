package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SessionPinsVaultTest {
    @get:Rule val temp = TemporaryFolder()
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private fun vault(dir: File) = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)
    private suspend fun prepare(vault: EncryptedVault, id: String = "profile-a", origin: String = "http://example.invalid:8080") {
        vault.saveConnectionProfile(ConnectionProfile(id, origin, "Synthetic"))
        vault.setActiveProfileId(id)
    }

    @Test fun pinsSurviveRestartSignOutAndCacheClear_andFullResetRemovesThem() = runBlocking {
        val dir = temp.newFolder()
        val v = vault(dir)
        prepare(v)
        val scope = v.getSessionPins()!!.scope
        v.setSessionPinned(scope, "synthetic-session", true)
        v.clearAuthCredential()
        v.clearCache()
        val restarted = vault(dir)
        assertEquals(setOf("synthetic-session"), restarted.getSessionPins()!!.sessionIds)
        restarted.setSessionPinned(scope, "synthetic-session", false)
        assertTrue(vault(dir).getSessionPins()!!.sessionIds.isEmpty())
        restarted.setSessionPinned(scope, "synthetic-session", true)
        restarted.clearAll()
        prepare(restarted)
        assertTrue(restarted.getSessionPins()!!.sessionIds.isEmpty())
    }

    @Test fun pinsAreScoped_andStaleWriteCannotChangeAnotherWorkspace() = runBlocking {
        val v = vault(temp.newFolder())
        prepare(v)
        val unscoped = v.getSessionPins()!!.scope
        v.setSessionPinned(unscoped, "session-a", true)
        v.saveSelectedContextProfile("workspace-a")
        assertTrue(v.getSessionPins()!!.sessionIds.isEmpty())
        assertTrue(runCatching { v.setSessionPinned(unscoped, "session-b", true) }.isFailure)
        val workspace = v.getSessionPins()!!.scope
        v.setSessionPinned(workspace, "session-b", true)
        v.clearSelectedContextProfile()
        assertEquals(setOf("session-a"), v.getSessionPins()!!.sessionIds)
        prepare(v, "profile-b")
        assertTrue(v.getSessionPins()!!.sessionIds.isEmpty())
        v.setActiveProfileId("profile-a")
        prepare(v, "profile-a", "http://other.example.invalid:8080")
        assertTrue(v.getSessionPins()!!.sessionIds.isEmpty())
        prepare(v)
        assertEquals(setOf("session-a"), v.getSessionPins()!!.sessionIds)
    }

    @Test fun concurrentUpdatesDoNotLosePins_andMissingRowsNeverDeleteThem() = runBlocking {
        val v = vault(temp.newFolder())
        prepare(v)
        val scope = v.getSessionPins()!!.scope
        (1..20).map { i -> async(Dispatchers.Default) { v.setSessionPinned(scope, "synthetic-$i", true) } }.awaitAll()
        assertEquals(20, v.getSessionPins()!!.sessionIds.size)
        v.setSessionPinned(scope, "not-present", false)
        assertEquals(20, v.getSessionPins()!!.sessionIds.size)
    }

    @Test fun failedAtomicSavePreservesPriorPins() = runBlocking {
        val dir = temp.newFolder()
        val v = vault(dir)
        prepare(v)
        val scope = v.getSessionPins()!!.scope
        v.setSessionPinned(scope, "session-a", true)
        val failing = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined,
            atomicMove = { _, _ -> throw IOException("Synthetic failure") })
        assertTrue(runCatching { failing.setSessionPinned(scope, "session-b", true) }.isFailure)
        assertEquals(setOf("session-a"), v.getSessionPins()!!.sessionIds)
    }

    @Test fun corruptOrFutureRecordsAreNotSilentlyOverwritten_andIdsAreEncrypted() = runBlocking {
        val dir = temp.newFolder()
        val v = vault(dir)
        prepare(v)
        val scope = v.getSessionPins()!!.scope
        v.setSessionPinned(scope, "synthetic-sensitive-id", true)
        val file = dir.listFiles()!!.single { it.name.startsWith("session_pins_") && it.name.endsWith(".enc") }
        assertFalse(String(file.readBytes()).contains("synthetic-sensitive-id"))
        val id = file.name.removePrefix("session_pins_").removeSuffix(".enc")
        val aad = "SESSION_PINS:1:$id:".toByteArray()
        val obj = GatewayJson.parseOrNull(String(cipher.decrypt(file.readBytes(), aad).getOrThrow()))!!.asObjectOrNull()!!
        val future = String(cipher.decrypt(file.readBytes(), aad).getOrThrow()).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        assertEquals(1, obj["schemaVersion"]!!.asIntOrNull())
        file.writeBytes(cipher.encrypt(future.toByteArray(), aad))
        val bytes = file.readBytes()
        assertTrue(runCatching { v.getSessionPins() }.isFailure)
        assertTrue(runCatching { v.setSessionPinned(scope, "another", true) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { v.setSessionPinned(scope, "another", true) }.isFailure)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
}
