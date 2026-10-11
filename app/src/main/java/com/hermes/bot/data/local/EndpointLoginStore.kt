package com.hermes.bot.data.local

import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.origin.CanonicalOrigin
import java.io.File
import java.security.MessageDigest

/** Used only under EncryptedVault's mutex. Secrets never cross the repository boundary. */
internal class EndpointLoginStore(
    private val directory: File,
    private val cipher: VaultCipherEngine,
    private val write: (File, String, ByteArray) -> Unit
) {
    internal class Entry(
        val origin: String,
        val username: String? = null,
        val provider: String? = null,
        val password: String? = null,
        val remember: Boolean = true,
        val automatic: Boolean = true
    ) {
        override fun toString() = "EndpointLogin([redacted])"
    }

    private fun identity(origin: String): String {
        val parsed = CanonicalOrigin.parse(origin).getOrThrow()
        require(parsed.pathPrefix.isEmpty()) { "Saved login requires a root origin" }
        return parsed.copy(rawPort = parsed.port).originUrl
    }
    private fun file(origin: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity(origin).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "endpoint_login_$digest.enc")
    }
    private fun aad(origin: String) = "ENDPOINT_LOGIN:1:${identity(origin)}".toByteArray(Charsets.UTF_8)
    private val preferences = File(directory, "endpoint_preferences.enc")
    private val preferencesAad = "ENDPOINT_PREFERENCES:1".toByteArray(Charsets.UTF_8)

    // Do not delete unreadable bytes: absence must never enable legacy resurrection.
    private fun read(file: File, aad: ByteArray): String? {
        if (!file.exists()) return null
        check(file.length() in 1..1_048_576) { "Saved login storage unreadable" }
        val plaintext = cipher.decrypt(file.readBytes(), aad).getOrThrow()
        return try { plaintext.toString(Charsets.UTF_8) } finally { plaintext.fill(0) }
    }
    fun get(origin: String): Entry? {
        val json = read(file(origin), aad(origin)) ?: return null
        val obj = GatewayJson.parseOrNull(json)?.asObjectOrNull()
            ?: throw SecurityException("Saved login storage unreadable")
        check(obj["schemaVersion"]?.asIntOrNull() == 1 &&
            obj["origin"]?.asStringOrNull() == identity(origin)) { "Saved login identity mismatch" }
        return Entry(identity(origin), obj["username"]?.asStringOrNull(), obj["provider"]?.asStringOrNull(),
            obj["password"]?.asStringOrNull(), obj["remember"]?.asBooleanOrNull() == true,
            obj["automatic"]?.asBooleanOrNull() == true)
    }
    fun put(entry: Entry) {
        write(file(entry.origin), GatewayJson.serialize(GatewayJson.fromAny(linkedMapOf(
            "schemaVersion" to 1, "origin" to identity(entry.origin), "username" to entry.username,
            "provider" to entry.provider, "password" to entry.password,
            "remember" to entry.remember, "automatic" to entry.automatic
        ))), aad(entry.origin))
    }
    fun suppress(origin: String) {
        val old = get(origin) ?: Entry(origin)
        put(Entry(origin, old.username, old.provider, old.password, old.remember, automatic = false))
    }
    fun forget(origin: String) = put(Entry(origin, remember = false, automatic = false))
    fun reject(origin: String) {
        val old = get(origin) ?: return
        put(Entry(origin, old.username, old.provider, remember = old.remember, automatic = old.automatic))
    }
    private fun successful(): List<String> {
        val json = read(preferences, preferencesAad) ?: return emptyList()
        val obj = GatewayJson.parseOrNull(json)?.asObjectOrNull()
            ?: throw SecurityException("Endpoint preferences unreadable")
        check(obj["schemaVersion"]?.asIntOrNull() == 1) { "Endpoint preferences schema unsupported" }
        return obj["origins"]?.asArrayOrNull()?.list?.map {
            identity(it.asStringOrNull() ?: throw SecurityException("Endpoint preferences unreadable"))
        } ?: throw SecurityException("Endpoint preferences unreadable")
    }
    private fun writeSuccessful(origins: List<String>) = write(preferences,
        GatewayJson.serialize(GatewayJson.fromAny(mapOf("schemaVersion" to 1, "origins" to origins))), preferencesAad)
    fun noteSuccess(origin: String) {
        val normalized = identity(origin)
        writeSuccessful(listOf(normalized) + successful().filter { it != normalized })
    }
    fun latest(host: String? = null): CanonicalOrigin? = successful().firstOrNull {
        host == null || CanonicalOrigin.parseOrThrow(it).host == host.trim().lowercase().removeSurrounding("[", "]")
    }?.let { CanonicalOrigin.parseOrThrow(it) }
    fun remove(origin: String) {
        // Tombstone first; deletion/profile mutations cannot resurrect legacy passwords.
        forget(origin)
        writeSuccessful(successful().filter { it != identity(origin) })
    }
    fun clear() {
        directory.listFiles { _, name -> name.startsWith("endpoint_login_") || name == preferences.name }
            ?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
    }
}
