package io.github.anshuman2401.hermesmobile.bootstrap

import io.github.anshuman2401.hermesmobile.data.bots.DefaultBotRepository
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
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
        val container = root.resolve("io/github/anshuman2401/hermesmobile/bootstrap/AppContainer.kt").readText()
        val activity = root.resolve("io/github/anshuman2401/hermesmobile/bootstrap/MainActivity.kt").readText()
        assertTrue(container.contains("createBotsRepository(gatewayAdapter, vaultStorage)"))
        assertTrue(container.contains("createBotsViewModel(botRepository,"))
        assertTrue(container.contains("DefaultScreenOverviewRepository(botRepository, gatewayAdapter)"))
        assertTrue(activity.contains("c.createBotsViewModel()"))
        assertTrue(activity.contains("botsViewModelProvider = botsVmProvider"))
    }
}
