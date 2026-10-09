package com.hermes.bot.bootstrap

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Supplements the real held-RPC witness; production must supply its existing shared repository. */
class SessionActivityWiringTest {
    @Test fun productionCoordinatorReceivesSharedSessionRepository() {
        val root = if (File("app/src/main").exists()) "app/src/main" else "src/main"
        val source = File("$root/java/com/hermes/bot/bootstrap/AppContainer.kt").readText()
        val coordinatorLazy = source.substringAfter("override val turnCoordinator: TurnCoordinator by lazy {")
            .substringBefore("override val reconnectManager:")
        assertTrue("Production turn writes must reach the real session repository",
            Regex("sessionRepository\\s*=\\s*sessionRepository").containsMatchIn(coordinatorLazy))
    }
}
