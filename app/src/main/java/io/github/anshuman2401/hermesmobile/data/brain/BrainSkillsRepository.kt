package io.github.anshuman2401.hermesmobile.data.brain

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.StaleAuthenticatedResponseException
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonArray
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonBoolean
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonObject
import io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString
import io.github.anshuman2401.hermesmobile.data.local.AuthCredentialLease
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BrainInstalledSkill(
    val name: String,
    val description: String,
    val enabled: Boolean
)

data class BrainSkillsSnapshot(
    val snapshotId: Long,
    val selectedProfile: String,
    val skills: List<BrainInstalledSkill>
)

enum class BrainSkillsFailureReason {
    INVALID_SELECTION,
    UNAVAILABLE,
    MALFORMED,
    STALE,
    BUSY,
    SKILL_UNAVAILABLE,
    WRITE_REJECTED,
    ACK_MALFORMED,
    READBACK_UNAVAILABLE,
    READBACK_MISMATCH,
    AUTH_UNAVAILABLE
}

data class BrainSkillsLoadFailure(val reason: BrainSkillsFailureReason)

sealed interface BrainSkillsLoadResult {
    data class Available(val snapshot: BrainSkillsSnapshot) : BrainSkillsLoadResult
    data class Unavailable(val failure: BrainSkillsLoadFailure) : BrainSkillsLoadResult
}

sealed interface BrainSkillsToggleResult {
    data class Saved(val snapshot: BrainSkillsSnapshot) : BrainSkillsToggleResult

    data class Rejected(
        val reason: BrainSkillsFailureReason,
        val authoritativeSnapshot: BrainSkillsSnapshot? = null
    ) : BrainSkillsToggleResult

    data class NotConfirmed(
        val reason: BrainSkillsFailureReason,
        val mayHaveSaved: Boolean,
        val authoritativeSnapshot: BrainSkillsSnapshot? = null
    ) : BrainSkillsToggleResult
}

enum class BrainSkillsContentFailureReason {
    STALE,
    BUSY,
    AUTH_UNAVAILABLE,
    MISSING,
    REFUSED,
    MALFORMED,
    TOO_LARGE,
    UNAVAILABLE
}

sealed interface BrainSkillsContentResult {
    class Available(
        val selectedProfile: String,
        val name: String,
        val content: String
    ) : BrainSkillsContentResult {
        override fun toString(): String =
            "Available(selectedProfile=[redacted], name=[redacted], content=[redacted])"
    }

    data class Unavailable(val reason: BrainSkillsContentFailureReason) : BrainSkillsContentResult
}

