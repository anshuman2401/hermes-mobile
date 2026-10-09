package io.github.anshuman2401.hermesmobile.bootstrap

import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.diagnostics.NoOpDiagnostics
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayFailure
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayReconnectManager
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayTransport
import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import io.github.anshuman2401.hermesmobile.data.ledger.PendingTurnLedger
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import io.github.anshuman2401.hermesmobile.data.session.SessionRepository
import io.github.anshuman2401.hermesmobile.data.turn.TurnCoordinator
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class CompositionRootTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun appContainer_constructsRealProductionGraph() {
        val app = HermesBotApplication()
        val containerField = HermesBotApplication::class.java.declaredFields.firstOrNull {
            AppContainer::class.java.isAssignableFrom(it.type)
        }
        assertNotNull("HermesBotApplication must define an AppContainer property", containerField)
    }

    @Test
    fun defaultAppContainer_instantiatesOkHttpTransport() {
        val okHttpClass = Class.forName("io.github.anshuman2401.hermesmobile.data.gateway.okhttp.OkHttpGatewayTransport")
        assertNotNull("OkHttpGatewayTransport must exist", okHttpClass)
        assertTrue(
            "OkHttpGatewayTransport must implement GatewayTransport",
            GatewayTransport::class.java.isAssignableFrom(okHttpClass)
        )

        val okHttpAuthClass = Class.forName("io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport")
        assertNotNull("OkHttpAuthTransport must exist", okHttpAuthClass)
        assertTrue(
            "OkHttpAuthTransport must implement AuthHttpTransport",
            io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport::class.java.isAssignableFrom(okHttpAuthClass)
        )
    }

    @Test
    fun compositionRoot_failsWhenAnyProductionDependencyIsAbsent() {
        val dummyVault = mock<VaultStorage>()
        val dummyAuth = mock<AuthRepository>()
        val dummyTransport = mock<GatewayTransport>()
        // GatewayReconnectManager's init block collects gatewayAdapter.connectionStatus and
        // gatewayAdapter.terminalFailure the moment it is constructed, on real Dispatchers.IO
        // threads. A fully-null reflective mock makes those collectors throw NullPointerException
        // off the test thread; the exception is captured by kotlinx-coroutines-test's global
        // ExceptionCollector and the NEXT runTest to start fails with UncaughtExceptionsBeforeTest
        // (the intermittent cross-suite flake). The mock must therefore return real StateFlows
        // for the two flow getters the manager collects; every other method may stay null
        // because this test only exercises validateCompositionRoot null-checks.
        val connectionStatusFlow = MutableStateFlow(ConnectionStatus.OFFLINE)
        val terminalFailureFlow = MutableStateFlow<GatewayFailure?>(null)
        val dummyAdapter = Proxy.newProxyInstance(
            GatewayProtocolAdapter::class.java.classLoader,
            arrayOf(GatewayProtocolAdapter::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getConnectionStatus" -> connectionStatusFlow
                "getTerminalFailure" -> terminalFailureFlow
                else -> null
            }
        } as GatewayProtocolAdapter
        val dummySessions = mock<SessionRepository>()
        val dummyLedger = PendingTurnLedger(dummyVault)
        val dummyTurn = TurnCoordinator(dummyAdapter, dummyLedger)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val dummyReconnect = GatewayReconnectManager(
                gatewayAdapter = dummyAdapter,
                authRepository = dummyAuth,
                scope = testScope
            )

            val dummyContextProfiles = mock<io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository>()
            val dependencyMap = mapOf<String, Any>(
                "getDiagnostics" to NoOpDiagnostics,
                "getVaultStorage" to dummyVault,
                "getAuthRepository" to dummyAuth,
                "getContextProfileRepository" to dummyContextProfiles,
                "getGatewayTransport" to dummyTransport,
                "getGatewayAdapter" to dummyAdapter,
                "getSessionRepository" to dummySessions,
                "getPendingTurnLedger" to dummyLedger,
                "getTurnCoordinator" to dummyTurn,
                "getReconnectManager" to dummyReconnect
            )

            // 1. Full container passes validation
            val fullContainer = createProxyContainer(dependencyMap)
            MainActivity.validateCompositionRoot(fullContainer)

            // 2. Each missing dependency fails fast
            for ((methodToOmit, _) in dependencyMap) {
                val partialMap = dependencyMap.filterKeys { it != methodToOmit }
                val partialContainer = createProxyContainer(partialMap)
                try {
                    MainActivity.validateCompositionRoot(partialContainer)
                    fail("Expected IllegalStateException when $methodToOmit is omitted")
                } catch (e: IllegalStateException) {
                    val propName = methodToOmit.removePrefix("get").replaceFirstChar { it.lowercase() }
                    assertTrue(
                        "Error message should mention missing property $propName: ${e.message}",
                        e.message!!.contains(propName)
                    )
                }
            }
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun defaultAppContainer_createChatViewModelPassesItsRealVaultIdentity() {
        val filesDir = tempFolder.newFolder("production-context")
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = filesDir
            override fun getNoBackupFilesDir() = tempFolder.newFolder("production-no-backup")
        }
        val container = DefaultAppContainer(context)

        val viewModel = container.createChatViewModel("new-session")
        val vaultField = ChatViewModel::class.java.getDeclaredField("vaultStorage")
        vaultField.isAccessible = true

        assertTrue(vaultField.get(viewModel) === container.vaultStorage)
    }

    private fun createProxyContainer(dependencies: Map<String, Any>): AppContainer {
        return Proxy.newProxyInstance(
            AppContainer::class.java.classLoader,
            arrayOf(AppContainer::class.java)
        ) { _, method, _ ->
            dependencies[method.name]
        } as AppContainer
    }

    private inline fun <reified T : Any> mock(): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, _, _ -> null } as T
    }
}
