package io.github.anshuman2401.hermesmobile.data.local

import io.github.anshuman2401.hermesmobile.data.gateway.SessionSummary
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import java.security.MessageDigest

/**
 * Android Keystore-backed encrypted application vault implementing VaultStorage.
 * Follows specs/architecture/contracts/local-persistence-contract.md, ADR-0003, and RPR-003.
 *
 * Security guarantees:
 * - AEAD Associated Authenticated Data (AAD) cryptographically binds record type, schema version,
 *   record ID, and origin/profile context.
 * - Record-swapping attacks (e.g. draft into auth_credential, or draft 1 into draft 2) fail closed.
 * - Collision-resistant filenames using SHA-256 hashes.
 * - Decrypted-identity verification ensuring decrypted payloads match requested record IDs.
 * - Atomic durable writes with fsync (fd.sync()).
 * - Strict schema version admission; unknown future schemas fail closed (null).
 * - Corrupted files quarantined and purged without falling back to cleartext.
 */
class EncryptedVault(
    private val storageDir: File,
    private val cipherEngine: VaultCipherEngine = AesGcmCipherEngine(AndroidKeyStoreMasterKeyProvider()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val atomicMove: (File, File) -> Unit = { source, target ->
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        Unit
    },
    private val directorySync: (File) -> Unit = { fsyncDirectoryBestEffort(it) },
    private val elapsedRealtimeMillis: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            // JVM unit tests run without a booted Android runtime.
            System.nanoTime() / 1_000_000L
        }
    },
    // PR-2 TASK-05 (PAT-01): wall-clock seam for the roster's 24h TTL —
    // defaulted to the system clock, so existing constructions are unchanged.
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    // PR-3 TASK-01 (PAT-01): observable ordering seam — fires after each
    // crash-critical conversation-cache step commits (content → index →
    // catalog), so fault-injection tests can abort exactly between durable
    // steps. Default no-op: production behavior unchanged.
    private val cacheStageObserver: (CacheWriteStage) -> Unit = {},
    // PR-5 TEST-03 seam (plan CACHE-PR5 re-anchored: main declared no injected
    // directory-sync param, so PR-5 adds this defaulted probe beside the
    // stage observer) — when set, phase 6 of clearAllCacheRecords runs it
    // instead of the real sync; defaulted null keeps every construction
    // unchanged and production behavior identical (PAT-01).
    private val directorySyncSeam: ((File) -> Unit)? = null,
    // Scoped workspace recovery must establish deletion and authoritative readback.
    private val workspaceSelectionDelete: (File) -> Unit = { Files.delete(it.toPath()); Unit }
) : VaultStorage {

    private val mutex = Mutex()
    private val loginGeneration = java.util.concurrent.atomic.AtomicLong()

    // Separate, origin-bound local authority. A tombstone prevents legacy resurrection.
    private class LocalLogin(val origin: String, val epoch: String, val username: String?,
        val provider: String?, val password: String?, val allowed: Boolean)
    private fun localLoginFile(id: String) = File(storageDir, "local_login_${sha256Hex(id)}.enc")
    private fun localLoginAad(id: String) = createAad("LOCAL_LOGIN", 1, "local-login:$id")
    private fun readLocalLogin(id: String): LocalLogin? {
        val file = localLoginFile(id)
        if (!file.exists() || file.length() > 65536) return null
        val obj = readEncryptedSingleton(file, localLoginAad(id))?.let {
            GatewayJson.parseOrNull(it)?.asObjectOrNull()
        } ?: run {
            if (!file.exists()) writeLocalLogin(id, LocalLogin("", "", null, null, null, false))
            return null
        }
        if (obj["schemaVersion"]?.asIntOrNull() != 1) return null
        return LocalLogin(obj["origin"]?.asStringOrNull() ?: return null,
            obj["epoch"]?.asStringOrNull() ?: return null, obj["username"]?.asStringOrNull(),
            obj["provider"]?.asStringOrNull(), obj["password"]?.asStringOrNull(),
            obj["allowed"]?.asBooleanOrNull() == true)
    }
    private fun writeLocalLogin(id: String, login: LocalLogin) {
        writeEncryptedSingleton(localLoginFile(id), GatewayJson.serialize(GatewayJson.fromAny(linkedMapOf(
            "schemaVersion" to 1, "origin" to login.origin, "epoch" to login.epoch,
            "username" to login.username, "provider" to login.provider,
            "password" to login.password, "allowed" to login.allowed
        ))), localLoginAad(id))
        directorySync(storageDir)
    }
    private fun revokeLocalLogin(id: String, origin: String) {
        loginGeneration.incrementAndGet()
        writeLocalLogin(id, LocalLogin(origin, "", null, null, null, false))
    }
    private fun localEpoch(id: String, origin: String): String? {
        val state = readCacheAccessStateInternal(id, origin) ?: return null
        if (state.rotationReason == CacheEpochRotationReason.SIGNED_OUT.raw ||
            state.rotationReason == CacheEpochRotationReason.ORIGIN_REPLACED.raw) return null
        return readLocalLogin(id)?.takeIf {
            it.allowed && it.origin == origin && it.epoch.isNotEmpty()
        }?.epoch
    }
    private fun loginOwnerCurrent(owner: LoginOwner): Boolean =
        loginGeneration.get() == owner.generation && activeAuthProfileInternal() == owner.profileId &&
            readCacheAccessStateInternal(owner.profileId, owner.origin)?.cacheEpoch == owner.authEpoch

    override fun invalidateLoginOwners() { loginGeneration.incrementAndGet() }

    override suspend fun captureLoginOwner(origin: String): LoginOwner? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val id = activeAuthProfileInternal() ?: return@withContext null
            LoginOwner(id, origin, loginGeneration.get(), readCacheAccessStateInternal(id, origin)?.cacheEpoch)
        }
    }
    override suspend fun readRememberedLogin(owner: LoginOwner): RememberedLogin? = mutex.withLock {
        withContext(ioDispatcher) {
            if (!loginOwnerCurrent(owner)) return@withContext null
            val login = readLocalLogin(owner.profileId) ?: return@withContext null
            if (localEpoch(owner.profileId, owner.origin) == null || !login.allowed ||
                login.origin != owner.origin || login.password == null ||
                login.username == null || login.provider == null) return@withContext null
            RememberedLogin(login.username, login.password.toCharArray(), login.provider)
        }
    }
    override suspend fun rejectRememberedLogin(owner: LoginOwner) = mutex.withLock {
        withContext(ioDispatcher) {
            if (loginOwnerCurrent(owner)) readLocalLogin(owner.profileId)?.let {
                writeLocalLogin(owner.profileId, LocalLogin(it.origin, it.epoch, it.username, it.provider, null, it.allowed))
            }
        }
        Unit
    }
    override suspend fun commitRememberedLogin(owner: LoginOwner, credential: AuthCredential,
        username: String, password: CharArray): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            if (!loginOwnerCurrent(owner) || credential.canonicalOrigin != owner.origin) return@withContext false
            require(username.length <= 4096 && password.size <= 4096)
            val old = readLocalLogin(owner.profileId)
            val retained = old?.takeIf { localEpoch(owner.profileId, owner.origin) == it.epoch &&
                it.allowed && it.origin == owner.origin &&
                it.username == username && it.provider == credential.providerId }?.epoch
            // Revoke first; a crash cannot leave old-account access or an unremembered successful login.
            writeLocalLogin(owner.profileId, LocalLogin(owner.origin, "", null, null, null, false))
            try {
                saveAuthCredentialLocked(owner.profileId, credential, completePublication = false)
                val epoch = retained ?: readCacheAccessStateInternal(owner.profileId, owner.origin)!!.cacheEpoch
                writeLocalLogin(owner.profileId, LocalLogin(owner.origin, epoch, username,
                    credential.providerId, String(password), true))
                if (loginGeneration.get() != owner.generation) {
                    revokeLocalLogin(owner.profileId, owner.origin)
                    writeCacheAccessStateInternal(owner.profileId, CacheEpochRotationReason.SIGNED_OUT, originOverride = owner.origin)
                    return@withContext false
                }
                deniedAuthEpochs.remove(AuthOwner(owner.profileId, owner.origin))
                true
            } catch (exception: Exception) {
                try { writeLocalLogin(owner.profileId, LocalLogin(owner.origin, "", null, null, null, false)) }
                catch (_: Exception) { /* Keep unpublished auth epochs denied; original failure wins. */ }
                throw exception
            }
        }
    }
    override suspend fun prepareLocalLogin(origin: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val id = activeAuthProfileInternal() ?: return@withContext false
            if (readConnectionProfileInternal(id)?.normalizedOrigin != origin) return@withContext false
            if (localLoginFile(id).exists()) return@withContext localEpoch(id, origin) != null
            val state = readCacheAccessStateInternal(id, origin) ?: return@withContext false
            if (accessDenied(id, origin, state)) return@withContext false
            val credential = readAuthCredentialInternal(id, allowExpired = true) ?: return@withContext false
            if (credential.canonicalOrigin != origin || credential.cookies.isEmpty()) return@withContext false
            writeLocalLogin(id, LocalLogin(origin, state.cacheEpoch, null, null, null, true))
            true
        }
    }


    // Application-vault lifetime only. No credentials, persistence or epoch ordering.
    private data class AuthOwner(val profileId: String, val origin: String)
    private data class DeniedAuthEpochs(
        val issuing: String?,
        val successor: String? = null,
        var recovery: String? = null
    ) {
        fun contains(epoch: String) = epoch == issuing || epoch == successor || epoch == recovery
    }
    private val deniedAuthEpochs = mutableMapOf<AuthOwner, DeniedAuthEpochs>()

    private fun accessDenied(id: String, origin: String, state: CacheAccessState): Boolean =
        state.rotationReason == CacheEpochRotationReason.SIGNED_OUT.raw ||
            state.rotationReason == CacheEpochRotationReason.AUTH_EXPIRED.raw ||
            deniedAuthEpochs[AuthOwner(id, origin)]?.contains(state.cacheEpoch) == true

    private fun authorizedCredentialInternal(id: String): AuthCredential? {
        val credential = readAuthCredentialInternal(id) ?: return null
        val profile = readConnectionProfileInternal(id)
        if (id != "default" && profile == null) return null
        if (profile != null && profile.normalizedOrigin != credential.canonicalOrigin) return null
        val state = readCacheAccessStateInternal(id, credential.canonicalOrigin) ?: return null
        return credential.takeUnless { it.cookies.isEmpty() || accessDenied(id, it.canonicalOrigin, state) ||
            (localLoginFile(id).exists() && localEpoch(id, it.canonicalOrigin) == null) }
    }

    // A failed explicit save reuses one never-public recovery epoch. Only complete
    // publication and raw verification under the mutex can prune this owner's slots.
    private fun saveAuthCredentialLocked(id: String, credential: AuthCredential, completePublication: Boolean = true) {
        val owner = AuthOwner(id, credential.canonicalOrigin)
        val denied = deniedAuthEpochs.getOrPut(owner) {
            DeniedAuthEpochs(readCacheAccessStateInternal(id, owner.origin)?.cacheEpoch)
        }
        val recovery = denied.recovery ?: mintSecureCacheEpoch().also { denied.recovery = it }
        writeCacheAccessStateInternal(id, CacheEpochRotationReason.CREDENTIAL_REPLACED,
            originOverride = owner.origin, nominatedEpoch = recovery)
        writeAuthCredentialInternal(id, credential)
        check(readCacheAccessStateInternal(id, owner.origin)?.let {
            it.cacheEpoch == recovery && it.rotationReason != CacheEpochRotationReason.SIGNED_OUT.raw
        } == true && readAuthCredentialInternal(id) == credential) { "Auth publication verification failed" }
        if (completePublication) deniedAuthEpochs.remove(owner)
    }

    private val legacyConnectionFile = File(storageDir, "connection_profile.enc")
    private val legacyAuthFile = File(storageDir, "auth_credential.enc")
    private val profileIndexFile = File(storageDir, "profile_index.enc")
    private val selectionFile = File(storageDir, "session_selection.enc")
    private val cacheFile = File(storageDir, "session_summary_cache.enc")
    private val draftsDir = File(storageDir, "drafts")
    private val pendingDir = File(storageDir, "pending_turns")
    private val pendingInterruptsDir = File(storageDir, "pending_interrupts")
    private val diagnosticsFile = File(storageDir, "diagnostics.enc")
    private val contextProfileSelectionFile = File(storageDir, "context_profile_selection.enc")
    // CHAT-011 S1 (PAT-001): one singleton-style encrypted file holding the
    // map of workspace key -> ProfileModelDefault. Same AAD + AEAD + durable
    // write guarantees as every other vault singleton.
    private val profileModelDefaultsFile = File(storageDir, "profile_model_defaults.enc")
    private val cacheRootDir = File(storageDir, "cache")
    private val quarantineSummaryFile = File(storageDir, "session_summary_cache.quarantine.enc")
    private val migrationStateFile =
        File(storageDir, "cache_migration_state_${sha256Hex("cache-migration-state")}.enc")

    // PR-3 (plan §5.2): conversation content rides cache/<sha256(P)>/<sha256(P:O)>/
    // (so PR-1 purge / PR-5 reset cover it BY LAYOUT); the per-partition index
    // lives under conversation_indexes/ at the vault root; the global catalog is
    // one opaque singleton at the vault root.
    private val conversationIndexesDir = File(storageDir, "conversation_indexes")
    private val globalCatalogFile =
        File(storageDir, "global_catalog_${sha256Hex(GlobalConversationCatalogV1.RECORD_ID)}.enc")
    private val globalCatalogQuarantineFile =
        File(storageDir, "${globalCatalogFile.name}.quarantine")

    /**
     * PR-3 (plan §5.2): the catalog's AAD is a FIXED LITERAL — never recomputed
     * from parsed fields, so a swapped payload can never re-bind it (REQ-03).
     */
    private val globalCatalogAad: ByteArray =
        "GLOBAL_CONVERSATION_CATALOG:1:global-conversation-catalog:catalog-v1"
            .toByteArray(Charsets.UTF_8)

    @Volatile private var migrationChecked = false
    @Volatile private var discardPending = false

    init {
        storageDir.mkdirs()
        draftsDir.mkdirs()
        pendingDir.mkdirs()
        pendingInterruptsDir.mkdirs()
        // REQ-05: the legacy summary record is discarded at construction —
        // never parsed, read, or migrated (SEC-01).
        runLegacySummaryDiscard()
        // PR-3 REQ-03/§5.3: conversation-cache recovery runs on every
        // construction (index validation → orphan scan → quarantine sweep →
        // catalog rebuild → cap enforcement, catalog written last). Best
        // effort — recovery must never block construction.
        runConversationCacheRecoveryBestEffort()
    }

    // --- Helper AAD & cryptographic primitives ---

    private fun createAad(recordType: String, schemaVersion: Int, recordId: String, context: String = ""): ByteArray {
        return "$recordType:$schemaVersion:$recordId:$context".toByteArray(Charsets.UTF_8)
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun writeEncryptedSingleton(file: File, jsonString: String, aad: ByteArray) {
        val plaintextBytes = jsonString.toByteArray(Charsets.UTF_8)
        val ciphertext = cipherEngine.encrypt(plaintextBytes, aad)
        writeAtomicDurable(file, ciphertext)
    }

    private fun readEncryptedSingleton(file: File, aad: ByteArray): String? {
        if (!file.exists() || file.length() == 0L) return null
        val ciphertext = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }

        val decryptResult = cipherEngine.decrypt(ciphertext, aad)
        if (decryptResult.isFailure) {
            // A temporarily unavailable Keystore is not evidence of corrupt ciphertext.
            decryptResult.exceptionOrNull()?.let { if (it is KeyStoreUnavailableException) throw it }
            // Decryption failure indicates swap attack, corruption, or invalidated key.
            // Fail closed: quarantine and purge corrupted record. Never fallback.
            file.delete()
            return null
        }

        return String(decryptResult.getOrThrow(), Charsets.UTF_8)
    }

    private fun writeEncryptedIdentifiedRecord(
        dir: File,
        filePrefix: String,
        recordId: String,
        jsonString: String,
        aad: ByteArray
    ) {
        val plaintextBytes = jsonString.toByteArray(Charsets.UTF_8)
        val ciphertext = cipherEngine.encrypt(plaintextBytes, aad)

        val idBytes = recordId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + idBytes.size + ciphertext.size)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.put(ciphertext)

        val file = File(dir, "${filePrefix}_${sha256Hex(recordId)}.enc")
        writeAtomicDurable(file, buffer.array())
    }

    private fun readEncryptedIdentifiedRecord(
        dir: File,
        filePrefix: String,
        expectedRecordId: String,
        recordType: String,
        schemaVersion: Int
    ): String? {
        val file = File(dir, "${filePrefix}_${sha256Hex(expectedRecordId)}.enc")
        if (!file.exists() || file.length() < 4L) return null

        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }

        if (bytes.size < 4) {
            file.delete()
            return null
        }

        val idLen = ByteBuffer.wrap(bytes, 0, 4).int
        if (idLen <= 0 || bytes.size < 4 + idLen) {
            file.delete()
            return null
        }

        val parsedRecordId = String(bytes, 4, idLen, Charsets.UTF_8)
        if (parsedRecordId != expectedRecordId) {
            // Decrypted-identity verification mismatch: fail closed
            file.delete()
            return null
        }

        val ciphertext = bytes.copyOfRange(4 + idLen, bytes.size)
        val aad = createAad(recordType, schemaVersion, expectedRecordId)
        val decryptResult = cipherEngine.decrypt(ciphertext, aad)
        if (decryptResult.isFailure) {
            // A temporarily unavailable Keystore is not evidence of corrupt ciphertext.
            decryptResult.exceptionOrNull()?.let { if (it is KeyStoreUnavailableException) throw it }
            file.delete()
            return null
        }

        return String(decryptResult.getOrThrow(), Charsets.UTF_8)
    }

    private fun writeAtomicDurable(file: File, data: ByteArray) {
        val tempFile = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(data)
                fos.flush()
                fos.fd.sync()
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    // --- Helper methods for keyed profile and credential storage ---

    private fun getProfileFile(localId: String): File =
        File(storageDir, "connection_profile_${sha256Hex("connection_profile_$localId")}.enc")

    private fun getCredentialFile(localId: String): File =
        File(storageDir, "auth_credential_${sha256Hex("auth_credential_$localId")}.enc")

    private fun writeProfileIndexInternal(index: ProfileIndex) {
        val map = linkedMapOf<String, Any?>(
            "profileIds" to index.profileIds,
            "activeProfileId" to index.activeProfileId,
            "migratedProfileId" to index.migratedProfileId,
            "updatedAt" to index.updatedAt,
            "schemaVersion" to index.schemaVersion
        )
        val aad = createAad("PROFILE_INDEX", index.schemaVersion, "singleton_profile_index")
        writeEncryptedSingleton(profileIndexFile, GatewayJson.serialize(GatewayJson.fromAny(map)), aad)
    }

    private fun readProfileIndexInternal(): ProfileIndex? {
        if (!profileIndexFile.exists() || profileIndexFile.length() == 0L) return null
        val aad = createAad("PROFILE_INDEX", ProfileIndex.CURRENT_SCHEMA_VERSION, "singleton_profile_index")
        val jsonStr = readEncryptedSingleton(profileIndexFile, aad) ?: return null
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null

        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > ProfileIndex.CURRENT_SCHEMA_VERSION) return null

        val idsArray = obj["profileIds"]?.asArrayOrNull()
        val idsList = idsArray?.list?.mapNotNull { it.asStringOrNull() } ?: emptyList()

        return ProfileIndex(
            profileIds = idsList,
            activeProfileId = obj["activeProfileId"]?.asStringOrNull(),
            migratedProfileId = obj["migratedProfileId"]?.asStringOrNull(),
            updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            schemaVersion = schemaVer
        )
    }

    private fun ensureMigrated() {
        if (discardPending) {
            runLegacySummaryDiscard()
        }
        if (migrationChecked && !legacyConnectionFile.exists()) return

        if (profileIndexFile.exists()) {
            if (legacyConnectionFile.exists()) legacyConnectionFile.delete()
            if (legacyAuthFile.exists()) legacyAuthFile.delete()
            migrationChecked = true
            return
        }

        if (legacyConnectionFile.exists()) {
            val oldAad = createAad("CONNECTION_PROFILE", 1, "singleton_connection_profile")
            val jsonStr = readEncryptedSingleton(legacyConnectionFile, oldAad)
            val obj = jsonStr?.let { GatewayJson.parseOrNull(it)?.asObjectOrNull() }
            val schemaVer = obj?.get("schemaVersion")?.asIntOrNull() ?: 1
            val localId = obj?.get("localId")?.asStringOrNull()
            val origin = obj?.get("normalizedOrigin")?.asStringOrNull()

            if (jsonStr == null || obj == null || localId == null || origin == null || schemaVer > ConnectionProfile.CURRENT_SCHEMA_VERSION) {
                legacyConnectionFile.delete()
                legacyAuthFile.delete()
                recordDiagnosticInternal(
                    LocalDiagnostic(
                        errorCategory = "CORRUPTION",
                        code = "PROFILE_MIGRATION_FAILED",
                        state = "EMPTY_DIRECTORY"
                    )
                )
                writeProfileIndexInternal(ProfileIndex(emptyList(), null, null))
                migrationChecked = true
                return
            }

            val migratedProfile = ConnectionProfile(
                localId = localId,
                normalizedOrigin = origin,
                displayLabel = obj["displayLabel"]?.asStringOrNull() ?: "",
                lastSuccessfulProtocol = obj["lastSuccessfulProtocol"]?.asStringOrNull(),
                createdAt = obj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                schemaVersion = schemaVer
            )
            writeConnectionProfileInternal(migratedProfile)

            if (legacyAuthFile.exists()) {
                val oldAuthAad = createAad("AUTH_CREDENTIAL", 1, "singleton_auth_credential")
                val authJsonStr = readEncryptedSingleton(legacyAuthFile, oldAuthAad)
                val authObj = authJsonStr?.let { GatewayJson.parseOrNull(it)?.asObjectOrNull() }
                if (authObj != null) {
                    val authSchemaVer = authObj["schemaVersion"]?.asIntOrNull() ?: 1
                    if (authSchemaVer <= AuthCredential.CURRENT_SCHEMA_VERSION) {
                        val cookiesObj = authObj["cookies"]?.asObjectOrNull()
                        val cookiesMap = cookiesObj?.map?.mapValues { it.value.asStringOrNull() ?: "" } ?: emptyMap()
                        val migratedCred = AuthCredential(
                            driverKind = authObj["driverKind"]?.asStringOrNull() ?: AuthCredential.DRIVER_COOKIE_SESSION,
                            providerId = authObj["providerId"]?.asStringOrNull() ?: "",
                            cookies = cookiesMap,
                            canonicalOrigin = authObj["canonicalOrigin"]?.asStringOrNull() ?: "",
                            expiryEpochSeconds = authObj["expiryEpochSeconds"]?.asLongOrNull(),
                            createdAt = authObj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                            updatedAt = authObj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                            schemaVersion = authSchemaVer
                        )
                        writeAuthCredentialInternal(localId, migratedCred)
                    }
                }
            }

            val index = ProfileIndex(
                profileIds = listOf(localId),
                activeProfileId = localId,
                migratedProfileId = localId,
                updatedAt = System.currentTimeMillis()
            )
            writeProfileIndexInternal(index)

            // Old files deleted last
            legacyConnectionFile.delete()
            legacyAuthFile.delete()
            migrationChecked = true
            return
        }

        // Neither exists: fresh install
        writeProfileIndexInternal(ProfileIndex(emptyList(), null, null))
        migrationChecked = true
    }

    private fun readConnectionProfileInternal(localId: String): ConnectionProfile? {
        val recordId = "connection_profile_$localId"
        val file = getProfileFile(localId)
        if (!file.exists() || file.length() < 4L) return null
        val jsonStr = readEncryptedIdentifiedRecord(
            dir = storageDir,
            filePrefix = "connection_profile",
            expectedRecordId = recordId,
            recordType = "CONNECTION_PROFILE",
            schemaVersion = ConnectionProfile.CURRENT_SCHEMA_VERSION
        ) ?: return null

        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > ConnectionProfile.CURRENT_SCHEMA_VERSION) return null

        val parsedLocalId = obj["localId"]?.asStringOrNull() ?: return null
        if (parsedLocalId != localId) {
            file.delete()
            return null
        }

        return ConnectionProfile(
            localId = parsedLocalId,
            normalizedOrigin = obj["normalizedOrigin"]?.asStringOrNull() ?: return null,
            displayLabel = obj["displayLabel"]?.asStringOrNull() ?: "",
            lastSuccessfulProtocol = obj["lastSuccessfulProtocol"]?.asStringOrNull(),
            createdAt = obj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            schemaVersion = schemaVer
        )
    }

    private fun writeConnectionProfileInternal(profile: ConnectionProfile) {
        val recordId = "connection_profile_${profile.localId}"
        val aad = createAad("CONNECTION_PROFILE", profile.schemaVersion, recordId)
        writeEncryptedIdentifiedRecord(
            dir = storageDir,
            filePrefix = "connection_profile",
            recordId = recordId,
            jsonString = serializeConnectionProfile(profile),
            aad = aad
        )
    }

    private fun serializeConnectionProfile(profile: ConnectionProfile): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            linkedMapOf(
                "localId" to profile.localId,
                "normalizedOrigin" to profile.normalizedOrigin,
                "displayLabel" to profile.displayLabel,
                "lastSuccessfulProtocol" to profile.lastSuccessfulProtocol,
                "createdAt" to profile.createdAt,
                "updatedAt" to profile.updatedAt,
                "schemaVersion" to profile.schemaVersion
            )
        )
    )

    private fun stageConnectionProfile(profile: ConnectionProfile): File {
        val recordId = "connection_profile_${profile.localId}"
        val aad = createAad("CONNECTION_PROFILE", profile.schemaVersion, recordId)
        val plaintextBytes = serializeConnectionProfile(profile).toByteArray(Charsets.UTF_8)
        val ciphertext = cipherEngine.encrypt(plaintextBytes, aad)

        val idBytes = recordId.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(4 + idBytes.size + ciphertext.size)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.put(ciphertext)

        val staged = File(storageDir, "connection_profile_${sha256Hex(recordId)}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(staged).use { output ->
                output.write(buffer.array())
                output.flush()
                output.fd.sync()
            }
            return staged
        } catch (exception: Exception) {
            staged.delete()
            throw exception
        }
    }

    // --- Connection Profiles (multi-profile) ---

    override suspend fun listConnectionProfiles(): List<ConnectionProfile> = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal() ?: return@withContext emptyList()
            index.profileIds.mapNotNull { readConnectionProfileInternal(it) }
        }
    }

    override suspend fun getConnectionProfile(localId: String?): ConnectionProfile? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val targetId = localId ?: readProfileIndexInternal()?.activeProfileId ?: return@withContext null
            readConnectionProfileInternal(targetId)
        }
    }

    override suspend fun saveConnectionProfile(profile: ConnectionProfile): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            // PR-1 REQ-07: purge-before-mutation — an origin change destroys the
            // old origin's partition directory and rotation state BEFORE the
            // profile record is overwritten; a failed purge aborts the save.
            purgeChangedOriginLocked(profile)
            writeConnectionProfileInternal(profile)
            promoteDefaultCredentialInternal(profile)
            val index = readProfileIndexInternal() ?: ProfileIndex()
            val newIds = if (index.profileIds.contains(profile.localId)) {
                index.profileIds
            } else {
                index.profileIds + profile.localId
            }
            val newActive = profile.localId
            writeProfileIndexInternal(
                index.copy(
                    profileIds = newIds,
                    activeProfileId = newActive,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun deleteConnectionProfile(localId: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal() ?: return@withContext false
            if (index.profileIds.size <= 1) {
                // Refuse to delete the last remaining profile
                return@withContext false
            }
            if (!index.profileIds.contains(localId)) {
                return@withContext false
            }
            // PR-1 REQ-07: purge-before-mutation — both refusals above returned
            // before any purge; the deleting branch destroys the partition first.
            readConnectionProfileInternal(localId)?.let { profile ->
                purgeOriginSubtreeLocked(profile.localId, profile.normalizedOrigin)
            }
            revokeLocalLogin(localId, readConnectionProfileInternal(localId)?.normalizedOrigin.orEmpty())
            getProfileFile(localId).delete()
            getCredentialFile(localId).delete()
            val newIds = index.profileIds.filter { it != localId }
            val newActive = if (index.activeProfileId == localId) null else index.activeProfileId
            writeProfileIndexInternal(
                index.copy(
                    profileIds = newIds,
                    activeProfileId = newActive,
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    override suspend fun clearConnectionProfile(localId: String?): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val targetId = localId ?: readProfileIndexInternal()?.activeProfileId ?: return@withContext
            // PR-1 REQ-07: purge-before-mutation for the profile clear.
            readConnectionProfileInternal(targetId)?.let { profile ->
                purgeOriginSubtreeLocked(profile.localId, profile.normalizedOrigin)
            }
            revokeLocalLogin(targetId, readConnectionProfileInternal(targetId)?.normalizedOrigin.orEmpty())
            getProfileFile(targetId).delete()
            getCredentialFile(targetId).delete()
            val index = readProfileIndexInternal() ?: return@withContext
            val newIds = index.profileIds.filter { it != targetId }
            val newActive = if (index.activeProfileId == targetId) null else index.activeProfileId
            writeProfileIndexInternal(
                index.copy(
                    profileIds = newIds,
                    activeProfileId = newActive,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun getActiveProfileId(): String? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            readProfileIndexInternal()?.activeProfileId
        }
    }

    override suspend fun setActiveProfileId(localId: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal() ?: return@withContext false
            if (localId.isNotEmpty() && !index.profileIds.contains(localId)) {
                return@withContext false
            }
            loginGeneration.incrementAndGet()
            writeProfileIndexInternal(
                index.copy(
                    activeProfileId = localId.ifEmpty { null },
                    updatedAt = System.currentTimeMillis()
                )
            )
            true
        }
    }

    override suspend fun getMigratedProfileId(): String? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            readProfileIndexInternal()?.migratedProfileId ?: readProfileIndexInternal()?.profileIds?.firstOrNull()
        }
    }

    override suspend fun readConnectionProfileForUpdate(localId: String?): ProfileReadResult = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val targetId = localId ?: readProfileIndexInternal()?.activeProfileId
                ?: return@withContext ProfileReadResult.ABSENT
            val file = getProfileFile(targetId)
            if (!file.exists() || file.length() == 0L) {
                return@withContext ProfileReadResult.ABSENT
            }
            val bytes = try {
                file.readBytes()
            } catch (exception: Exception) {
                return@withContext ProfileReadResult.FAILED(exception.javaClass.simpleName)
            }
            if (bytes.size < 4) {
                file.delete()
                return@withContext ProfileReadResult.FAILED("MalformedConnectionProfile")
            }
            val idLen = ByteBuffer.wrap(bytes, 0, 4).int
            if (idLen <= 0 || bytes.size < 4 + idLen) {
                file.delete()
                return@withContext ProfileReadResult.FAILED("MalformedConnectionProfile")
            }
            val parsedRecordId = String(bytes, 4, idLen, Charsets.UTF_8)
            val expectedRecordId = "connection_profile_$targetId"
            if (parsedRecordId != expectedRecordId) {
                file.delete()
                return@withContext ProfileReadResult.FAILED("IdentityMismatch")
            }
            val ciphertext = bytes.copyOfRange(4 + idLen, bytes.size)
            val aad = createAad("CONNECTION_PROFILE", ConnectionProfile.CURRENT_SCHEMA_VERSION, expectedRecordId)
            val plaintext = cipherEngine.decrypt(ciphertext, aad).getOrElse { exception ->
                return@withContext ProfileReadResult.FAILED(exception.javaClass.simpleName)
            }
            val obj = GatewayJson.parseOrNull(String(plaintext, Charsets.UTF_8))?.asObjectOrNull()
                ?: return@withContext ProfileReadResult.FAILED("MalformedConnectionProfile")
            val schemaVersion = obj["schemaVersion"]?.asIntOrNull() ?: 1
            if (schemaVersion > ConnectionProfile.CURRENT_SCHEMA_VERSION) {
                return@withContext ProfileReadResult.FAILED("UnsupportedConnectionProfileSchema")
            }
            val parsedLocalId = obj["localId"]?.asStringOrNull()
                ?: return@withContext ProfileReadResult.FAILED("MalformedConnectionProfile")
            val origin = obj["normalizedOrigin"]?.asStringOrNull()
                ?: return@withContext ProfileReadResult.FAILED("MalformedConnectionProfile")
            ProfileReadResult.AVAILABLE(
                ConnectionProfile(
                    localId = parsedLocalId,
                    normalizedOrigin = origin,
                    displayLabel = obj["displayLabel"]?.asStringOrNull() ?: "",
                    lastSuccessfulProtocol = obj["lastSuccessfulProtocol"]?.asStringOrNull(),
                    createdAt = obj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                    updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                    schemaVersion = schemaVersion
                )
            )
        }
    }

    override suspend fun saveConnectionProfileIfCurrent(
        profile: ConnectionProfile,
        generation: Long,
        generationGate: ConnectionProfileGenerationGate
    ): ProfileCommitResult = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            // PR-1 REQ-07: stale commit attempts never reach staging or purge.
            if (!generationGate.isCurrent(generation)) {
                return@withContext ProfileCommitResult.STALE
            }
            val staged = try {
                stageConnectionProfile(profile)
            } catch (exception: Exception) {
                return@withContext ProfileCommitResult.FAILED(exception.javaClass.simpleName)
            }
            try {
                generationGate.commitIfCurrent(generation) {
                    try {
                        // PR-1 REQ-06/07 (review BLOCKER-1): the PRODUCTION
                        // origin-edit path rotates the epoch and purges the OLD
                        // (P,O) subtree BEFORE committing the new origin — same
                        // strict ordering as saveConnectionProfile (TEST-08). A
                        // purge failure aborts the commit (FAILED, fail-closed):
                        // the caller's mutation never runs beneath a stale
                        // subtree and the staged profile is discarded.
                        purgeChangedOriginLocked(profile)
                        val targetFile = getProfileFile(profile.localId)
                        atomicMove(staged, targetFile)
                        promoteDefaultCredentialInternal(profile)
                        val currentIndex = readProfileIndexInternal() ?: ProfileIndex()
                        val newIds = if (currentIndex.profileIds.contains(profile.localId)) {
                            currentIndex.profileIds
                        } else {
                            currentIndex.profileIds + profile.localId
                        }
                        val newActiveId = profile.localId
                        val newIndex = currentIndex.copy(
                            profileIds = newIds,
                            activeProfileId = newActiveId,
                            updatedAt = System.currentTimeMillis()
                        )
                        writeProfileIndexInternal(newIndex)
                        ProfileCommitResult.SAVED
                    } catch (exception: Exception) {
                        ProfileCommitResult.FAILED(exception.javaClass.simpleName)
                    }
                }
            } finally {
                if (staged.exists()) staged.delete()
            }
        }
    }

    // --- Keyed Auth Credential ---

    private fun readAuthCredentialInternal(profileLocalId: String, allowExpired: Boolean = false): AuthCredential? {
        val recordId = "auth_credential_$profileLocalId"
        val file = getCredentialFile(profileLocalId)
        if (!file.exists() || file.length() < 4L) return null
        val jsonStr = readEncryptedIdentifiedRecord(
            dir = storageDir,
            filePrefix = "auth_credential",
            expectedRecordId = recordId,
            recordType = "AUTH_CREDENTIAL",
            schemaVersion = AuthCredential.CURRENT_SCHEMA_VERSION
        ) ?: return null

        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > AuthCredential.CURRENT_SCHEMA_VERSION) return null

        val expiry = obj["expiryEpochSeconds"]?.asLongOrNull()
        val nowSec = System.currentTimeMillis() / 1000
        if (!allowExpired && expiry != null && expiry < nowSec) {
            file.delete()
            return null
        }

        val cookiesObj = obj["cookies"]?.asObjectOrNull()
        val cookiesMap = cookiesObj?.map?.mapValues { it.value.asStringOrNull() ?: "" } ?: emptyMap()

        return AuthCredential(
            driverKind = obj["driverKind"]?.asStringOrNull() ?: AuthCredential.DRIVER_COOKIE_SESSION,
            providerId = obj["providerId"]?.asStringOrNull() ?: "",
            cookies = cookiesMap,
            canonicalOrigin = obj["canonicalOrigin"]?.asStringOrNull() ?: "",
            expiryEpochSeconds = expiry,
            createdAt = obj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            schemaVersion = schemaVer
        )
    }

    private fun writeAuthCredentialInternal(profileLocalId: String, credential: AuthCredential) {
        val recordId = "auth_credential_$profileLocalId"
        val aad = createAad("AUTH_CREDENTIAL", credential.schemaVersion, recordId)
        writeEncryptedIdentifiedRecord(
            dir = storageDir,
            filePrefix = "auth_credential",
            recordId = recordId,
            jsonString = serializeAuthCredential(credential),
            aad = aad
        )
    }

    private fun serializeAuthCredential(credential: AuthCredential): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            linkedMapOf(
                "driverKind" to credential.driverKind,
                "providerId" to credential.providerId,
                "cookies" to credential.cookies,
                "canonicalOrigin" to credential.canonicalOrigin,
                "expiryEpochSeconds" to credential.expiryEpochSeconds,
                "createdAt" to credential.createdAt,
                "updatedAt" to credential.updatedAt,
                "schemaVersion" to credential.schemaVersion
            )
        )
    )

    /** Called under the vault mutex, after the destination profile is committed. */
    private fun promoteDefaultCredentialInternal(profile: ConnectionProfile) {
        val source = getCredentialFile("default")
        val destination = getCredentialFile(profile.localId)
        if (!source.exists() || destination.exists()) return
        val credential = authorizedCredentialInternal("default") ?: return
        val remembered = readLocalLogin("default")
        if (credential.canonicalOrigin != profile.normalizedOrigin) return
        // Invalidate captured source leases before any destination write. Bind the
        // destination epoch before its credential, matching the ordinary auth funnel.
        writeCacheAccessStateInternal("default", CacheEpochRotationReason.SIGNED_OUT,
            originOverride = credential.canonicalOrigin)
        try {
            saveAuthCredentialLocked(profile.localId, credential, completePublication = false)
            remembered?.takeIf { it.allowed && it.origin == profile.normalizedOrigin }?.let {
                val epoch = readCacheAccessStateInternal(profile.localId, it.origin)!!.cacheEpoch
                writeLocalLogin(profile.localId, LocalLogin(it.origin, epoch, it.username, it.provider, it.password, true))
                revokeLocalLogin("default", it.origin)
            }
            check(source.delete()) { "Credential promotion source removal failed" }
            deniedAuthEpochs.remove(AuthOwner(profile.localId, credential.canonicalOrigin))
        } catch (exception: Exception) {
            // An interrupted promotion never leaves a readable destination jar.
            check(!destination.exists() || destination.delete()) { "Credential promotion cleanup failed" }
            throw exception
        }
    }

    private fun activeAuthProfileInternal(): String? {
        val index = readProfileIndexInternal()
        return index?.activeProfileId ?: if (index?.profileIds.isNullOrEmpty()) "default" else null
    }

    private fun authLeaseInternal(origin: String, rawCandidate: Boolean = false): AuthCredentialLease? {
        val id = activeAuthProfileInternal() ?: return null
        val profile = readConnectionProfileInternal(id)
        if (profile == null && id != "default") return null
        if (profile != null && profile.normalizedOrigin != origin) return null
        val credential = readAuthCredentialInternal(id) ?: return null
        if (credential.canonicalOrigin != origin || credential.cookies.isEmpty()) return null
        val state = readCacheAccessStateInternal(id, origin) ?: return null
        if (state.rotationReason == CacheEpochRotationReason.SIGNED_OUT.raw ||
            state.rotationReason == CacheEpochRotationReason.AUTH_EXPIRED.raw ||
            (!rawCandidate && (accessDenied(id, origin, state) ||
                (localLoginFile(id).exists() && localEpoch(id, origin) == null)))) return null
        return AuthCredentialLease(id, origin, state.cacheEpoch, credential, localEpoch(id, origin) ?: state.cacheEpoch)
    }

    override suspend fun readAuthCredentialLease(origin: String): AuthCredentialLease? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            authLeaseInternal(origin)
        }
    }

    /** Only called while holding the existing vault mutex on the IO dispatcher. */
    private fun capturedOwnerMatchesInternal(expected: AuthCredentialLease, selectedContext: String?): Boolean {
        val actual = authLeaseInternal(expected.origin) ?: return false
        return actual.profileLocalId == expected.profileLocalId && actual.origin == expected.origin &&
            actual.epoch == expected.epoch && actual.credential == expected.credential &&
            readSelectedContextProfileInternal() == selectedContext
    }

    private fun capturedPartitionMatchesInternal(
        partition: CacheAccessPartition, expected: AuthCredentialLease, selectedContext: String?
    ): Boolean = capturedOwnerMatchesInternal(expected, selectedContext) &&
        partition.profileLocalId == expected.profileLocalId &&
        partition.originSha256 == sha256Hex(expected.origin) && partition.cacheEpoch == expected.cacheEpoch &&
        partition.workspaceScopeTag == workspaceTagFor(selectedContext)

    override suspend fun mutateAuthCredentialIfCurrent(
        lease: AuthCredentialLease,
        replacement: AuthCredential?,
        clear: Boolean
    ): AuthCredentialMutationResult = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val current = authLeaseInternal(lease.origin)
            if (current == null || current.profileLocalId != lease.profileLocalId ||
                current.epoch != lease.epoch || current.credential != lease.credential) {
                return@withContext AuthCredentialMutationResult.Stale
            }
            if (!clear && replacement == null) {
                return@withContext AuthCredentialMutationResult.Accepted(current)
            }
            if (!clear && replacement?.canonicalOrigin != lease.origin) {
                return@withContext AuthCredentialMutationResult.Failed
            }
            val owner = AuthOwner(lease.profileLocalId, lease.origin)
            val successor = if (clear) null else mintSecureCacheEpoch()
            deniedAuthEpochs[owner] = DeniedAuthEpochs(lease.epoch, successor)
            var successorAttempted = false
            try {
                writeCacheAccessStateInternal(lease.profileLocalId,
                    if (clear) CacheEpochRotationReason.AUTH_EXPIRED else CacheEpochRotationReason.SIGNED_OUT,
                    originOverride = lease.origin, nominatedEpoch = if (clear) mintSecureCacheEpoch() else null)
                val accepted = if (clear) {
                    val file = getCredentialFile(lease.profileLocalId)
                    check(!file.exists() || file.delete()) { "Credential deletion failed" }
                    AuthCredentialMutationResult.Accepted(null)
                } else {
                    writeAuthCredentialInternal(lease.profileLocalId, replacement!!)
                    successorAttempted = true
                    writeCacheAccessStateInternal(lease.profileLocalId,
                        CacheEpochRotationReason.CREDENTIAL_REPLACED, originOverride = lease.origin,
                        nominatedEpoch = successor!!)
                    val receipt = authLeaseInternal(lease.origin, rawCandidate = true)
                    check(receipt?.epoch == successor && receipt.credential == replacement) {
                        "Auth publication verification failed"
                    }
                    AuthCredentialMutationResult.Accepted(receipt)
                }
                deniedAuthEpochs.remove(owner)
                accepted
            } catch (exception: Exception) {
                if (successorAttempted) {
                    // One bounded restoration; its failure never clears process denial
                    // or replaces the original failure. D0/DU are restart limitations.
                    try {
                        writeCacheAccessStateInternal(lease.profileLocalId,
                            CacheEpochRotationReason.SIGNED_OUT, originOverride = lease.origin)
                    } catch (_: Exception) { /* Retain all nominated denial roles. */ }
                }
                if (exception is CancellationException) throw exception
                AuthCredentialMutationResult.Failed
            }
        }
    }

    override suspend fun getAuthCredential(profileLocalId: String?): AuthCredential? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal()
            val targetId = profileLocalId
                ?: index?.activeProfileId
                ?: if (index?.profileIds.isNullOrEmpty() && getCredentialFile("default").exists()) "default" else null
                ?: return@withContext null
            authorizedCredentialInternal(targetId)
        }
    }

    override suspend fun saveAuthCredential(credential: AuthCredential, profileLocalId: String?): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal()
            val targetId = profileLocalId
                ?: index?.activeProfileId
                ?: if (index?.profileIds.isNullOrEmpty()) "default" else null
                ?: return@withContext
            loginGeneration.incrementAndGet()
            val hadLocalLogin = localLoginFile(targetId).exists()
            if (hadLocalLogin) revokeLocalLogin(targetId, credential.canonicalOrigin)
            saveAuthCredentialLocked(targetId, credential)
            if (hadLocalLogin) writeLocalLogin(targetId, LocalLogin(credential.canonicalOrigin,
                readCacheAccessStateInternal(targetId, credential.canonicalOrigin)!!.cacheEpoch, null, null, null, true))
        }
    }

    override suspend fun clearAuthCredential(
        profileLocalId: String?,
        reason: CacheEpochRotationReason,
        // PR-6 REQ-02/REQ-05: KEEP retains ciphertext; CLEAR purges this
        // profile's cache AFTER the credential removal below (rotation is
        // unconditional — REQ-01, independent of the choice).
        cacheChoice: SignOutCacheChoice
    ): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal()
            val targetId = profileLocalId
                ?: index?.activeProfileId
                ?: if (index?.profileIds.isNullOrEmpty() && getCredentialFile("default").exists()) "default" else null
                ?: return@withContext
            // PR-1 REQ-06: rotate (signed out) BEFORE clearing the credential so
            // the superseded partition stops serving. Only an existing rotation
            // record is rotated — sign-out never mints a usable one (SEC-01).
            writeCacheAccessStateInternal(
                targetId,
                reason,
                rotateOnlyIfStateExists = true
            )
            if (reason != CacheEpochRotationReason.AUTH_EXPIRED) revokeLocalLogin(targetId,
                readConnectionProfileInternal(targetId)?.normalizedOrigin ?: readAuthCredentialInternal(targetId, true)?.canonicalOrigin.orEmpty())
            getCredentialFile(targetId).delete()
            // PR-6 REQ-05: the choice is applied strictly AFTER the credential
            // is gone — KEEP retains every byte of ciphertext (an offline
            // re-sign-in reuses it under a fresh epoch); CLEAR purges the
            // ACTIVE profile's subtree, its conversation indexes, and the
            // catalog reconcile, then one durable directory sync (PAT-01).
            if (cacheChoice == SignOutCacheChoice.CLEAR) {
                // PR-6 REQ-08: cache cleanup must never fail the sign-out —
                // injected purge faults exercise exactly this boundary; the
                // residue stays fail-closed (no credential, rotated state,
                // resolver Unavailable) and a later re-run resumes it.
                try {
                    purgeProfileCacheSubtreeLocked(targetId)
                } catch (_: Exception) {
                    // Swallow: identity cleanup already completed (ADR-0003).
                }
            }
        }
    }

    /**
     * PR-6 REQ-05 (plan "Clear-cache"): purge ONE profile's cache subtree —
     * `cache/sha256(profileLocalId)/` (all per-origin rotation states,
     * summaries, roster, partition drafts/pending turns, conversation
     * content) — then every `conversation-index:<profileLocalId>:...`
     * envelope this profile owns, then the §5.3 catalog reconcile (no locator
     * of a deleted index may survive). Order is content -> index -> catalog,
     * ending with one directory sync (§5.3 PAT-01). Only the ACTIVE profile
     * is purged — other profiles' credentials/records are untouched.
     */
    private fun purgeProfileCacheSubtreeLocked(profileLocalId: String) {
        val profileDir = File(cacheRootDir, sha256Hex(profileLocalId))
        val hadContent = profileDir.exists()
        if (hadContent) {
            profileDir.deleteRecursively()
        }
        var anyIndexDeleted = false
        conversationIndexFiles().forEach { file ->
            val header = envelopeHeaderRecordId(file, "index") ?: return@forEach
            if (header.startsWith("conversation-index:$profileLocalId:")) {
                file.delete()
                anyIndexDeleted = true
            }
        }
        if (hadContent) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        if (anyIndexDeleted) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        // §5.3 reconcile fires STAGE_CONVERSATION_CATALOG_DELETED itself.
        reconcileConversationCatalogAfterDeleteInternal(anyIndexDeleted)
        directorySync(storageDir)
    }

    // --- Session Selection ---

    override suspend fun getSessionSelection(): SessionSelection? = mutex.withLock {
        withContext(ioDispatcher) {
            val aad = createAad("SESSION_SELECTION", SessionSelection.CURRENT_SCHEMA_VERSION, "singleton_session_selection")
            val jsonStr = readEncryptedSingleton(selectionFile, aad) ?: return@withContext null
            val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return@withContext null

            val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
            if (schemaVer > SessionSelection.CURRENT_SCHEMA_VERSION) return@withContext null

            SessionSelection(
                storedSessionId = obj["storedSessionId"]?.asStringOrNull() ?: return@withContext null,
                lastKnownTitle = obj["lastKnownTitle"]?.asStringOrNull(),
                profileLocalId = obj["profileLocalId"]?.asStringOrNull(),
                updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
                schemaVersion = schemaVer
            )
        }
    }

    override suspend fun saveSessionSelection(selection: SessionSelection): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            writeSessionSelectionInternal(selection)
        }
    }

    override suspend fun saveSessionSelectionIfCurrent(
        selection: SessionSelection, expected: AuthCredentialLease, selectedContext: String?
    ): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            if (selection.profileLocalId != expected.profileLocalId ||
                !capturedOwnerMatchesInternal(expected, selectedContext)) return@withContext false
            writeSessionSelectionInternal(selection)
            true
        }
    }

    private fun writeSessionSelectionInternal(selection: SessionSelection) {
        val activeId = selection.profileLocalId ?: readProfileIndexInternal()?.activeProfileId
        val map = linkedMapOf<String, Any?>(
            "storedSessionId" to selection.storedSessionId,
            "lastKnownTitle" to selection.lastKnownTitle,
            "profileLocalId" to activeId,
            "updatedAt" to selection.updatedAt,
            "schemaVersion" to selection.schemaVersion
        )
        val aad = createAad("SESSION_SELECTION", selection.schemaVersion, "singleton_session_selection")
        writeEncryptedSingleton(selectionFile, GatewayJson.serialize(GatewayJson.fromAny(map)), aad)
    }

    override suspend fun clearSessionSelection(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            selectionFile.delete()
        }
    }

    // --- Session Summary Cache (V3: partitioned, origin/workspace/epoch bound) ---

    override suspend fun activeAccessPartition(): CacheAccessPartitionResult = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val activeId = readProfileIndexInternal()?.activeProfileId
                ?: return@withContext CacheAccessPartitionResult.Unavailable(
                    CacheUnavailableReason.NO_ACTIVE_PROFILE
                )
            val profile = readConnectionProfileInternal(activeId)
                ?: return@withContext CacheAccessPartitionResult.Unavailable(
                    CacheUnavailableReason.PROFILE_ABSENT
                )
            if (localLoginFile(activeId).exists()) {
                val epoch = localEpoch(activeId, profile.normalizedOrigin)
                    ?: return@withContext CacheAccessPartitionResult.Unavailable(CacheUnavailableReason.CREDENTIAL_ABSENT)
                return@withContext CacheAccessPartitionResult.Available(CacheAccessPartition(activeId,
                    sha256Hex(profile.normalizedOrigin), epoch, workspaceTagFor(readSelectedContextProfileInternal())))
            }
            val credential = readAuthCredentialInternal(activeId)
                ?: return@withContext CacheAccessPartitionResult.Unavailable(
                    CacheUnavailableReason.CREDENTIAL_ABSENT
                )
            val credentialOrigin = CanonicalOrigin
                .parse(credential.canonicalOrigin)
                .getOrNull()
                ?.originUrl
            if (credentialOrigin == null || credentialOrigin != profile.normalizedOrigin) {
                // SEC-02: a credential bound to a different origin can never gate
                // offline reads for this profile's partition.
                return@withContext CacheAccessPartitionResult.Unavailable(
                    CacheUnavailableReason.CREDENTIAL_ORIGIN_MISMATCH
                )
            }
            val stateFile = cacheAccessStateFileFor(activeId, profile.normalizedOrigin)
            val stateExisted = stateFile.exists()
            val state = readCacheAccessStateInternal(activeId, profile.normalizedOrigin)
                ?: return@withContext CacheAccessPartitionResult.Unavailable(
                    if (stateExisted) CacheUnavailableReason.CACHE_ACCESS_STATE_INVALID
                    else CacheUnavailableReason.CACHE_ACCESS_STATE_ABSENT
                )
            if (accessDenied(activeId, profile.normalizedOrigin, state)) {
                return@withContext CacheAccessPartitionResult.Unavailable(CacheUnavailableReason.CREDENTIAL_ABSENT)
            }
            if (state.originSha256 != sha256Hex(profile.normalizedOrigin)) {
                return@withContext CacheAccessPartitionResult.Unavailable(
                    CacheUnavailableReason.CACHE_ACCESS_STATE_INVALID
                )
            }
            // REQ-04: the partition is scoped to the current workspace selection.
            val selectedName = readSelectedContextProfileInternal()
            val workspaceScopeTag = workspaceTagFor(selectedName)
            CacheAccessPartitionResult.Available(
                CacheAccessPartition(
                    profileLocalId = activeId,
                    originSha256 = state.originSha256,
                    cacheEpoch = state.cacheEpoch,
                    workspaceScopeTag = workspaceScopeTag
                )
            )
        }
    }

    override suspend fun readSessionSummaryCache(
        partition: CacheAccessPartition
    ): CacheReadResult<SessionSummaryCache> = mutex.withLock {
        // PR-1 P3-4 (review): summary I/O runs under the SAME lifecycle mutex
        // every other vault mutation takes (base class had it) — an in-flight
        // read/write/clear can never interleave with credential/selection
        // state changes beneath the lock.
        withContext(ioDispatcher) {
            ensureMigrated()
            withContext(Dispatchers.IO) { readSummaryRecordInternal(partition) }
        }
    }

    override suspend fun saveSessionSummaryCache(
        cache: SessionSummaryCache,
        partition: CacheAccessPartition
    ): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            withContext(Dispatchers.IO) { writeSummaryRecordInternal(cache, partition) }
        }
    }

    override suspend fun clearSessionSummaryCache(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            withContext(Dispatchers.IO) { purgeSummaryRecordsLocked() }
        }
    }

    // --- Context-profile (workspace) roster cache — PR-2 (REQ-01/02/03) ---

    override suspend fun readContextProfileCache(
        partition: CacheAccessPartition
    ): CacheReadResult<ContextProfileCacheV2> = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            withContext(Dispatchers.IO) { readRosterRecordInternal(partition) }
        }
    }

    override suspend fun saveContextProfileCache(
        cache: ContextProfileCacheV2,
        partition: CacheAccessPartition
    ): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            withContext(Dispatchers.IO) { writeRosterRecordInternal(cache, partition) }
        }
    }

    private val cachedRosterNamePattern = Regex("[A-Za-z0-9._-]+")

    /** The shared `<P>:<O>:<E>:<W>` AAD/record-id context (plan §5.2). */
    private fun partitionContextFor(partition: CacheAccessPartition): String =
        "${partition.profileLocalId}:${partition.originSha256}:${partition.cacheEpoch}:${partition.workspaceScopeTag}"

    private fun rosterRecordIdFor(partition: CacheAccessPartition): String =
        "context-profiles:" + partitionContextFor(partition)

    private fun rosterCacheFileFor(partition: CacheAccessPartition): File =
        File(
            summaryOriginDirFor(partition.profileLocalId, partition.originSha256),
            "roster_" + sha256Hex(rosterRecordIdFor(partition)) + ".enc"
        )

    private fun rosterAadFor(partition: CacheAccessPartition): ByteArray =
        createAad(
            "CONTEXT_PROFILE_CACHE",
            ContextProfileCacheV2.CURRENT_SCHEMA_VERSION,
            rosterRecordIdFor(partition),
            partitionContextFor(partition)
        )

    /**
     * PR-2 REQ-01/REQ-02: durable atomic write of one roster record. An
     * identity/tag mismatch with the CALLER's partition (a mid-flight
     * selection change may not write another scope) or a cap breach skips the
     * write entirely — capture is best-effort by contract.
     */
    private fun writeRosterRecordInternal(
        cache: ContextProfileCacheV2,
        partition: CacheAccessPartition
    ) {
        if (cache.profileLocalId != partition.profileLocalId ||
            !cache.originSha256.equals(partition.originSha256, ignoreCase = true) ||
            cache.cacheEpoch != partition.cacheEpoch ||
            cache.workspaceScopeTag != partition.workspaceScopeTag
        ) {
            return
        }
        val expectedTag = workspaceTagFor(cache.selectedWorkspaceName)
        if (expectedTag != partition.workspaceScopeTag) return
        if (cache.entries.size > ContextProfileCacheV2.MAX_ENTRIES) return
        val bytes = rosterPayloadJson(cache, partition).toByteArray(Charsets.UTF_8)
        if (bytes.size > ContextProfileCacheV2.MAX_PAYLOAD_BYTES) return

        val originDir = summaryOriginDirFor(partition.profileLocalId, partition.originSha256)
        originDir.mkdirs()
        val file = rosterCacheFileFor(partition)
        // A fresh write supersedes any quarantine residue for this record.
        File(file.parentFile, file.name + ".quarantine").delete()
        writeAtomicDurable(file, cipherEngine.encrypt(bytes, rosterAadFor(partition)))
    }

    /** Mandatory payload per plan §3 — created/updated/captured all stamp the capture instant. */
    private fun rosterPayloadJson(
        cache: ContextProfileCacheV2,
        partition: CacheAccessPartition
    ): String {
        val entriesList = cache.entries.map { entry ->
            linkedMapOf<String, Any?>(
                "name" to entry.name,
                "isRunning" to entry.isRunning,
                "isActive" to entry.isActive
            )
        }
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to ContextProfileCacheV2.CURRENT_SCHEMA_VERSION,
            "recordId" to rosterRecordIdFor(partition),
            "profileLocalId" to partition.profileLocalId,
            "originSha256" to partition.originSha256,
            "cacheEpoch" to partition.cacheEpoch,
            "workspaceScopeTag" to partition.workspaceScopeTag,
            "createdAtMillis" to cache.capturedAtEpochMillis,
            "updatedAtMillis" to cache.capturedAtEpochMillis,
            "capturedAtEpochMillis" to cache.capturedAtEpochMillis,
            "selectedWorkspaceName" to cache.selectedWorkspaceName,
            "entries" to entriesList
        )
        return GatewayJson.serialize(GatewayJson.fromAny(map))
    }

    /**
     * PR-2 REQ-03 read path (plan "Read path"): fail-closed in PR-1 style.
     * The plan's idealized step order inverts only where the headerless record
     * requires decryption before schema/identity checks — realized order:
     * quarantine residue → missing/empty → pre-decrypt size guard → AEAD under
     * the exact caller AAD → schema (future → quarantine/Unavailable) →
     * required fields → record ID recomputed from the caller partition →
     * P/O/E/W equality → selected-name ⇔ W consistency → 24h TTL → caps.
     * Every failure deletes (or quarantines) the record and never falls back.
     */
    private fun readRosterRecordInternal(
        partition: CacheAccessPartition
    ): CacheReadResult<ContextProfileCacheV2> {
        val file = rosterCacheFileFor(partition)
        val quarantine = File(file.parentFile, file.name + ".quarantine")
        if (quarantine.exists()) {
            return CacheReadResult.Unavailable
        }
        if (!file.exists()) return CacheReadResult.Absent
        if (file.length() == 0L) {
            file.delete()
            return CacheReadResult.Absent
        }
        if (file.length() > ContextProfileCacheV2.MAX_PAYLOAD_BYTES + ROSTER_CIPHERTEXT_OVERHEAD_BYTES) {
            // Pre-decrypt anti-DoS guard (PR-1 read style); the authoritative
            // byte cap runs on the decrypted DTO bytes below (REQ-01 step 9).
            file.delete()
            return CacheReadResult.Corrupt
        }
        val plaintext = try {
            cipherEngine.decrypt(file.readBytes(), rosterAadFor(partition)).getOrNull()
        } catch (_: Exception) {
            null
        } ?: run {
            // AEAD failure — corrupt or foreign AAD: delete, fail closed.
            file.delete()
            return CacheReadResult.Corrupt
        }
        if (plaintext.size > ContextProfileCacheV2.MAX_PAYLOAD_BYTES) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val obj = GatewayJson.parseOrNull(String(plaintext, Charsets.UTF_8))?.asObjectOrNull()
            ?: run {
                file.delete()
                return CacheReadResult.Corrupt
            }
        val schema = obj["schemaVersion"]?.asIntOrNull()
            ?: run {
                file.delete()
                return CacheReadResult.Corrupt
            }
        if (schema > ContextProfileCacheV2.CURRENT_SCHEMA_VERSION) {
            // A future schema is never parsed — quarantine the residue.
            file.renameTo(quarantine)
            return CacheReadResult.Unavailable
        }
        if (schema < ContextProfileCacheV2.CURRENT_SCHEMA_VERSION) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val recordId = obj["recordId"]?.asStringOrNull()
        val profileLocalId = obj["profileLocalId"]?.asStringOrNull()
        val originSha256 = obj["originSha256"]?.asStringOrNull()
        val cacheEpoch = obj["cacheEpoch"]?.asStringOrNull()
        val workspaceScopeTag = obj["workspaceScopeTag"]?.asStringOrNull()
        val createdAt = obj["createdAtMillis"]?.asLongOrNull()
        val updatedAt = obj["updatedAtMillis"]?.asLongOrNull()
        val capturedAt = obj["capturedAtEpochMillis"]?.asLongOrNull()
        val entriesArray = obj["entries"]?.asArrayOrNull()
        if (recordId == null || profileLocalId == null || originSha256 == null ||
            cacheEpoch == null || workspaceScopeTag == null || createdAt == null ||
            updatedAt == null || capturedAt == null || entriesArray == null
        ) {
            // Required field missing (unreachable by construction — defensive).
            file.delete()
            return CacheReadResult.Corrupt
        }
        // Record ID recomputed from the CALLER's partition — never from disk.
        val expectedRecordId = rosterRecordIdFor(partition)
        if (recordId != expectedRecordId) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        // Payload identity must equal the caller partition (hex case-insensitive).
        if (profileLocalId != partition.profileLocalId ||
            !originSha256.equals(partition.originSha256, ignoreCase = true) ||
            cacheEpoch != partition.cacheEpoch ||
            workspaceScopeTag != partition.workspaceScopeTag
        ) {
            file.delete()
            return CacheReadResult.PartitionMismatch
        }
        // Selected-name ⇔ W consistency: unscoped is exactly null — never cross-scope.
        val selectedName = obj["selectedWorkspaceName"]?.asStringOrNull()
        val expectedTag = workspaceTagFor(selectedName)
        if (expectedTag != partition.workspaceScopeTag) {
            file.delete()
            return CacheReadResult.PartitionMismatch
        }
        // 24h wall-clock TTL anchored at the capture instant.
        if (wallClockMillis() - capturedAt > ContextProfileCacheV2.MAX_TTL_MILLIS) {
            file.delete()
            return CacheReadResult.Expired
        }
        // Caps: entries bound; the byte cap already ran on the decrypted bytes.
        if (entriesArray.list.size > ContextProfileCacheV2.MAX_ENTRIES) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val entries = ArrayList<CachedContextProfile>(entriesArray.list.size)
        for (element in entriesArray.list) {
            val entry = element.asObjectOrNull() ?: run {
                file.delete()
                return CacheReadResult.Corrupt
            }
            val name = entry["name"]?.asStringOrNull()
            val isRunning = entry["isRunning"]?.asBooleanOrNull()
            val isActive = entry["isActive"]?.asBooleanOrNull()
            if (name == null || isRunning == null || isActive == null ||
                !cachedRosterNamePattern.matches(name)
            ) {
                // Only sanitized rows are ever surfaced (re-validated on read).
                file.delete()
                return CacheReadResult.Corrupt
            }
            entries.add(
                CachedContextProfile(name = name, isRunning = isRunning, isActive = isActive)
            )
        }
        return CacheReadResult.Available(
            ContextProfileCacheV2(
                schemaVersion = ContextProfileCacheV2.CURRENT_SCHEMA_VERSION,
                recordId = expectedRecordId,
                profileLocalId = profileLocalId,
                originSha256 = originSha256,
                cacheEpoch = cacheEpoch,
                workspaceScopeTag = workspaceScopeTag,
                createdAtMillis = createdAt,
                updatedAtMillis = updatedAt,
                capturedAtEpochMillis = capturedAt,
                selectedWorkspaceName = selectedName,
                entries = entries
            )
        )
    }

    // --- Session summary internals (REQ-03/REQ-06/REQ-07/REQ-08) ---

    private fun summaryOriginDirFor(profileLocalId: String, originSha256: String): File =
        File(
            File(cacheRootDir, sha256Hex(profileLocalId)),
            sha256Hex("$profileLocalId:$originSha256")
        )

    private fun summaryRecordIdFor(
        profileLocalId: String,
        originSha256: String,
        cacheEpoch: String,
        workspaceScopeTag: String
    ): String =
        "session-summaries:$profileLocalId:$originSha256:$cacheEpoch:$workspaceScopeTag"

    private fun summaryFileFor(partition: CacheAccessPartition): File =
        File(
            summaryOriginDirFor(partition.profileLocalId, partition.originSha256),
            "summary_" + sha256Hex(
                summaryRecordIdFor(
                    partition.profileLocalId,
                    partition.originSha256,
                    partition.cacheEpoch,
                    partition.workspaceScopeTag
                )
            ) + ".enc"
        )

    private fun summaryAadFor(partition: CacheAccessPartition): ByteArray =
        createAad(
            "SESSION_SUMMARY_CACHE",
            SUMMARY_SCHEMA_V3,
            summaryRecordIdFor(
                partition.profileLocalId,
                partition.originSha256,
                partition.cacheEpoch,
                partition.workspaceScopeTag
            ),
            partitionContextFor(partition)
        )

    private data class CacheAccessState(
        val cacheEpoch: String,
        val originSha256: String,
        val rotationReason: String,
        val updatedAtMs: Long
    )

    private fun cacheAccessStateFileFor(profileLocalId: String, origin: String): File =
        File(
            summaryOriginDirFor(profileLocalId, sha256Hex(origin)),
            "cache_access_" +
                sha256Hex("cache-access:$profileLocalId:${sha256Hex(origin)}") + ".enc"
        )

    private fun readCacheAccessStateInternal(
        profileLocalId: String,
        origin: String
    ): CacheAccessState? {
        val file = cacheAccessStateFileFor(profileLocalId, origin)
        val originShaKey = sha256Hex(origin)
        val aad = createAad(
            "CACHE_ACCESS_STATE",
            1,
            "cache-access:$profileLocalId:$originShaKey",
            "$profileLocalId:$originShaKey"
        )
        val json = readEncryptedSingleton(file, aad) ?: return null
        val obj = GatewayJson.parseOrNull(json)?.asObjectOrNull() ?: return null
        // PR-1 P3-2 (review): no-downgrade fail-closed on READ — a record from
        // a NEWER schema (or one without a declared version) is never parsed
        // into this build's fields; the resolver surfaces
        // CACHE_ACCESS_STATE_INVALID instead of guessing.
        val declaredSchema = obj["schemaVersion"]?.asIntOrNull()
        if (declaredSchema == null || declaredSchema > 1) return null
        val epoch = obj["cacheEpoch"]?.asStringOrNull()
        val originSha = obj["originSha256"]?.asStringOrNull()
        if (epoch.isNullOrBlank() || originSha == null) return null
        if (originSha != sha256Hex(origin)) return null
        return CacheAccessState(
            cacheEpoch = epoch,
            originSha256 = originSha,
            rotationReason = obj["rotationReason"]?.asStringOrNull() ?: "",
            updatedAtMs = obj["updatedAtMs"]?.asLongOrNull() ?: 0L
        )
    }

    /**
     * REQ-06: rotate the partition epoch and durably commit the rotation state
     * (file fsync + parent directory sync) BEFORE the caller's mutation. A first
     * record is stamped INITIAL_AUTH; later writes carry [reason].
     */
    private fun writeCacheAccessStateInternal(
        profileLocalId: String,
        reason: CacheEpochRotationReason,
        rotateOnlyIfStateExists: Boolean = false,
        originOverride: String? = null,
        nominatedEpoch: String? = null
    ) {
        val origin = originOverride ?: readConnectionProfileInternal(profileLocalId)?.normalizedOrigin ?: return
        val file = cacheAccessStateFileFor(profileLocalId, origin)
        if (rotateOnlyIfStateExists && !file.exists()) return
        val deniedSource = nominatedEpoch == null &&
            (deniedAuthEpochs.containsKey(AuthOwner(profileLocalId, origin)) ||
                readCacheAccessStateInternal(profileLocalId, origin)?.rotationReason ==
                    CacheEpochRotationReason.SIGNED_OUT.raw)
        val effectiveReason = if (deniedSource) CacheEpochRotationReason.SIGNED_OUT
            else if (file.exists() || reason == CacheEpochRotationReason.SIGNED_OUT) reason
            else CacheEpochRotationReason.INITIAL_AUTH
        val originDir = file.parentFile ?: return
        originDir.mkdirs()
        val map = linkedMapOf<String, Any?>(
            // PR-1 P3-3 (review): 128-bit SecureRandom, base64url — the
            // binding schema's shape. Epochs are equality-compared only, so
            // the millisecond prefix carried no needed ordering and leaked
            // time; 128 random bits make rotation detection collision-safe.
            "cacheEpoch" to (nominatedEpoch ?: mintSecureCacheEpoch()),
            "originSha256" to sha256Hex(origin),
            "rotationReason" to effectiveReason.raw,
            "updatedAtMs" to System.currentTimeMillis(),
            "schemaVersion" to 1
        )
        val originShaKey = sha256Hex(origin)
        val aad = createAad(
            "CACHE_ACCESS_STATE",
            1,
            "cache-access:$profileLocalId:$originShaKey",
            "$profileLocalId:$originShaKey"
        )
        writeEncryptedSingleton(file, GatewayJson.serialize(GatewayJson.fromAny(map)), aad)
        directorySync(originDir)
    }

    private fun readSummaryRecordInternal(
        partition: CacheAccessPartition
    ): CacheReadResult<SessionSummaryCache> {
        val file = summaryFileFor(partition)
        val quarantine = File(file.parentFile, file.name + ".quarantine")
        if (quarantine.exists()) {
            // SEC: quarantined residue is never served — Unavailable, not Absent.
            return CacheReadResult.Unavailable
        }
        if (!file.exists()) return CacheReadResult.Absent
        if (file.length() > MAX_SUMMARY_PAYLOAD_BYTES) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val plaintext = try {
            val decrypted = cipherEngine.decrypt(file.readBytes(), summaryAadFor(partition))
            decrypted.exceptionOrNull()?.let { if (it is KeyStoreUnavailableException) throw it }
            decrypted.getOrNull()
        } catch (unavailable: KeyStoreUnavailableException) {
            throw unavailable
        } catch (_: Exception) {
            null
        } ?: run {
            // Decrypt/AEAD failure: delete and fail closed — never fall back.
            file.delete()
            return CacheReadResult.Corrupt
        }
        val json = String(plaintext, Charsets.UTF_8)
        val obj = GatewayJson.parseOrNull(json)?.asObjectOrNull() ?: run {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val schema = obj["schemaVersion"]?.asIntOrNull() ?: run {
            file.delete()
            return CacheReadResult.Corrupt
        }
        if (schema > SUMMARY_SCHEMA_V3) {
            // REQ-03/SEC: a future schema is never parsed — quarantine it.
            file.renameTo(quarantine)
            return CacheReadResult.Unavailable
        }
        if (schema < SUMMARY_SCHEMA_V3) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        // SEC-02: identity binding — every field of the record's identity must
        // match the partition it is being read under.
        if (obj["profileLocalId"]?.asStringOrNull() != partition.profileLocalId ||
            obj["originSha256"]?.asStringOrNull() != partition.originSha256 ||
            obj["cacheEpoch"]?.asStringOrNull() != partition.cacheEpoch ||
            obj["workspaceScopeTag"]?.asStringOrNull() != partition.workspaceScopeTag
        ) {
            file.delete()
            return CacheReadResult.PartitionMismatch
        }
        val selectedName = obj["selectedWorkspaceName"]?.asStringOrNull()
        val expectedTag = workspaceTagFor(selectedName)
        if (expectedTag != partition.workspaceScopeTag) {
            file.delete()
            return CacheReadResult.PartitionMismatch
        }
        val sessions = parseSessionSummaryList(obj["sessions"])
        val cap = obj["sessions"]?.asArrayOrNull()?.list?.size ?: sessions.size
        if (cap > MAX_SUMMARY_ENTRIES || sessions.isEmpty() && cap > 0) {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val syncTime = obj["syncTimeEpochSeconds"]?.asLongOrNull() ?: run {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val capturedWall = obj["capturedAtWallMillis"]?.asLongOrNull() ?: run {
            file.delete()
            return CacheReadResult.Corrupt
        }
        val capturedElapsed = obj["capturedAtElapsedMillis"]?.asLongOrNull() ?: run {
            file.delete()
            return CacheReadResult.Corrupt
        }
        // REQ-03: 7-day retention + capture skew, enforced with BOTH clocks —
        // the monotonic clock ages the record even if the wall clock is frozen
        // or rolled back; a skew beyond tolerance means inconsistent clocks.
        val nowWall = System.currentTimeMillis()
        val nowClock = elapsedRealtimeMillis()
        val wallDelta = nowWall - capturedWall
        val clockDelta = nowClock - capturedElapsed
        val skew = wallDelta - clockDelta
        if (kotlin.math.abs(skew) > CACHE_CAPTURE_SKEW_LIMIT_MS ||
            clockDelta > MAX_RETENTION_MS ||
            wallDelta > MAX_RETENTION_MS
        ) {
            file.delete()
            return CacheReadResult.Expired
        }
        return CacheReadResult.Available(
            SessionSummaryCache(
                sessions = sessions,
                syncTimeEpochSeconds = syncTime,
                profileLocalId = partition.profileLocalId,
                cachedProfileName = selectedName,
                schemaVersion = SUMMARY_SCHEMA_V3
            )
        )
    }

    private fun writeSummaryRecordInternal(
        cache: SessionSummaryCache,
        partition: CacheAccessPartition
    ) {
        val originDir = summaryOriginDirFor(partition.profileLocalId, partition.originSha256)
        originDir.mkdirs()
        var sessions = cache.sessions.take(MAX_SUMMARY_ENTRIES)
        var payload = summaryPayloadJson(cache, sessions, partition)
        var bytes = payload.toByteArray(Charsets.UTF_8)
        while (bytes.size > MAX_SUMMARY_PAYLOAD_BYTES && sessions.size > 1) {
            sessions = sessions.dropLast(1)
            payload = summaryPayloadJson(cache, sessions, partition)
            bytes = payload.toByteArray(Charsets.UTF_8)
        }
        if (bytes.size > MAX_SUMMARY_PAYLOAD_BYTES) {
            // PR-1 OQ-04 (review BLOCKER-2): SKIP oversized writes silently —
            // never throw. The count-cap loop trims by entry; a payload that
            // still cannot fit leaves the partition untouched (reads stay
            // fail-closed) and the caller's live list is never gated on it.
            return
        }
        val file = summaryFileFor(partition)
        // A fresh write supersedes any quarantine residue for this record.
        File(file.parentFile, file.name + ".quarantine").delete()
        writeAtomicDurable(file, cipherEngine.encrypt(bytes, summaryAadFor(partition)))
    }

    private fun summaryPayloadJson(
        cache: SessionSummaryCache,
        sessions: List<SessionSummary>,
        partition: CacheAccessPartition
    ): String {
        val sessionsList = sessions.map { s ->
            linkedMapOf<String, Any?>(
                "id" to s.id,
                "title" to s.title,
                "preview" to s.preview,
                "started_at" to s.startedAt,
                "message_count" to s.messageCount,
                "source" to s.source
            ).apply {
                s.lastActive?.takeIf { it.isFinite() }?.let { put("last_active", it) }
            }
        }
        val map = linkedMapOf<String, Any?>(
            "schemaVersion" to SUMMARY_SCHEMA_V3,
            "sessions" to sessionsList,
            "selectedWorkspaceName" to cache.cachedProfileName,
            "profileLocalId" to partition.profileLocalId,
            "originSha256" to partition.originSha256,
            "cacheEpoch" to partition.cacheEpoch,
            "workspaceScopeTag" to partition.workspaceScopeTag,
            "syncTimeEpochSeconds" to cache.syncTimeEpochSeconds,
            "capturedAtWallMillis" to System.currentTimeMillis(),
            "capturedAtElapsedMillis" to elapsedRealtimeMillis()
        )
        return GatewayJson.serialize(GatewayJson.fromAny(map))
    }

    /**
     * REQ-08: granular clear — every partition's summary record plus quarantine
     * residue under cache/. Rotation state, profiles, and credentials survive.
     */
    private fun purgeSummaryRecordsLocked() {
        cacheFile.delete()
        if (cacheRootDir.exists()) {
            cacheRootDir.walkBottomUp().forEach { f ->
                if (f.isFile &&
                    (f.name.startsWith("summary_") || f.name.endsWith(".quarantine"))
                ) {
                    f.delete()
                }
            }
        }
        quarantineSummaryFile.delete()
    }

    /** REQ-07: destroy one (profile, origin) partition subtree, then sync. */
    private fun purgeOriginSubtreeLocked(profileLocalId: String, origin: String) {
        val profileDir = File(cacheRootDir, sha256Hex(profileLocalId))
        val originDir = File(profileDir, sha256Hex("$profileLocalId:${sha256Hex(origin)}"))
        if (!originDir.exists() && !profileDir.exists()) return

        // PR-3 REQ-08 (re-anchored by symbol): conversation content lives under
        // the origin subtree, so the recursive delete above IS the content step.
        // Index and catalog reconcile next — content → index → catalog — before
        // the directory sync below.
        val hadConversationContent =
            originDir.walkTopDown().any { it.isFile && isConversationContentFileName(it.name) }
        originDir.deleteRecursively()
        if (hadConversationContent) {
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        }
        purgeConversationIndexAndCatalogLocked(
            profileLocalId = profileLocalId,
            originSha256 = sha256Hex(origin)
        )
        if (profileDir.exists()) directorySync(profileDir)
    }

    /** REQ-06/07: rotate then purge the OLD origin's subtree when the origin changes. */
    private fun purgeChangedOriginLocked(profile: ConnectionProfile) {
        val existing = readConnectionProfileInternal(profile.localId) ?: return
        if (existing.normalizedOrigin == profile.normalizedOrigin) return
        writeCacheAccessStateInternal(
            profile.localId,
            CacheEpochRotationReason.ORIGIN_REPLACED,
            rotateOnlyIfStateExists = false
        )
        purgeOriginSubtreeLocked(profile.localId, existing.normalizedOrigin)
        revokeLocalLogin(profile.localId, existing.normalizedOrigin)
    }

    /**
     * REQ-05/SEC-01: discard the legacy (pre-PR-1) single-slot summary record.
     * The record is NEVER parsed, read, or migrated: move it into quarantine,
     * persist the disposition, delete it, and repeat — on failure the residue
     * is retried on the next initialization (init and ensureMigrated).
     */
    private fun runLegacySummaryDiscard() {
        try {
            val stateAad = createAad("CACHE_MIGRATION_STATE", 1, "cache-migration-state")
            val stateJson = readEncryptedSingleton(migrationStateFile, stateAad)
            val stateObj = stateJson?.let { GatewayJson.parseOrNull(it)?.asObjectOrNull() }
            // PR-1 P3-2 (review): no-downgrade fail-closed — a record written
            // by a NEWER schema is never parsed into this build (nor
            // overwritten, which would destroy data that schema still owns);
            // its legacy-cache residue is still swept.
            if (stateObj != null && (stateObj["schemaVersion"]?.asIntOrNull() ?: 1) > 1) {
                if (cacheFile.exists() && cacheFile.renameTo(quarantineSummaryFile)) {
                    quarantineSummaryFile.delete()
                }
                cacheFile.delete()
                quarantineSummaryFile.delete()
                discardPending = false
                return
            }
            val disposition = stateObj?.get("disposition")?.asStringOrNull()
            if (disposition == LEGACY_SUMMARY_CACHE_DISPOSITION) {
                // Already discarded once: sweep any residue that reappeared —
                // still never parsed.
                if (cacheFile.exists() && cacheFile.renameTo(quarantineSummaryFile)) {
                    quarantineSummaryFile.delete()
                }
                cacheFile.delete()
                quarantineSummaryFile.delete()
                discardPending = false
                return
            }
            if (cacheFile.exists()) {
                quarantineSummaryFile.delete()
                if (!cacheFile.renameTo(quarantineSummaryFile)) {
                    discardPending = true
                    return
                }
                // PR-1 P3-1 (review): CacheMigrationState is a disposition
                // record — no timestamp fields (plan forbids them here).
                val map = linkedMapOf<String, Any?>(
                    "disposition" to LEGACY_SUMMARY_CACHE_DISPOSITION,
                    "schemaVersion" to 1
                )
                writeEncryptedSingleton(
                    migrationStateFile,
                    GatewayJson.serialize(GatewayJson.fromAny(map)),
                    stateAad
                )
                quarantineSummaryFile.delete()
                cacheFile.delete()
            } else {
                quarantineSummaryFile.delete()
            }
            discardPending = false
        } catch (_: Exception) {
            // Fail-safe: residue and disposition are retried on the next pass.
            discardPending = true
        }
    }

    private fun parseSessionSummaryList(jsonValue: io.github.anshuman2401.hermesmobile.data.gateway.json.JsonValue?): List<SessionSummary> {
        val array = jsonValue?.asArrayOrNull() ?: return emptyList()
        val list = mutableListOf<SessionSummary>()
        for (item in array.list) {
            val o = item.asObjectOrNull() ?: continue
            val id = o["id"]?.asStringOrNull() ?: continue
            list.add(
                SessionSummary(
                    id = id,
                    title = o["title"]?.asStringOrNull() ?: "",
                    preview = o["preview"]?.asStringOrNull() ?: "",
                    startedAt = o["started_at"]?.asDoubleOrNull() ?: 0.0,
                    messageCount = o["message_count"]?.asIntOrNull() ?: 0,
                    source = o["source"]?.asStringOrNull() ?: "android",
                    lastActive = o["last_active"]?.asDoubleOrNull()?.takeIf { it.isFinite() }
                )
            )
        }
        return list
    }

    // --- Drafts (30 days retention, collision-resistant & identity-verified) ---

    override suspend fun getDraft(storedSessionId: String): Draft? = mutex.withLock {
        withContext(ioDispatcher) {
            val jsonStr = readEncryptedIdentifiedRecord(
                dir = draftsDir,
                filePrefix = "draft",
                expectedRecordId = storedSessionId,
                recordType = "DRAFT",
                schemaVersion = Draft.CURRENT_SCHEMA_VERSION
            ) ?: return@withContext null

            val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return@withContext null
            val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
            if (schemaVer > Draft.CURRENT_SCHEMA_VERSION) return@withContext null

            // Decrypted identity check
            val parsedSessionId = obj["storedSessionId"]?.asStringOrNull() ?: return@withContext null
            if (parsedSessionId != storedSessionId) {
                // Identity mismatch: fail closed and delete
                File(draftsDir, "draft_${sha256Hex(storedSessionId)}.enc").delete()
                return@withContext null
            }

            val draft = Draft(
                storedSessionId = parsedSessionId,
                composerText = obj["composerText"]?.asStringOrNull() ?: "",
                profileLocalId = obj["profileLocalId"]?.asStringOrNull(),
                editTimeEpochSeconds = obj["editTimeEpochSeconds"]?.asLongOrNull() ?: (System.currentTimeMillis() / 1000),
                schemaVersion = schemaVer
            )

            if (draft.isExpired()) {
                File(draftsDir, "draft_${sha256Hex(storedSessionId)}.enc").delete()
                return@withContext null
            }
            draft
        }
    }

    override suspend fun saveDraft(draft: Draft): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            val activeId = draft.profileLocalId ?: readProfileIndexInternal()?.activeProfileId
            val map = linkedMapOf<String, Any?>(
                "storedSessionId" to draft.storedSessionId,
                "composerText" to draft.composerText,
                "profileLocalId" to activeId,
                "editTimeEpochSeconds" to draft.editTimeEpochSeconds,
                "schemaVersion" to draft.schemaVersion
            )
            val aad = createAad("DRAFT", draft.schemaVersion, draft.storedSessionId)
            writeEncryptedIdentifiedRecord(
                dir = draftsDir,
                filePrefix = "draft",
                recordId = draft.storedSessionId,
                jsonString = GatewayJson.serialize(GatewayJson.fromAny(map)),
                aad = aad
            )
        }
    }

    override suspend fun deleteDraft(storedSessionId: String): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            File(draftsDir, "draft_${sha256Hex(storedSessionId)}.enc").delete()
        }
    }

    override suspend fun getAllDrafts(): List<Draft> = mutex.withLock {
        withContext(ioDispatcher) {
            val files = draftsDir.listFiles { _, name -> name.startsWith("draft_") && name.endsWith(".enc") } ?: emptyArray()
            val list = mutableListOf<Draft>()
            for (file in files) {
                val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                if (bytes.size < 4) {
                    file.delete()
                    continue
                }
                val idLen = ByteBuffer.wrap(bytes, 0, 4).int
                if (idLen <= 0 || bytes.size < 4 + idLen) {
                    file.delete()
                    continue
                }
                val recordId = String(bytes, 4, idLen, Charsets.UTF_8)
                val ciphertext = bytes.copyOfRange(4 + idLen, bytes.size)
                val aad = createAad("DRAFT", Draft.CURRENT_SCHEMA_VERSION, recordId)
                val decryptResult = cipherEngine.decrypt(ciphertext, aad)
                if (decryptResult.isFailure) {
                    file.delete()
                    continue
                }
                val jsonStr = String(decryptResult.getOrThrow(), Charsets.UTF_8)
                val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: continue
                val parsedSessionId = obj["storedSessionId"]?.asStringOrNull() ?: continue
                if (parsedSessionId != recordId) {
                    file.delete()
                    continue
                }
                val draft = Draft(
                    storedSessionId = parsedSessionId,
                    composerText = obj["composerText"]?.asStringOrNull() ?: "",
                    profileLocalId = obj["profileLocalId"]?.asStringOrNull(),
                    editTimeEpochSeconds = obj["editTimeEpochSeconds"]?.asLongOrNull() ?: 0L,
                    schemaVersion = obj["schemaVersion"]?.asIntOrNull() ?: 1
                )
                if (draft.isExpired()) {
                    file.delete()
                } else {
                    list.add(draft)
                }
            }
            list
        }
    }

    override suspend fun clearDrafts(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            draftsDir.listFiles()?.forEach { it.delete() }
        }
    }

    // --- Pending Turn Ledger (collision-resistant & identity-verified) ---

    override suspend fun getPendingTurn(pendingTurnId: String): PendingTurnRecord? = mutex.withLock {
        withContext(ioDispatcher) {
            val jsonStr = readEncryptedIdentifiedRecord(
                dir = pendingDir,
                filePrefix = "pending",
                expectedRecordId = pendingTurnId,
                recordType = "PENDING_TURN",
                schemaVersion = PendingTurnRecord.CURRENT_SCHEMA_VERSION
            ) ?: return@withContext null

            parsePendingTurn(jsonStr, pendingTurnId)
        }
    }

    override suspend fun getAllPendingTurns(): List<PendingTurnRecord> = mutex.withLock {
        withContext(ioDispatcher) {
            val files = pendingDir.listFiles { _, name -> name.startsWith("pending_") && name.endsWith(".enc") } ?: emptyArray()
            val list = mutableListOf<PendingTurnRecord>()
            for (file in files) {
                val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                if (bytes.size < 4) {
                    file.delete()
                    continue
                }
                val idLen = ByteBuffer.wrap(bytes, 0, 4).int
                if (idLen <= 0 || bytes.size < 4 + idLen) {
                    file.delete()
                    continue
                }
                val recordId = String(bytes, 4, idLen, Charsets.UTF_8)
                val ciphertext = bytes.copyOfRange(4 + idLen, bytes.size)
                val aad = createAad("PENDING_TURN", PendingTurnRecord.CURRENT_SCHEMA_VERSION, recordId)
                val decryptResult = cipherEngine.decrypt(ciphertext, aad)
                if (decryptResult.isFailure) {
                    file.delete()
                    continue
                }
                val jsonStr = String(decryptResult.getOrThrow(), Charsets.UTF_8)
                parsePendingTurn(jsonStr, recordId)?.let { list.add(it) }
            }
            list
        }
    }

    private fun parsePendingTurn(jsonStr: String, expectedTurnId: String): PendingTurnRecord? {
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > PendingTurnRecord.CURRENT_SCHEMA_VERSION) return null

        val parsedTurnId = obj["pendingTurnId"]?.asStringOrNull() ?: return null
        if (parsedTurnId != expectedTurnId) {
            // Identity mismatch: fail closed
            File(pendingDir, "pending_${sha256Hex(expectedTurnId)}.enc").delete()
            return null
        }

        val stateName = obj["state"]?.asStringOrNull() ?: PendingTurnState.PREPARED.name
        val state = try {
            PendingTurnState.valueOf(stateName)
        } catch (_: Exception) {
            PendingTurnState.UNKNOWN
        }

        if (obj["operation"] != null && obj["operation"] !is io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString) return null
        val edit = when (obj["operation"]?.asStringOrNull()) {
            null, "prompt" -> { if (obj["editRowId"] != null || obj["editOwner"] != null) return null; null }
            "edit" -> try { PendingEditMetadata(obj["editRowId"]?.asLongOrNull() ?: return null,
                obj["editOwner"]?.asStringOrNull() ?: return null) } catch (_: IllegalArgumentException) { return null }
            else -> return null
        }
        return PendingTurnRecord(
            pendingTurnId = parsedTurnId,
            storedSessionId = obj["storedSessionId"]?.asStringOrNull() ?: return null,
            runtimeSessionId = obj["runtimeSessionId"]?.asStringOrNull(),
            promptContent = obj["promptContent"]?.asStringOrNull() ?: "",
            state = state,
            createdAt = obj["createdAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            updatedAt = obj["updatedAt"]?.asLongOrNull() ?: System.currentTimeMillis(),
            submissionEvidenceFlag = obj["submissionEvidenceFlag"]?.asBooleanOrNull() ?: false,
            edit = edit,
            schemaVersion = schemaVer
        )
    }

    override suspend fun savePendingTurn(record: PendingTurnRecord): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            val map = linkedMapOf<String, Any?>(
                "pendingTurnId" to record.pendingTurnId,
                "storedSessionId" to record.storedSessionId,
                "runtimeSessionId" to record.runtimeSessionId,
                "promptContent" to record.promptContent,
                "state" to record.state.name,
                "createdAt" to record.createdAt,
                "updatedAt" to record.updatedAt,
                "submissionEvidenceFlag" to record.submissionEvidenceFlag,
                "schemaVersion" to record.schemaVersion
            )
            if (record.edit != null) {
                map["operation"] = "edit"
                map["editRowId"] = record.edit.rowId
                map["editOwner"] = record.edit.ownerKey
            }
            val aad = createAad("PENDING_TURN", record.schemaVersion, record.pendingTurnId)
            writeEncryptedIdentifiedRecord(
                dir = pendingDir,
                filePrefix = "pending",
                recordId = record.pendingTurnId,
                jsonString = GatewayJson.serialize(GatewayJson.fromAny(map)),
                aad = aad
            )
        }
    }

    override suspend fun updatePendingTurnState(pendingTurnId: String, newState: PendingTurnState): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            val current = getPendingTurnInternal(pendingTurnId) ?: return@withContext
            val updated = current.copy(
                state = newState,
                updatedAt = System.currentTimeMillis(),
                submissionEvidenceFlag = if (newState == PendingTurnState.CONFIRMED_ADMITTED) true else current.submissionEvidenceFlag
            )
            val map = linkedMapOf<String, Any?>(
                "pendingTurnId" to updated.pendingTurnId,
                "storedSessionId" to updated.storedSessionId,
                "runtimeSessionId" to updated.runtimeSessionId,
                "promptContent" to updated.promptContent,
                "state" to updated.state.name,
                "createdAt" to updated.createdAt,
                "updatedAt" to updated.updatedAt,
                "submissionEvidenceFlag" to updated.submissionEvidenceFlag,
                "schemaVersion" to updated.schemaVersion
            )
            if (updated.edit != null) {
                map["operation"] = "edit"
                map["editRowId"] = updated.edit.rowId
                map["editOwner"] = updated.edit.ownerKey
            }
            val aad = createAad("PENDING_TURN", updated.schemaVersion, updated.pendingTurnId)
            writeEncryptedIdentifiedRecord(
                dir = pendingDir,
                filePrefix = "pending",
                recordId = updated.pendingTurnId,
                jsonString = GatewayJson.serialize(GatewayJson.fromAny(map)),
                aad = aad
            )
        }
    }

    private fun getPendingTurnInternal(pendingTurnId: String): PendingTurnRecord? {
        val jsonStr = readEncryptedIdentifiedRecord(
            dir = pendingDir,
            filePrefix = "pending",
            expectedRecordId = pendingTurnId,
            recordType = "PENDING_TURN",
            schemaVersion = PendingTurnRecord.CURRENT_SCHEMA_VERSION
        ) ?: return null
        return parsePendingTurn(jsonStr, pendingTurnId)
    }

    override suspend fun deletePendingTurn(pendingTurnId: String): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            File(pendingDir, "pending_${sha256Hex(pendingTurnId)}.enc").delete()
        }
    }

    override suspend fun clearPendingTurns(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            pendingDir.listFiles()?.forEach { it.delete() }
        }
    }

    // --- Pending Interrupt Holds (T3 PR-1) ---
    //
    // Headerless per-session files using the encrypted-singleton encode/decode
    // pattern (same helpers as the session summary cache): unlike the
    // identified-record format, nothing outside the AEAD ciphertext names the
    // session (FD-001 "session ids encrypted at rest" / SEC-001). The filename
    // carries only a SHA-256 hash; after decrypt the payload id must hash back
    // to the filename, and the AAD binds record type + schema + filename, so
    // cross-type swaps and renames fail closed.

    private fun pendingInterruptFile(storedSessionId: String): File =
        File(pendingInterruptsDir, "pinterrupt_${sha256Hex(storedSessionId)}.enc")

    override suspend fun savePendingInterrupt(record: PendingInterruptRecord): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            val map = linkedMapOf<String, Any?>(
                "storedSessionId" to record.storedSessionId,
                "runtimeSessionId" to record.runtimeSessionId,
                "enqueuedAtMs" to record.enqueuedAtMs,
                "schemaVersion" to record.schemaVersion
            )
            val aad = createAad("PENDING_INTERRUPT", record.schemaVersion, pendingInterruptFile(record.storedSessionId).name)
            writeEncryptedSingleton(
                pendingInterruptFile(record.storedSessionId),
                GatewayJson.serialize(GatewayJson.fromAny(map)),
                aad
            )
        }
    }

    override suspend fun getPendingInterrupts(): List<PendingInterruptRecord> = mutex.withLock {
        withContext(ioDispatcher) {
            val files = pendingInterruptsDir.listFiles { _, name ->
                name.startsWith("pinterrupt_") && name.endsWith(".enc")
            } ?: emptyArray()
            val list = mutableListOf<PendingInterruptRecord>()
            for (file in files) {
                val aad = createAad("PENDING_INTERRUPT", PendingInterruptRecord.CURRENT_SCHEMA_VERSION, file.name)
                // Decrypt failure quarantines the file inside the helper (fail closed, no fallback).
                val jsonStr = readEncryptedSingleton(file, aad) ?: continue
                val record = parsePendingInterrupt(jsonStr) ?: continue
                if (pendingInterruptFile(record.storedSessionId).name != file.name) {
                    // Identity mismatch: the decrypted id must hash to the filename.
                    file.delete()
                    continue
                }
                list.add(record)
            }
            list
        }
    }

    private fun parsePendingInterrupt(jsonStr: String): PendingInterruptRecord? {
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > PendingInterruptRecord.CURRENT_SCHEMA_VERSION) return null
        val storedSessionId = obj["storedSessionId"]?.asStringOrNull() ?: return null
        return PendingInterruptRecord(
            storedSessionId = storedSessionId,
            runtimeSessionId = obj["runtimeSessionId"]?.asStringOrNull(),
            // Missing timestamp decodes as "already lapsed": a hold is never
            // granted a fresh TTL by a decode default (FD-003 fail-safe).
            enqueuedAtMs = obj["enqueuedAtMs"]?.asLongOrNull() ?: 0L,
            schemaVersion = schemaVer
        )
    }

    override suspend fun deletePendingInterrupt(storedSessionId: String): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            pendingInterruptFile(storedSessionId).delete()
        }
    }

    override suspend fun clearPendingInterrupts(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            clearPendingInterruptsInternal()
        }
    }

    private fun clearPendingInterruptsInternal() {
        pendingInterruptsDir.listFiles()?.forEach { it.delete() }
    }

    // --- Diagnostics Ring Buffer (100 items max) ---

    override suspend fun recordDiagnostic(diagnostic: LocalDiagnostic): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            recordDiagnosticInternal(diagnostic)
        }
    }

    private fun recordDiagnosticInternal(diagnostic: LocalDiagnostic) {
        val current = getDiagnosticsInternal().toMutableList()
        if (current.size >= 100) {
            current.removeAt(0)
        }
        current.add(diagnostic)

        val list = current.map { d ->
            mapOf(
                "errorCategory" to d.errorCategory,
                "code" to d.code,
                "state" to d.state,
                "timestamp" to d.timestamp,
                "appVersion" to d.appVersion,
                "hostVersion" to d.hostVersion
            )
        }
        val aad = createAad("LOCAL_DIAGNOSTIC", 1, "singleton_diagnostics")
        writeEncryptedSingleton(diagnosticsFile, GatewayJson.serialize(GatewayJson.fromAny(list)), aad)
    }

    override suspend fun getDiagnostics(): List<LocalDiagnostic> = mutex.withLock {
        withContext(ioDispatcher) {
            getDiagnosticsInternal()
        }
    }

    private fun getDiagnosticsInternal(): List<LocalDiagnostic> {
        val aad = createAad("LOCAL_DIAGNOSTIC", 1, "singleton_diagnostics")
        val jsonStr = readEncryptedSingleton(diagnosticsFile, aad) ?: return emptyList()
        val array = GatewayJson.parseOrNull(jsonStr)?.asArrayOrNull() ?: return emptyList()
        val result = mutableListOf<LocalDiagnostic>()
        for (item in array.list) {
            val o = item.asObjectOrNull() ?: continue
            result.add(
                LocalDiagnostic(
                    errorCategory = o["errorCategory"]?.asStringOrNull() ?: "",
                    code = o["code"]?.asStringOrNull() ?: "",
                    state = o["state"]?.asStringOrNull() ?: "",
                    timestamp = o["timestamp"]?.asLongOrNull() ?: 0L,
                    appVersion = o["appVersion"]?.asStringOrNull() ?: "0.1.0",
                    hostVersion = o["hostVersion"]?.asStringOrNull()
                )
            )
        }
        return result
    }

    override suspend fun clearDiagnostics(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            diagnosticsFile.delete()
        }
    }

    // =========================================================================
    // PR-3: offline conversation snapshot cache (plan §5, REQ-01..REQ-10).
    // Per-partition index = SOLE existence authority; global catalog = opaque
    // maintenance metadata (never authorizing); write order content → index →
    // caps/evict → catalog (catalog last); delete order content → index →
    // catalog; §5.3 recovery validates, re-links, sweeps, rebuilds, enforces.
    // =========================================================================

    // --- Exact ID / AAD / path derivations (plan §5.2 — literals, not rebuilds) ---

    private fun conversationRecordIdFor(partition: CacheAccessPartition, durableSessionId: String): String =
        ConversationCacheV2.recordIdFor(
            partition.profileLocalId, partition.originSha256,
            partition.cacheEpoch, partition.workspaceScopeTag, durableSessionId
        )

    private fun conversationContextFor(partition: CacheAccessPartition, durableSessionId: String): String =
        "${partition.profileLocalId}:${partition.originSha256}:${partition.cacheEpoch}:${partition.workspaceScopeTag}:$durableSessionId"

    private fun conversationAadFor(partition: CacheAccessPartition, durableSessionId: String): ByteArray =
        createAad(
            "CONVERSATION",
            ConversationCacheV2.CURRENT_SCHEMA_VERSION,
            conversationRecordIdFor(partition, durableSessionId),
            conversationContextFor(partition, durableSessionId)
        )

    private fun conversationContentFileFor(partition: CacheAccessPartition, durableSessionId: String): File =
        File(
            summaryOriginDirFor(partition.profileLocalId, partition.originSha256),
            "conversation_${sha256Hex(conversationRecordIdFor(partition, durableSessionId))}.enc"
        )

    private fun conversationIndexRecordIdFor(partition: CacheAccessPartition): String =
        ConversationPartitionIndexV2.recordIdFor(
            partition.profileLocalId, partition.originSha256,
            partition.cacheEpoch, partition.workspaceScopeTag
        )

    private fun conversationIndexContextFor(partition: CacheAccessPartition): String =
        partitionContextFor(partition)

    private fun conversationIndexAadFor(partition: CacheAccessPartition): ByteArray =
        createAad(
            "CONVERSATION_INDEX",
            ConversationPartitionIndexV2.CURRENT_SCHEMA_VERSION,
            conversationIndexRecordIdFor(partition),
            conversationIndexContextFor(partition)
        )

    private fun conversationIndexFileFor(partition: CacheAccessPartition): File =
        File(conversationIndexesDir, "index_${sha256Hex(conversationIndexRecordIdFor(partition))}.enc")

    private fun quarantineTwinOf(file: File): File = File(file.parentFile, "${file.name}.quarantine")

    /** Conversation content filenames under cache/ (live record + quarantine residue). */
    private fun isConversationContentFileName(name: String): Boolean =
        name.startsWith("conversation_") && (name.endsWith(".enc") || name.endsWith(".enc.quarantine"))

    /** Deterministic index enumeration (§5.3 (a): sorted, never raw dir order). */
    private fun conversationIndexFiles(): List<File> =
        conversationIndexesDir.listFiles { f ->
            f.isFile && f.name.startsWith("index_") && f.name.endsWith(".enc")
        }?.sortedBy { it.name } ?: emptyList()

    /** Workspace tag ⇔ selected workspace name (plan §5.2 "u" / "w:<sha256(name)>"). */
    private fun workspaceTagFor(selectedWorkspaceName: String?): String =
        if (selectedWorkspaceName == null) "u" else "w:" + sha256Hex(selectedWorkspaceName)

    // --- Typed envelope read (header ⇔ filename ⇔ recordId, then AEAD) ---

    /**
     * Read only an identified record's envelope header. Returns null when the
     * envelope is malformed OR the filename doesn't hash to it (§5.3 (c)
     * `filename ⇔ sha256(recordId)`), which callers map to delete+Corrupt.
     */
    private fun envelopeHeaderRecordId(file: File, filePrefix: String): String? {
        if (!file.exists() || file.length() < 4L) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (bytes.size < 4) return null
        val idLen = ByteBuffer.wrap(bytes, 0, 4).int
        if (idLen <= 0 || bytes.size < 4 + idLen) return null
        val headerRecordId = String(bytes, 4, idLen, Charsets.UTF_8)
        if (file.name != "${filePrefix}_${sha256Hex(headerRecordId)}.enc") return null
        return headerRecordId
    }

    /**
     * PR-3 typed envelope reader: Missing / Invalid (malformed, filename-hash
     * mismatch, header ⇔ expected mismatch) / AeadFailure / Decrypted — REQ-02's
     * read contract needs those outcomes DISTINGUISHED, which the PR-1
     * null-collapsing helper cannot express; PR-1 call sites stay untouched.
     */
    private fun readIdentifiedEnvelope(
        file: File,
        filePrefix: String,
        expectedRecordId: String,
        aad: ByteArray
    ): IdentifiedEnvelopeRead {
        if (!file.exists() || file.length() == 0L) return IdentifiedEnvelopeRead.Missing
        val headerRecordId = envelopeHeaderRecordId(file, filePrefix) ?: return IdentifiedEnvelopeRead.Invalid
        if (headerRecordId != expectedRecordId) return IdentifiedEnvelopeRead.Invalid
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return IdentifiedEnvelopeRead.Invalid
        }
        if (bytes.size < 4) return IdentifiedEnvelopeRead.Invalid
        val idLen = ByteBuffer.wrap(bytes, 0, 4).int
        if (idLen <= 0 || bytes.size < 4 + idLen) return IdentifiedEnvelopeRead.Invalid
        val plaintext = cipherEngine.decrypt(bytes.copyOfRange(4 + idLen, bytes.size), aad)
            .getOrNull() ?: return IdentifiedEnvelopeRead.AeadFailure
        return IdentifiedEnvelopeRead.Decrypted(plaintext)
    }

    // --- Payload serializers (exact §5.2 field sets) ---

    private fun conversationPayloadJson(cache: ConversationCacheV2): String = GatewayJson.serialize(
        GatewayJson.fromAny(
            linkedMapOf<String, Any?>(
                "schemaVersion" to cache.schemaVersion,
                "recordId" to cache.recordId,
                "profileLocalId" to cache.profileLocalId,
                "originSha256" to cache.originSha256,
                "cacheEpoch" to cache.cacheEpoch,
                "workspaceScopeTag" to cache.workspaceScopeTag,
                "durableSessionId" to cache.durableSessionId,
                "selectedWorkspaceName" to cache.selectedWorkspaceName,
                "snapshot" to linkedMapOf<String, Any?>(
                    "snapshotId" to cache.snapshot.snapshotId,
                    "capturedAtMillis" to cache.snapshot.capturedAtMillis,
                    "messagesOmitted" to cache.snapshot.messagesOmitted,
                    "clientTruncated" to cache.snapshot.clientTruncated,
                    "rows" to cache.snapshot.rows.map { row ->
                        linkedMapOf<String, Any?>(
                            "ordinal" to row.ordinal,
                            "role" to row.role,
                            "content" to row.content,
                            "toolName" to row.toolName,
                            "toolContext" to row.toolContext,
                            "reasoning" to row.reasoning,
                            "createdAtMillis" to row.createdAtMillis
                        )
                    }
                ),
                "lastAccessedAtMillis" to cache.lastAccessedAtMillis,
                "createdAtMillis" to cache.createdAtMillis,
                "updatedAtMillis" to cache.updatedAtMillis,
                // PR-4 (REQ-04): the bounded local trace rides the record —
                // enums serialize as their closed-vocabulary names; the list
                // is always present (empty for pre-PR-4 records' writers).
                "localTrace" to cache.localTrace.map { trace ->
                    traceRowJsonMap(trace)
                }
            )
        )
    )

    private fun conversationIndexPayloadJson(index: ConversationPartitionIndexV2): String =
        GatewayJson.serialize(
            GatewayJson.fromAny(
                linkedMapOf<String, Any?>(
                    "schemaVersion" to index.schemaVersion,
                    "recordId" to index.recordId,
                    "profileLocalId" to index.profileLocalId,
                    "originSha256" to index.originSha256,
                    "cacheEpoch" to index.cacheEpoch,
                    "workspaceScopeTag" to index.workspaceScopeTag,
                    "revision" to index.revision,
                    "createdAtMillis" to index.createdAtMillis,
                    "updatedAtMillis" to index.updatedAtMillis,
                    "entries" to index.entries.map { entry ->
                        linkedMapOf<String, Any?>(
                            "recordIdHash" to entry.recordIdHash,
                            "bytesEstimate" to entry.bytesEstimate,
                            "expiresAtMillis" to entry.expiresAtMillis,
                            "lastAccessedAtMillis" to entry.lastAccessedAtMillis
                        )
                    }
                )
            )
        )

    private fun globalCatalogPayloadJson(catalog: GlobalConversationCatalogV1): String =
        GatewayJson.serialize(
            GatewayJson.fromAny(
                linkedMapOf<String, Any?>(
                    "schemaVersion" to catalog.schemaVersion,
                    "recordId" to catalog.recordId,
                    "createdAtMillis" to catalog.createdAtMillis,
                    "updatedAtMillis" to catalog.updatedAtMillis,
                    "entries" to catalog.entries.map { entry ->
                        linkedMapOf<String, Any?>(
                            "indexLocatorSha256" to entry.indexLocatorSha256,
                            "observedIndexRevision" to entry.observedIndexRevision,
                            "bytesEstimate" to entry.bytesEstimate,
                            "oldestLastAccessedAtMillis" to entry.oldestLastAccessedAtMillis,
                            "newestExpiryAtMillis" to entry.newestExpiryAtMillis
                        )
                    }
                )
            )
        )

    // --- Strict payload parse (read steps 5–9) ---

    private fun parseConversationPayload(
        plaintext: ByteArray,
        expectedRecordId: String,
        partition: CacheAccessPartition,
        callerSid: String
    ): ConversationPayloadParse {
        val obj = GatewayJson.parseOrNull(String(plaintext, Charsets.UTF_8))?.asObjectOrNull()
            ?: return ConversationPayloadParse.Corrupt
        val schema = obj["schemaVersion"]?.asIntOrNull() ?: return ConversationPayloadParse.Corrupt
        // (5) future schema ⇒ quarantine+Unavailable; absent/older ⇒ delete+Corrupt.
        if (schema > ConversationCacheV2.CURRENT_SCHEMA_VERSION) return ConversationPayloadParse.SchemaFuture
        if (schema < ConversationCacheV2.CURRENT_SCHEMA_VERSION) return ConversationPayloadParse.Corrupt
        // (5 continued) required fields — missing ⇒ delete+Corrupt (no fallbacks).
        val payloadRecordId = obj["recordId"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val profileLocalId = obj["profileLocalId"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val originSha256 = obj["originSha256"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val cacheEpoch = obj["cacheEpoch"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val workspaceScopeTag = obj["workspaceScopeTag"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val durableSessionId = obj["durableSessionId"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        // selectedWorkspaceName: key must be PRESENT (null value allowed — §5.2).
        if (!obj.map.containsKey("selectedWorkspaceName")) return ConversationPayloadParse.Corrupt
        val selectedWorkspaceName: String? = obj["selectedWorkspaceName"]?.asStringOrNull()
        // §5.2 nested capture window — all fields required inside `snapshot`.
        val snapshotObj = obj["snapshot"]?.asObjectOrNull() ?: return ConversationPayloadParse.Corrupt
        val snapshotId = snapshotObj["snapshotId"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
        val capturedAtMillis = snapshotObj["capturedAtMillis"]?.asLongOrNull() ?: return ConversationPayloadParse.Corrupt
        val messagesOmitted = snapshotObj["messagesOmitted"]?.asBooleanOrNull() ?: return ConversationPayloadParse.Corrupt
        val clientTruncated = snapshotObj["clientTruncated"]?.asBooleanOrNull() ?: return ConversationPayloadParse.Corrupt
        val lastAccessedAtMillis = obj["lastAccessedAtMillis"]?.asLongOrNull() ?: return ConversationPayloadParse.Corrupt
        val createdAtMillis = obj["createdAtMillis"]?.asLongOrNull() ?: return ConversationPayloadParse.Corrupt
        val updatedAtMillis = obj["updatedAtMillis"]?.asLongOrNull() ?: return ConversationPayloadParse.Corrupt
        if (capturedAtMillis < 0L || lastAccessedAtMillis < 0L || createdAtMillis < 0L || updatedAtMillis < 0L) {
            return ConversationPayloadParse.Corrupt
        }
        val rowsArray = snapshotObj["rows"]?.asArrayOrNull() ?: return ConversationPayloadParse.Corrupt
        val rows = ArrayList<CachedRemoteHistoryRow>(rowsArray.list.size)
        for (rowValue in rowsArray.list) {
            val rowObj = rowValue.asObjectOrNull() ?: return ConversationPayloadParse.Corrupt
            val ordinal = rowObj["ordinal"]?.asIntOrNull() ?: return ConversationPayloadParse.Corrupt
            val role = rowObj["role"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
            val content = rowObj["content"]?.asStringOrNull() ?: return ConversationPayloadParse.Corrupt
            rows.add(
                CachedRemoteHistoryRow(
                    ordinal = ordinal,
                    role = role,
                    content = content,
                    toolName = rowObj["toolName"]?.asStringOrNull(),
                    toolContext = rowObj["toolContext"]?.asStringOrNull(),
                    reasoning = rowObj["reasoning"]?.asStringOrNull(),
                    createdAtMillis = rowObj["createdAtMillis"]?.asLongOrNull()
                )
            )
        }
        // PR-4 (REQ-04): `localTrace` is OPTIONAL within schema 2 (PR-4
        // appended it without a version bump) — absent ⇒ emptyList (records
        // written before PR-4); present-but-unparsable ⇒ strict Corrupt.
        val localTrace: List<CachedLocalToolTraceEvent> = when (val traceValue = obj.map["localTrace"]) {
            null -> emptyList()
            else -> {
                val traceArray = traceValue.asArrayOrNull() ?: return ConversationPayloadParse.Corrupt
                val events = ArrayList<CachedLocalToolTraceEvent>(traceArray.list.size)
                for (eventValue in traceArray.list) {
                    val eventObj = eventValue.asObjectOrNull() ?: return ConversationPayloadParse.Corrupt
                    val localEventId = eventObj["localEventId"]?.asStringOrNull()
                        ?: return ConversationPayloadParse.Corrupt
                    val sourceTypeName = eventObj["sourceType"]?.asStringOrNull()
                        ?: return ConversationPayloadParse.Corrupt
                    val sourceType = ToolTraceSourceType.values().firstOrNull { it.name == sourceTypeName }
                        ?: return ConversationPayloadParse.Corrupt
                    val statusName = eventObj["status"]?.asStringOrNull()
                        ?: return ConversationPayloadParse.Corrupt
                    val status = ToolStatus.values().firstOrNull { it.name == statusName }
                        ?: return ConversationPayloadParse.Corrupt
                    val name = eventObj["name"]?.asStringOrNull()
                        ?: return ConversationPayloadParse.Corrupt
                    val observedAtMillis = eventObj["observedAtMillis"]?.asLongOrNull()
                        ?: return ConversationPayloadParse.Corrupt
                    if (observedAtMillis < 0L) return ConversationPayloadParse.Corrupt
                    events.add(
                        CachedLocalToolTraceEvent(
                            localEventId = localEventId,
                            sourceSeq = eventObj["sourceSeq"]?.asLongOrNull(),
                            sourceType = sourceType,
                            toolId = eventObj["toolId"]?.asStringOrNull(),
                            name = name,
                            context = eventObj["context"]?.asStringOrNull(),
                            status = status,
                            durationSeconds = eventObj["durationSeconds"]?.asDoubleOrNull(),
                            observedAtMillis = observedAtMillis
                        )
                    )
                }
                events
            }
        }
        // (8) payload identity ⇔ expected record ID + caller partition + caller sid.
        if (payloadRecordId != expectedRecordId) return ConversationPayloadParse.IdentityMismatch
        if (profileLocalId != partition.profileLocalId ||
            !originSha256.equals(partition.originSha256, ignoreCase = true) ||
            cacheEpoch != partition.cacheEpoch ||
            workspaceScopeTag != partition.workspaceScopeTag ||
            durableSessionId != callerSid
        ) {
            return ConversationPayloadParse.IdentityMismatch
        }
        // (9) workspace-tag consistency: tag must match the payload's own selection.
        if (workspaceTagFor(selectedWorkspaceName) != partition.workspaceScopeTag) {
            return ConversationPayloadParse.IdentityMismatch
        }
        return ConversationPayloadParse.Parsed(
            ConversationCacheV2(
                schemaVersion = schema,
                recordId = payloadRecordId,
                profileLocalId = profileLocalId,
                originSha256 = originSha256,
                cacheEpoch = cacheEpoch,
                workspaceScopeTag = workspaceScopeTag,
                durableSessionId = durableSessionId,
                selectedWorkspaceName = selectedWorkspaceName,
                snapshot = CachedRemoteHistorySnapshot(
                    snapshotId = snapshotId,
                    capturedAtMillis = capturedAtMillis,
                    messagesOmitted = messagesOmitted,
                    clientTruncated = clientTruncated,
                    rows = rows
                ),
                lastAccessedAtMillis = lastAccessedAtMillis,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                localTrace = localTrace
            )
        )
    }

    // --- Index read/write (SOLE existence authority) ---

    private fun readConversationIndexInternal(partition: CacheAccessPartition): ConversationIndexRead {
        val file = conversationIndexFileFor(partition)
        if (quarantineTwinOf(file).exists()) return ConversationIndexRead.Corrupt
        if (!file.exists()) return ConversationIndexRead.Missing
        val index = parseConversationIndexFile(file, conversationIndexRecordIdFor(partition))
        if (index == null) {
            file.delete()
            return ConversationIndexRead.Corrupt
        }
        return ConversationIndexRead.Valid(index)
    }

    /**
     * Full index validation: envelope header ⇔ filename ⇔ expected record ID,
     * exact `CONVERSATION_INDEX:2:…` AAD, schema gate, payload identity ⇔
     * derived template, strict entry parse (64-hex hashes, no duplicates).
     * null ⇒ invalid (caller deletes / quarantines per §5.3 (b)).
     */
    private fun parseConversationIndexFile(file: File, expectedRecordId: String): ConversationPartitionIndexV2? {
        if (!expectedRecordId.startsWith("conversation-index:")) return null
        if (file.length() == 0L || file.length() > MAX_CONVERSATION_INDEX_CIPHERTEXT_BYTES) return null
        val context = expectedRecordId.removePrefix("conversation-index:")
        if (context.length == expectedRecordId.length) return null
        val aad = createAad(
            "CONVERSATION_INDEX",
            ConversationPartitionIndexV2.CURRENT_SCHEMA_VERSION,
            expectedRecordId,
            context
        )
        val plaintext = when (val envelope = readIdentifiedEnvelope(file, "index", expectedRecordId, aad)) {
            IdentifiedEnvelopeRead.Missing -> return null
            IdentifiedEnvelopeRead.Invalid -> return null
            IdentifiedEnvelopeRead.AeadFailure -> return null
            is IdentifiedEnvelopeRead.Decrypted -> envelope.plaintext
        }
        if (plaintext.size > MAX_CONVERSATION_INDEX_PAYLOAD_BYTES) return null
        val obj = GatewayJson.parseOrNull(String(plaintext, Charsets.UTF_8))?.asObjectOrNull() ?: return null
        val schema = obj["schemaVersion"]?.asIntOrNull() ?: return null
        if (schema != ConversationPartitionIndexV2.CURRENT_SCHEMA_VERSION) return null
        val payloadRecordId = obj["recordId"]?.asStringOrNull() ?: return null
        val profileLocalId = obj["profileLocalId"]?.asStringOrNull() ?: return null
        val originSha256 = obj["originSha256"]?.asStringOrNull() ?: return null
        val cacheEpoch = obj["cacheEpoch"]?.asStringOrNull() ?: return null
        val workspaceScopeTag = obj["workspaceScopeTag"]?.asStringOrNull() ?: return null
        val revision = obj["revision"]?.asLongOrNull() ?: return null
        val createdAtMillis = obj["createdAtMillis"]?.asLongOrNull() ?: return null
        val updatedAtMillis = obj["updatedAtMillis"]?.asLongOrNull() ?: return null
        if (revision < 0L || createdAtMillis < 0L || updatedAtMillis < 0L) return null
        if (payloadRecordId != expectedRecordId) return null
        if (ConversationPartitionIndexV2.recordIdFor(profileLocalId, originSha256, cacheEpoch, workspaceScopeTag) != payloadRecordId) {
            return null
        }
        val entriesArray = obj["entries"]?.asArrayOrNull() ?: return null
        val entries = ArrayList<ConversationIndexEntry>(entriesArray.list.size)
        for (entryValue in entriesArray.list) {
            val entryObj = entryValue.asObjectOrNull() ?: return null
            val recordIdHash = entryObj["recordIdHash"]?.asStringOrNull() ?: return null
            if (recordIdHash.length != 64 || !recordIdHash.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val bytesEstimate = entryObj["bytesEstimate"]?.asLongOrNull() ?: return null
            val expiresAtMillis = entryObj["expiresAtMillis"]?.asLongOrNull() ?: return null
            val lastAccessedAtMillis = entryObj["lastAccessedAtMillis"]?.asLongOrNull() ?: return null
            if (bytesEstimate < 0L || lastAccessedAtMillis < 0L) return null
            entries.add(
                ConversationIndexEntry(
                    recordIdHash = recordIdHash,
                    bytesEstimate = bytesEstimate,
                    expiresAtMillis = expiresAtMillis,
                    lastAccessedAtMillis = lastAccessedAtMillis
                )
            )
        }
        // Duplicate hashes ⇒ invalid (the index must identify each record once).
        if (entries.map { it.recordIdHash }.toSet().size != entries.size) return null
        return ConversationPartitionIndexV2(
            schemaVersion = schema,
            recordId = payloadRecordId,
            profileLocalId = profileLocalId,
            originSha256 = originSha256,
            cacheEpoch = cacheEpoch,
            workspaceScopeTag = workspaceScopeTag,
            revision = revision,
            entries = entries,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun writeConversationIndexInternal(index: ConversationPartitionIndexV2) {
        conversationIndexesDir.mkdirs()
        val partition = CacheAccessPartition(
            index.profileLocalId, index.originSha256, index.cacheEpoch, index.workspaceScopeTag
        )
        quarantineTwinOf(conversationIndexFileFor(partition)).delete()
        writeEncryptedIdentifiedRecord(
            dir = conversationIndexesDir,
            filePrefix = "index",
            recordId = index.recordId,
            jsonString = conversationIndexPayloadJson(index),
            aad = conversationIndexAadFor(partition)
        )
        directorySync(conversationIndexesDir)
    }

    // --- Global catalog (opaque maintenance metadata, exact fixed AAD) ---

    /** §5.3 rebuild scan — deterministic: validated indexes only, sorted by locator. */
    private fun conversationCatalogEntriesInternal(): List<GlobalCatalogEntry> =
        conversationIndexFiles().mapNotNull { file ->
            val headerRecordId = envelopeHeaderRecordId(file, "index") ?: return@mapNotNull null
            if (!headerRecordId.startsWith("conversation-index:")) return@mapNotNull null
            val index = parseConversationIndexFile(file, headerRecordId) ?: return@mapNotNull null
            if (index.entries.isEmpty()) return@mapNotNull null
            GlobalCatalogEntry(
                indexLocatorSha256 = sha256Hex(headerRecordId),
                observedIndexRevision = index.revision,
                bytesEstimate = index.entries.sumOf { it.bytesEstimate },
                oldestLastAccessedAtMillis = index.entries.minOf { it.lastAccessedAtMillis },
                newestExpiryAtMillis = index.entries.maxOf { it.expiresAtMillis }
            )
        }.sortedBy { it.indexLocatorSha256 }

    /**
     * Catalog read (REQ-03): filename ⇔ sha256(recordId) (structural), the
     * EXACT fixed AAD literal, schema gate (future ⇒ quarantine), identity,
     * strict entries. null ⇒ absent/corrupt ⇒ rebuild — never trusted, never
     * fallback (PR-1 read style).
     */
    private fun readGlobalCatalogInternal(): GlobalConversationCatalogV1? {
        if (globalCatalogFile.name != "global_catalog_${sha256Hex(GlobalConversationCatalogV1.RECORD_ID)}.enc") {
            return null
        }
        if (!globalCatalogFile.exists() || globalCatalogFile.length() == 0L) return null
        if (globalCatalogFile.length() > MAX_CONVERSATION_CATALOG_CIPHERTEXT_BYTES) {
            globalCatalogFile.delete()
            return null
        }
        // Exact AAD: readEncryptedSingleton deletes the file on AEAD failure.
        val jsonStr = readEncryptedSingleton(globalCatalogFile, globalCatalogAad) ?: return null
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        val schema = obj["schemaVersion"]?.asIntOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        if (schema > GlobalConversationCatalogV1.CURRENT_SCHEMA_VERSION) {
            globalCatalogFile.renameTo(globalCatalogQuarantineFile)
            return null
        }
        if (schema < GlobalConversationCatalogV1.CURRENT_SCHEMA_VERSION) {
            globalCatalogFile.delete()
            return null
        }
        val recordId = obj["recordId"]?.asStringOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        if (recordId != GlobalConversationCatalogV1.RECORD_ID) {
            globalCatalogFile.delete()
            return null
        }
        val createdAtMillis = obj["createdAtMillis"]?.asLongOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        val updatedAtMillis = obj["updatedAtMillis"]?.asLongOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        if (createdAtMillis < 0L || updatedAtMillis < 0L) {
            globalCatalogFile.delete()
            return null
        }
        val entriesArray = obj["entries"]?.asArrayOrNull() ?: run {
            globalCatalogFile.delete()
            return null
        }
        val entries = ArrayList<GlobalCatalogEntry>(entriesArray.list.size)
        for (entryValue in entriesArray.list) {
            val entryObj = entryValue.asObjectOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            val locatorSha256 = entryObj["indexLocatorSha256"]?.asStringOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            if (locatorSha256.length != 64 || !locatorSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
                globalCatalogFile.delete()
                return null
            }
            val observedRevision = entryObj["observedIndexRevision"]?.asLongOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            val bytesEstimate = entryObj["bytesEstimate"]?.asLongOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            val oldestLastAccessed = entryObj["oldestLastAccessedAtMillis"]?.asLongOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            val newestExpiry = entryObj["newestExpiryAtMillis"]?.asLongOrNull() ?: run {
                globalCatalogFile.delete()
                return null
            }
            if (observedRevision < 0L || bytesEstimate < 0L || oldestLastAccessed < 0L) {
                globalCatalogFile.delete()
                return null
            }
            entries.add(
                GlobalCatalogEntry(
                    indexLocatorSha256 = locatorSha256,
                    observedIndexRevision = observedRevision,
                    bytesEstimate = bytesEstimate,
                    oldestLastAccessedAtMillis = oldestLastAccessed,
                    newestExpiryAtMillis = newestExpiry
                )
            )
        }
        if (entries.map { it.indexLocatorSha256 }.toSet().size != entries.size) {
            globalCatalogFile.delete()
            return null
        }
        return GlobalConversationCatalogV1(
            schemaVersion = schema,
            recordId = recordId,
            entries = entries,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis
        )
    }

    private fun writeGlobalCatalogInternal() {
        val entries = conversationCatalogEntriesInternal()
        if (entries.isEmpty()) {
            // Nothing valid to catalog — the singleton must not outlive its indexes.
            globalCatalogFile.delete()
            globalCatalogQuarantineFile.delete()
            return
        }
        val previous = readGlobalCatalogInternal()
        val catalog = GlobalConversationCatalogV1(
            recordId = GlobalConversationCatalogV1.RECORD_ID,
            entries = entries,
            createdAtMillis = previous?.createdAtMillis ?: wallClockMillis(),
            updatedAtMillis = wallClockMillis()
        )
        globalCatalogQuarantineFile.delete()
        writeEncryptedSingleton(globalCatalogFile, globalCatalogPayloadJson(catalog), globalCatalogAad)
        directorySync(storageDir)
    }

    /** Current iff present, schema-valid, and every observed revision matches (stale ⇒ rebuild). */
    private fun isGlobalCatalogCurrentInternal(): Boolean {
        val indexFiles = conversationIndexFiles()
        if (indexFiles.isEmpty() &&
            !globalCatalogFile.exists() &&
            !globalCatalogQuarantineFile.exists()
        ) {
            return true
        }
        val catalog = readGlobalCatalogInternal() ?: return false
        val expected = conversationCatalogEntriesInternal()
        if (catalog.entries.size != expected.size) return false
        val revisionByLocator = expected.associate { it.indexLocatorSha256 to it.observedIndexRevision }
        return catalog.entries.all { revisionByLocator[it.indexLocatorSha256] == it.observedIndexRevision }
    }

    // --- Caps / eviction (REQ-01) ---

    /**
     * PR-3 caps: 24 MiB + 256 records per partition index, 32 MiB globally.
     * LRU order: (lastAccessedAtMillis asc, indexLocatorSha256 asc,
     * recordIdHash asc). Evicted CONTENT deletes first, affected INDEXES
     * rewrite once after (rev+1); the caller writes the catalog LAST.
     */
    private fun enforceConversationCacheCapsInternal(): Boolean {
        val openIndexes = conversationIndexFiles().mapNotNull { file ->
            val headerRecordId = envelopeHeaderRecordId(file, "index") ?: return@mapNotNull null
            if (!headerRecordId.startsWith("conversation-index:")) return@mapNotNull null
            parseConversationIndexFile(file, headerRecordId)
        }
        if (openIndexes.isEmpty()) return false

        val candidates = openIndexes.flatMap { index ->
            val locator = sha256Hex(index.recordId)
            index.entries.map { entry -> ConversationEvictionCandidate(index, locator, entry) }
        }
        fun lruOrder(rows: List<ConversationEvictionCandidate>): List<ConversationEvictionCandidate> =
            rows.sortedWith(
                compareBy(
                    { it.entry.lastAccessedAtMillis },
                    { it.locatorSha256 },
                    { it.entry.recordIdHash }
                )
            )

        val evicted = LinkedHashSet<ConversationEvictionCandidate>()
        var globalBytes = candidates.sumOf { it.entry.bytesEstimate }
        for (candidate in lruOrder(candidates)) {
            if (globalBytes <= MAX_CONVERSATION_GLOBAL_BYTES) break
            if (evicted.add(candidate)) globalBytes -= candidate.entry.bytesEstimate
        }
        candidates.groupBy { it.index.recordId }.forEach { (_, rows) ->
            var bytes = rows.sumOf { it.entry.bytesEstimate }
            var count = rows.size
            for (candidate in lruOrder(rows)) {
                if (bytes <= MAX_CONVERSATION_PARTITION_BYTES &&
                    count <= ConversationPartitionIndexV2.MAX_ENTRIES
                ) {
                    break
                }
                if (evicted.add(candidate)) {
                    bytes -= candidate.entry.bytesEstimate
                    count--
                }
            }
        }
        if (evicted.isEmpty()) return false

        // Content first (delete order) …
        evicted.forEach { candidate ->
            val contentFile = File(
                summaryOriginDirFor(candidate.index.profileLocalId, candidate.index.originSha256),
                "conversation_${candidate.entry.recordIdHash}.enc"
            )
            if (contentFile.exists()) {
                contentFile.delete()
                cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
            }
            quarantineTwinOf(contentFile).delete()
        }
        // … then one index rewrite per affected partition.
        evicted.groupBy { it.index.recordId }.forEach { (indexRecordId, removed) ->
            val partitionIndex = openIndexes.firstOrNull { it.recordId == indexRecordId } ?: return@forEach
            val removedHashes = removed.map { it.entry.recordIdHash }.toSet()
            writeConversationIndexInternal(
                partitionIndex.copy(
                    revision = partitionIndex.revision + 1L,
                    entries = partitionIndex.entries.filterNot { it.recordIdHash in removedHashes },
                    updatedAtMillis = wallClockMillis()
                )
            )
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_UPDATED)
        }
        return true
    }

    // --- Write path (REQ-01 order: content → index → caps/evict → catalog) ---

    /**
     * Atomically REPLACE the snapshot: whole-file atomic content write (PR-1
     * writeAtomicDurable), then index rev+1, then caps/eviction, then the
     * catalog LAST. Fail-closed guards (roster style): identity mismatch,
     * W-consistency violation, or a record over either plaintext cap ⇒ silent
     * skip, never a throw. I/O faults and injected stage faults PROPAGATE so
     * the caller maps them to a soft Failed (never partial success).
     */
    private fun writeConversationSnapshotInternal(
        cache: ConversationCacheV2,
        partition: CacheAccessPartition,
        // PR-4: the append path commits the same §5.2 chain under a
        // distinct content stage (TRACE_APPENDED); default keeps the
        // existing RECORD_WRITTEN sequence byte-identical (T-11).
        contentStage: CacheWriteStage = CacheWriteStage.STAGE_CONVERSATION_RECORD_WRITTEN
    ): Boolean {
        if (cache.profileLocalId != partition.profileLocalId ||
            !cache.originSha256.equals(partition.originSha256, ignoreCase = true) ||
            cache.cacheEpoch != partition.cacheEpoch ||
            cache.workspaceScopeTag != partition.workspaceScopeTag
        ) {
            return false
        }
        if (workspaceTagFor(cache.selectedWorkspaceName) != partition.workspaceScopeTag) return false
        val recordId = conversationRecordIdFor(partition, cache.durableSessionId)
        if (cache.durableSessionId.isBlank() || cache.recordId != recordId) return false
        val json = conversationPayloadJson(cache)
        val plaintextBytes = json.toByteArray(Charsets.UTF_8)
        if (plaintextBytes.size > ConversationCacheV2.MAX_SNAPSHOT_BYTES) return false
        // Defensive: a single record that alone exceeds the partition budget is skipped.
        if (plaintextBytes.size > MAX_CONVERSATION_PARTITION_BYTES) return false

        val originDir = summaryOriginDirFor(partition.profileLocalId, partition.originSha256)
        originDir.mkdirs()
        val contentFile = conversationContentFileFor(partition, cache.durableSessionId)
        quarantineTwinOf(contentFile).delete()
        // (1) content record — atomic durable write + directory fsync.
        writeEncryptedIdentifiedRecord(
            dir = originDir,
            filePrefix = "conversation",
            recordId = recordId,
            jsonString = json,
            aad = conversationAadFor(partition, cache.durableSessionId)
        )
        directorySync(originDir)
        cacheStageObserver(contentStage)

        // (2) partition index rev+1 — same recordId hash upserted (sole authority).
        val priorIndex = (readConversationIndexInternal(partition) as? ConversationIndexRead.Valid)?.index
        val recordHash = sha256Hex(recordId)
        val now = wallClockMillis()
        val upsertedEntries = (priorIndex?.entries ?: emptyList())
            .filterNot { it.recordIdHash == recordHash } +
            ConversationIndexEntry(
                recordIdHash = recordHash,
                // §5.2: bytesEstimate = the ENCRYPTED content file on disk —
                // step (1) already wrote it, so the length is post-write truth.
                bytesEstimate = contentFile.length(),
                expiresAtMillis = cache.snapshot.capturedAtMillis + ConversationCacheV2.MAX_TTL_MILLIS,
                lastAccessedAtMillis = cache.lastAccessedAtMillis
            )
        writeConversationIndexInternal(
            ConversationPartitionIndexV2(
                recordId = conversationIndexRecordIdFor(partition),
                profileLocalId = partition.profileLocalId,
                originSha256 = partition.originSha256,
                cacheEpoch = partition.cacheEpoch,
                workspaceScopeTag = partition.workspaceScopeTag,
                revision = (priorIndex?.revision ?: 0L) + 1L,
                entries = upsertedEntries,
                createdAtMillis = priorIndex?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
        )
        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_WRITTEN)

        // (3)(4) caps / eviction over validated indexes (a corrupt sibling index
        // is skipped here — the next init's recovery quarantines it).
        enforceConversationCacheCapsInternal()
        // (5) catalog reconcile + eviction summary — written LAST (§5.3).
        writeGlobalCatalogInternal()
        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_CATALOG_WRITTEN)
        return true
    }

    // --- Read path (REQ-02 order, steps 1–13) ---

    private fun readConversationRecordInternal(
        partition: CacheAccessPartition,
        durableSessionId: String,
        nowMillis: Long
    ): CacheReadResult<ConversationCacheV2> {
        if (durableSessionId.isBlank()) return CacheReadResult.Absent
        val recordId = conversationRecordIdFor(partition, durableSessionId)
        val recordHash = sha256Hex(recordId)
        val contentFile = conversationContentFileFor(partition, durableSessionId)
        // Quarantined residue is never served (schema-future marker lives there).
        if (quarantineTwinOf(contentFile).exists()) return CacheReadResult.Unavailable
        // (1) resolver-unavailable is a REPOSITORY mapping; here the partition is given.
        if (!contentFile.exists()) return CacheReadResult.Absent
        // (3) missing/empty ⇒ delete + Miss/Absent.
        if (contentFile.length() == 0L) {
            contentFile.delete()
            return CacheReadResult.Absent
        }
        // Pre-decrypt anti-DoS guard, sized from the ACTUAL envelope (repair
        // finding 2): 4-byte length + recordId header + 32 B GCM (12 B IV +
        // 16 B tag via CIPHERTEXT_OVERHEAD_BYTES) + 16 B slack. The old flat
        // +128 B under-counted every conversation recordId (>= ~105 chars) and
        // destroyed valid near-cap snapshots as Corrupt on first read. The
        // 16 B slack keeps a marginally over-cap payload flowing to step (12),
        // which fires at its plan position (after step 11), so multi-defect
        // reasons stay REQ-02-ordered (expired+oversized ⇒ Expired).
        if (contentFile.length() >
            ConversationCacheV2.MAX_SNAPSHOT_BYTES + 4 + recordId.length +
            ConversationCacheV2.CIPHERTEXT_OVERHEAD_BYTES + 16
        ) {
            contentFile.delete()
            return CacheReadResult.Corrupt
        }
        val plaintext = when (
            val envelope = readIdentifiedEnvelope(
                contentFile, "conversation", recordId, conversationAadFor(partition, durableSessionId)
            )
        ) {
            // (3) missing/empty
            IdentifiedEnvelopeRead.Missing -> {
                contentFile.delete()
                return CacheReadResult.Absent
            }
            // (4)+(6) malformed / header ⇔ recordId ⇔ filename mismatch
            IdentifiedEnvelopeRead.Invalid -> {
                contentFile.delete()
                return CacheReadResult.Corrupt
            }
            // (7) wrong AAD (tamper) — no fallback
            IdentifiedEnvelopeRead.AeadFailure -> {
                contentFile.delete()
                return CacheReadResult.Corrupt
            }
            is IdentifiedEnvelopeRead.Decrypted -> envelope.plaintext
        }
        // (5)(8)(9) strict payload parse → typed mappings (bounded by the
        // pre-decrypt guard above — at most cap + envelope + 16 B slack).
        val parsed = when (
            val result = parseConversationPayload(plaintext, recordId, partition, durableSessionId)
        ) {
            // (5) schema future ⇒ quarantine + Unavailable
            ConversationPayloadParse.SchemaFuture -> {
                contentFile.renameTo(quarantineTwinOf(contentFile))
                return CacheReadResult.Unavailable
            }
            ConversationPayloadParse.Corrupt -> {
                contentFile.delete()
                return CacheReadResult.Corrupt
            }
            ConversationPayloadParse.IdentityMismatch -> {
                contentFile.delete()
                return CacheReadResult.PartitionMismatch
            }
            is ConversationPayloadParse.Parsed -> {
                // PR-4 REQ-05 "again after decryption" (review F2): trace
                // caps are re-validated on the freshly decrypted normalized
                // DTO rows — the plan's remedy runs verbatim: "On overflow,
                // drop oldest WHOLE events until both caps hold" — a row
                // alone over the budget drops too; the record itself still
                // serves (drop-row, never fail-closed).
                result.value.copy(localTrace = boundCarriedTrace(result.value.localTrace))
            }
        }
        // (10) index — the SOLE existence authority, checked ONCE at its plan
        // position (repair finding 4: no pre-step-3 index gate, no duplicate
        // membership test). Steps (1)-(9) already validated the envelope and
        // payload, so a VALID index lacking the entry is a proven orphan
        // (REQ-05/§5.3 ⇒ delete). A missing/corrupt index authorizes NOTHING:
        // the content stays inaccessible and is retried by recovery (§5.3
        // "leave inaccessible") — never deleted on an invalid index.
        val index = when (val indexRead = readConversationIndexInternal(partition)) {
            // missing index or entry ⇒ Miss/Unavailable, no serve (REQ-02 (10)).
            ConversationIndexRead.Missing -> return CacheReadResult.Absent
            ConversationIndexRead.Corrupt -> return CacheReadResult.Unavailable
            is ConversationIndexRead.Valid -> indexRead.index
        }
        if (index.entries.none { it.recordIdHash == recordHash }) {
            contentFile.delete()
            return CacheReadResult.Absent
        }
        // (11) TTL: fresh up to and including 7 days; expired ⇒ delete flow.
        if (nowMillis - parsed.snapshot.capturedAtMillis > ConversationCacheV2.MAX_TTL_MILLIS) {
            contentFile.delete()
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
            try {
                writeConversationIndexInternal(
                    index.copy(
                        revision = index.revision + 1L,
                        entries = index.entries.filterNot { it.recordIdHash == recordHash },
                        updatedAtMillis = wallClockMillis()
                    )
                )
                cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_UPDATED)
            } catch (_: Exception) {
                // Content is already gone; recovery reconciles the dangling entry.
            }
            return CacheReadResult.Expired
        }
        // (12) byte cap re-check after decryption, at its plan position:
        // steps (5)-(11) ran first, so multi-defect records report the earlier
        // reason (expired+oversized ⇒ Expired, not Corrupt — repair finding 4).
        if (plaintext.size > ConversationCacheV2.MAX_SNAPSHOT_BYTES) {
            contentFile.delete()
            return CacheReadResult.Corrupt
        }
        // (13) best-effort LRU touch — index only; the resulting revision bump
        // makes the catalog revision-stale, which §5.3 (d) rebuilds (never trusts).
        try {
            if (nowMillis > parsed.lastAccessedAtMillis) {
                writeConversationIndexInternal(
                    index.copy(
                        revision = index.revision + 1L,
                        entries = index.entries.map { entry ->
                            if (entry.recordIdHash == recordHash) {
                                entry.copy(lastAccessedAtMillis = nowMillis)
                            } else {
                                entry
                            }
                        },
                        updatedAtMillis = wallClockMillis()
                    )
                )
                cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_UPDATED)
            }
        } catch (_: Exception) {
            // Best effort: a failed touch must never fail the read.
        }
        return CacheReadResult.Available(parsed)
    }

    // --- Delete flow (content → index → catalog; REQ-01 delete order) ---

    private fun deleteConversationSnapshotInternal(
        partition: CacheAccessPartition,
        durableSessionId: String
    ) {
        val contentFile = conversationContentFileFor(partition, durableSessionId)
        if (contentFile.exists()) {
            contentFile.delete()
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        }
        quarantineTwinOf(contentFile).delete()
        val index = parseConversationIndexFile(
            conversationIndexFileFor(partition),
            conversationIndexRecordIdFor(partition)
        ) ?: return // missing/corrupt: nothing to unlink; the next recovery reconciles
        val recordHash = sha256Hex(conversationRecordIdFor(partition, durableSessionId))
        if (index.entries.none { it.recordIdHash == recordHash }) return
        writeConversationIndexInternal(
            index.copy(
                revision = index.revision + 1L,
                entries = index.entries.filterNot { it.recordIdHash == recordHash },
                updatedAtMillis = wallClockMillis()
            )
        )
        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_UPDATED)
        reconcileConversationCatalogAfterDeleteInternal(anyIndexDeleted = true)
    }

    /** Drop this partition's indexes, then reconcile the catalog (REQ-08 hook). */
    private fun purgeConversationIndexAndCatalogLocked(
        profileLocalId: String,
        originSha256: String
    ) {
        var anyIndexDeleted = false
        conversationIndexFiles().forEach { file ->
            val headerRecordId = envelopeHeaderRecordId(file, "index") ?: return@forEach
            val index = parseConversationIndexFile(file, headerRecordId) ?: return@forEach
            if (index.profileLocalId == profileLocalId &&
                index.originSha256.equals(originSha256, ignoreCase = true)
            ) {
                file.delete()
                quarantineTwinOf(file).delete()
                anyIndexDeleted = true
            }
        }
        if (anyIndexDeleted) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        reconcileConversationCatalogAfterDeleteInternal(anyIndexDeleted)
    }

    /** §5.3 delete-flow catalog reconcile: drop the singleton when nothing remains, else rebuild — observed LAST. */
    private fun reconcileConversationCatalogAfterDeleteInternal(anyIndexDeleted: Boolean) {
        val catalogObserved = globalCatalogFile.exists() || globalCatalogQuarantineFile.exists()
        if (!catalogObserved && !anyIndexDeleted) return
        if (conversationIndexFiles().isEmpty()) {
            globalCatalogFile.delete()
            globalCatalogQuarantineFile.delete()
        } else {
            writeGlobalCatalogInternal()
        }
        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED)
    }

    private fun purgeConversationCacheInternal(profileLocalId: String, originSha256: String) {
        val originDir = summaryOriginDirFor(profileLocalId, originSha256)
        var anyContentDeleted = false
        if (originDir.exists()) {
            originDir.walkTopDown().forEach { f ->
                if (f.isFile && isConversationContentFileName(f.name)) {
                    f.delete()
                    anyContentDeleted = true
                }
            }
        }
        if (anyContentDeleted) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        purgeConversationIndexAndCatalogLocked(profileLocalId, originSha256)
    }

    /** REQ-08: ALL conversation content, then all indexes, then the catalog. */
    private fun clearConversationCacheInternal() {
        var anyContentDeleted = false
        if (cacheRootDir.exists()) {
            cacheRootDir.walkTopDown().forEach { f ->
                if (f.isFile && isConversationContentFileName(f.name)) {
                    f.delete()
                    anyContentDeleted = true
                }
            }
        }
        if (anyContentDeleted) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_RECORD_DELETED)
        val indexResidue = conversationIndexesDir.listFiles()?.toList().orEmpty()
        if (indexResidue.isNotEmpty()) {
            indexResidue.forEach { it.delete() }
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_DELETED)
        }
        val catalogObserved = globalCatalogFile.exists() || globalCatalogQuarantineFile.exists()
        globalCatalogFile.delete()
        globalCatalogQuarantineFile.delete()
        if (catalogObserved) cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_CATALOG_DELETED)
    }

    // --- Recovery (§5.3 / REQ-03) ---

    private fun runConversationCacheRecoveryBestEffort() {
        try {
            runConversationCacheRecoveryInternal()
        } catch (_: Exception) {
            // Recovery must never block construction; the next init/on-demand pass retries.
        }
    }

    private fun runConversationCacheRecoveryInternal() {
        // (1) validate each index file — delete invalid residue (no quarantine
        // residue for indexes: a bad index is simply gone, content becomes
        // unreachable-until-relinked).
        val repairNow = wallClockMillis()
        conversationIndexFiles().forEach { file ->
            val headerRecordId = envelopeHeaderRecordId(file, "index")
            val index = headerRecordId?.let { parseConversationIndexFile(file, it) }
            if (index == null) {
                file.delete()
                quarantineTwinOf(file).delete()
                return@forEach
            }
            // (1b) §5.3 ENTRY-LEVEL REPAIR (repair finding 1): for each VALID
            // index, drop entries whose referenced content is expired
            // (expiresAtMillis), missing (no conversation_<hash>.enc in the
            // partition origin dir), or corrupt (envelope header invalid or
            // sha256(header) != recordIdHash), then write the REPAIRED index
            // durably (revision+1, §5.3 "write repaired indexes"). Dropped
            // content files are swept by the orphan pass (2) below in the same
            // run; stale bytesEstimate/count stop inflating the caps, and the
            // contract's 7-day retention holds without a read of that session.
            val repairedEntries = index.entries.filter { entry ->
                if (repairNow > entry.expiresAtMillis) return@filter false
                val contentFile = File(
                    summaryOriginDirFor(index.profileLocalId, index.originSha256),
                    "conversation_${entry.recordIdHash}.enc"
                )
                if (!contentFile.exists()) return@filter false
                val contentHeader = envelopeHeaderRecordId(contentFile, "conversation")
                contentHeader != null &&
                    contentHeader.startsWith("conversation:") &&
                    sha256Hex(contentHeader) == entry.recordIdHash
            }
            if (repairedEntries.size != index.entries.size) {
                writeConversationIndexInternal(
                    index.copy(
                        revision = index.revision + 1L,
                        entries = repairedEntries,
                        updatedAtMillis = repairNow
                    )
                )
                cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_INDEX_UPDATED)
            }
        }
        // (2) orphan content — delete ONLY when the envelope validates as a
        // conversation record AND sha256(recordId) is absent from its VALIDATED
        // partition index. Anything else stays inaccessible and is retried next
        // recovery (§5.3 note: never delete on a non-validating envelope).
        if (cacheRootDir.exists()) {
            cacheRootDir.walkTopDown().forEach { f ->
                if (!f.isFile || !isConversationContentFileName(f.name)) return@forEach
                if (f.name.endsWith(".quarantine")) return@forEach
                val contentHeader = envelopeHeaderRecordId(f, "conversation") ?: return@forEach
                if (!contentHeader.startsWith("conversation:")) return@forEach
                // conversation:<P>:<O>:<E>:<W>:<sid> → index locator without sid.
                val indexRecordId =
                    "conversation-index:" + contentHeader.removePrefix("conversation:").substringBeforeLast(':')
                val indexFile = File(conversationIndexesDir, "index_${sha256Hex(indexRecordId)}.enc")
                if (!indexFile.exists()) return@forEach
                val index = parseConversationIndexFile(indexFile, indexRecordId) ?: return@forEach
                if (index.entries.none { it.recordIdHash == sha256Hex(contentHeader) }) {
                    f.delete()
                }
            }
        }
        // (3) sweep quarantined content/catalog residue.
        if (cacheRootDir.exists()) {
            cacheRootDir.walkTopDown().forEach { f ->
                if (f.isFile && f.name.startsWith("conversation_") && f.name.endsWith(".enc.quarantine")) {
                    f.delete()
                }
            }
        }
        if (globalCatalogQuarantineFile.exists() && !globalCatalogFile.exists()) {
            // Quarantined-but-not-rebuilt catalog: drop the residue; rebuild below.
            globalCatalogQuarantineFile.delete()
        }
        // (4) rebuild the catalog only if absent, corrupt, unknown-schema, or
        // revision-stale (never trusted, never fallback) …
        var catalogDirty = !isGlobalCatalogCurrentInternal()
        // (5) … then enforce global-cap eviction …
        if (enforceConversationCacheCapsInternal()) catalogDirty = true
        // … and rebuild the catalog LAST (§5.3 (e)).
        if (catalogDirty) writeGlobalCatalogInternal()
    }

    // --- PR-3 public surface (defaults live on VaultStorage) ---

    override suspend fun readConversationSnapshot(
        partition: CacheAccessPartition,
        storedSessionId: String,
        nowMillis: Long
    ): CacheReadResult<ConversationCacheV2> = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            readConversationRecordInternal(partition, storedSessionId, nowMillis)
        }
    }

    override suspend fun replaceConversationSnapshot(
        cache: ConversationCacheV2,
        partition: CacheAccessPartition
    ): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            replaceConversationSnapshotInternal(cache, partition)
        }
    }

    override suspend fun replaceConversationSnapshotIfCurrent(
        cache: ConversationCacheV2, partition: CacheAccessPartition,
        expected: AuthCredentialLease, selectedContext: String?
    ): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            if (cache.selectedWorkspaceName != selectedContext ||
                !capturedPartitionMatchesInternal(partition, expected, selectedContext)) return@withContext false
            replaceConversationSnapshotInternal(cache, partition)
        }
    }

    override suspend fun readConversationSnapshotIfCurrent(
        partition: CacheAccessPartition, storedSessionId: String,
        expected: AuthCredentialLease, selectedContext: String?
    ): CacheReadResult<ConversationCacheV2> = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            if (!capturedPartitionMatchesInternal(partition, expected, selectedContext))
                return@withContext CacheReadResult.Unavailable
            readConversationRecordInternal(partition, storedSessionId, wallClockMillis())
        }
    }

    private fun replaceConversationSnapshotInternal(cache: ConversationCacheV2, partition: CacheAccessPartition): Boolean {
        if (cache.schemaVersion != ConversationCacheV2.CURRENT_SCHEMA_VERSION) return false
        // Replace rows wholesale while carrying the current record's bounded local trace.
        val carriedTrace = runCatching {
            readConversationRecordInternal(partition, cache.durableSessionId, wallClockMillis())
                .takeIf { it is CacheReadResult.Available }
                ?.let { (it as CacheReadResult.Available).value.localTrace }
        }.getOrNull()
        val cacheToWrite = if (carriedTrace.isNullOrEmpty()) cache
            else cache.copy(localTrace = boundCarriedTrace(carriedTrace))
        // Genuine IO failures propagate to the repository's soft Failed result.
        return writeConversationSnapshotInternal(cacheToWrite, partition)
    }

    /**
     * PR-4 (plan CACHE-PR4 §5.5 "then apply caps"): the carried-forward trace
     * re-bounded on every replace-on-resume. The list can never grow during
     * carry (each append already enforced both caps), so this is the
     * plan-pinned defensive pass — oldest WHOLE events drop first, mirroring
     * the append bound (T-09: no partial rows, ever).
     */
    private fun boundCarriedTrace(
        events: List<CachedLocalToolTraceEvent>
    ): List<CachedLocalToolTraceEvent> {
        if (events.isEmpty()) return events
        val bounded = events.toMutableList()
        while (bounded.size > ConversationCacheV2.MAX_LOCAL_TRACE_EVENTS) {
            bounded.removeAt(0)
        }
        fun traceBytes(list: List<CachedLocalToolTraceEvent>): Int =
            list.sumOf { traceRowBytes(it) }
        while (traceBytes(bounded) > MAX_LOCAL_TRACE_BYTES) {
            bounded.removeAt(0)
        }
        return bounded
    }

    /**
     * PR-4 §5.2 / REQ-05 (review F2): the row's ACTUAL serialized DTO bytes —
     * every allowlisted field (incl. unbounded name, toolId, context) plus
     * JSON structure, produced from the SAME map the record serializer
     * writes, so the pre-write gate and the post-decryption re-check measure
     * identical bytes; +1 carries the array separator so summed row bytes
     * never understate the stored trace. (The old 260+context estimate
     * ignored name/toolId and let 128 × ~4 KiB names through.)
     */
    private fun traceRowBytes(event: CachedLocalToolTraceEvent): Int =
        1 + GatewayJson.serialize(GatewayJson.fromAny(traceRowJsonMap(event)))
            .toByteArray(Charsets.UTF_8).size

    /** The single §5.3 row shape: serializer output == cap measurement input. */
    private fun traceRowJsonMap(trace: CachedLocalToolTraceEvent): Map<String, Any?> =
        linkedMapOf(
            "localEventId" to trace.localEventId,
            "sourceSeq" to trace.sourceSeq,
            "sourceType" to trace.sourceType.name,
            "toolId" to trace.toolId,
            "name" to trace.name,
            "context" to trace.context,
            "status" to trace.status.name,
            "durationSeconds" to trace.durationSeconds,
            "observedAtMillis" to trace.observedAtMillis
        )

    /**
     * PR-4 (plan CACHE-PR4 §5.5): append ONE observed tool event onto the
     * EXISTING conversation record under the same §5.2 write chain
     * (content → index) with the distinct content stage TRACE_APPENDED.
     * Fail-closed: a missing/expired/identity-mismatched record is NEVER
     * created here (the trace never authorizes — no record ⇒ Skipped);
     * dedupe is on locally computable exact keys only (RISK-01); caps are
     * count 128 + byte budget 256 KiB with oldest-WHOLE-event eviction and
     * a single-oversize gate (T-09). TTL (REQ-06): capturedAt/expiresAt
     * untouched — only updatedAt/lastAccessedAt move. Best-effort (PAT-01):
     * guarded skips and I/O failures return false, never surface to chat.
     */
    override suspend fun appendConversationToolTrace(
        partition: CacheAccessPartition,
        storedSessionId: String,
        event: CachedLocalToolTraceEvent
    ): Boolean {
        if (storedSessionId.isBlank()) {
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
            return false
        }
        return try {
            mutex.withLock {
                withContext(ioDispatcher) {
                    ensureMigrated()
                    // Schema gate first (PR-1 discipline): unknown future
                    // schema ⇒ no append, no stage beyond SKIP.
                    val existing = readConversationRecordInternal(
                        partition,
                        storedSessionId,
                        wallClockMillis()
                    )
                    if (existing !is CacheReadResult.Available ||
                        existing.value.schemaVersion != ConversationCacheV2.CURRENT_SCHEMA_VERSION
                    ) {
                        // Miss / expired (read-path cleanup ran) / identity
                        // mismatch / unknown schema — an append NEVER creates
                        // or repairs a record (trace never authorizes).
                        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
                        return@withContext false
                    }
                    val current = existing.value
                    // REQ-04 exact-tuple dedupe, seeded from the LOADED
                    // record's stored TRIPLES (sourceSeq, sourceType,
                    // toolId). Stored rows never carry a runtime (§5.3
                    // NEVER-list) and the tuple's runtime component IS the
                    // admission context of this append (re-checked by the
                    // caller's collector guard and the repository seam), so a
                    // post-resume re-delivery under a NEW admitted runtime
                    // still matches the stored triple and is suppressed to
                    // one row (review F1). Every component must be
                    // host-supplied: null seq ⇒ never deduped; BLANK toolId
                    // ⇒ ALWAYS unique (§9 / review F4); names/contexts never
                    // participate (no fuzzy matching, CON-03/RISK-01).
                    if (event.sourceSeq != null &&
                        !event.toolId.isNullOrBlank() &&
                        current.localTrace.any { previous ->
                            previous.sourceSeq == event.sourceSeq &&
                                previous.sourceType == event.sourceType &&
                                previous.toolId == event.toolId
                        }
                    ) {
                        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
                        return@withContext false
                    }
                    fun traceBytes(events: List<CachedLocalToolTraceEvent>): Int =
                        events.sumOf { traceRowBytes(it) }
                    // Single-oversize gate FIRST: one event alone beyond the
                    // trace budget ⇒ Skipped with the stored trace byte-
                    // identical (nothing is ever partially written, T-09).
                    if (traceBytes(listOf(event)) > MAX_LOCAL_TRACE_BYTES) {
                        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
                        return@withContext false
                    }
                    // Caps: oldest WHOLE events evict first — count bound,
                    // then byte budget; rows are never truncated in place.
                    val appended = (current.localTrace + event).toMutableList()
                    while (appended.size > ConversationCacheV2.MAX_LOCAL_TRACE_EVENTS) {
                        appended.removeAt(0)
                    }
                    while (traceBytes(appended) > MAX_LOCAL_TRACE_BYTES) {
                        appended.removeAt(0)
                    }
                    // lastAccessedAt/updatedAt move to NOW; capturedAt (the
                    // TTL anchor) deliberately does not (REQ-06).
                    val now = wallClockMillis()
                    val updated = current.copy(
                        localTrace = appended,
                        lastAccessedAtMillis = now,
                        updatedAtMillis = now
                    )
                    val written = writeConversationSnapshotInternal(
                        updated,
                        partition,
                        contentStage = CacheWriteStage.STAGE_CONVERSATION_TRACE_APPENDED
                    )
                    if (!written) {
                        cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
                        return@withContext false
                    }
                    true
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // I/O and injected faults ⇒ false (never surfaces; the caller
            // maps it to a soft skip/failure on the advisory path).
            cacheStageObserver(CacheWriteStage.STAGE_CONVERSATION_TRACE_SKIPPED)
            false
        }
    }

    override suspend fun deleteConversationSnapshot(
        partition: CacheAccessPartition,
        storedSessionId: String
    ): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            deleteConversationSnapshotInternal(partition, storedSessionId)
        }
    }

    override suspend fun runConversationCacheRecovery(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            runConversationCacheRecoveryBestEffort()
        }
    }

    override suspend fun purgeConversationCache(
        profileLocalId: String,
        originSha256: String
    ): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            purgeConversationCacheInternal(profileLocalId, originSha256)
        }
    }

    override suspend fun clearConversationCache(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            clearConversationCacheInternal()
        }
    }

    // --- Granular Resets ---

    /**
     * PR-5 REQ-02 / V2 §7 line 248 (re-anchored: main landed PR-1 without
     * CacheLifecycleStore, so PR-5 declares it in VaultStorage.kt and builds
     * the all-cache deletion here): the SEVEN-PHASE clear, reached by callers
     * only through [VaultStorage.clearCache]. Entirely inside one
     * mutex-held run so no PR-3/PR-4 write can interleave:
     *  (1) conversation content → (2) per-partition index envelopes →
     *  (3) catalog delete ([clearConversationCacheInternal], §5.2 order, fires
     *  PR-3's delete stages) → (4) every `roster_<digest>.enc` under the cache
     *  layout (PR-2 REQ-07's deferred extension) → (5) every partition's
     *  `summary_*.enc` + quarantine residue ([purgeSummaryRecordsLocked],
     *  PR-1 REQ-08 — consolidation reorders it after phase 4, does not alter
     *  it) → (6) the [directorySync] durability seam → (7) the pre-existing
     *  pending-interrupt tail (PR-1 REQ-10, FD-001 cost (b)).
     *
     * Scope: exactly the five content families. `roster_`/`summary_`/
     * `conversation_` prefixes are checked inside the cache layout so
     * `cache_access_*`
     * rotation-state files in the same directories can never match, and no
     * epoch primitive is called — credentials, profiles, selection, drafts,
     * pending turns, diagnostics, quarantine disposition and the partition
     * epoch all survive (REQ-06/SEC-03/SEC-04). Delete-if-present per phase
     * (idempotent): an interruption leaves only opaque AEAD ciphertext plus a
     * possibly-stale but non-authorizing catalog that §5.3 recovery rebuilds
     * on next init, and a re-run completes (REQ-03/SEC-02). Fail-closed:
     * any thrown I/O error aborts the remaining phases and converts to a
     * residue-carrying [CacheClearResult.Residual] — never blocking, no new
     * diagnostics vocabulary (OQ-02).
     */
    override suspend fun clearAllCacheRecords(): CacheClearResult = mutex.withLock {
        withContext(ioDispatcher) {
            var phase = CacheClearResiduePhase.CONVERSATION_CACHE
            try {
                // Phases 1–3: §5.2 mandatory order content → indexes → catalog
                // (PR-3 REQ-08); the staging observer fires per durable step.
                clearConversationCacheInternal()
                phase = CacheClearResiduePhase.ROSTER
                // Phase 4 (PR-2 REQ-07 deferral): every roster record in every
                // partition directory. `roster_`-prefix-scoped inside cache/**,
                // so cache_access_* state files in the walked directories can
                // never match; absent file = success (idempotent).
                var anyRosterDeleted = false
                cacheRootDir.walkTopDown().forEach { f ->
                    if (f.isFile && f.name.startsWith("roster_") && f.name.endsWith(".enc")) {
                        if (f.delete()) anyRosterDeleted = true
                    }
                }
                if (anyRosterDeleted) cacheStageObserver(CacheWriteStage.STAGE_ROSTER_DELETED)
                phase = CacheClearResiduePhase.SUMMARIES
                // Phase 5: PR-1's summary purge, ordered AFTER the roster phase
                // (plan consolidation). Fire the boundary only if summaries
                // were actually present, so a second idempotent run fires no
                // additional deletion counters.
                val anySummariesPresent = cacheRootDir.walkTopDown().any {
                    it.isFile && it.name.startsWith("summary_") && it.name.endsWith(".enc")
                }
                purgeSummaryRecordsLocked()
                if (anySummariesPresent) cacheStageObserver(CacheWriteStage.STAGE_SUMMARY_DELETED)
                phase = CacheClearResiduePhase.DIRECTORY_SYNC
                // Phase 6: PR-1's sync seam makes the deletion durable — routed
                // through the optional TEST-03 recording probe when injected.
                directorySyncSeam?.invoke(storageDir) ?: directorySync(storageDir)
                phase = CacheClearResiduePhase.PENDING_INTERRUPTS
                // Phase 7: FD-001 cost (b) — undelivered interrupt holds never
                // outlive a granular reset (pre-existing tail, preserved
                // verbatim; pinned by EncryptedVaultInterruptRecordTest).
                clearPendingInterruptsInternal()
                CacheClearResult.Complete
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // REQ-02/OQ-02: an I/O failure (or an injected observer
                // interruption) aborts the remaining phases and is carried as
                // residue — every phase is idempotent, so an idempotent
                // re-tap re-runs it.
                CacheClearResult.Residual(phase)
            }
        }
    }

    /**
     * PR-5 REQ-02: the caller-facing spelling of the seven-phase clear — one
     * mutex-held run via [clearAllCacheRecords] (the V2 §7 lifecycle member).
     */
    override suspend fun clearCache(): CacheClearResult = clearAllCacheRecords()

    override suspend fun clearCredentials(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val index = readProfileIndexInternal()
            index?.profileIds?.forEach { getCredentialFile(it).delete() }
            legacyAuthFile.delete()
            storageDir.listFiles { _, name -> name.startsWith("auth_credential_") && name.endsWith(".enc") }?.forEach { it.delete() }
            // FD-001 cost (b): undelivered interrupt holds never outlive a granular reset.
            clearPendingInterruptsInternal()
        }
    }

    override suspend fun clearAll(): CacheClearResult = mutex.withLock {
        withContext(ioDispatcher) {
            try {
                loginGeneration.incrementAndGet()
                storageDir.listFiles { _, name -> name.startsWith("local_login_") }?.forEach { check(it.delete()) }
                legacyConnectionFile.delete()
                legacyAuthFile.delete()
                profileIndexFile.delete()
                storageDir.listFiles { _, name ->
                    (name.startsWith("connection_profile_") || name.startsWith("auth_credential_")) && name.endsWith(".enc")
                }?.forEach { it.delete() }
                selectionFile.delete()
                cacheFile.delete()
                // PR-3 REQ-08 (re-anchored by symbol): full reset deletes conversation
                // content → indexes → catalog first, then the wholesale layout wipe.
                clearConversationCacheInternal()
                conversationIndexesDir.deleteRecursively()
                // PR-1 REQ-08: full reset also wipes the partition layout, the
                // quarantine residue, and the legacy-discard disposition record;
                // discard re-runs on the next initialization. The recursive
                // cache/ wipe also covers roster + summary records — PR-5 adds
                // no wipe logic here, only the completeness assertion (REQ-10).
                cacheRootDir.deleteRecursively()
                quarantineSummaryFile.delete()
                migrationStateFile.delete()
                discardPending = true
                directorySync(storageDir)
                draftsDir.listFiles()?.forEach { it.delete() }
                pendingDir.listFiles()?.forEach { it.delete() }
                // FD-001 cost (b): undelivered interrupt holds never outlive a granular reset.
                clearPendingInterruptsInternal()
                diagnosticsFile.delete()
                contextProfileSelectionFile.delete()
                storageDir.listFiles { _, name -> (name.startsWith("session_pins_") || name.startsWith("session_filters_") || name.startsWith("bot_favourites_") || name.startsWith("kanban_board_")) }
                    ?.forEach { Files.deleteIfExists(it.toPath()) }
                migrationChecked = false
                CacheClearResult.Complete
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // PR-5 REQ-02/OQ-02: the wipe converts an I/O failure into a
                // residue-carrying result instead of failing the user's action;
                // deletion order itself is untouched (REQ-10/SEC-05).
                CacheClearResult.Residual(CacheClearResiduePhase.WHOLE_WIPE)
            }
        }
    }

    private fun selectedContextAuthorityInternal(): SelectedContextReadResult {
        return try {
            val attributes = try {
                Files.readAttributes(contextProfileSelectionFile.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
            } catch (_: java.nio.file.NoSuchFileException) { return SelectedContextReadResult.Absent }
            if (!attributes.isRegularFile || attributes.size() == 0L) return SelectedContextReadResult.Unreadable
            val bytes = contextProfileSelectionFile.readBytes()
            val aad = createAad("CONTEXT_PROFILE_SELECTION", ContextProfileSelection.CURRENT_SCHEMA_VERSION, "singleton_context_profile_selection")
            val plaintext = cipherEngine.decrypt(bytes, aad).getOrNull() ?: return SelectedContextReadResult.Unreadable
            val obj = GatewayJson.parseOrNull(plaintext.toString(Charsets.UTF_8))?.asObjectOrNull()
                ?: return SelectedContextReadResult.Unreadable
            if (obj["schemaVersion"]?.asIntOrNull() != ContextProfileSelection.CURRENT_SCHEMA_VERSION)
                return SelectedContextReadResult.Unreadable
            val name = obj["selectedProfileName"]?.asStringOrNull() ?: return SelectedContextReadResult.Unreadable
            if (!name.matches(Regex("[A-Za-z0-9._-]{1,80}"))) return SelectedContextReadResult.Unreadable
            SelectedContextReadResult.Selected(name)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { SelectedContextReadResult.Failed }
        catch (_: Exception) { SelectedContextReadResult.Unreadable }
    }

    override suspend fun readSelectedContextProfileAuthoritative(): SelectedContextReadResult = mutex.withLock {
        withContext(ioDispatcher) { selectedContextAuthorityInternal() }
    }

    override suspend fun captureWorkspaceRecoveryOwner(): WorkspaceRecoveryOwnerResult = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val selection = selectedContextAuthorityInternal()
            if (selection !is SelectedContextReadResult.Selected && selection != SelectedContextReadResult.Absent)
                return@withContext WorkspaceRecoveryOwnerResult.Unavailable
            val id = activeAuthProfileInternal() ?: return@withContext WorkspaceRecoveryOwnerResult.Unavailable
            val credential = authorizedCredentialInternal(id) ?: return@withContext WorkspaceRecoveryOwnerResult.Unavailable
            val lease = authLeaseInternal(credential.canonicalOrigin) ?: return@withContext WorkspaceRecoveryOwnerResult.Unavailable
            WorkspaceRecoveryOwnerResult.Captured(WorkspaceRecoveryOwner(lease, selection))
        }
    }

    override suspend fun clearSelectedContextProfileIfCurrent(
        expectedLease: AuthCredentialLease, expectedContext: String
    ): WorkspaceClearResult = mutex.withLock {
        withContext(ioDispatcher) {
            val selection = selectedContextAuthorityInternal()
            if (selection == SelectedContextReadResult.Failed || selection == SelectedContextReadResult.Unreadable)
                return@withContext WorkspaceClearResult.Failed
            if ((selection as? SelectedContextReadResult.Selected)?.name != expectedContext ||
                !capturedOwnerMatchesInternal(expectedLease, expectedContext)) return@withContext WorkspaceClearResult.Stale
            try {
                workspaceSelectionDelete(contextProfileSelectionFile)
                if (selectedContextAuthorityInternal() == SelectedContextReadResult.Absent) WorkspaceClearResult.Applied
                else WorkspaceClearResult.Failed
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WorkspaceClearResult.Failed }
        }
    }

    override suspend fun getSelectedContextProfile(): String? = mutex.withLock {
        withContext(ioDispatcher) {
            readSelectedContextProfileInternal()
        }
    }

    private fun readSelectedContextProfileInternal(): String? {
        val aad = createAad("CONTEXT_PROFILE_SELECTION", ContextProfileSelection.CURRENT_SCHEMA_VERSION, "singleton_context_profile_selection")
        val jsonStr = readEncryptedSingleton(contextProfileSelectionFile, aad) ?: return null
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return null
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > ContextProfileSelection.CURRENT_SCHEMA_VERSION) return null
        return obj["selectedProfileName"]?.asStringOrNull()
    }

    override suspend fun saveSelectedContextProfile(name: String): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            val selection = ContextProfileSelection(selectedProfileName = name)
            val map = linkedMapOf<String, Any?>(
                "selectedProfileName" to selection.selectedProfileName,
                "updatedAt" to selection.updatedAt,
                "schemaVersion" to selection.schemaVersion
            )
            val aad = createAad("CONTEXT_PROFILE_SELECTION", selection.schemaVersion, "singleton_context_profile_selection")
            writeEncryptedSingleton(contextProfileSelectionFile, GatewayJson.serialize(GatewayJson.fromAny(map)), aad)
        }
    }

    override suspend fun clearSelectedContextProfile(): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            contextProfileSelectionFile.delete()
        }
    }

    // --- CHAT-011 S1: per-workspace default model + reasoning effort ---

    private fun readProfileModelDefaultsInternal(): MutableMap<String, ProfileModelDefault> {
        val aad = createAad(
            "PROFILE_MODEL_DEFAULTS",
            ProfileModelDefault.CURRENT_SCHEMA_VERSION,
            "singleton_profile_model_defaults"
        )
        val jsonStr = readEncryptedSingleton(profileModelDefaultsFile, aad) ?: return mutableMapOf()
        val obj = GatewayJson.parseOrNull(jsonStr)?.asObjectOrNull() ?: return mutableMapOf()
        val schemaVer = obj["schemaVersion"]?.asIntOrNull() ?: 1
        if (schemaVer > ProfileModelDefault.CURRENT_SCHEMA_VERSION) return mutableMapOf()
        val entries = obj["workspaces"]?.asObjectOrNull() ?: return mutableMapOf()
        val map = mutableMapOf<String, ProfileModelDefault>()
        for ((key, value) in entries.map) {
            val record = value.asObjectOrNull() ?: continue
            val workspaceName = record["workspaceName"]?.asStringOrNull() ?: continue
            val modelName = record["modelName"]?.asStringOrNull() ?: continue
            val reasoningEffort = record["reasoningEffort"]?.asStringOrNull() ?: continue
            val updatedAtMillis = record["updatedAtMillis"]?.asLongOrNull() ?: continue
            map[key] = ProfileModelDefault(
                workspaceName = workspaceName,
                modelName = modelName,
                reasoningEffort = reasoningEffort,
                updatedAtMillis = updatedAtMillis,
                schemaVersion = schemaVer
            )
        }
        return map
    }

    private fun writeProfileModelDefaultsInternal(map: Map<String, ProfileModelDefault>) {
        val entries = linkedMapOf<String, Any?>()
        for ((key, record) in map) {
            entries[key] = linkedMapOf<String, Any?>(
                "workspaceName" to record.workspaceName,
                "modelName" to record.modelName,
                "reasoningEffort" to record.reasoningEffort,
                "updatedAtMillis" to record.updatedAtMillis,
                "schemaVersion" to record.schemaVersion
            )
        }
        val root = linkedMapOf<String, Any?>(
            "workspaces" to entries,
            "schemaVersion" to ProfileModelDefault.CURRENT_SCHEMA_VERSION
        )
        val aad = createAad(
            "PROFILE_MODEL_DEFAULTS",
            ProfileModelDefault.CURRENT_SCHEMA_VERSION,
            "singleton_profile_model_defaults"
        )
        writeEncryptedSingleton(
            profileModelDefaultsFile,
            GatewayJson.serialize(GatewayJson.fromAny(root)),
            aad
        )
    }

    override suspend fun getProfileModelDefault(workspace: String): ProfileModelDefault? = mutex.withLock {
        withContext(ioDispatcher) {
            readProfileModelDefaultsInternal()[ProfileModelDefault.workspaceKey(workspace)]
        }
    }

    override suspend fun saveProfileModelDefault(record: ProfileModelDefault): Unit = mutex.withLock {
        withContext(ioDispatcher) {
            // Read-modify-write inside the vault mutex: other workspaces'
            // records survive an overwrite of THIS workspace's entry.
            val map = readProfileModelDefaultsInternal()
            map[ProfileModelDefault.workspaceKey(record.workspaceName)] = record
            writeProfileModelDefaultsInternal(map)
        }
    }

    private fun botFavouriteId(profileId: String, origin: String) = sha256Hex(
        GatewayJson.serialize(GatewayJson.fromAny(listOf(profileId, sha256Hex(origin))))
    )

    private fun kanbanSelectionFile(profileId: String, origin: String): Pair<File, String> {
        val id = sha256Hex(GatewayJson.serialize(GatewayJson.fromAny(listOf(profileId, sha256Hex(origin)))))
        return File(storageDir, "kanban_board_$id.enc") to id
    }

    private fun kanbanSelectionOwner(profileId: String, origin: String): Boolean =
        readProfileIndexInternal()?.activeProfileId == profileId &&
            readConnectionProfileInternal(profileId)?.normalizedOrigin == origin

    override suspend fun getKanbanBoardSelection(profileId: String, origin: String): String? = mutex.withLock {
        withContext(ioDispatcher) {
            if (!kanbanSelectionOwner(profileId, origin)) return@withContext null
            val (file, id) = kanbanSelectionFile(profileId, origin)
            if (!file.exists()) return@withContext null
            check(file.length() in 1L..4096L) { "Board selection unavailable" }
            val bytes = cipherEngine.decrypt(file.readBytes(), createAad("KANBAN_BOARD", 1, id)).getOrThrow()
            val obj = GatewayJson.parseOrNull(String(bytes, Charsets.UTF_8))?.asObjectOrNull()
                ?: error("Board selection unavailable")
            check(obj["schemaVersion"]?.asIntOrNull() == 1) { "Board selection unavailable" }
            obj["slug"]?.asStringOrNull()?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: error("Board selection unavailable")
        }
    }

    override suspend fun saveKanbanBoardSelection(profileId: String, origin: String, slug: String): Boolean = mutex.withLock {
        withContext(ioDispatcher) {
            if (!kanbanSelectionOwner(profileId, origin) || slug.isBlank() || slug.length > 128) return@withContext false
            val (file, id) = kanbanSelectionFile(profileId, origin)
            val payload = GatewayJson.serialize(GatewayJson.fromAny(mapOf("schemaVersion" to 1, "slug" to slug)))
            val ciphertext = cipherEngine.encrypt(payload.toByteArray(Charsets.UTF_8), createAad("KANBAN_BOARD", 1, id))
            val staged = File(storageDir, "${file.name}.tmp")
            try {
                FileOutputStream(staged).use { stream -> stream.write(ciphertext); stream.flush(); stream.fd.sync() }
                atomicMove(staged, file)
            } finally { staged.delete() }
            true
        }
    }

    private fun readBotFavouritesInternal(id: String): List<String> {
        val file = File(storageDir, "bot_favourites_$id.enc")
        if (!file.exists()) return emptyList()
        check(file.length() <= 128 * 1024) { "Bot favourites unavailable" }
        val bytes = cipherEngine.decrypt(file.readBytes(), createAad("BOT_FAVOURITES", 1, id)).getOrThrow()
        val obj = GatewayJson.parseOrNull(String(bytes, Charsets.UTF_8))?.asObjectOrNull()
            ?: error("Bot favourites unavailable")
        check(obj["schemaVersion"]?.asIntOrNull() == 1) { "Bot favourites unavailable" }
        val values = obj["names"]?.asArrayOrNull()?.list ?: error("Bot favourites unavailable")
        check(values.size <= 100) { "Bot favourites unavailable" }
        val names = values.map { it.asStringOrNull()?.takeIf { name -> name.isNotBlank() && name.length <= 512 }
            ?: error("Bot favourites unavailable") }
        check(names.distinct().size == names.size) { "Bot favourites unavailable" }
        return names
    }

    override suspend fun getBotFavourites(profileId: String, origin: String): BotFavourites? = mutex.withLock {
        withContext(ioDispatcher) {
            ensureMigrated()
            val owner = authLeaseInternal(origin)?.takeIf { it.profileLocalId == profileId }
                ?: return@withContext null
            BotFavourites(profileId, origin, readBotFavouritesInternal(botFavouriteId(profileId, origin)), owner)
        }
    }

    override suspend fun setBotFavourite(expected: BotFavourites, name: String, favourite: Boolean): BotFavourites = mutex.withLock {
        withContext(ioDispatcher) {
            val owner = authLeaseInternal(expected.origin) ?: error("Bot selection changed")
            check(owner.profileLocalId == expected.profileId && owner.profileLocalId == expected.owner.profileLocalId &&
                owner.origin == expected.owner.origin && owner.epoch == expected.owner.epoch &&
                owner.credential == expected.owner.credential) { "Bot selection changed" }
            require(name.isNotBlank() && name.length <= 512) { "Invalid bot favourite" }
            val id = botFavouriteId(expected.profileId, expected.origin)
            val prior = readBotFavouritesInternal(id)
            val names = if (favourite) (prior + name).distinct() else prior - name
            check(names.size <= 100) { "Bot favourites limit reached" }
            val payload = GatewayJson.serialize(GatewayJson.fromAny(mapOf("schemaVersion" to 1, "names" to names)))
            check(payload.toByteArray(Charsets.UTF_8).size <= 128 * 1024 - 64) { "Bot favourites limit reached" }
            val file = File(storageDir, "bot_favourites_$id.enc")
            val ciphertext = cipherEngine.encrypt(payload.toByteArray(Charsets.UTF_8), createAad("BOT_FAVOURITES", 1, id))
            val staged = File(storageDir, "${file.name}.tmp")
            try {
                FileOutputStream(staged).use { stream -> stream.write(ciphertext); stream.flush(); stream.fd.sync() }
                atomicMove(staged, file)
            } finally { staged.delete() }
            BotFavourites(expected.profileId, expected.origin, names, owner)
        }
    }

    private fun currentPinScopeInternal(): SessionPinScope? {
        val id = readProfileIndexInternal()?.activeProfileId ?: return null
        val profile = readConnectionProfileInternal(id) ?: return null
        val workspace = when (val selection = selectedContextAuthorityInternal()) {
            SelectedContextReadResult.Absent -> null
            is SelectedContextReadResult.Selected -> selection.name
            else -> throw IllegalStateException("Local pins unavailable")
        }
        return SessionPinScope(id, sha256Hex(profile.normalizedOrigin), workspace)
    }

    private fun pinRecordId(scope: SessionPinScope): String = sha256Hex(
        GatewayJson.serialize(GatewayJson.fromAny(listOf(scope.profileId, scope.originHash, scope.workspace)))
    )

    private fun readPinsInternal(scope: SessionPinScope): Pair<Set<String>, Long> {
        val id = pinRecordId(scope)
        val file = File(storageDir, "session_pins_$id.enc")
        if (!file.exists()) return emptySet<String>() to wallClockMillis()
        // Unlike best-effort cache reads, an unreadable preference must never
        // become an empty set that a subsequent edit silently overwrites.
        val plaintext = cipherEngine.decrypt(file.readBytes(), createAad("SESSION_PINS", 1, id)).getOrThrow()
        val obj = GatewayJson.parseOrNull(String(plaintext, Charsets.UTF_8))?.asObjectOrNull()
            ?: throw IllegalStateException("Local pins unavailable")
        check(obj["schemaVersion"]?.asIntOrNull() == 1) { "Local pins unavailable" }
        val ids = obj["sessionIds"]?.asArrayOrNull()?.list
            ?: throw IllegalStateException("Local pins unavailable")
        val parsed = ids.map { value -> value.asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Local pins unavailable") }.toSet()
        val createdAt = obj["createdAt"]?.asLongOrNull()
            ?: throw IllegalStateException("Local pins unavailable")
        check(obj["updatedAt"]?.asLongOrNull() != null) { "Local pins unavailable" }
        return parsed to createdAt
    }

    override suspend fun getSessionPins(): SessionPins? = mutex.withLock {
        withContext(ioDispatcher) {
            val scope = currentPinScopeInternal() ?: return@withContext null
            SessionPins(scope, readPinsInternal(scope).first)
        }
    }

    override suspend fun setSessionPinned(scope: SessionPinScope, sessionId: String, pinned: Boolean): SessionPins = mutex.withLock {
        withContext(ioDispatcher) {
            check(currentPinScopeInternal() == scope) { "Local pin selection changed" }
            require(sessionId.isNotBlank()) { "Invalid local pin" }
            val (prior, createdAt) = readPinsInternal(scope)
            val ids = if (pinned) prior + sessionId else prior - sessionId
            val id = pinRecordId(scope)
            val payload = linkedMapOf<String, Any?>("schemaVersion" to 1,
                "createdAt" to createdAt, "updatedAt" to wallClockMillis(), "sessionIds" to ids.sorted())
            val file = File(storageDir, "session_pins_$id.enc")
            val ciphertext = cipherEngine.encrypt(
                GatewayJson.serialize(GatewayJson.fromAny(payload)).toByteArray(Charsets.UTF_8),
                createAad("SESSION_PINS", 1, id))
            val staged = File(storageDir, "${file.name}.tmp")
            try {
                FileOutputStream(staged).use { stream ->
                    stream.write(ciphertext)
                    stream.flush()
                    stream.fd.sync()
                }
                atomicMove(staged, file)
            } finally {
                staged.delete()
            }
            SessionPins(scope, ids)
        }
    }

    private fun filterFile(scope: SessionPinScope): Pair<File, String> {
        val id = pinRecordId(scope)
        return File(storageDir, "session_filters_$id.enc") to id
    }

    private fun readFiltersInternal(scope: SessionPinScope): SessionFilters {
        val (file, id) = filterFile(scope)
        if (!file.exists()) return SessionFilters(scope)
        check(file.length() in 1..(32 * 1024 + 64).toLong()) { "Local filters unavailable" }
        val bytes = cipherEngine.decrypt(file.readBytes(), createAad("SESSION_FILTERS", 1, id)).getOrThrow()
        val obj = GatewayJson.parseOrNull(String(bytes, Charsets.UTF_8))?.asObjectOrNull()
            ?: throw IllegalStateException("Local filters unavailable")
        check(obj["schemaVersion"]?.asIntOrNull() == 1) { "Local filters unavailable" }
        val pinned = obj["pinnedOnly"]?.asBooleanOrNull()
            ?: throw IllegalStateException("Local filters unavailable")
        val sourcesValue = obj["selectedSources"]
            ?: throw IllegalStateException("Local filters unavailable")
        val sources = if (sourcesValue is io.github.anshuman2401.hermesmobile.data.gateway.json.JsonNull) null else {
            val items = sourcesValue.asArrayOrNull()?.list
                ?: throw IllegalStateException("Local filters unavailable")
            check(items.size <= 100) { "Local filters unavailable" }
            items.map { it.asStringOrNull()?.takeIf { value -> value.length <= 512 && value == value.trim() }
                ?: throw IllegalStateException("Local filters unavailable") }.toSet()
        }
        return SessionFilters(scope, pinned, sources)
    }

    override suspend fun getSessionFilters(): SessionFilters? = mutex.withLock {
        withContext(ioDispatcher) {
            val scope = currentPinScopeInternal() ?: return@withContext null
            readFiltersInternal(scope)
        }
    }

    override suspend fun saveSessionFilters(filters: SessionFilters): SessionFilters = mutex.withLock {
        withContext(ioDispatcher) {
            check(currentPinScopeInternal() == filters.scope) { "Local filter selection changed" }
            // Refuse to overwrite an unreadable or future record without an explicit recovery path.
            readFiltersInternal(filters.scope)
            val sources = filters.selectedSources?.toList()?.sorted()
            check(sources == null || (sources.size <= 100 && sources.all { it.length <= 512 && it == it.trim() })) {
                "Local filters unavailable"
            }
            val payload = GatewayJson.serialize(GatewayJson.fromAny(mapOf(
                "schemaVersion" to 1, "pinnedOnly" to filters.pinnedOnly, "selectedSources" to sources
            ))).toByteArray(Charsets.UTF_8)
            check(payload.size <= 32 * 1024) { "Local filters unavailable" }
            val (file, id) = filterFile(filters.scope)
            val ciphertext = cipherEngine.encrypt(payload, createAad("SESSION_FILTERS", 1, id))
            val staged = File(storageDir, "${file.name}.tmp")
            try {
                FileOutputStream(staged).use { stream -> stream.write(ciphertext); stream.flush(); stream.fd.sync() }
                atomicMove(staged, file)
            } finally { staged.delete() }
            filters
        }
    }

}

