package com.hermes.bot.bootstrap

import com.hermes.bot.data.bots.DefaultBotRepository
import com.hermes.bot.data.session.FakeGatewayProtocolAdapter
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BotsWiringTest {
    @Test fun factoryRetainsActualAdapterAndRepositoryIdentity() {
        val adapter = FakeGatewayProtocolAdapter()
        val repository = createBotsRepository(adapter)
        val vm = createBotsViewModel(repository)
        assertSame(adapter, repository.configuredGatewayAdapter)
        assertSame(repository, vm.configuredRepository)
    }

    @Test fun productionRootPassesBotsFactoryToShell() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java")).first { it.exists() }
        val container = root.resolve("com/hermes/bot/bootstrap/AppContainer.kt").readText()
        val activity = root.resolve("com/hermes/bot/bootstrap/MainActivity.kt").readText()
        assertTrue(container.contains("DefaultBotRepository(gatewayAdapter, vaultStorage, memory = botMemory)"))
        assertTrue(container.contains("BotsViewModel(botRepository,"))
        assertTrue(container.contains("DefaultScreenOverviewRepository(botRepository, gatewayAdapter)"))
        assertTrue(container.contains("botMemory.disconnect(gatewayAdapter.connectionGeneration); reconnectManager.clearOrigin()"))
        assertTrue(container.contains("clearMemory = { botMemory.clear() }"))
        assertTrue(container.contains("repository.connectionStatus.collect"))
        assertTrue(container.contains("botThumbnails"))
        assertTrue(activity.contains("c.createBotsViewModel()"))
        assertTrue(activity.contains("botsViewModelProvider = botsVmProvider"))
    }
}
