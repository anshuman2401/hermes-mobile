package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BotFavouritesVaultTest {
    @get:Rule val temp = TemporaryFolder()
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private val origin = "http://example.invalid:8080"
    private fun vault(dir: File) = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)
    private suspend fun prepare(v: EncryptedVault, id: String = "synthetic-profile", host: String = origin) {
        v.saveConnectionProfile(ConnectionProfile(id, host, "Synthetic")); v.setActiveProfileId(id)
        v.saveAuthCredential(AuthCredential(driverKind = AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = "password", canonicalOrigin = host, cookies = mapOf("session_id" to "synthetic-cookie")), id)
    }
    @Test fun stableOrderRestartAndWorkspaceIndependence() = runBlocking {
        val dir = temp.newFolder(); val v = vault(dir); prepare(v)
        var prefs = v.getBotFavourites("synthetic-profile", origin)!!
        prefs = v.setBotFavourite(prefs, "Beta", true); prefs = v.setBotFavourite(prefs, "Alpha", true)
        prefs = v.setBotFavourite(prefs, "Beta", true)
        assertEquals(listOf("Beta", "Alpha"), prefs.names)
        v.saveSelectedContextProfile("other-synthetic-workspace")
        assertEquals(prefs.names, vault(dir).getBotFavourites("synthetic-profile", origin)!!.names)
        prefs = v.setBotFavourite(prefs, "Beta", false); prefs = v.setBotFavourite(prefs, "Beta", true)
        assertEquals(listOf("Alpha", "Beta"), prefs.names)
        v.clearCache(); assertEquals(prefs.names, v.getBotFavourites("synthetic-profile", origin)!!.names)
        v.clearAll(); prepare(v); assertTrue(v.getBotFavourites("synthetic-profile", origin)!!.names.isEmpty())
    }
    @Test fun signOutReplacementAndDifferentConnectionRejectStaleWrites() = runBlocking {
        val v = vault(temp.newFolder()); prepare(v)
        val old = v.getBotFavourites("synthetic-profile", origin)!!
        v.setBotFavourite(old, "Alpha", true); v.clearAuthCredential()
        assertNull(v.getBotFavourites("synthetic-profile", origin))
        assertTrue(runCatching { v.setBotFavourite(old, "Beta", true) }.isFailure)
        prepare(v); assertEquals(listOf("Alpha"), v.getBotFavourites("synthetic-profile", origin)!!.names)
        assertTrue(runCatching { v.setBotFavourite(old, "Beta", true) }.isFailure)
        val current = v.getBotFavourites("synthetic-profile", origin)!!
        prepare(v, "other-profile"); assertNull(v.getBotFavourites("synthetic-profile", origin))
        assertTrue(v.getBotFavourites("other-profile", origin)!!.names.isEmpty())
        assertTrue(runCatching { v.setBotFavourite(current, "Beta", true) }.isFailure)
        prepare(v, host = "http://other.example.invalid:8080")
        assertNull(v.getBotFavourites("synthetic-profile", origin))
        assertTrue(v.getBotFavourites("synthetic-profile", "http://other.example.invalid:8080")!!.names.isEmpty())
    }
    @Test fun concurrentEditsAreSerializedAndBoundsPreservePriorRecord() = runBlocking {
        val v = vault(temp.newFolder()); prepare(v); val prefs = v.getBotFavourites("synthetic-profile", origin)!!
        (1..20).map { i -> async(Dispatchers.Default) { v.setBotFavourite(prefs, "synthetic-$i", true) } }.awaitAll()
        assertEquals(20, v.getBotFavourites("synthetic-profile", origin)!!.names.size)
        assertTrue(runCatching { v.setBotFavourite(prefs, "x".repeat(513), true) }.isFailure)
        (21..100).forEach { v.setBotFavourite(prefs, "synthetic-$it", true) }
        assertTrue(runCatching { v.setBotFavourite(prefs, "overflow", true) }.isFailure)
        assertEquals(100, v.getBotFavourites("synthetic-profile", origin)!!.names.size)
    }
    @Test fun corruptFutureOversizedAndAtomicFailureNeverOverwrite() = runBlocking {
        val dir = temp.newFolder(); val v = vault(dir); prepare(v); val prefs = v.getBotFavourites("synthetic-profile", origin)!!
        v.setBotFavourite(prefs, "synthetic-sensitive-name", true)
        val file = dir.listFiles()!!.single { it.name.startsWith("bot_favourites_") && it.name.endsWith(".enc") }
        val prior = file.readBytes(); assertFalse(String(prior).contains("synthetic-sensitive-name"))
        val failing = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined, atomicMove = { _, _ -> throw IOException("Synthetic failure") })
        assertTrue(runCatching { failing.setBotFavourite(prefs, "Beta", true) }.isFailure); assertArrayEquals(prior, file.readBytes())
        val id = file.name.removePrefix("bot_favourites_").removeSuffix(".enc")
        val aad = "BOT_FAVOURITES:1:$id:".toByteArray()
        val future = String(cipher.decrypt(prior, aad).getOrThrow()).replace("\"schemaVersion\":1", "\"schemaVersion\":2")
        for (bytes in listOf(cipher.encrypt(future.toByteArray(), aad), byteArrayOf(1,2,3), ByteArray(128 * 1024 + 1))) {
            file.writeBytes(bytes)
            assertTrue(runCatching { v.getBotFavourites("synthetic-profile", origin) }.isFailure)
            assertTrue(runCatching { v.setBotFavourite(prefs, "Beta", true) }.isFailure)
            assertArrayEquals(bytes, file.readBytes())
        }
    }
}
