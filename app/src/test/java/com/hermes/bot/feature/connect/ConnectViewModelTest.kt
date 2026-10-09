package com.hermes.bot.feature.connect

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectViewModelTest {
    @Test
    fun defaultsAreHttpHermesBlankHostAndPort9119() {
        val state = ConnectViewModel().uiState.value
        assertEquals("Hermes", state.serverNameDraft)
        assertEquals("", state.hostDraft)
        assertEquals("9119", state.portDraft)
        assertEquals("", state.origin)
    }

    @Test
    fun draftsSurviveRecreationAndEachFieldClearsOnlyItsOwnError() {
        val handle = SavedStateHandle()
        val viewModel = ConnectViewModel(handle)
        viewModel.updateHost("bad host")
        viewModel.updatePort("0")
        viewModel.testConnection()
        assertTrue(viewModel.uiState.value.hostError != null)
        viewModel.updateHost("hermes.local")
        assertNull(viewModel.uiState.value.hostError)
        assertEquals("0", viewModel.uiState.value.portDraft)

        val recreated = ConnectViewModel(handle)
        assertEquals("hermes.local", recreated.uiState.value.hostDraft)
        assertEquals("0", recreated.uiState.value.portDraft)
    }

    @Test
    fun invalidSubmissionStaysEditableAndDoesNotEnterNetworkState() {
        val viewModel = ConnectViewModel()
        viewModel.updateHost("http://host")
        viewModel.testConnection()
        assertEquals(ConnectState.FIRST_LAUNCH, viewModel.uiState.value.state)
        assertTrue(viewModel.uiState.value.hostError != null)
    }

    @Test
    fun sevenStatesRemainAvailableThroughInternalAutomatedSeam() {
        val viewModel = ConnectViewModel()
        ConnectState.entries.forEach {
            viewModel.transitionForTest(it)
            assertEquals(it, viewModel.uiState.value.state)
        }
    }

    @Test
    fun serverNameCannotChangeDerivedEndpoint() {
        val first = ConnectUiState(serverNameDraft = "Hermes", hostDraft = "hermes.local", portDraft = "9119")
        val second = first.copy(serverNameDraft = "Different local label")
        assertEquals(first.origin, second.origin)
    }

    @Test
    fun serverName_acceptsBlankAndBoundedOrdinaryLocalLabels() {
        val viewModel = ConnectViewModel()

        viewModel.updateServerName("")
        assertEquals("", viewModel.uiState.value.serverNameDraft)
        assertNull(viewModel.uiState.value.errorMessage)

        val boundedLabel = "H".repeat(64)
        viewModel.updateServerName(boundedLabel)
        assertEquals(boundedLabel, viewModel.uiState.value.serverNameDraft)
        assertNull(viewModel.uiState.value.errorMessage)

        viewModel.updateServerName("H".repeat(65))
        assertEquals(boundedLabel, viewModel.uiState.value.serverNameDraft)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("64"))
    }

    @Test
    fun serverName_rejectsControlAndBidirectionalCharactersWithoutChangingEndpoint() {
        val viewModel = ConnectViewModel()
        viewModel.updateHost("hermes.local")
        viewModel.updatePort("9119")
        viewModel.updateServerName("Hermes")
        val endpointBeforeRejection = viewModel.uiState.value.origin

        viewModel.updateServerName("Hermes\u0000Admin")
        assertEquals("Hermes", viewModel.uiState.value.serverNameDraft)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("control"))
        assertEquals(endpointBeforeRejection, viewModel.uiState.value.origin)

        viewModel.updateServerName("Hermes\u202EAdmin")
        assertEquals("Hermes", viewModel.uiState.value.serverNameDraft)
        assertTrue(viewModel.uiState.value.errorMessage!!.contains("bidirectional"))
        assertEquals(endpointBeforeRejection, viewModel.uiState.value.origin)
    }

    @Test
    fun restoredSavedStateHostileServerNamesAreReplacedBeforeUiState() {
        listOf(
            "H".repeat(ConnectViewModel.MAX_SERVER_NAME_LENGTH + 1),
            "Hermes\u0085Admin",
            "Hermes\u202EAdmin"
        ).forEach { hostileName ->
            val handle = SavedStateHandle(mapOf(ConnectViewModel.KEY_SERVER_NAME to hostileName))

            val restored = ConnectViewModel(handle)

            assertEquals("Hermes", restored.uiState.value.serverNameDraft)
            assertTrue(restored.uiState.value.errorMessage!!.startsWith("Server name"))
            assertEquals("Hermes", handle[ConnectViewModel.KEY_SERVER_NAME])
        }
    }

    @Test
    fun restoredSavedStateBlankAndOrdinaryServerNamesRemainLocalLabels() {
        listOf("", "Office gateway").forEach { savedName ->
            val restored = ConnectViewModel(SavedStateHandle(mapOf(ConnectViewModel.KEY_SERVER_NAME to savedName)))

            assertEquals(savedName, restored.uiState.value.serverNameDraft)
            assertNull(restored.uiState.value.errorMessage)
        }
    }
}