/** PR-1 schema-3 session summary caps and retention (REQ-03). */
private const val SUMMARY_SCHEMA_V3 = 3
private const val MAX_SUMMARY_ENTRIES = 100
private const val MAX_SUMMARY_PAYLOAD_BYTES = 262_144 // 256 KiB
/** PR-2 REQ-01: roster ciphertext pre-decrypt guard (plaintext cap + AEAD overhead slack). */
private const val ROSTER_CIPHERTEXT_OVERHEAD_BYTES = 64
private const val MAX_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
private const val CACHE_CAPTURE_SKEW_LIMIT_MS = 5L * 60 * 1000
private const val LEGACY_SUMMARY_CACHE_DISPOSITION = "DISCARDED_NO_HISTORICAL_ORIGIN"

// --- PR-3 (plan §5): conversation-cache caps and pre-decrypt guards. ---

/** Per-partition conversation-cache budget: 24 MiB. */
private const val MAX_CONVERSATION_PARTITION_BYTES = 24L * 1024 * 1024
/** Global (cross-partition) conversation-cache budget: 32 MiB. */
private const val MAX_CONVERSATION_GLOBAL_BYTES = 32L * 1024 * 1024
// PR-4 (plan CACHE-PR4 REQ-01, T-09): phone-captured trace byte budget —
// 256 KiB across all rows of ONE record; oldest WHOLE events evict first,
// a single event alone over the budget is Skipped (never truncated).
private const val MAX_LOCAL_TRACE_BYTES = 256 * 1024
/** Index pre-decrypt guard: plaintext cap 480 KiB + envelope overhead slack. */
private const val MAX_CONVERSATION_INDEX_PAYLOAD_BYTES = 480 * 1024
private const val MAX_CONVERSATION_INDEX_CIPHERTEXT_BYTES =
    MAX_CONVERSATION_INDEX_PAYLOAD_BYTES + 2048L
