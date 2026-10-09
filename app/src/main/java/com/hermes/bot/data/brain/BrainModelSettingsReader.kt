package com.hermes.bot.data.brain

import com.hermes.bot.data.auth.AuthRepository
import com.hermes.bot.data.auth.AuthenticatedExchange
import com.hermes.bot.data.auth.StaleAuthenticatedResponseException
import com.hermes.bot.data.gateway.GatewayProtocolAdapter
import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonArray
import com.hermes.bot.data.gateway.json.JsonBoolean
import com.hermes.bot.data.gateway.json.JsonObject
import com.hermes.bot.data.gateway.json.JsonString
import com.hermes.bot.data.local.AuthCredentialLease
import com.hermes.bot.data.local.SelectedContextReadResult
import com.hermes.bot.data.local.VaultStorage
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BrainModelCapability(
    val reasoning: Boolean?,
    val canDisableReasoning: Boolean?
)

data class BrainModelProvider(
    val slug: String,
    val models: List<String>,
    val authenticated: Boolean,
    val capabilities: Map<String, BrainModelCapability>,
    val requiresEndpointPassthrough: Boolean = false
)

data class BrainModelMetadata(
    val name: String?,
    val slug: String?,
    val supportsReasoning: Boolean?
)

data class BrainMainModel(
    val provider: String,
    val model: String,
    val metadata: BrainModelMetadata?
)

data class BrainModelSettings(
    val selectedProfile: String,
    val main: BrainMainModel?,
    val providers: List<BrainModelProvider>
)

enum class BrainModelReadFailure {
    UNAVAILABLE,
    INVALID_SELECTION,
    MALFORMED,
    STALE
}

sealed interface BrainModelReadResult {
    data class Available(val settings: BrainModelSettings) : BrainModelReadResult
    data class Unavailable(val reason: BrainModelReadFailure) : BrainModelReadResult
}

/** Captured selected-profile ownership used by the Phase A repository. */
internal data class BrainModelCapturedTarget(
    val selectedProfile: String,
    val localProfileId: String,
    val origin: CanonicalOrigin,
    val lease: AuthCredentialLease,
    val generation: Long?,
    val gatewayAdapter: GatewayProtocolAdapter?
) {
    override fun toString(): String = "BrainModelCapturedTarget([redacted])"

    fun tupleStillCurrent(): Boolean {
        if (gatewayAdapter == null) return true
        val capturedGeneration = generation ?: return false
        return gatewayAdapter.isScopedConnectionCurrent(origin, capturedGeneration)
    }
}

internal sealed interface BrainModelTargetCaptureResult {
    data class Captured(val target: BrainModelCapturedTarget) : BrainModelTargetCaptureResult

    data class Unavailable(val reason: BrainModelReadFailure) : BrainModelTargetCaptureResult

    object InvalidSelection : BrainModelTargetCaptureResult
}

internal sealed interface BrainModelCapturedReadResult {
    data class Available(
        val settings: BrainModelSettings,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease
    ) : BrainModelCapturedReadResult

    data class Unavailable(
        val reason: BrainModelReadFailure,
        val target: BrainModelCapturedTarget,
        val receipt: AuthCredentialLease?
    ) : BrainModelCapturedReadResult
}

