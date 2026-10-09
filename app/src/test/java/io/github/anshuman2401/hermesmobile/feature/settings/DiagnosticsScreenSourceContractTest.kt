package io.github.anshuman2401.hermesmobile.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Slice S2 source-contract guard:
 * Verifies that the Diagnostics UI and ViewModel read only from the DiagnosticRing
 * and do not introduce direct coupling to transports or repositories.
 */
class DiagnosticsScreenSourceContractTest {

    private val screenFile = File("src/main/java/io/github/anshuman2401/hermesmobile/feature/settings/DebugLogsScreen.kt").let {
        if (it.exists()) it else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/settings/DebugLogsScreen.kt")
    }

    private val vmFile = File("src/main/java/io/github/anshuman2401/hermesmobile/feature/settings/DebugLogsViewModel.kt").let {
        if (it.exists()) it else File("app/src/main/java/io/github/anshuman2401/hermesmobile/feature/settings/DebugLogsViewModel.kt")
    }

    @Test
    fun sourceFiles_existAndAreReadable() {
        assertTrue("DebugLogsScreen.kt must exist", screenFile.exists())
        assertTrue("DebugLogsViewModel.kt must exist", vmFile.exists())
    }

    @Test
    fun screenAndViewModel_doNotDirectlyAccessTransportsOrRepositories() {
        val screenContent = screenFile.readText()
        val vmContent = vmFile.readText()

        val forbiddenReferences = listOf(
            "io.github.anshuman2401.hermesmobile.data.auth.AuthRepository",
            "io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport",
            "io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter",
            "io.github.anshuman2401.hermesmobile.data.gateway.DefaultGatewayProtocolAdapter",
            "io.github.anshuman2401.hermesmobile.data.session.SessionRepository",
            "io.github.anshuman2401.hermesmobile.data.session.DefaultSessionRepository",
            "io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository",
            "io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository"
        )

        for (forbidden in forbiddenReferences) {
            assertFalse(
                "DebugLogsScreen.kt must not reference $forbidden",
                screenContent.contains(forbidden)
            )
            assertFalse(
                "DebugLogsViewModel.kt must not reference $forbidden",
                vmContent.contains(forbidden)
            )
        }
    }

    @Test
    fun viewModel_readsFromDiagnosticRingViaPolicy() {
        val vmContent = vmFile.readText()
        assertTrue("DebugLogsViewModel must reference DiagnosticRing", vmContent.contains("DiagnosticRing"))
        assertTrue("DebugLogsViewModel must reference DiagnosticsScreenPolicy", vmContent.contains("DiagnosticsScreenPolicy"))
    }

    @Test
    fun screen_providesCopyShareAndRefreshActions() {
        val screenContent = screenFile.readText()
        assertTrue("DebugLogsScreen must contain Copy diagnostics", screenContent.contains("Copy diagnostics"))
        assertTrue("DebugLogsScreen must contain Share", screenContent.contains("Share"))
        assertTrue("DebugLogsScreen must contain Refresh", screenContent.contains("Refresh"))
        assertTrue("DebugLogsScreen must display reportPreview", screenContent.contains("reportPreview"))
    }
}