/** Catalog pre-decrypt guard: aggregates only — generous bound, fail closed. */
private const val MAX_CONVERSATION_CATALOG_CIPHERTEXT_BYTES = 16L * 1024 * 1024

/** PR-3 typed envelope outcome — REQ-02 needs missing/invalid/AEAD distinguished (unlike PR-1's null-collapsing helper). */
private sealed interface IdentifiedEnvelopeRead {
    class Decrypted(val plaintext: ByteArray) : IdentifiedEnvelopeRead
    object Missing : IdentifiedEnvelopeRead
    object Invalid : IdentifiedEnvelopeRead
    object AeadFailure : IdentifiedEnvelopeRead
}

/** PR-3 typed index-read outcome for the read path's Miss vs Corrupt mapping. */
private sealed interface ConversationIndexRead {
    class Valid(val index: ConversationPartitionIndexV2) : ConversationIndexRead
    object Missing : ConversationIndexRead
    object Corrupt : ConversationIndexRead
}

/** PR-3 typed payload-parse outcome for read steps 5–9. */
private sealed interface ConversationPayloadParse {
    class Parsed(val value: ConversationCacheV2) : ConversationPayloadParse
    object Corrupt : ConversationPayloadParse
    object SchemaFuture : ConversationPayloadParse
    object IdentityMismatch : ConversationPayloadParse
}

/** PR-3 eviction candidate — identity triples the LRU sweep runs over. */
private data class ConversationEvictionCandidate(
    val index: ConversationPartitionIndexV2,
    val locatorSha256: String,
    val entry: ConversationIndexEntry
)
