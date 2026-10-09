package com.hermes.bot.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * CHG-004c: unit tests for the retry-once-on-InvalidAlgorithmParameterException path in
 * [AesGcmCipherEngine.encrypt].
 *
 * Design note: javax.crypto.Cipher.getInstance is a static JCE lookup and cannot be mocked
 * directly on the JVM. The tests instead register a test JCE provider whose CipherSpi
 * reproduces the real-device behavior of an AndroidKeyStore key created with
 * randomized-encryption-REQUIRED: it rejects a caller-supplied IV (GCMParameterSpec) with
 * InvalidAlgorithmParameterException for the "old policy" key. All accepted operations
 * delegate to a real JCE AES/GCM/NoPadding cipher, so the successful retry path exercises
 * genuine AES-GCM encryption.
 */
class AesGcmCipherEngineRetryTest {

    /** SPI simulating an old-policy keystore key that rejects caller-supplied IVs. */
    class OldPolicyKeyRejectingSpi : CipherSpi() {
        companion object {
            /** Keys whose encrypt init must fail with InvalidAlgorithmParameterException. */
            @JvmStatic
            @Volatile
            var rejectedKeys: Set<SecretKey> = emptySet()
        }

        private val delegate = Cipher.getInstance("AES/GCM/NoPadding")

        override fun engineSetMode(mode: String?) {
            // Delegate's mode parsing happens lazily on init; nothing to do here.
        }

        override fun engineSetPadding(padding: String?) {
            // Delegate's padding parsing happens lazily on init; nothing to do here.
        }

        override fun engineGetBlockSize(): Int = delegate.blockSize

        override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)

        override fun engineGetIV(): ByteArray = delegate.iv

