package io.github.anshuman2401.hermesmobile.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KanbanBoardSelectionVaultTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = AesGcmCipherEngine(InMemoryMasterKeyProvider())
    private fun vault(dir: java.io.File) = EncryptedVault(dir, cipher, ioDispatcher = Dispatchers.Unconfined)

    @Test fun selectionIsEncryptedScopedToConnectionAndOriginAndRemovedOnReset() = runBlocking {
        val dir = temporary.newFolder()
        val first = vault(dir)
        first.saveConnectionProfile(ConnectionProfile("alpha", "http://example.test:9119", "Synthetic"))
        first.setActiveProfileId("alpha")
        assertTrue(first.saveKanbanBoardSelection("alpha", "http://example.test:9119", "board-a"))
        val record = dir.listFiles()!!.single { it.name.startsWith("kanban_board_") }
        assertFalse(String(record.readBytes()).contains("board-a"))
        assertEquals("board-a", vault(dir).getKanbanBoardSelection("alpha", "http://example.test:9119"))
        assertFalse(first.saveKanbanBoardSelection("alpha", "http://other.test:9119", "board-b"))
        assertNull(first.getKanbanBoardSelection("alpha", "http://other.test:9119"))
        first.clearAll()
        assertFalse(record.exists())
    }
}
