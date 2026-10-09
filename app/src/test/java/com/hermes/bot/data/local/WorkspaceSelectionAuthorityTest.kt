package com.hermes.bot.data.local

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceSelectionAuthorityTest {
    @get:Rule val temp = TemporaryFolder()
    private val origin = "http://example.test:9711"
    private fun credential(value: String = "synthetic-cookie") = AuthCredential("cookie_session", "basic", mapOf("session" to value), origin)
    private suspend fun initialize(vault: EncryptedVault) {
        vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin, "Synthetic"))
        vault.saveAuthCredential(credential())
        vault.saveSelectedContextProfile("A")
    }

    @Test fun absenceCorruptionAndFutureSchemaAreDifferentAndDoNotDeleteEvidence() = runBlocking {
        val folder = temp.newFolder(); val file = File(folder, "context_profile_selection.enc")
        val aes = AesGcmCipherEngine(InMemoryMasterKeyProvider())
        var selectedAad: ByteArray? = null
        val cipher = object : VaultCipherEngine by aes {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
                if (plaintext.toString(Charsets.UTF_8).contains("selectedProfileName")) selectedAad = associatedData?.copyOf()
                return aes.encrypt(plaintext, associatedData)
            }
        }
        val vault = EncryptedVault(folder, cipher)
        assertSame(SelectedContextReadResult.Absent, vault.readSelectedContextProfileAuthoritative())
        vault.saveSelectedContextProfile("A")
        assertEquals("A", (vault.readSelectedContextProfileAuthoritative() as SelectedContextReadResult.Selected).name)
        for (body in listOf("{}", "{\"schemaVersion\":99,\"selectedProfileName\":\"A\"}", "{\"schemaVersion\":1,\"selectedProfileName\":\"../invalid\"}")) {
            file.writeBytes(aes.encrypt(body.toByteArray(), selectedAad))
            val before = file.readBytes()
            assertSame(SelectedContextReadResult.Unreadable, vault.readSelectedContextProfileAuthoritative())
            assertArrayEquals(before, file.readBytes())
        }
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertSame(SelectedContextReadResult.Unreadable, vault.readSelectedContextProfileAuthoritative())
        assertTrue(file.exists())
        file.delete(); Files.createSymbolicLink(file.toPath(), file.toPath().fileName)
        assertSame(SelectedContextReadResult.Failed, vault.readSelectedContextProfileAuthoritative())
    }

    @Test fun capturedSelectionAndLeaseRemainOriginalAndGuardedClearIsOneOwnerOnly() = runBlocking {
        val vault = EncryptedVault(temp.newFolder(), AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        initialize(vault)
        val owner = (vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner
        assertEquals("A", (owner.selection as SelectedContextReadResult.Selected).name)
        vault.saveSelectedContextProfile("B")
        assertEquals(WorkspaceClearResult.Stale, vault.clearSelectedContextProfileIfCurrent(owner.lease, "A"))
        assertEquals("B", vault.getSelectedContextProfile())
        vault.saveSelectedContextProfile("A"); vault.saveAuthCredential(credential("synthetic-new-cookie"))
        assertEquals(WorkspaceClearResult.Stale, vault.clearSelectedContextProfileIfCurrent(owner.lease, "A"))
        val current = (vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner
        assertEquals(WorkspaceClearResult.Applied, vault.clearSelectedContextProfileIfCurrent(current.lease, "A"))
        assertSame(SelectedContextReadResult.Absent, vault.readSelectedContextProfileAuthoritative())
        assertEquals(WorkspaceClearResult.Stale, vault.clearSelectedContextProfileIfCurrent(current.lease, "A"))
        assertFalse(owner.toString().contains("synthetic-cookie"))
    }

    @Test fun unreadableSelectionAndDeniedAuthCannotCaptureOrClear() = runBlocking {
        val folder = temp.newFolder(); val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        initialize(vault)
        val owner = (vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner
        File(folder, "context_profile_selection.enc").writeBytes(byteArrayOf(0))
        assertSame(WorkspaceRecoveryOwnerResult.Unavailable, vault.captureWorkspaceRecoveryOwner())
        assertEquals(WorkspaceClearResult.Failed, vault.clearSelectedContextProfileIfCurrent(owner.lease, "A"))
        vault.saveSelectedContextProfile("A"); vault.clearAuthCredential()
        assertSame(WorkspaceRecoveryOwnerResult.Unavailable, vault.captureWorkspaceRecoveryOwner())
        assertEquals(WorkspaceClearResult.Stale, vault.clearSelectedContextProfileIfCurrent(owner.lease, "A"))
    }
    @Test fun guardedDeleteAndReadbackFailuresNeverAdvertiseApplied() = runBlocking {
        for (mode in listOf("delete", "unreadable", "read-failed")) {
            val folder = temp.newFolder()
            val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()),
                workspaceSelectionDelete = { file ->
                    if (mode == "delete") throw java.io.IOException("synthetic delete failure")
                    Files.delete(file.toPath())
                    if (mode == "unreadable") file.writeBytes(byteArrayOf(0))
                    else Files.createSymbolicLink(file.toPath(), file.toPath().fileName)
                })
            initialize(vault)
            val owner = (vault.captureWorkspaceRecoveryOwner() as WorkspaceRecoveryOwnerResult.Captured).owner
            assertEquals(WorkspaceClearResult.Failed, vault.clearSelectedContextProfileIfCurrent(owner.lease, "A"))
            val actual = vault.readSelectedContextProfileAuthoritative()
            when (mode) {
                "delete" -> assertEquals("A", (actual as SelectedContextReadResult.Selected).name)
                "unreadable" -> assertSame(SelectedContextReadResult.Unreadable, actual)
                else -> assertSame(SelectedContextReadResult.Failed, actual)
            }
        }
    }

}
