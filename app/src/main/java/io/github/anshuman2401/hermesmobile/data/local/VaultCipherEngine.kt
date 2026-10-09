package io.github.anshuman2401.hermesmobile.data.local

import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Exception thrown when Android Keystore is unavailable, uninitialized, or invalidated.
 * Enforces fail-closed security policy per ADR-0003 and RPR-003.
 */
class KeyStoreUnavailableException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

/**
 * Interface for cryptographic encryption and decryption of local vault records.
 * Supports AES-GCM Associated Authenticated Data (AAD) to cryptographically bind
 * record type, schema version, record ID, and origin.
 */
interface VaultCipherEngine {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray
    fun decrypt(ciphertextWithIv: ByteArray, associatedData: ByteArray? = null): Result<ByteArray>
}

/**
 * Provider interface for obtaining the master secret key.
 */
interface MasterKeyProvider {
    fun getOrCreateSecretKey(): SecretKey

    /**
     * Delete and recreate the master key with the current key-generation policy.
     * Used by [AesGcmCipherEngine] when an existing keystore key was created under an
     * incompatible policy (e.g. randomized-encryption-required rejecting caller IVs) and
     * all vault contents encrypted under it are unreadable anyway.
     *
     * Default: same as [getOrCreateSecretKey] (no-op regeneration for non-keystore providers).
     */
    fun regenerateKey(): SecretKey = getOrCreateSecretKey()
}

/**
 * In-memory / JCE master key provider for deterministic unit testing.
 */
class InMemoryMasterKeyProvider(
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
) : MasterKeyProvider {
    override fun getOrCreateSecretKey(): SecretKey = key
}

/**
 * Direct Android Keystore backed master key provider.
 * Strictly fails closed: zero fallback to software or random-key generation in production.
 */
class AndroidKeyStoreMasterKeyProvider(
    private val keyAlias: String = "hermes_bot_vault_master_key"
) : MasterKeyProvider {

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }

    override fun getOrCreateSecretKey(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (e: Throwable) {
            throw KeyStoreUnavailableException("AndroidKeyStore provider unavailable: ${e.message}", e)
        }

        return try {
            if (keyStore.containsAlias(keyAlias)) {
                val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                }
            }

            val keyGenerator = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
            )
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                keyAlias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // CHG-004c: the engine always supplies its own SecureRandom 12-byte IV.
                // Keystore-mandated IV generation would make AES-GCM keys reject caller-provided
                // IVs with InvalidAlgorithmParameterException on real devices.
                .setRandomizedEncryptionRequired(false)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: KeyStoreUnavailableException) {
            throw e
        } catch (e: Throwable) {
            throw KeyStoreUnavailableException("Failed to access or generate key in AndroidKeyStore: ${e.message}", e)
        }
    }

    /**
     * CHG-004c: delete the existing alias and regenerate the key with the current policy
     * (including [android.security.keystore.KeyGenParameterSpec.Builder.setRandomizedEncryptionRequired(false)]).
     * Existing vault records encrypted under the old key are unreadable regardless, so
     * deleting it does not lose recoverable data.
     */
    override fun regenerateKey(): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (e: Throwable) {
            throw KeyStoreUnavailableException("AndroidKeyStore provider unavailable: ${e.message}", e)
        }

        try {
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (e: Throwable) {
            throw KeyStoreUnavailableException("Failed to delete stale AndroidKeyStore key: ${e.message}", e)
        }

        return getOrCreateSecretKey()
    }
}

/**
 * Standard AES-256-GCM cipher engine (12-byte IV, 128-bit authentication tag).
 * Enforces authenticated encryption with associated data (AEAD).
 *
 * CHG-004c: if an existing keystore master key was generated under an incompatible policy
 * (e.g. randomized-encryption-required, which rejects the caller-supplied IV used here) the
 * first encrypt attempt throws [InvalidAlgorithmParameterException]. In that case the engine
 * regenerates the master key via [MasterKeyProvider.regenerateKey] and retries encrypt exactly
 * once; existing vault contents encrypted under the old key are unreadable anyway when the
 * key changes. A persistent failure surfaces as [KeyStoreUnavailableException] (a
 * SecurityException subtype), which the UI maps to "security check failed".
 *
 * @param cipherFactory pluggable [Cipher] factory; defaults to the standard JCE lookup.
 *   Exists solely so JVM unit tests can inject a controllable fake cipher path.
 */
class AesGcmCipherEngine(
    private val masterKeyProvider: MasterKeyProvider,
    private val cipherFactory: (String) -> Cipher = { algorithm -> Cipher.getInstance(algorithm) }
) : VaultCipherEngine {

    companion object {
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
    }

    private val secureRandom = SecureRandom()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        return try {
            encryptWithCurrentKey(plaintext, associatedData)
        } catch (e: InvalidAlgorithmParameterException) {
            // CHG-004c: keystore key likely predates the randomized-encryption policy fix.
            // Regenerate and retry exactly once.
            try {
                val regeneratedKey = masterKeyProvider.regenerateKey()
                encryptWithKey(regeneratedKey, plaintext, associatedData)
            } catch (retryFailure: Throwable) {
                throw KeyStoreUnavailableException(
                    "Vault master key rejected the cipher IV and regeneration retry failed: ${retryFailure.message}",
                    retryFailure
                )
            }
        }
    }

    private fun encryptWithCurrentKey(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        val key = masterKeyProvider.getOrCreateSecretKey()
        return encryptWithKey(key, plaintext, associatedData)
    }

    private fun encryptWithKey(key: SecretKey, plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = cipherFactory(CIPHER_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        val encrypted = cipher.doFinal(plaintext)

        // Output format: [12-byte IV] + [encrypted ciphertext with auth tag]
        val result = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)
        return result
    }

    override fun decrypt(ciphertextWithIv: ByteArray, associatedData: ByteArray?): Result<ByteArray> {
        if (ciphertextWithIv.size <= GCM_IV_LENGTH_BYTES) {
            return Result.failure(IllegalArgumentException("Ciphertext payload too short"))
        }

        return try {
            val key = masterKeyProvider.getOrCreateSecretKey()
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            System.arraycopy(ciphertextWithIv, 0, iv, 0, GCM_IV_LENGTH_BYTES)

            val encryptedSize = ciphertextWithIv.size - GCM_IV_LENGTH_BYTES
            val encrypted = ByteArray(encryptedSize)
            System.arraycopy(ciphertextWithIv, GCM_IV_LENGTH_BYTES, encrypted, 0, encryptedSize)

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            if (associatedData != null) {
                cipher.updateAAD(associatedData)
            }
            val decrypted = cipher.doFinal(encrypted)
            Result.success(decrypted)
        } catch (e: GeneralSecurityException) {
            Result.failure(SecurityException("Vault record corrupted, swapped, or key invalidated", e))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