        override fun engineGetParameters(): java.security.AlgorithmParameters? = delegate.parameters

        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
            rejectIfOldPolicyEncryptKey(opmode, key)
            delegate.init(opmode, key, random)
        }

        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
            rejectIfOldPolicyEncryptKey(opmode, key)
            delegate.init(opmode, key, params, random)
        }

        override fun engineInit(opmode: Int, key: Key?, params: java.security.AlgorithmParameters?, random: SecureRandom?) {
            rejectIfOldPolicyEncryptKey(opmode, key)
            delegate.init(opmode, key, params, random)
        }

        private fun rejectIfOldPolicyEncryptKey(opmode: Int, key: Key?) {
            if (opmode == Cipher.ENCRYPT_MODE && key is SecretKey && key in rejectedKeys) {
                // Reproduces AndroidKeyStore keys created WITHOUT
                // setRandomizedEncryptionRequired(false): caller-supplied IV rejected.
                throw InvalidAlgorithmParameterException(
                    "Caller-provided IV not permitted for key generated with randomized encryption required"
                )
            }
        }

        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray?,
            outputOffset: Int
        ): Int = delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineUpdateAAD(input: ByteArray?, inputOffset: Int, inputLen: Int) {
            delegate.updateAAD(input, inputOffset, inputLen)
        }

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            delegate.doFinal(input, inputOffset, inputLen)

        override fun engineDoFinal(
            input: ByteArray?,
            inputOffset: Int,
            inputLen: Int,
            output: ByteArray?,
            outputOffset: Int
        ): Int = delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)
    }

    private class TestCipherProvider :
        Provider("HermesTestCipherProvider", 1.0, "CHG-004c unit test cipher provider") {
        init {
            put(
                "Cipher.AES/GCM/NoPadding",
                "com.hermes.bot.data.local.AesGcmCipherEngineRetryTest\$OldPolicyKeyRejectingSpi"
            )
        }
    }

    /**
     * Fake MasterKeyProvider: getOrCreateSecretKey keeps returning the OLD key (as a keystore
     * provider would while the stale alias still exists); regenerateKey returns the new key
     * (or throws, to simulate regeneration failure).
     */
    private class FakeMasterKeyProvider(
        private val oldKey: SecretKey,
        private val regeneratedKey: SecretKey,
        private val regenerateThrows: Throwable? = null
    ) : MasterKeyProvider {
        var getOrCreateCalls = 0
            private set
        var regenerateCalls = 0
            private set

        override fun getOrCreateSecretKey(): SecretKey {
            getOrCreateCalls++
            return oldKey
        }

        override fun regenerateKey(): SecretKey {
            regenerateCalls++
            regenerateThrows?.let { throw it }
            return regeneratedKey
        }
    }

    private fun engineWithTestCipher(provider: FakeMasterKeyProvider): AesGcmCipherEngine =
        AesGcmCipherEngine(provider) { Cipher.getInstance("AES/GCM/NoPadding", TestCipherProvider()) }

    private companion object {
        fun newAesKey(): SecretKey {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            return SecretKeySpec(bytes, "AES")
        }
        val PLAINTEXT = "hermes vault record chg-004c".toByteArray()
    }

    @Test
    fun encrypt_oldPolicyKeyRejectedByKeystore_regeneratesKeyAndRetriesExactlyOnce() {
        val oldKey = newAesKey()
        val newKey = newAesKey()
        OldPolicyKeyRejectingSpi.rejectedKeys = setOf(oldKey)
        val provider = FakeMasterKeyProvider(oldKey, newKey)
        val engine = engineWithTestCipher(provider)

        val ciphertext = engine.encrypt(PLAINTEXT)

        // Retry happened exactly once, via regeneration.
        assertEquals(1, provider.getOrCreateCalls)
        assertEquals(1, provider.regenerateCalls)

        // The retried output is genuine AES-GCM under the regenerated key and decrypts back
        // (decrypt path uses the standard JCE cipher with the regenerated key).
        val standardEngine = AesGcmCipherEngine(object : MasterKeyProvider {
            override fun getOrCreateSecretKey(): SecretKey = newKey
        })
        val roundTrip = standardEngine.decrypt(ciphertext)
        assertTrue("Round-trip decrypt should succeed with the regenerated key", roundTrip.isSuccess)
        assertArrayEquals(PLAINTEXT, roundTrip.getOrThrow())
    }

    @Test
    fun encrypt_regenerationStillRejectedOrFails_throwsKeyStoreUnavailableSecurityException() {
        val oldKey = newAesKey()
        OldPolicyKeyRejectingSpi.rejectedKeys = setOf(oldKey)
        // Regeneration returns the same unusable key: retry also fails.
        val provider = FakeMasterKeyProvider(oldKey, oldKey)
        val engine = engineWithTestCipher(provider)

        try {
            engine.encrypt(PLAINTEXT)
            fail("Expected KeyStoreUnavailableException when retry after regeneration also fails")
        } catch (e: KeyStoreUnavailableException) {
            // B2 acceptance: SecurityException subtype (UI maps to security check failed).
            assertTrue("KeyStoreUnavailableException must be a SecurityException subtype", e is SecurityException)
            assertTrue(e.message!!.contains("regeneration"))
        }
        assertEquals(1, provider.getOrCreateCalls)
        assertEquals(1, provider.regenerateCalls)
    }

    @Test
    fun encrypt_providerRegenerationThrows_throwsKeyStoreUnavailableSecurityException() {
        val oldKey = newAesKey()
        OldPolicyKeyRejectingSpi.rejectedKeys = setOf(oldKey)
        val provider = FakeMasterKeyProvider(
            oldKey,
            newAesKey(),
            regenerateThrows = KeyStoreUnavailableException("keystore delete failed")
        )
        val engine = engineWithTestCipher(provider)

        try {
            engine.encrypt(PLAINTEXT)
            fail("Expected KeyStoreUnavailableException when regeneration itself fails")
        } catch (e: KeyStoreUnavailableException) {
            assertTrue("KeyStoreUnavailableException must be a SecurityException subtype", e is SecurityException)
        }
        assertEquals(1, provider.regenerateCalls)
    }

    @Test
    fun encrypt_healthyKey_neverRegenerates() {
        val healthyKey = newAesKey()
        OldPolicyKeyRejectingSpi.rejectedKeys = emptySet()
        val provider = FakeMasterKeyProvider(healthyKey, newAesKey())
        val engine = engineWithTestCipher(provider)

        val ciphertext = engine.encrypt(PLAINTEXT, "aad-binding".toByteArray())

        assertEquals(0, provider.regenerateCalls)
        assertArrayEquals(PLAINTEXT, engine.decrypt(ciphertext, "aad-binding".toByteArray()).getOrThrow())
    }
}
