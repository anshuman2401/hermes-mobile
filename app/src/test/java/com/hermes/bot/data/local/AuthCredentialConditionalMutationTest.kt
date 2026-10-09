package com.hermes.bot.data.local

import kotlinx.coroutines.*
import com.hermes.bot.data.auth.*
import com.hermes.bot.data.origin.CanonicalOrigin
import com.hermes.bot.data.gateway.json.GatewayJson
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthCredentialConditionalMutationTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = "http://example.test"
    private fun credential(value: String) = AuthCredential("cookie_session", "basic", mapOf("session" to value), origin)
    private fun vault() = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))

    private class Faults {
        var failAccess = false
        var failCredential = false
        var syncFault: (Int) -> Boolean = { false }
        var syncs = 0
        var accessAad: ByteArray? = null
        val epochs = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val engine = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        val cipher = object : VaultCipherEngine by engine {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                val category = associatedData?.toString(Charsets.UTF_8).orEmpty().substringBefore(':')
                if (category == "CACHE_ACCESS_STATE") {
                    accessAad = associatedData
                    val state = GatewayJson.parseOrNull(plaintext.toString(Charsets.UTF_8))!!.asObjectOrNull()!!
                    epochs.add(state["cacheEpoch"]!!.asStringOrNull()!!)
                    reasons.add(state["rotationReason"]!!.asStringOrNull()!!)
                    if (failAccess) error("Synthetic access failure")
                }
                if (category == "AUTH_CREDENTIAL" && failCredential) error("Synthetic credential failure")
                return engine.encrypt(plaintext, associatedData)
            }
        }
        fun vault(directory: java.io.File) = EncryptedVault(directory, cipher, directorySync = {
            if (syncFault(++syncs)) throw java.io.IOException("Synthetic sync failure")
        })
    }
    private class SyntheticTransport : AuthHttpTransport {
        var tickets = 0
        override suspend fun execute(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse =
            when {
                url.endsWith("/api/status") -> HttpResponse(200, body = """{"version":"synthetic","auth_providers":["basic"]}""")
                url.endsWith("/auth/password-login") -> HttpResponse(200, mapOf("Set-Cookie" to listOf("session=synthetic-login; Path=/")))
                else -> { tickets++; HttpResponse(200, body = """{"ticket":"synthetic-ticket","ttl":30}""") }
            }
    }
    private suspend fun assertDenied(vault: EncryptedVault, lease: AuthCredentialLease, transport: SyntheticTransport) {
        assertNull(vault.getAuthCredential())
        assertNull(vault.getAuthCredential(lease.profileLocalId))
        assertNull(vault.readAuthCredentialLease(origin))
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
        val shared = DefaultAuthRepository(vault, transport)
        assertFalse(shared.isResponseCurrent(lease))
        assertFalse(shared.hasValidSession())
        assertNull(shared.getStoredCredential())
        val before = transport.tickets
        assertTrue(shared.mintWebSocketTicket(CanonicalOrigin.parse(origin).getOrThrow()).isFailure)
        assertEquals(before, transport.tickets)
        assertTrue(com.hermes.bot.data.context.DefaultContextProfileRepository(vault, transport)
            .getScopedSessions("synthetic-workspace").isFailure)
        assertEquals(before, transport.tickets)
    }
    private suspend fun failResponse(vault: EncryptedVault): AuthCredentialLease {
        val lease = vault.readAuthCredentialLease(origin)!!
        assertTrue(vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-rotation")) is AuthCredentialMutationResult.Failed)
        return lease
    }

    @Test fun failedOriginEditCannotReauthorizeDeniedCredential() = runBlocking {
        for (gated in listOf(false, true)) {
            val faults = Faults(); val directory = temp.newFolder(); val vault = faults.vault(directory)
            val profile = ConnectionProfile("synthetic-a", origin, "Synthetic")
            vault.saveConnectionProfile(profile); vault.saveAuthCredential(credential("synthetic-old"))
            faults.failAccess = true; val issuing = failResponse(vault); faults.failAccess = false
            faults.syncs = 0; faults.syncFault = { true }
            val edited = profile.copy(normalizedOrigin = "http://other.test")
            if (gated) {
                val gate = ConnectionProfileGenerationGate()
                assertTrue(vault.saveConnectionProfileIfCurrent(edited, gate.beginAttempt(), gate) is ProfileCommitResult.FAILED)
            } else assertTrue(runCatching { vault.saveConnectionProfile(edited) }.isFailure)
            assertEquals(origin, vault.getConnectionProfile(profile.localId)!!.normalizedOrigin)
            assertEquals(CacheEpochRotationReason.SIGNED_OUT.raw, faults.reasons.last())
            val accessFile = directory.walkTopDown().single {
                it.name.startsWith("cache_access_") && it.extension == "enc"
            }
            val persisted = faults.engine.decrypt(accessFile.readBytes(), faults.accessAad).getOrThrow()
            assertEquals(CacheEpochRotationReason.SIGNED_OUT.raw,
                GatewayJson.parseOrNull(persisted.toString(Charsets.UTF_8))!!.asObjectOrNull()!!
                    ["rotationReason"]!!.asStringOrNull())
            assertDenied(vault, issuing, SyntheticTransport())
            val reopened = EncryptedVault(directory, faults.engine)
            assertNull(reopened.getAuthCredential(profile.localId))
            assertNull(reopened.readAuthCredentialLease(origin))
            faults.syncFault = { false }
            val gate = ConnectionProfileGenerationGate(); val stale = gate.beginAttempt(); gate.beginAttempt()
            val writes = faults.epochs.size
            assertEquals(ProfileCommitResult.STALE, vault.saveConnectionProfileIfCurrent(edited, stale, gate))
            assertEquals(writes, faults.epochs.size)
        }
    }

    @Test fun failedLoginCannotPublishRecoveryEpoch() = runBlocking {
        val faults = Faults(); val vault = faults.vault(temp.newFolder()); val transport = SyntheticTransport()
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic"))
        vault.saveAuthCredential(credential("synthetic-old"))
        faults.syncs = 0; faults.syncFault = { it == 2 }
        val issuing = failResponse(vault)
        val failedSuccessor = faults.epochs[ faults.epochs.size - 2 ]
        faults.syncFault = { false }; faults.epochs.clear()
        val repo = DefaultAuthRepository(vault, transport)
        repeat(6) { attempt ->
            faults.failAccess = attempt % 2 == 0; faults.failCredential = !faults.failAccess
            assertTrue(repo.login(CanonicalOrigin.parse(origin).getOrThrow(), "synthetic-user", "synthetic-password".toCharArray(), "basic").isFailure)
            assertDenied(vault, issuing, transport)
            assertEquals("Failed saves reuse one unpublished recovery epoch", 1, faults.epochs.toSet().size)
            faults.failAccess = false; faults.failCredential = false
            vault.saveConnectionProfile(ConnectionProfile("synthetic-b", origin, "Synthetic"))
            vault.setActiveProfileId("synthetic-b"); vault.saveAuthCredential(credential("synthetic-independent"))
            assertTrue(repo.mintWebSocketTicket(origin).isSuccess)
            vault.setActiveProfileId("synthetic-a")
            // Exclude unrelated owner's epochs from the equality-only recovery observation.
            val recovery = faults.epochs.first(); faults.epochs.clear(); faults.epochs.add(recovery)
        }
        assertTrue(repo.login(CanonicalOrigin.parse(origin).getOrThrow(), "synthetic-user", "synthetic-password".toCharArray(), "basic").isSuccess)
        val committed = vault.readAuthCredentialLease(origin)!!
        assertEquals(faults.epochs.first(), committed.epoch)
        assertNotEquals(issuing.epoch, committed.epoch); assertNotEquals(failedSuccessor, committed.epoch)
        assertTrue(vault.mutateAuthCredentialIfCurrent(issuing, null, true) is AuthCredentialMutationResult.Stale)
        assertTrue(DefaultAuthRepository(vault, transport).mintWebSocketTicket(origin).isSuccess)
    }

    @Test fun twoFailedProfilesRemainIndependentlyDenied() = runBlocking {
        val faults = Faults(); val vault = faults.vault(temp.newFolder()); val transport = SyntheticTransport()
        val leases = mutableListOf<AuthCredentialLease>()
        for (id in listOf("synthetic-a", "synthetic-b")) {
            vault.saveConnectionProfile(ConnectionProfile(id, origin, "Synthetic")); vault.setActiveProfileId(id)
            vault.saveAuthCredential(credential("synthetic-old"))
            faults.failAccess = true; leases.add(failResponse(vault)); faults.failAccess = false
        }
        vault.saveConnectionProfile(ConnectionProfile("synthetic-c", origin, "Synthetic")); vault.setActiveProfileId("synthetic-c")
        vault.saveAuthCredential(credential("synthetic-independent"))
        assertTrue(DefaultAuthRepository(vault, transport).mintWebSocketTicket(origin).isSuccess)
        for (lease in leases) { vault.setActiveProfileId(lease.profileLocalId); assertDenied(vault, lease, transport) }
    }

    @Test fun deniedDefaultCredentialCannotBePromoted() = runBlocking {
        for (gated in listOf(false, true)) {
            val faults = Faults(); val vault = faults.vault(temp.newFolder())
            vault.saveAuthCredential(credential("synthetic-old")); faults.failAccess = true
            val lease = failResponse(vault); faults.failAccess = false
            val profile = ConnectionProfile("synthetic-a", origin, "Synthetic")
            if (gated) {
                val gate = ConnectionProfileGenerationGate()
                assertEquals(ProfileCommitResult.SAVED, vault.saveConnectionProfileIfCurrent(profile, gate.beginAttempt(), gate))
            } else vault.saveConnectionProfile(profile)
            assertNull(vault.getAuthCredential("default")); assertNull(vault.getAuthCredential(profile.localId))
            assertNull(vault.readAuthCredentialLease(origin))
            assertFalse(DefaultAuthRepository(vault, SyntheticTransport()).isResponseCurrent(lease))
        }
    }

    @Test fun failedFinalPublicationAndRestorationStayProcessDenied() = runBlocking {
        for (failRestoreEncryption in listOf(false, true)) {
            val faults = Faults(); val directory = temp.newFolder(); val vault = faults.vault(directory)
            vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic")); vault.saveAuthCredential(credential("synthetic-old"))
            faults.syncs = 0
            faults.syncFault = { count ->
                if (count == 2 && failRestoreEncryption) faults.failAccess = true
                count >= 2
            }
            val issuing = failResponse(vault)
            assertEquals(if (failRestoreEncryption) 2 else 3, faults.syncs)
            assertDenied(vault, issuing, SyntheticTransport())
            val reopened = EncryptedVault(directory, faults.engine)
            if (failRestoreEncryption) {
                assertNotNull("DU retains installed successor when restoration encryption fails", reopened.readAuthCredentialLease(origin))
                assertTrue(DefaultAuthRepository(reopened, SyntheticTransport()).mintWebSocketTicket(origin).isSuccess)
            } else {
                assertNull("DU sync failure can still leave installed SIGNED_OUT bytes", reopened.readAuthCredentialLease(origin))
            }
        }
    }

    @Test fun missingOrCorruptDenialBoundaryFailsClosed() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val faults = Faults(); val directory = temp.newFolder(); val vault = faults.vault(directory)
            vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic")); vault.saveAuthCredential(credential("synthetic-old"))
            faults.failCredential = true; failResponse(vault)
            val access = directory.walkTopDown().single { it.name.startsWith("cache_access_") && it.extension == "enc" }
            if (corrupt) access.writeBytes(byteArrayOf(0)) else assertTrue(access.delete())
            val reopened = EncryptedVault(directory, faults.engine)
            assertNull(reopened.getAuthCredential()); assertNull(reopened.readAuthCredentialLease(origin))
            assertTrue(DefaultAuthRepository(reopened, SyntheticTransport()).mintWebSocketTicket(origin).isFailure)
        }
    }

    @Test fun failedPromotionCannotPublishDestinationCredential() = runBlocking {
        for (gated in listOf(false, true)) {
            val faults = Faults(); val vault = faults.vault(temp.newFolder())
            vault.saveAuthCredential(credential("synthetic-old"))
            val source = vault.readAuthCredentialLease(origin)!!
            faults.failCredential = true
            val profile = ConnectionProfile("synthetic-a", origin, "Synthetic")
            if (gated) {
                val gate = ConnectionProfileGenerationGate()
                assertTrue(vault.saveConnectionProfileIfCurrent(profile, gate.beginAttempt(), gate)
                    is ProfileCommitResult.FAILED)
            } else assertTrue(runCatching { vault.saveConnectionProfile(profile) }.isFailure)
            faults.failCredential = false
            assertNull(vault.getAuthCredential("default"))
            assertNull(vault.getAuthCredential(profile.localId))
            assertFalse(DefaultAuthRepository(vault, SyntheticTransport()).isResponseCurrent(source))
            assertTrue(DefaultAuthRepository(vault, SyntheticTransport()).mintWebSocketTicket(origin).isFailure)
            vault.setActiveProfileId(profile.localId)
            assertTrue(DefaultAuthRepository(vault, SyntheticTransport()).login(origin,
                "synthetic-user", "synthetic-password".toCharArray(), "basic").isSuccess)
        }
    }

    @Test fun staleFailureDoesNotWriteOrDenyNewLogin() = runBlocking {
        val faults = Faults(); val vault = faults.vault(temp.newFolder())
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic"))
        vault.saveAuthCredential(credential("synthetic-old"))
        val old = vault.readAuthCredentialLease(origin)!!
        vault.saveAuthCredential(credential("synthetic-login"))
        val current = vault.readAuthCredentialLease(origin)!!
        val writes = faults.epochs.size; faults.failAccess = true
        assertEquals(AuthCredentialMutationResult.Stale, vault.mutateAuthCredentialIfCurrent(old, credential("synthetic-late")))
        assertEquals(writes, faults.epochs.size)
        val after = vault.readAuthCredentialLease(origin)!!
        assertEquals(current.epoch, after.epoch)
        assertEquals(current.credential, after.credential)
        assertTrue(DefaultAuthRepository(vault, SyntheticTransport()).mintWebSocketTicket(origin).isSuccess)
    }

    @Test fun defaultCredentialPromotionBindsLeaseOnNormalProfileSave() = runBlocking {
        assertDefaultCredentialPromotionBindsLease(gated = false)
    }

    @Test fun defaultCredentialPromotionBindsLeaseOnGenerationGatedProfileSave() = runBlocking {
        assertDefaultCredentialPromotionBindsLease(gated = true)
    }

    private suspend fun assertDefaultCredentialPromotionBindsLease(gated: Boolean) {
        val vault = vault()
        val savedCredential = credential("synthetic-promoted")
        vault.saveAuthCredential(savedCredential)
        val sourceLease = vault.readAuthCredentialLease(origin)!!
        val profile = ConnectionProfile("synthetic-promoted-profile", origin, "Synthetic")
        if (gated) {
            val gate = ConnectionProfileGenerationGate()
            assertEquals(ProfileCommitResult.SAVED,
                vault.saveConnectionProfileIfCurrent(profile, gate.beginAttempt(), gate))
        } else {
            vault.saveConnectionProfile(profile)
        }
        assertEquals(profile.localId, vault.getActiveProfileId())
        assertEquals(savedCredential, vault.getAuthCredential())
        assertTrue("A late default response must not mutate the promoted profile",
            vault.mutateAuthCredentialIfCurrent(sourceLease, credential("synthetic-late"))
                is AuthCredentialMutationResult.Stale)
        val destinationLease = vault.readAuthCredentialLease(origin)
        assertNotNull("Promoted credential must have a destination access epoch", destinationLease)
        val destinationPartition = vault.activeAccessPartition()
        assertTrue("Promoted credential must resolve its destination cache partition",
            destinationPartition is CacheAccessPartitionResult.Available)
        val partition = (destinationPartition as CacheAccessPartitionResult.Available).partition
        assertEquals(profile.localId, destinationLease!!.profileLocalId)
        assertEquals(partition.cacheEpoch, destinationLease.epoch)
        assertNotEquals(sourceLease.epoch, destinationLease.epoch)
        assertTrue(vault.mutateAuthCredentialIfCurrent(destinationLease, null)
            is AuthCredentialMutationResult.Accepted)
        assertEquals(savedCredential, vault.getAuthCredential())
    }

    @Test fun successorReceiptAndCacheEpochShareTheAtomicCommit() = runBlocking {
        val vault = vault()
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic"))
        vault.saveAuthCredential(credential("synthetic-old"))
        val lease = vault.readAuthCredentialLease(origin)!!
        val before = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        val result = vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-new")) as AuthCredentialMutationResult.Accepted
        val after = (vault.activeAccessPartition() as CacheAccessPartitionResult.Available).partition
        assertNotEquals(before.cacheEpoch, after.cacheEpoch)
        assertEquals(after.cacheEpoch, result.receipt!!.epoch)
        assertTrue(vault.mutateAuthCredentialIfCurrent(lease, null, true) is AuthCredentialMutationResult.Stale)
        assertTrue(vault.mutateAuthCredentialIfCurrent(result.receipt, null) is AuthCredentialMutationResult.Accepted)
        vault.clearAuthCredential(cacheChoice = SignOutCacheChoice.KEEP)
        assertTrue(vault.mutateAuthCredentialIfCurrent(result.receipt, null) is AuthCredentialMutationResult.Stale)
        assertTrue(vault.activeAccessPartition() is CacheAccessPartitionResult.Unavailable)
    }

    @Test fun loginCannotInterleaveBetweenCompareAndCredentialWrite() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var armed = false
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()),
            directorySync = {
                if (armed) {
                    armed = false; entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Synthetic synchronization timeout" }
                }
            })
        vault.saveAuthCredential(credential("synthetic-old"))
        val lease = vault.readAuthCredentialLease(origin)!!; armed = true
        val rotation = async(Dispatchers.IO) { vault.mutateAuthCredentialIfCurrent(lease, credential("synthetic-rotation")) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            val writerEntered = CompletableDeferred<Unit>()
            val login = async(start = CoroutineStart.UNDISPATCHED) {
                writerEntered.complete(Unit); vault.saveAuthCredential(credential("synthetic-login"))
            }
            writerEntered.await(); assertFalse(login.isCompleted)
            release.countDown()
            val receipt = (rotation.await() as AuthCredentialMutationResult.Accepted).receipt!!
            login.await()
            assertEquals(credential("synthetic-login").cookies, vault.getAuthCredential()!!.cookies)
            assertTrue(vault.mutateAuthCredentialIfCurrent(receipt, null) is AuthCredentialMutationResult.Stale)
        } finally { release.countDown() }
    }

    @Test fun originChangeRejectsOldLeaseBeforeClear() = runBlocking {
        val vault = vault()
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", origin, "Synthetic"))
        vault.saveAuthCredential(credential("synthetic-old"))
        val lease = vault.readAuthCredentialLease(origin)!!
        vault.saveConnectionProfile(ConnectionProfile("synthetic-a", "http://other.test", "Synthetic"))
        assertTrue(vault.mutateAuthCredentialIfCurrent(lease, null, true) is AuthCredentialMutationResult.Stale)
        assertNull(vault.readAuthCredentialLease(origin))
    }
}
