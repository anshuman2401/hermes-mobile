package com.hermes.bot.bootstrap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.hermes.bot.feature.connect.ConnectViewModel
import com.hermes.bot.feature.sessions.SessionsViewModel
import com.hermes.bot.feature.sessions.SessionsViewModelTest
import com.hermes.bot.test.MainDispatcherRule
import org.junit.*
import org.junit.Assert.*
import java.lang.reflect.Proxy
import java.io.File

class StartupViewModelFactoryTest {
    @get:Rule val main = MainDispatcherRule()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    @After fun close() { store.clear() }

    @Test fun activityStoreRetainsBothStartupOwnersAcrossCompositionRecreation() {
        var connects = 0
        var sessions = 0
        val repo = SessionsViewModelTest.FakeSessionRepository()
        val container = Proxy.newProxyInstance(AppContainer::class.java.classLoader, arrayOf(AppContainer::class.java)) { _, method, args ->
            when (method.name) {
                "createConnectViewModel" -> { connects++; ConnectViewModel(args!![0] as SavedStateHandle) }
                "createSessionsViewModel" -> { sessions++; SessionsViewModel(repo, args!![0] as SavedStateHandle, deferInitialLoad = true) }
                else -> error("Unexpected startup dependency")
            }
        } as AppContainer
        fun provider() = ViewModelProvider(owner, startupViewModelFactory(container) { SavedStateHandle() })
        val first = provider()
        val connection = first[ConnectViewModel::class.java]
        val list = first[SessionsViewModel::class.java]
        repeat(3) {
            val rotated = provider()
            assertSame(connection, rotated[ConnectViewModel::class.java])
            assertSame(list, rotated[SessionsViewModel::class.java])
        }
        assertEquals(1, connects)
        assertEquals(1, sessions)
        assertEquals(0, repo.listSessionsCallCount)
    }

    @Test fun rootUsesTheRetainedFactoryForBothOwnersAndNeutralStartupGate() {
        val root = File("src/main/java/com/hermes/bot/bootstrap/MainActivity.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/hermes/bot/bootstrap/MainActivity.kt")
        val source = root.readText()
        assertTrue(source.contains("connectViewModel ?: viewModel(factory = startupFactory)"))
        assertTrue(source.contains("if (container != null) viewModel(factory = startupFactory) else null"))
        assertTrue(source.contains("StartupPresentation.LOADING"))
        assertTrue(source.contains("StartupPresentation.STORAGE_UNAVAILABLE"))
        assertTrue(source.indexOf("StartupScreen(") < source.indexOf("HermesNavShell("))
    }
}
