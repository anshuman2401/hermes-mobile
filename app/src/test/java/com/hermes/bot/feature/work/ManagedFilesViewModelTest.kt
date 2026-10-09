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
        val listedPaths = mutableListOf<String?>()
        var uploads = mutableListOf<Boolean>()
        var conflict = false
        var canChangePath = true
        var lockedRoot: String? = "/"
        var entries = listOf(file, folder)
        var failingPath: String? = null
        override suspend fun list(path: String?): ManagedFileListing {
            this.path = path
            listedPaths += path
            if (failingPath != null && path == failingPath) throw ManagedFileException(ManagedFileFailure.FORBIDDEN)
            return if (path == "/workspace/sub") ManagedFileListing(path, "/workspace", emptyList(), false, canChangePath, lockedRoot)
                else ManagedFileListing("/workspace", "/", entries, false, canChangePath, lockedRoot)
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

    @Test fun directPathChecksHostCapabilityAndLockedRootBeforeListing() {
        val repo = Repo().apply { lockedRoot = "/workspace" }
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner", true); vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        vm.openPath("relative/path")
        vm.openPath("/workspace/../elsewhere")
        vm.openPath("/elsewhere")
        assertEquals(1, repo.listedPaths.size)
        assertEquals("/workspace", vm.state.value.listing?.path)
        assertEquals("Enter an allowed absolute folder path.", vm.state.value.pathError)
        vm.openPath(" /workspace/sub "); main.testDispatcher.scheduler.runCurrent()
        assertEquals("/workspace/sub", repo.path)
        assertEquals("/workspace/sub", vm.state.value.listing?.path)
        assertNull(vm.state.value.pathError)

        val restricted = Repo().apply { canChangePath = false }
        val blocked = ManagedFilesViewModel(restricted)
        blocked.bind("owner", true); blocked.refresh(); main.testDispatcher.scheduler.runCurrent()
        blocked.openPath("/workspace/sub"); main.testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(null), restricted.listedPaths)
    }

    @Test fun failedDirectPathKeepsListingAndBackParent() {
        val repo = Repo().apply { failingPath = "/workspace/missing" }
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner", true); vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        val before = vm.state.value.listing
        vm.openPath("/workspace/missing"); main.testDispatcher.scheduler.runCurrent()
        assertSame(before, vm.state.value.listing)
        assertEquals("Folder could not be opened. Current folder is unchanged.", vm.state.value.pathError)
        assertFalse(vm.state.value.loading)
        assertTrue(vm.back()); main.testDispatcher.scheduler.runCurrent()
        assertEquals("/", repo.path)
    }

    @Test fun hiddenFilterChangesOnlyVisibleRowsAndResetsForNewOwner() {
        val dotFolder = ManagedFileEntry(".settings", "/workspace/.settings", true, null, null)
        val dotFile = ManagedFileEntry(".env", "/workspace/.env", false, 1, null)
        val repo = Repo().apply { entries = listOf(dotFolder, folder, dotFile, file) }
        val vm = ManagedFilesViewModel(repo)
        vm.bind("owner", true); vm.refresh(); main.testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(".settings", "sub", ".env", "note.txt"), vm.state.value.visibleEntries.map { it.name })
        vm.setHideHiddenFiles(true)
        assertEquals(listOf("sub", "note.txt"), vm.state.value.visibleEntries.map { it.name })
        assertEquals(4, vm.state.value.listing?.entries?.size)
        vm.pageStopped()
        assertTrue(vm.state.value.hideHiddenFiles)
        vm.bind("other-owner", true)
        assertFalse(vm.state.value.hideHiddenFiles)
    }
}
