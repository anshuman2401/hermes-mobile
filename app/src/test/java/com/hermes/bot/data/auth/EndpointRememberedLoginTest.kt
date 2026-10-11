package com.hermes.bot.data.auth

import com.hermes.bot.data.local.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.gateway.json.GatewayJson
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Synthetic fixtures; software crypto is an explicit JVM seam, never a platform-security claim. */
class EndpointRememberedLoginTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = CanonicalOrigin.parseOrThrow("http://fixture.invalid:9119")
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private lateinit var directory: File
    private lateinit var vault: EncryptedVault
    private lateinit var auth: DefaultAuthRepository
    private var passwordRequests = 0
    private var rejected = false
    private var entered: CompletableDeferred<Unit>? = null
    private var gate: CompletableDeferred<Unit>? = null
    private val transport = object : AuthHttpTransport {
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
            if (url.endsWith("/api/status")) HttpResponse(200, body =
                """{"gateway_running":true,"auth_required":true,"auth_providers":["basic"]}""")
            else {
                passwordRequests++
                entered?.complete(Unit)
                gate?.await()
                if (rejected) HttpResponse(401) else HttpResponse(200,
                    headers = mapOf("Set-Cookie" to listOf("session=synthetic-cookie; Path=/; HttpOnly")))
            }
    }
    @Before fun setup() = runBlocking {
        directory = temp.newFolder()
        restart()
        vault.saveConnectionProfile(ConnectionProfile("fixture-profile", origin.originUrl, "Fixture"))
    }
    private fun restart() {
        vault = EncryptedVault(directory, cipher)
        auth = DefaultAuthRepository(vault, transport)
    }
    private suspend fun login(remember: Boolean = true, username: String = "synthetic-user") {
        val password = "synthetic-password".toCharArray()
        assertTrue(auth.login(origin, username, password, remember = remember).isSuccess)
        assertTrue(password.all { it == '0' })
    }
    @Test fun signOutRetainsPasswordButBlocksBackgroundAcrossRestart() = runBlocking {
        login()
        auth.noteSuccessfulEndpoint(origin)
        auth.signOut(cacheChoice = SignOutCacheChoice.KEEP)
        restart()
        assertEquals(origin.host, auth.rememberedEndpoint()?.host)
        assertFalse(auth.prepareLocalLogin(origin))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        assertTrue(auth.recoverSession(origin).isFailure)
        assertEquals(1, passwordRequests)
        assertTrue(auth.recoverSession(origin, explicitConnect = true).isSuccess)
        assertTrue(auth.prepareLocalLogin(origin))
        auth.notifyAuthExpired()
        restart()
        assertTrue(auth.recoverSession(origin).isSuccess)
        assertEquals(3, passwordRequests)
    }
    @Test fun failedSuppressionWriteCannotUndoDurableSignOutAfterRestart() = runBlocking {
        var fail = false
        val failingCipher = object : VaultCipherEngine by cipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (fail && associatedData?.toString(Charsets.UTF_8)?.startsWith("ENDPOINT_LOGIN:") == true &&
                    plaintext.toString(Charsets.UTF_8).contains("\"automatic\":false"))
                    throw java.io.IOException("Synthetic suppression write failure")
                return cipher.encrypt(plaintext, associatedData)
            }
        }
        vault = EncryptedVault(directory, failingCipher)
        auth = DefaultAuthRepository(vault, transport)
        login()
        fail = true
        var failed = false
        try { auth.signOut(cacheChoice = SignOutCacheChoice.KEEP) }
        catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        restart()
        assertTrue(auth.hasRememberedLogin(origin))
        assertFalse(auth.hasAutomaticLogin(origin))
        assertFalse(auth.prepareLocalLogin(origin))
        assertNull(auth.getStoredCredential())
        assertTrue(auth.recoverSession(origin).isFailure)
        assertEquals(1, passwordRequests)
        assertTrue(auth.recoverSession(origin, explicitConnect = true).isSuccess)
    }

    @Test fun uncheckedSuccessfulLoginRemovesPreviousPasswordWithoutBreakingCookie() = runBlocking {
        login()
        login(remember = false)
        assertFalse(auth.rememberLoginPreference(origin))
        assertTrue(auth.hasValidSession(origin))
        auth.notifyAuthExpired()
        restart()
        assertTrue(auth.recoverSession(origin, explicitConnect = true).isFailure)
        assertEquals(2, passwordRequests)
        login()
        assertTrue(auth.rememberLoginPreference(origin))
    }
    @Test fun failedUncheckedLoginPreservesRememberedAccount() = runBlocking {
        login()
        rejected = true
        assertTrue(auth.login(origin, "other-synthetic-user", "synthetic-password".toCharArray(), remember = false).isFailure)
        rejected = false
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(origin).isSuccess)
        assertTrue(auth.rememberLoginPreference(origin))
    }
    @Test fun forgetKeepsCurrentCookieAndPreferencesButNeverRecoversPassword() = runBlocking {
        login()
        auth.noteSuccessfulEndpoint(origin)
        auth.forgetRememberedLogin(origin)
        assertTrue(auth.hasValidSession(origin))
        assertEquals(origin, auth.rememberedEndpoint())
        auth.notifyAuthExpired()
        restart()
        assertTrue(auth.recoverSession(origin, true).isFailure)
        assertEquals(1, passwordRequests)
    }
    @Test fun credentialCanBeUsedByAnotherProfileForSameEndpointWithoutSharingCacheAuthority() = runBlocking {
        login()
        val original = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        vault.saveConnectionProfile(ConnectionProfile("duplicate-profile", origin.originUrl, "Duplicate"))
        assertFalse(auth.prepareLocalLogin(origin))
        assertTrue(auth.recoverSession(origin).isSuccess)
        val duplicate = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        assertNotEquals(original, duplicate)
        assertEquals(2, passwordRequests)
    }
    @Test fun navigationRevokesAccessWithoutSuppressingFutureAutomaticLogin() = runBlocking {
        login()
        auth.leaveConnection()
        assertNull(auth.getStoredCredential())
        assertFalse(auth.prepareLocalLogin(origin))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        restart()
        assertTrue(auth.hasAutomaticLogin(origin))
        assertTrue(auth.recoverSession(origin).isSuccess)
        assertEquals(2, passwordRequests)
    }
    @Test fun otherPortSchemeAndHostCannotBorrowPassword() = runBlocking {
        login()
        listOf("http://fixture.invalid:9120", "https://fixture.invalid:9119", "http://other.invalid:9119").forEach {
            assertTrue(auth.recoverSession(CanonicalOrigin.parseOrThrow(it), true).isFailure)
        }
        assertEquals(1, passwordRequests)
    }
    @Test fun rejectedPasswordCannotBeRepeatedByExplicitConnectAfterRestart() = runBlocking {
        login()
        auth.notifyAuthExpired()
        rejected = true
        assertTrue(auth.recoverSession(origin).exceptionOrNull() is SavedPasswordRejectedException)
        restart()
        assertTrue(auth.recoverSession(origin, true).isFailure)
        assertEquals(2, passwordRequests)
        rejected = false
        login()
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(origin).isSuccess)
    }
    @Test fun lateLoginCannotUndoForget() = runBlocking {
        login()
        entered = CompletableDeferred()
        gate = CompletableDeferred()
        val pending = async { auth.login(origin, "synthetic-user", "synthetic-password".toCharArray()) }
        entered!!.await()
        auth.forgetRememberedLogin(origin)
        gate!!.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is StaleAuthenticatedResponseException)
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(origin, true).isFailure)
    }
    @Test fun ciphertextCannotBeSwappedBetweenPorts() = runBlocking {
        login()
        val first = directory.listFiles()!!.first { it.name.startsWith("endpoint_login_") }.readBytes()
        val other = CanonicalOrigin.parseOrThrow("http://fixture.invalid:9120")
        vault.saveConnectionProfile(ConnectionProfile("other-profile", other.originUrl, "Other"))
        assertTrue(auth.login(other, "synthetic-user", "synthetic-password".toCharArray()).isSuccess)
        val owner = vault.captureLoginOwner(other.originUrl)!!
        val second = directory.listFiles()!!.filter { it.name.startsWith("endpoint_login_") }.first { !it.readBytes().contentEquals(first) }
        second.writeBytes(first)
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(other, true).isFailure)
        assertEquals(2, passwordRequests)
        assertNotNull(owner)
    }

    @Test fun preferencesUseMostRecentSuccessfulPortAndNormalizeHostIncludingIpv6() = runBlocking {
        val otherPort = CanonicalOrigin.parseOrThrow("http://FIXTURE.invalid:9120")
        auth.noteSuccessfulEndpoint(origin)
        auth.noteSuccessfulEndpoint(otherPort)
        restart()
        assertEquals(otherPort, auth.rememberedEndpoint(" Fixture.INVALID "))
        val ipv6 = CanonicalOrigin.parseOrThrow("http://[2001:db8::1]:9119")
        auth.noteSuccessfulEndpoint(ipv6)
        assertEquals(ipv6, auth.rememberedEndpoint("[2001:db8::1]"))
        assertEquals(ipv6, auth.rememberedEndpoint())
        assertNull(auth.rememberedEndpoint("unknown.invalid"))
    }
    @Test fun cacheResetKeepsSecretsAndWholeResetDeletesThem() = runBlocking {
        login()
        auth.noteSuccessfulEndpoint(origin)
        vault.clearAllCacheRecords()
        restart()
        assertEquals(origin, auth.rememberedEndpoint())
        assertTrue(auth.recoverSession(origin, true).isSuccess)
        vault.clearAll()
        restart()
        assertNull(auth.rememberedEndpoint())
        assertTrue(auth.recoverSession(origin, true).isFailure)
    }
    @Test fun deletingFinalReferenceForAnEndpointForgetsItAndRepairsPreferences() = runBlocking {
        login()
        auth.noteSuccessfulEndpoint(origin)
        val other = CanonicalOrigin.parseOrThrow("http://other.invalid:9119")
        vault.saveConnectionProfile(ConnectionProfile("other-profile", other.originUrl, "Other"))
        auth.noteSuccessfulEndpoint(other)
        vault.setActiveProfileId("fixture-profile")
        assertTrue(vault.deleteConnectionProfile("other-profile"))
        assertEquals(origin, auth.rememberedEndpoint())
        assertFalse(auth.rememberLoginPreference(other))
    }
    @Test fun encryptedFilesContainNoPlaintextCredentialsOrEndpoint() = runBlocking {
        login()
        auth.noteSuccessfulEndpoint(origin)
        val forbidden = listOf("synthetic-user", "synthetic-password", "fixture.invalid")
        directory.walkTopDown().filter { it.isFile }.forEach { file ->
            val contents = file.readBytes().toString(Charsets.UTF_8)
            forbidden.forEach { assertFalse(contents.contains(it)); assertFalse(file.name.contains(it)) }
        }
    }
    @Test fun corruptRecordFailsClosedAcrossRestartAndCannotReviveLegacyPassword() = runBlocking {
        login()
        val file = directory.listFiles()!!.first { it.name.startsWith("endpoint_login_") }
        file.writeBytes(byteArrayOf(1, 2, 3))
        auth.notifyAuthExpired()
        repeat(2) {
            restart()
            assertTrue(auth.recoverSession(origin, true).isFailure)
            assertTrue(file.exists())
        }
        assertEquals(1, passwordRequests)
    }
    @Test fun legacyPasswordMigratesDestinationFirstAndLegacyCopyIsRemoved() = runBlocking {
        login()
        val epoch = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition.cacheEpoch
        val id = "fixture-profile"
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        val legacyFile = File(directory, "local_login_$hash.enc")
        val aad = "LOCAL_LOGIN:1:local-login:$id:".toByteArray()
        val payload = GatewayJson.serialize(GatewayJson.fromAny(mapOf("schemaVersion" to 1,
            "origin" to origin.originUrl, "epoch" to epoch, "username" to "synthetic-user",
            "provider" to "basic", "password" to "synthetic-password", "allowed" to true)))
        legacyFile.writeBytes(cipher.encrypt(payload.toByteArray(), aad))
        directory.listFiles()!!.filter { it.name.startsWith("endpoint_login_") }.forEach { it.delete() }
        restart()
        auth.notifyAuthExpired()
        assertTrue(auth.recoverSession(origin).isSuccess)
        val migrated = cipher.decrypt(legacyFile.readBytes(), aad).getOrThrow().toString(Charsets.UTF_8)
        assertFalse(migrated.contains("synthetic-password"))
        assertEquals(2, passwordRequests)
    }
}
