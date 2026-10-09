package io.github.anshuman2401.hermesmobile.data.local

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM design test proving fail-closed boundaries of the Android KeyStore abstraction
 * per ADR-0003 and RPR-003.
 *
 * Enforces:
 * - When AndroidKeyStore is unavailable (as on standard off-device JVM),
 *   AndroidKeyStoreMasterKeyProvider strictly fails closed by throwing KeyStoreUnavailableException.
 * - Zero fallback to software KeyGenerator or in-memory keys in production code.
 */
class AndroidKeyStoreFailClosedJvmTest {

    @Test
    fun androidKeyStoreMasterKeyProvider_onJvmWithoutKeyStore_failsClosed() {
        val provider = AndroidKeyStoreMasterKeyProvider()

        try {
            provider.getOrCreateSecretKey()
            org.junit.Assert.fail("Expected KeyStoreUnavailableException when AndroidKeyStore is unavailable")
        } catch (e: Throwable) {
            assertTrue(
                "Expected KeyStoreUnavailableException or SecurityException, got ${e::class.java.name}",
                e is KeyStoreUnavailableException || e is SecurityException
            )
            assertNotNull(e.message)
            assertTrue(
                "Error message should mention AndroidKeyStore unavailability",
                e.message!!.contains("AndroidKeyStore") || e.message!!.contains("unavailable")
            )
        }
    }
}
