package com.hermes.bot.bootstrap

import com.hermes.bot.data.auth.DefaultAuthRepository
import com.hermes.bot.data.auth.OkHttpAuthTransport
import com.hermes.bot.data.local.AesGcmCipherEngine
import com.hermes.bot.data.local.EncryptedVault
import com.hermes.bot.data.local.InMemoryMasterKeyProvider
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrainAuxiliaryModelsWiringTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun productionFactoryRetainsTheSuppliedVaultAuthAndGatewayResources() {
        val vault = EncryptedVault(
            temp.newFolder("brain-auxiliary-wiring"),
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        val auth = DefaultAuthRepository(vault, OkHttpAuthTransport())
        val gateway = FakeGatewayProtocolAdapter()

        val repository = createBrainAuxiliaryModelsRepository(vault, auth, gateway)

        assertSame(vault, repository.configuredVaultStorage)
        assertSame(auth, repository.configuredAuthRepository)
        assertSame(gateway, repository.configuredGatewayAdapter)
    }
}