/** Authenticated, read-only selected-profile catalog and main-model read. */
class BrainModelSettingsReader(
    private val vaultStorage: VaultStorage,
    private val authRepository: AuthRepository,
    private val transport: BrainModelReadTransport = OkHttpBrainModelReadTransport(),
    private val gatewayAdapter: GatewayProtocolAdapter? = null
) {
    suspend fun read(): BrainModelReadResult {
        return when (val capture = captureTargetResult()) {
            BrainModelTargetCaptureResult.InvalidSelection ->
                BrainModelReadResult.Unavailable(BrainModelReadFailure.INVALID_SELECTION)
            is BrainModelTargetCaptureResult.Unavailable ->
                BrainModelReadResult.Unavailable(capture.reason)
            is BrainModelTargetCaptureResult.Captured -> when (val result = readCaptured(capture.target)) {
                is BrainModelCapturedReadResult.Available -> BrainModelReadResult.Available(result.settings)
                is BrainModelCapturedReadResult.Unavailable -> BrainModelReadResult.Unavailable(result.reason)
            }
        }
    }

    internal suspend fun captureTarget(): BrainModelCapturedTarget? =
        (captureTargetResult() as? BrainModelTargetCaptureResult.Captured)?.target

    internal suspend fun captureTargetResult(): BrainModelTargetCaptureResult {
        val selection = vaultStorage.readSelectedContextProfileAuthoritative()
        val selected = (selection as? SelectedContextReadResult.Selected)?.name
            ?: return BrainModelTargetCaptureResult.InvalidSelection
        if (!isValidProfile(selected)) return BrainModelTargetCaptureResult.InvalidSelection
        val localId = vaultStorage.getActiveProfileId()
            ?: return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.UNAVAILABLE)
        val connection = vaultStorage.getConnectionProfile(localId)
            ?: return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.UNAVAILABLE)
        val origin = CanonicalOrigin.parse(connection.normalizedOrigin).getOrNull()
            ?: return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.MALFORMED)
        val lease = vaultStorage.readAuthCredentialLease(origin.originUrl)
            ?: return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.UNAVAILABLE)
        val generation = gatewayAdapter?.connectionGeneration
        if (gatewayAdapter != null) {
            val currentGeneration = generation
                ?: return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.UNAVAILABLE)
            if (!gatewayAdapter.isScopedConnectionCurrent(origin, currentGeneration)) {
                return BrainModelTargetCaptureResult.Unavailable(BrainModelReadFailure.STALE)
            }
        }
        return BrainModelTargetCaptureResult.Captured(
            BrainModelCapturedTarget(selected, localId, origin, lease, generation, gatewayAdapter)
        )
    }

    internal suspend fun readCaptured(
        target: BrainModelCapturedTarget,
        expectedLease: AuthCredentialLease = target.lease
    ): BrainModelCapturedReadResult {
        var latestReceipt: AuthCredentialLease? = expectedLease
        return try {
            if (!isCurrent(target, expectedLease)) {
                return capturedUnavailable(BrainModelReadFailure.STALE, target, latestReceipt)
            }
            val options = exchange(target, OPTIONS_PATH, expectedLease)
            latestReceipt = options.receipt
            val optionsBody = options.response.use { response ->
                if (response.statusCode !in 200..299) {
                    return capturedUnavailable(BrainModelReadFailure.UNAVAILABLE, target, latestReceipt)
                }
                response.readBoundedBody()
            }
            val catalog = decodeOptions(optionsBody)
            if (!isCurrent(target, options.receipt)) {
                return capturedUnavailable(BrainModelReadFailure.STALE, target, latestReceipt)
            }

            val info = exchange(target, INFO_PATH, options.receipt)
            latestReceipt = info.receipt
            val infoBody = info.response.use { response ->
                if (response.statusCode !in 200..299) {
                    return capturedUnavailable(BrainModelReadFailure.UNAVAILABLE, target, latestReceipt)
                }
                response.readBoundedBody()
            }
            val metadata = decodeInfo(infoBody, catalog.main)
            if (!isCurrent(target, info.receipt)) {
                return capturedUnavailable(BrainModelReadFailure.STALE, target, latestReceipt)
            }
            currentCoroutineContext().ensureActive()
            if (!target.tupleStillCurrent()) {
                return capturedUnavailable(BrainModelReadFailure.STALE, target, latestReceipt)
            }

            BrainModelCapturedReadResult.Available(
                settings = BrainModelSettings(
                    selectedProfile = target.selectedProfile,
                    main = catalog.main?.copy(metadata = metadata),
                    providers = catalog.providers
                ),
                target = target,
                receipt = info.receipt
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleAuthenticatedResponseException) {
            capturedUnavailable(BrainModelReadFailure.STALE, target, latestReceipt)
        } catch (_: BrainModelMalformedException) {
            capturedUnavailable(BrainModelReadFailure.MALFORMED, target, latestReceipt)
        } catch (_: BrainModelTransportException) {
            capturedUnavailable(BrainModelReadFailure.UNAVAILABLE, target, latestReceipt)
        } catch (_: Exception) {
            capturedUnavailable(BrainModelReadFailure.UNAVAILABLE, target, latestReceipt)
        }
    }

    internal suspend fun isTargetCurrent(
        target: BrainModelCapturedTarget,
        receipt: AuthCredentialLease? = null
    ): Boolean = isCurrent(target, receipt)

    private suspend fun exchange(
        target: BrainModelCapturedTarget,
        endpoint: String,
        expected: AuthCredentialLease
    ): AuthenticatedExchange<BrainModelReadResponse> = authRepository.exchangeAuthenticated(
        target.origin,
        requestPath(endpoint, target.selectedProfile),
        expected
    ) { headers ->
        transport.open(target.origin, requestPath(endpoint, target.selectedProfile), headers,
            OkHttpBrainModelReadTransport.DEFAULT_TIMEOUT_MILLIS)
    }.getOrThrow()

    private suspend fun isCurrent(target: BrainModelCapturedTarget, receipt: AuthCredentialLease? = null): Boolean {
        val selection = vaultStorage.readSelectedContextProfileAuthoritative()
        if ((selection as? SelectedContextReadResult.Selected)?.name != target.selectedProfile) return false
        val activeId = vaultStorage.getActiveProfileId() ?: return false
        if (activeId != target.localProfileId) return false
        val current = vaultStorage.getConnectionProfile(activeId) ?: return false
        if (current.normalizedOrigin != target.origin.originUrl) return false
        if (receipt != null && !authRepository.isResponseCurrent(receipt)) return false
        return target.tupleStillCurrent()
    }

    private fun decodeOptions(bytes: ByteArray): DecodedCatalog {
        val root = parseObject(bytes)
        val provider = requiredString(root, "provider", allowEmpty = true)
        val model = requiredString(root, "model", allowEmpty = true)
        if ((provider.isEmpty()) != (model.isEmpty()) || provider.isBlank() || model.isBlank()) {
            if (provider.isNotEmpty() || model.isNotEmpty()) throw BrainModelMalformedException()
        }
        val rows = requiredArray(root, "providers")
        val providers = rows.list.map { value ->
            val row = (value as? JsonObject) ?: throw BrainModelMalformedException()
            val slug = requiredString(row, "slug")
            val authenticated = requiredBoolean(row, "authenticated")
            val modelValues = requiredArray(row, "models").list.map { item ->
                (item as? JsonString)?.value?.takeIf { it.isSafeText(512) } ?: throw BrainModelMalformedException()
            }
            val capabilities = parseCapabilities(row["capabilities"], modelValues)
            BrainModelProvider(
                slug = slug,
                models = modelValues,
                authenticated = authenticated,
                capabilities = capabilities,
                // The URL itself is deliberately discarded. Presence is the
                // only public fact needed by the model-only mutation surface;
                // even a malformed optional value must not become a new
                // rejection or a retained endpoint.
                requiresEndpointPassthrough = row.map.containsKey("api_url")
            )
        }
        val main = if (provider.isEmpty()) null else BrainMainModel(provider, model, null)
        return DecodedCatalog(main, providers)
    }

    private fun decodeInfo(bytes: ByteArray, main: BrainMainModel?): BrainModelMetadata? {
        val root = parseObject(bytes)
        val provider = requiredString(root, "provider", allowEmpty = true)
        val model = requiredString(root, "model", allowEmpty = true)
        if (main == null) {
            if (provider.isNotEmpty() || model.isNotEmpty()) throw BrainModelMalformedException()
        } else if (provider != main.provider || model != main.model) {
            throw BrainModelMalformedException()
        }
        val name = optionalString(root, "name")
        val slug = optionalString(root, "slug")
        val supportsReasoning = optionalBoolean(root.map, "supports_reasoning")
        return if (main == null) null else BrainModelMetadata(name, slug, supportsReasoning)
    }

    private fun parseCapabilities(value: com.hermes.bot.data.gateway.json.JsonValue?, models: List<String>): Map<String, BrainModelCapability> {
        if (value == null) return emptyMap()
        val map = (value as? JsonObject)?.map ?: throw BrainModelMalformedException()
        return models.mapNotNull { model ->
            val raw = map[model] ?: return@mapNotNull null
            val capability = (raw as? JsonObject)?.map ?: throw BrainModelMalformedException()
            BrainModelCapability(
                optionalBoolean(capability, "reasoning"),
                optionalBoolean(capability, "can_disable_reasoning")
            ).let { model to it }
        }.toMap()
    }

    private fun parseObject(bytes: ByteArray): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw BrainModelMalformedException()
        }
        return (GatewayJson.parseOrNull(text) as? JsonObject) ?: throw BrainModelMalformedException()
    }

    private fun requiredArray(root: JsonObject, key: String): JsonArray =
        (root[key] as? JsonArray) ?: throw BrainModelMalformedException()

    private fun requiredString(root: JsonObject, key: String, allowEmpty: Boolean = false): String {
        val value = root[key] ?: throw BrainModelMalformedException()
        val string = (value as? JsonString)?.value ?: throw BrainModelMalformedException()
        if (string.isEmpty()) {
            if (allowEmpty) return string
            throw BrainModelMalformedException()
        }
        if ((!allowEmpty && string.isBlank()) || !string.isSafeText(512)) throw BrainModelMalformedException()
        return string
    }

    private fun optionalString(root: JsonObject, key: String): String? {
        if (!root.map.containsKey(key)) return null
        return requiredString(root, key, allowEmpty = true).takeIf { it.isNotEmpty() }
    }

    private fun requiredBoolean(root: JsonObject, key: String): Boolean =
        (root[key] as? JsonBoolean)?.value ?: throw BrainModelMalformedException()

    private fun optionalBoolean(root: Map<String, com.hermes.bot.data.gateway.json.JsonValue>, key: String): Boolean? {
        if (!root.containsKey(key)) return null
        return (root[key] as? JsonBoolean)?.value ?: throw BrainModelMalformedException()
    }

    private fun isValidProfile(value: String): Boolean =
        PROFILE_REGEX.matches(value) && value !in RESERVED_PROFILES

    private fun String.isSafeText(maxLength: Int): Boolean =
        isNotEmpty() && length <= maxLength && none { it.isISOControl() }

    internal fun requestPath(endpoint: String, profile: String): String {
        val url = "http://model-read.invalid$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("profile", profile).build()
        return url.encodedPath + "?" + url.encodedQuery
    }

    private data class DecodedCatalog(val main: BrainMainModel?, val providers: List<BrainModelProvider>)

    private fun capturedUnavailable(
        reason: BrainModelReadFailure,
        target: BrainModelCapturedTarget,
        receipt: AuthCredentialLease?
    ): BrainModelCapturedReadResult =
        BrainModelCapturedReadResult.Unavailable(reason, target, receipt)

    private class BrainModelMalformedException : IllegalArgumentException()

    companion object {
        const val OPTIONS_PATH = "/api/model/options"
        const val INFO_PATH = "/api/model/info"
        private val PROFILE_REGEX = Regex("[a-z0-9][a-z0-9_-]{0,63}\\z")
        private val RESERVED_PROFILES = setOf("current", "hermes", "test", "tmp", "root", "sudo")
    }
}
