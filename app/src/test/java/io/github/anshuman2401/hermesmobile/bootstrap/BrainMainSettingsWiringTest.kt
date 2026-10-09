package io.github.anshuman2401.hermesmobile.bootstrap

import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrainMainSettingsWiringTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun factoryRetainsSuppliedVaultAuthAndGatewayInstances() {
        val vault = EncryptedVault(
            temp.newFolder("brain-main-wiring"),
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gateway = FakeGatewayProtocolAdapter()

        val repository = createBrainMainSettingsRepository(vault, auth, gateway)

        assertSame(vault, repository.configuredVaultStorage)
        assertSame(auth, repository.configuredAuthRepository)
        assertSame(gateway, repository.configuredGatewayAdapter)
    }
}
