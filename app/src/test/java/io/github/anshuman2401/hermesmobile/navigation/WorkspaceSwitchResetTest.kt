package io.github.anshuman2401.hermesmobile.navigation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.github.anshuman2401.hermesmobile.data.context.DefaultContextProfileRepository
import io.github.anshuman2401.hermesmobile.data.local.*
import io.github.anshuman2401.hermesmobile.feature.chat.ChatViewModel
import io.github.anshuman2401.hermesmobile.feature.chat.StoredChatFixture
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSwitchResetTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun resetClearsEveryMaterializedChildBeforeAuthoritativeSelectedWrite() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { fixture ->
            fixture.initialize()
            val children = mutableListOf<ChatViewModel>()
            val owner = ChatRouteOwnerViewModel { fixture.vm().also(children::add) }
            owner.get("synthetic-visible"); owner.get("synthetic-untracked")
            val jobs = children.map { it.viewModelScope.coroutineContext[Job]!! }
            var reset = 0
            val vault = object : VaultStorage by fixture.vault {
                override suspend fun saveSelectedContextProfile(name: String) {
                    assertTrue(owner.activeKeys().isEmpty())
                    assertTrue(jobs.all { it.isCancelled })
                    assertEquals(1, reset)
                    fixture.vault.saveSelectedContextProfile(name)
                }
            }
            performWorkspaceSelection("B", DefaultContextProfileRepository(vault), owner) { reset++ }
            assertEquals("B", fixture.vault.getSelectedContextProfile())
            withTimeout(5000) { jobs.joinAll() }
            assertTrue(owner.activeKeys().isEmpty())
        }
    }

    @Test fun sameWorkspaceAndMalformedTargetAreRejectedBeforeAnyDestructiveReset() = runBlocking(main.testDispatcher) {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        vault.saveSelectedContextProfile("A")
        var providers = 0
        val owner = ChatRouteOwnerViewModel { providers++; ChatViewModel(it) }
        owner.get("synthetic-selected")
        var reset = 0
        try {
            val context = DefaultContextProfileRepository(vault)
            performWorkspaceSelection("A", context, owner) { reset++ }
            performWorkspaceSelection("../invalid", context, owner) { reset++ }
            assertEquals(0, reset)
            assertEquals(listOf("synthetic-selected"), owner.activeKeys())
            assertEquals(1, providers)
            assertEquals("A", vault.getSelectedContextProfile())
        } finally { owner.activeKeys().forEach(owner::clear) }
    }
    @Test fun bothLayoutsAllRoutesHaveNoHiddenFactoryUntilExplicitRowOpenThenScopedResumeOnce() = runBlocking(main.testDispatcher) {
        StoredChatFixture(temp.newFolder(), main.testDispatcher).use { fixture ->
            fixture.initialize()
            var providers = 0
            val owner = ChatRouteOwnerViewModel { key ->
                assertEquals("synthetic-session", key)
                providers++; fixture.vm()
            }
            var selection = false; var key = ""; var generation = 0L
            val oldIntent = generation
            val oldOpen = workspaceOpenCallback({ workspaceActionIsCurrent(oldIntent, generation, true) }) { id -> key = id; selection = true }
            try {
                for (expanded in listOf(false, true)) for (screen in ShellScreen.entries) for (chatsTab in listOf(false, true)) {
                    repeat(2) {
                        assertNull(selectedChatForDisplay(owner, selection, screen, expanded, chatsTab, "synthetic-session"))
                        assertTrue(owner.activeKeys().isEmpty())
                    }
                }
                assertEquals(0, providers); assertTrue(fixture.resumeParams.isEmpty())
                val context = DefaultContextProfileRepository(fixture.vault)
                performWorkspaceSelection("B", context, owner) { generation++; selection = false; key = "" }
                performWorkspaceSelection("default", context, owner) { generation++; selection = false; key = "" }
                // A captured old row action cannot reopen after transition flags clear.
                oldOpen("synthetic-session")
                assertFalse(selection); assertEquals("", key)
                assertEquals(0, providers); assertTrue(owner.activeKeys().isEmpty())
                val currentIntent = generation
                var actionsEnabled = false
                val open = workspaceOpenCallback({ workspaceActionIsCurrent(currentIntent, generation, actionsEnabled) }) { id -> key = id; selection = true }
                open("synthetic-session")
                assertFalse(selection); assertEquals("", key); assertEquals(0, providers)
                assertTrue(owner.activeKeys().isEmpty()); assertTrue(fixture.resumeParams.isEmpty())
                actionsEnabled = true
                open("synthetic-session")
                assertTrue(selection); assertEquals("synthetic-session", key)
                val vm = selectedChatForDisplay(owner, selection, ShellScreen.CHAT, false, true, key)!!
                fixture.awaitLoaded(vm)
                assertEquals(1, providers); assertEquals(1, fixture.resumeParams.size)
                assertEquals("default", (fixture.resumeParams.single()["profile"] as io.github.anshuman2401.hermesmobile.data.gateway.json.JsonString).value)
                assertSame(vm, selectedChatForDisplay(owner, selection, ShellScreen.SESSIONS, true, true, "synthetic-session"))
                assertNull(selectedChatForDisplay(owner, selection, ShellScreen.SESSIONS, false, true, "synthetic-session"))
                assertEquals(1, providers)
                performWorkspaceSelection("B", context, owner) { generation++; selection = false; key = "" }
                assertEquals(3L, generation)
                // Repeated confirmation of the same authoritative target is a no-op.
                performWorkspaceSelection("B", context, owner) { generation++; selection = false; key = "" }
                assertEquals(3L, generation)
                open("synthetic-session")
                assertFalse(selection); assertEquals("", key); assertEquals(1, providers)
                assertTrue(owner.activeKeys().isEmpty())
                assertNull(selectedChatForDisplay(owner, selection, ShellScreen.CHAT, true, true, "synthetic-session"))
            } finally { owner.activeKeys().forEach(owner::clear) }
        }
    }

    @Test fun vetoAndUnavailableAuthorityLeaveExistingOwnerAndCancellationClearsTransition() = runBlocking(main.testDispatcher) {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        vault.saveSelectedContextProfile("A")
        val owner = ChatRouteOwnerViewModel { ChatViewModel(it) }
        owner.get("synthetic-selected")
        var reset = 0; var transition = false
        try {
            val context = DefaultContextProfileRepository(vault)
            performWorkspaceSelection("B", context, owner, canSwitch = { false }) { reset++ }
            performWorkspaceSelection("B", null, owner) { reset++ }
            val cancelled = object : io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository by context {
                override suspend fun readSelectedProfileAuthoritative(): SelectedContextReadResult = throw CancellationException("synthetic cancel")
            }
            try {
                performWorkspaceSelection("B", cancelled, owner, onTransition = { transition = it }) { reset++ }
                fail("cancellation must propagate")
            } catch (_: CancellationException) { }
            assertFalse(transition); assertEquals(0, reset)
            assertEquals(listOf("synthetic-selected"), owner.activeKeys())
            assertEquals("A", vault.getSelectedContextProfile())
        } finally { owner.activeKeys().forEach(owner::clear) }
    }

    @Test fun failedSelectionUsesAuthoritativeRollbackOwnerAndPostResetCancellationClearsTransition() = runBlocking(main.testDispatcher) {
        for (phase in listOf("rollback-success", "rollback-failure", "cancel")) {
            val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
            vault.saveSelectedContextProfile("A")
            val owner = ChatRouteOwnerViewModel { ChatViewModel(it) }
            val childJob = owner.get("synthetic-old")!!.viewModelScope.coroutineContext[Job]!!
            val storage = object : VaultStorage by vault {
                override suspend fun saveSelectedContextProfile(name: String) {
                    if (phase == "cancel") throw CancellationException("synthetic cancel")
                    if (name == "B") { vault.saveSelectedContextProfile(name); throw IllegalStateException("synthetic write failure") }
                    if (phase == "rollback-failure") throw IllegalStateException("synthetic rollback failure")
                    vault.saveSelectedContextProfile(name)
                }
            }
            var transition = true; var resets = 0
            val accepted = mutableListOf<SelectedContextReadResult>()
            try {
                try {
                    val result = performWorkspaceSelection("B", DefaultContextProfileRepository(storage), owner,
                        onAuthoritativeProfile = { accepted.add(it) }, onTransition = { transition = it }) { resets++ }
                    assertNotEquals("cancel", phase)
                    assertTrue(result is io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error)
                    assertEquals(1, accepted.size)
                    assertTrue(accepted.single() is SelectedContextReadResult.Selected)
                    assertEquals(if (phase == "rollback-failure") "B" else "A", (accepted.single() as SelectedContextReadResult.Selected).name)
                } catch (_: CancellationException) {
                    assertEquals("cancel", phase); assertTrue(accepted.isEmpty())
                }
                assertFalse(transition); assertEquals(1, resets)
                assertTrue(owner.activeKeys().isEmpty()); assertTrue(childJob.isCancelled); childJob.join()
                assertEquals(if (phase == "rollback-failure") "B" else "A", vault.getSelectedContextProfile())
            } finally { owner.activeKeys().forEach(owner::clear) }
        }
    }

    @Test fun competingIntentVetoIsRecheckedAfterAuthoritativePreReadBeforeDisposal() = runBlocking(main.testDispatcher) {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()), ioDispatcher = main.testDispatcher)
        vault.saveSelectedContextProfile("A")
        val sessions = io.github.anshuman2401.hermesmobile.feature.sessions.SessionsViewModel()
        val owner = ChatRouteOwnerViewModel { ChatViewModel(it) }
        owner.get("synthetic-old")
        var allowed = true; var resets = 0
        val context = DefaultContextProfileRepository(vault)
        val gated = object : io.github.anshuman2401.hermesmobile.data.context.ContextProfileRepository by context {
            override suspend fun readSelectedProfileAuthoritative(): SelectedContextReadResult {
                val actual = context.readSelectedProfileAuthoritative()
                allowed = false
                return actual
            }
        }
        try {
            val result = performWorkspaceSelection("B", gated, owner, canSwitch = { allowed }, sessions = sessions) { resets++ }
            assertTrue(result is io.github.anshuman2401.hermesmobile.data.context.ContextProfileSelectionResult.Error)
            assertEquals(0, resets); assertEquals(listOf("synthetic-old"), owner.activeKeys())
            assertEquals("A", vault.getSelectedContextProfile())
        } finally { owner.activeKeys().forEach(owner::clear) }
    }

}