/** Selected-profile installed-skill inventory and immediate toggle orchestration. */
class BrainSkillsRepository(
    private val vaultStorage: VaultStorage,
    private val authRepository: AuthRepository,
    private val gatewayAdapter: GatewayProtocolAdapter? = null,
    private val transport: BrainModelReadTransport = OkHttpBrainModelReadTransport(),
    private val reader: BrainModelSettingsReader = BrainModelSettingsReader(
        vaultStorage = vaultStorage,
        authRepository = authRepository,
        transport = transport,
        gatewayAdapter = gatewayAdapter
    )
) {
    internal val configuredVaultStorage get() = vaultStorage
    internal val configuredAuthRepository get() = authRepository
    internal val configuredGatewayAdapter get() = gatewayAdapter

    private val operationMutex = Mutex()
    private var nextSnapshotId = 0L
    private var loaded: LoadedState? = null

    /** A busy call is rejected immediately; no mutation is queued behind another call. */
    suspend fun load(): BrainSkillsLoadResult {
        if (!operationMutex.tryLock()) {
            return BrainSkillsLoadResult.Unavailable(
                BrainSkillsLoadFailure(BrainSkillsFailureReason.BUSY)
            )
        }
        try {
            loaded = null
            val target = when (val capture = reader.captureTargetResult()) {
                BrainModelTargetCaptureResult.InvalidSelection ->
                    return BrainSkillsLoadResult.Unavailable(
                        BrainSkillsLoadFailure(BrainSkillsFailureReason.INVALID_SELECTION)
                    )
                is BrainModelTargetCaptureResult.Unavailable ->
                    return BrainSkillsLoadResult.Unavailable(
                        BrainSkillsLoadFailure(capture.reason.toSkillsFailureReason())
                    )
                is BrainModelTargetCaptureResult.Captured -> capture.target
            }
            val inventory = readInventoryCaptured(target, target.lease)
            val available = inventory as? InventoryRead.Available
                ?: return BrainSkillsLoadResult.Unavailable(
                    BrainSkillsLoadFailure((inventory as InventoryRead.Unavailable).reason)
                )
            currentCoroutineContext().ensureActive()
            if (!reader.isTargetCurrent(target, available.receipt)) {
                return BrainSkillsLoadResult.Unavailable(
                    BrainSkillsLoadFailure(BrainSkillsFailureReason.STALE)
                )
            }
            val snapshot = BrainSkillsSnapshot(
                snapshotId = ++nextSnapshotId,
                selectedProfile = target.selectedProfile,
                skills = available.skills
            )
            loaded = LoadedState(snapshot, target, available.receipt)
            return BrainSkillsLoadResult.Available(snapshot)
        } catch (cancelled: CancellationException) {
            loaded = null
            throw cancelled
        } catch (_: Exception) {
            loaded = null
            return BrainSkillsLoadResult.Unavailable(
                BrainSkillsLoadFailure(BrainSkillsFailureReason.UNAVAILABLE)
            )
        } finally {
            operationMutex.unlock()
        }
    }

    /** Performs at most one PUT for this intent, then confirms it from a fresh inventory. */
    suspend fun toggle(
        snapshotId: Long,
        name: String,
        enabled: Boolean
    ): BrainSkillsToggleResult {
        if (!operationMutex.tryLock()) {
            return BrainSkillsToggleResult.Rejected(BrainSkillsFailureReason.BUSY)
        }
        try {
            val state = loaded ?: return BrainSkillsToggleResult.NotConfirmed(
                BrainSkillsFailureReason.STALE,
                mayHaveSaved = false
            )
            if (state.snapshot.snapshotId != snapshotId) {
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.STALE,
                    mayHaveSaved = false
                )
            }
            if (!reader.isTargetCurrent(state.target, state.receipt)) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.STALE,
                    mayHaveSaved = false
                )
            }
            val row = state.snapshot.skills.firstOrNull { it.name == name }
                ?: return BrainSkillsToggleResult.Rejected(BrainSkillsFailureReason.SKILL_UNAVAILABLE)
            if (row.enabled == enabled) {
                return BrainSkillsToggleResult.Saved(state.snapshot)
            }

            val path = reader.requestPath(SKILLS_TOGGLE_PATH, state.target.selectedProfile)
            val body = GatewayJson.serialize(
                GatewayJson.fromAny(
                    linkedMapOf(
                        "name" to name,
                        "enabled" to enabled,
                        "profile" to state.target.selectedProfile
                    )
                )
            )
            val exchange = try {
                authRepository.exchangeAuthenticated(
                    state.target.origin,
                    path,
                    state.receipt
                ) { headers ->
                    transport.openSkillsMutation(
                        state.target.origin,
                        path,
                        headers,
                        body,
                        OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                    )
                }
            } catch (cancelled: CancellationException) {
                loaded = null
                throw cancelled
            } catch (_: Exception) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.AUTH_UNAVAILABLE,
                    mayHaveSaved = true
                )
            }
            if (exchange.isFailure) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    if (exchange.exceptionOrNull() is StaleAuthenticatedResponseException) {
                        BrainSkillsFailureReason.STALE
                    } else {
                        BrainSkillsFailureReason.AUTH_UNAVAILABLE
                    },
                    mayHaveSaved = true
                )
            }

            val exchanged = exchange.getOrThrow()
            val mutationReceipt = exchanged.receipt
            val response = exchanged.response
            try {
                if (response.statusCode in 400..499) {
                    return if (retainAcceptedReceipt(state, mutationReceipt)) {
                        BrainSkillsToggleResult.Rejected(BrainSkillsFailureReason.WRITE_REJECTED)
                    } else {
                        BrainSkillsToggleResult.NotConfirmed(
                            BrainSkillsFailureReason.STALE,
                            mayHaveSaved = false
                        )
                    }
                }
                if (response.statusCode !in 200..299) {
                    loaded = null
                    return BrainSkillsToggleResult.NotConfirmed(
                        BrainSkillsFailureReason.WRITE_REJECTED,
                        mayHaveSaved = true
                    )
                }
                val ack = try {
                    parseObject(response.readBoundedBody())
                } catch (cancelled: CancellationException) {
                    loaded = null
                    throw cancelled
                } catch (_: Exception) {
                    loaded = null
                    return BrainSkillsToggleResult.NotConfirmed(
                        BrainSkillsFailureReason.ACK_MALFORMED,
                        mayHaveSaved = true
                    )
                }
                val ok = ack["ok"] as? JsonBoolean
                    ?: run {
                        loaded = null
                        return BrainSkillsToggleResult.NotConfirmed(
                            BrainSkillsFailureReason.ACK_MALFORMED,
                            mayHaveSaved = true
                        )
                }
                if (!ok.value) {
                    return if (retainAcceptedReceipt(state, mutationReceipt)) {
                        BrainSkillsToggleResult.Rejected(BrainSkillsFailureReason.WRITE_REJECTED)
                    } else {
                        BrainSkillsToggleResult.NotConfirmed(
                            BrainSkillsFailureReason.STALE,
                            mayHaveSaved = false
                        )
                    }
                }
                if ((ack["name"] as? JsonString)?.value != name ||
                    (ack["enabled"] as? JsonBoolean)?.value != enabled
                ) {
                    loaded = null
                    return BrainSkillsToggleResult.NotConfirmed(
                        BrainSkillsFailureReason.ACK_MALFORMED,
                        mayHaveSaved = true
                    )
                }
            } catch (cancelled: CancellationException) {
                loaded = null
                throw cancelled
            } catch (_: Exception) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.AUTH_UNAVAILABLE,
                    mayHaveSaved = true
                )
            } finally {
                response.close()
            }

            val readback = readInventoryCaptured(state.target, mutationReceipt)
            val authoritative = (readback as? InventoryRead.Available)?.let {
                BrainSkillsSnapshot(
                    snapshotId = ++nextSnapshotId,
                    selectedProfile = state.target.selectedProfile,
                    skills = it.skills
                )
            }
            if (readback !is InventoryRead.Available) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    (readback as InventoryRead.Unavailable).reason,
                    mayHaveSaved = true
                )
            }
            if (!reader.isTargetCurrent(state.target, readback.receipt)) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.STALE,
                    mayHaveSaved = true
                )
            }
            val confirmed = authoritative!!.skills.firstOrNull { it.name == name }?.enabled == enabled
            if (!confirmed) {
                loaded = null
                return BrainSkillsToggleResult.NotConfirmed(
                    BrainSkillsFailureReason.READBACK_MISMATCH,
                    mayHaveSaved = true,
                    authoritativeSnapshot = authoritative
                )
            }
            loaded = LoadedState(authoritative, state.target, readback.receipt)
            return BrainSkillsToggleResult.Saved(authoritative)
        } catch (cancelled: CancellationException) {
            loaded = null
            throw cancelled
        } catch (_: Exception) {
            loaded = null
            return BrainSkillsToggleResult.NotConfirmed(
                BrainSkillsFailureReason.READBACK_UNAVAILABLE,
                mayHaveSaved = true
            )
        } finally {
            operationMutex.unlock()
        }
    }

    /** Reads the complete document for the exact still-eligible inventory member. */
    suspend fun readContent(snapshotId: Long, name: String): BrainSkillsContentResult {
        if (!operationMutex.tryLock()) {
            return BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.BUSY)
        }
        val state = loaded
        if (state == null) {
            operationMutex.unlock()
            return BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.STALE)
        }
        var acceptedReceipt: AuthCredentialLease? = null
        try {
            if (state.snapshot.snapshotId != snapshotId ||
                state.snapshot.skills.none { it.name == name }
            ) {
                return BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.STALE)
            }
            if (!reader.isTargetCurrent(state.target, state.receipt)) {
                loaded = null
                return BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.STALE)
            }

            val path = contentPath(state.target.selectedProfile, name)
            val exchange = try {
                authRepository.exchangeAuthenticated(
                    state.target.origin,
                    path,
                    state.receipt
                ) { headers ->
                    transport.openSkillsContentRead(
                        state.target.origin,
                        path,
                        headers,
                        OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loaded = null
                return BrainSkillsContentResult.Unavailable(
                    BrainSkillsContentFailureReason.AUTH_UNAVAILABLE
                )
            }
            if (exchange.isFailure) {
                loaded = null
                return BrainSkillsContentResult.Unavailable(
                    if (exchange.exceptionOrNull() is StaleAuthenticatedResponseException) {
                        BrainSkillsContentFailureReason.STALE
                    } else {
                        BrainSkillsContentFailureReason.AUTH_UNAVAILABLE
                    }
                )
            }

            val exchanged = exchange.getOrThrow()
            acceptedReceipt = exchanged.receipt
            try {
                exchanged.response.use { response ->
                    if (response.statusCode == 404) {
                        return contentFailure(
                            state,
                            acceptedReceipt,
                            BrainSkillsContentFailureReason.MISSING
                        )
                    }
                    if (response.statusCode !in 200..299) {
                        return contentFailure(
                            state,
                            acceptedReceipt,
                            BrainSkillsContentFailureReason.REFUSED
                        )
                    }
                    val body = response.readBoundedBody()
                    val document = parseContent(body, name)
                    currentCoroutineContext().ensureActive()
                    if (!isLoadedStateCurrent(state, exchanged.receipt)) {
                        loaded = null
                        return BrainSkillsContentResult.Unavailable(
                            BrainSkillsContentFailureReason.STALE
                        )
                    }
                    loaded = state.copy(receipt = exchanged.receipt)
                    return BrainSkillsContentResult.Available(
                        selectedProfile = state.target.selectedProfile,
                        name = document.first,
                        content = document.second
                    )
                }
            } catch (cancelled: CancellationException) {
                // Header acceptance may already have rotated the cookie. Keep
                // only that receipt, and only for this still-current loaded state.
                withContext(NonCancellable) {
                    if (acceptedReceipt != null) {
                        try {
                            retainAcceptedReceipt(state, acceptedReceipt!!)
                        } catch (_: Exception) {
                            loaded = null
                        }
                    }
                }
                throw cancelled
            } catch (_: BrainSkillsMalformedException) {
                return contentFailure(state, acceptedReceipt, BrainSkillsContentFailureReason.MALFORMED)
            } catch (_: BrainModelBodyTooLargeException) {
                return contentFailure(state, acceptedReceipt, BrainSkillsContentFailureReason.TOO_LARGE)
            } catch (_: BrainModelTransportException) {
                return contentFailure(state, acceptedReceipt, BrainSkillsContentFailureReason.UNAVAILABLE)
            } catch (_: Exception) {
                return contentFailure(state, acceptedReceipt, BrainSkillsContentFailureReason.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun contentFailure(
        state: LoadedState,
        receipt: AuthCredentialLease?,
        reason: BrainSkillsContentFailureReason
    ): BrainSkillsContentResult {
        if (receipt != null) {
            try {
                if (retainAcceptedReceipt(state, receipt)) {
                    return BrainSkillsContentResult.Unavailable(reason)
                }
            } catch (_: Exception) {
                loaded = null
            }
        }
        return BrainSkillsContentResult.Unavailable(BrainSkillsContentFailureReason.STALE)
    }

    private suspend fun isLoadedStateCurrent(
        state: LoadedState,
        receipt: AuthCredentialLease
    ): Boolean = loaded?.snapshot?.snapshotId == state.snapshot.snapshotId &&
        loaded?.target == state.target && reader.isTargetCurrent(state.target, receipt)

    private fun contentPath(profile: String, name: String): String {
        val url = "http://skills-read.invalid$SKILLS_CONTENT_PATH".toHttpUrl()
            .newBuilder()
            .addQueryParameter("profile", profile)
            .addQueryParameter("name", name)
            .build()
        return url.encodedPath + "?" + url.encodedQuery
    }

    private suspend fun retainAcceptedReceipt(
        state: LoadedState,
        receipt: AuthCredentialLease
    ): Boolean {
        if (!reader.isTargetCurrent(state.target, receipt)) {
            loaded = null
            return false
        }
        loaded = state.copy(receipt = receipt)
        return true
    }

    private suspend fun readInventoryCaptured(
        target: BrainModelCapturedTarget,
        expected: AuthCredentialLease
    ): InventoryRead {
        var acceptedReceipt: AuthCredentialLease? = null
        return try {
            if (!reader.isTargetCurrent(target, expected)) {
                return InventoryRead.Unavailable(BrainSkillsFailureReason.STALE, expected)
            }
            val path = reader.requestPath(SKILLS_PATH, target.selectedProfile)
            val exchange = authRepository.exchangeAuthenticated(
                target.origin,
                path,
                expected
            ) { headers ->
                transport.openSkillsRead(
                    target.origin,
                    path,
                    headers,
                    OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS
                )
            }
            if (exchange.isFailure) {
                return InventoryRead.Unavailable(
                    if (exchange.exceptionOrNull() is StaleAuthenticatedResponseException) {
                        BrainSkillsFailureReason.STALE
                    } else {
                        BrainSkillsFailureReason.UNAVAILABLE
                    },
                    acceptedReceipt
                )
            }
            val exchanged = exchange.getOrThrow()
            acceptedReceipt = exchanged.receipt
            exchanged.response.use { response ->
                if (response.statusCode !in 200..299) {
                    return@use InventoryRead.Unavailable(
                        BrainSkillsFailureReason.UNAVAILABLE,
                        acceptedReceipt
                    )
                }
                val skills = parseSkills(response.readBoundedBody())
                if (!reader.isTargetCurrent(target, exchanged.receipt)) {
                    return@use InventoryRead.Unavailable(
                        BrainSkillsFailureReason.STALE,
                        exchanged.receipt
                    )
                }
                InventoryRead.Available(skills, exchanged.receipt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleAuthenticatedResponseException) {
            InventoryRead.Unavailable(BrainSkillsFailureReason.STALE, acceptedReceipt)
        } catch (_: BrainSkillsMalformedException) {
            InventoryRead.Unavailable(BrainSkillsFailureReason.MALFORMED, acceptedReceipt)
        } catch (_: BrainModelTransportException) {
            InventoryRead.Unavailable(BrainSkillsFailureReason.UNAVAILABLE, acceptedReceipt)
        } catch (_: Exception) {
            InventoryRead.Unavailable(BrainSkillsFailureReason.UNAVAILABLE, acceptedReceipt)
        }
    }

    private fun parseSkills(bytes: ByteArray): List<BrainInstalledSkill> {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val rows = (GatewayJson.parseOrNull(text) as? JsonArray)
            ?: throw BrainSkillsMalformedException()
        val names = HashSet<String>()
        return rows.list.map { value ->
            val row = value as? JsonObject ?: throw BrainSkillsMalformedException()
            val name = (row["name"] as? JsonString)?.value
                ?.takeIf { it.isSafeSkillText(512) && it.isNotBlank() }
                ?: throw BrainSkillsMalformedException()
            if (!names.add(name)) throw BrainSkillsMalformedException()
            val description = (row["description"] as? JsonString)?.value
                ?.takeIf { it.isSafeSkillDescription(2048, allowEmpty = true) }
                ?: throw BrainSkillsMalformedException()
            val enabled = (row["enabled"] as? JsonBoolean)?.value
                ?: throw BrainSkillsMalformedException()
            BrainInstalledSkill(name, description, enabled)
        }
    }

    private fun parseObject(bytes: ByteArray): JsonObject {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return (GatewayJson.parseOrNull(text) as? JsonObject)
            ?: throw BrainSkillsMalformedException()
    }

    private fun parseContent(bytes: ByteArray, expectedName: String): Pair<String, String> {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw BrainSkillsMalformedException()
        }
        val root = (GatewayJson.parseOrNull(text) as? JsonObject)
            ?: throw BrainSkillsMalformedException()
        val returnedName = (root["name"] as? JsonString)?.value
            ?: throw BrainSkillsMalformedException()
        val content = (root["content"] as? JsonString)?.value
            ?: throw BrainSkillsMalformedException()
        if (returnedName != expectedName) throw BrainSkillsMalformedException()
        return returnedName to content
    }

    private fun String.isSafeSkillText(maxLength: Int, allowEmpty: Boolean = false): Boolean =
        length <= maxLength && (allowEmpty || isNotEmpty()) && none { it.isISOControl() }

    private fun String.isSafeSkillDescription(maxLength: Int, allowEmpty: Boolean = false): Boolean =
        length <= maxLength && (allowEmpty || isNotEmpty()) && none {
            it.isISOControl() && it != '\n' && it != '\r' && it != '\t'
        }

    private data class LoadedState(
        val snapshot: BrainSkillsSnapshot,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease
    )

    private sealed interface InventoryRead {
        data class Available(val skills: List<BrainInstalledSkill>, val receipt: AuthCredentialLease) : InventoryRead
        data class Unavailable(val reason: BrainSkillsFailureReason, val receipt: AuthCredentialLease?) : InventoryRead
    }

    private class BrainSkillsMalformedException : IllegalArgumentException()

    companion object {
        const val SKILLS_PATH = "/api/skills"
        const val SKILLS_TOGGLE_PATH = "/api/skills/toggle"
        const val SKILLS_CONTENT_PATH = "/api/skills/content"
    }
}

private fun BrainModelReadFailure.toSkillsFailureReason(): BrainSkillsFailureReason = when (this) {
    BrainModelReadFailure.INVALID_SELECTION -> BrainSkillsFailureReason.INVALID_SELECTION
    BrainModelReadFailure.MALFORMED -> BrainSkillsFailureReason.MALFORMED
    BrainModelReadFailure.STALE -> BrainSkillsFailureReason.STALE
    BrainModelReadFailure.UNAVAILABLE -> BrainSkillsFailureReason.UNAVAILABLE
}
