package io.github.anshuman2401.hermesmobile.data.context

import io.github.anshuman2401.hermesmobile.data.auth.AuthHttpTransport
import io.github.anshuman2401.hermesmobile.data.auth.HttpResponse
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.local.VaultStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceLocalSelectionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun normalSelection_persistsLocally_withoutPostingToHost() = runBlocking {
        val transport = RecordingTransport()
        val vault = EncryptedVault(
            tempFolder.newFolder("workspace-selection"),
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        val repository = DefaultContextProfileRepository(vault, transport)

        val result = repository.selectProfile("architect")

        assertEquals(
            "architect",
            (result as ContextProfileSelectionResult.Success).selectedProfileName
        )
        assertEquals("architect", vault.getSelectedContextProfile())
        assertEquals(0, transport.postCount)
    }

    @Test
    fun persistenceFailure_restoresPreviousSelection_withoutPostingToHost() = runBlocking {
        val transport = RecordingTransport()
        val vault = EncryptedVault(
            tempFolder.newFolder("workspace-selection-failure"),
            AesGcmCipherEngine(InMemoryMasterKeyProvider())
        )
        vault.saveSelectedContextProfile("default")
        val repository = DefaultContextProfileRepository(
            PersistThenFailVault(vault),
            transport
        )

        val result = repository.selectProfile("architect")

        assertEquals(ContextProfileErrorReason.UNAVAILABLE, (result as ContextProfileSelectionResult.Error).reason)
        assertEquals("default", vault.getSelectedContextProfile())
        assertEquals(0, transport.postCount)
    }

    @Test
    fun failedRollbackReportsErrorAndAuthoritativeReadKeepsActualOwner() = runBlocking {
        val vault = EncryptedVault(tempFolder.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        vault.saveSelectedContextProfile("default")
        val storage = object : VaultStorage by vault {
            override suspend fun saveSelectedContextProfile(name: String) {
                if (name == "architect") vault.saveSelectedContextProfile(name)
                throw IllegalStateException("synthetic write failure")
            }
        }
        val repository = DefaultContextProfileRepository(storage, RecordingTransport())
        org.junit.Assert.assertTrue(repository.selectProfile("architect") is ContextProfileSelectionResult.Error)
        assertEquals("architect", (repository.readSelectedProfileAuthoritative() as io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Selected).name)
    }

    @Test
    fun cancellationFromPreReadSaveAndRollbackPropagatesWithoutSuccessMapping() = runBlocking {
        for (phase in listOf("read", "save", "rollback")) {
            val vault = EncryptedVault(tempFolder.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
            vault.saveSelectedContextProfile("default")
            val storage = object : VaultStorage by vault {
                override suspend fun readSelectedContextProfileAuthoritative(): io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult {
                    if (phase == "read") throw kotlinx.coroutines.CancellationException("synthetic cancel")
                    return vault.readSelectedContextProfileAuthoritative()
                }
                override suspend fun saveSelectedContextProfile(name: String) {
                    if (phase == "save" || (phase == "rollback" && name == "default"))
                        throw kotlinx.coroutines.CancellationException("synthetic cancel")
                    if (phase == "rollback") throw IllegalStateException("synthetic write failure")
                    vault.saveSelectedContextProfile(name)
                }
            }
            val repository = DefaultContextProfileRepository(storage, RecordingTransport())
            try { repository.selectProfile("architect"); org.junit.Assert.fail("cancellation must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals("default", vault.getSelectedContextProfile())
        }
    }

    @Test
    fun unreadableOrFailedAuthorityCannotBeUsedAsDefaultRollback() = runBlocking {
        for (authority in listOf(io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Unreadable,
            io.github.anshuman2401.hermesmobile.data.local.SelectedContextReadResult.Failed)) {
            val vault = EncryptedVault(tempFolder.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
            vault.saveSelectedContextProfile("default")
            var writes = 0
            val storage = object : VaultStorage by vault {
                override suspend fun readSelectedContextProfileAuthoritative() = authority
                override suspend fun saveSelectedContextProfile(name: String) { writes++ }
                override suspend fun clearSelectedContextProfile() { writes++ }
            }
            val repository = DefaultContextProfileRepository(storage, RecordingTransport())
            org.junit.Assert.assertTrue(repository.selectProfile("architect") is ContextProfileSelectionResult.Error)
            assertEquals(0, writes)
            assertEquals("default", vault.getSelectedContextProfile())
        }
    }

    private class RecordingTransport : AuthHttpTransport {
        var postCount = 0

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): HttpResponse {
            if (method == "POST") postCount += 1
            return HttpResponse(500, body = "unexpected network request")
        }
    }

    private class PersistThenFailVault(
        private val delegate: VaultStorage
    ) : VaultStorage by delegate {
        override suspend fun saveSelectedContextProfile(name: String) {
            delegate.saveSelectedContextProfile(name)
            if (name == "architect") throw IllegalStateException("synthetic persistence failure")
        }
    }
}
