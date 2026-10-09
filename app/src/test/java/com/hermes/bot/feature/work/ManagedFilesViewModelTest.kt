package com.hermes.bot.feature.work

import com.hermes.bot.data.files.*
import com.hermes.bot.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ManagedFilesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private companion object {
        val file = ManagedFileEntry("note.txt", "/workspace/note.txt", false, 4, null)
        val folder = ManagedFileEntry("sub", "/workspace/sub", true, null, null)
    }
    private class Repo : ManagedFileRepository {
        var path: String? = null
        var uploads = mutableListOf<Boolean>()
        var conflict = false
        override suspend fun list(path: String?): ManagedFileListing {
            this.path = path
            return if (path == "/workspace/sub") ManagedFileListing(path, "/workspace", emptyList(), false, true, "/")
                else ManagedFileListing("/workspace", "/", listOf(file, folder), false, true, "/")
        }
        override suspend fun upload(directory: String, source: ManagedUploadSource, overwrite: Boolean) {
            uploads += overwrite
            if (conflict && !overwrite) throw ManagedFileException(ManagedFileFailure.CONFLICT)
        }
        override suspend fun download(entry: ManagedFileEntry, maxBytes: Long): ManagedDownload = error("unused")
        override suspend fun isCurrent(download: ManagedDownload) = false
        override suspend fun export(download: ManagedDownload, output: OutputStream) = false
    }
    @Test fun folderNavigationAndConnectionChangeClearState() {
        val repo = Repo()
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner-one", true)
        vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        assertEquals("/workspace", vm.state.value.listing?.path)
        vm.openFolder(folder); main.testDispatcher.scheduler.runCurrent()
        assertEquals("/workspace/sub", repo.path)
        assertTrue(vm.back()); main.testDispatcher.scheduler.runCurrent()
        assertEquals("/workspace", repo.path)
        vm.bind("owner-two", true)
        assertNull(vm.state.value.listing)
    }

    @Test fun definiteConflictRequiresExplicitReplace() {
        val repo = Repo().apply { conflict = true }
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner", true); vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        vm.upload(ManagedUploadSource("note.txt", 4) { "test".byteInputStream() }); main.testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(false), repo.uploads)
        assertEquals("note.txt", vm.state.value.conflictName)
        vm.replace(); main.testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(false, true), repo.uploads)
        assertNull(vm.state.value.conflictName)
    }

    @Test fun disconnectDropsListingAndBlocksNewTransfers() {
        val repo = Repo()
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner", true); vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.listing)
        vm.bind("owner", false)
        assertNull(vm.state.value.listing)
        vm.upload(ManagedUploadSource("note.txt", 4) { "test".byteInputStream() })
        main.testDispatcher.scheduler.runCurrent()
        assertTrue(repo.uploads.isEmpty())
    }
}
