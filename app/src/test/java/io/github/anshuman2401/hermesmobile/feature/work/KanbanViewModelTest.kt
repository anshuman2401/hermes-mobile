package io.github.anshuman2401.hermesmobile.feature.work

import io.github.anshuman2401.hermesmobile.data.kanban.*
import io.github.anshuman2401.hermesmobile.test.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class KanbanViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val card = KanbanCard("card-a", "Synthetic card", "Description", "todo", 2, null, 1, 0, null, 0)
    private class Repo(private val initial: KanbanCard) : KanbanRepository {
        var boardSlug = "alpha"
        var currentCard = initial
        var writes = 0
        var patchResult: KanbanResult<KanbanCard>? = null
        var createResult: KanbanResult<KanbanCard>? = null
        var pending: CompletableDeferred<KanbanResult<KanbanCard>>? = null
        var boardReads = 0
        var detailReads = 0
        var boardFailure: KanbanFailure? = null
        override suspend fun rememberedBoard() = "beta"
        override suspend fun rememberBoard(slug: String): Boolean { boardSlug = slug; return true }
        override suspend fun boards() = KanbanResult.Success(listOf(
            KanbanBoardMeta("alpha", "Alpha", 1, true, "scratch", null),
            KanbanBoardMeta("beta", "Beta", 1, false, "scratch", null)) to "alpha")
        override suspend fun board(slug: String): KanbanResult<KanbanBoard> {
            boardReads++
            boardFailure?.let { return KanbanResult.Failure(it) }
            return KanbanResult.Success(KanbanBoard(KanbanLanes.all.map { lane ->
                KanbanLane(lane, if (lane == currentCard.status) listOf(currentCard) else emptyList()) }, 4))
        }
        override suspend fun detail(slug: String, id: String): KanbanResult<KanbanDetail> {
            detailReads++
            return KanbanResult.Success(KanbanDetail(currentCard, emptyList()))
        }
        override suspend fun catalog(slug: String) = KanbanResult.Success(KanbanCatalog(emptyList(), emptyMap()))
        override suspend fun create(slug: String, draft: KanbanCreate): KanbanResult<KanbanCard> {
            writes++
            return createResult ?: KanbanResult.Success(currentCard.copy(status = "triage").also { currentCard = it })
        }
        override suspend fun patch(slug: String, id: String, fields: Map<String, Any?>): KanbanResult<KanbanCard> {
            writes++
            return pending?.await() ?: patchResult ?: KanbanResult.Success(currentCard.copy(
                status = fields["status"] as? String ?: currentCard.status).also { currentCard = it })
        }
        override suspend fun comment(slug: String, id: String, body: String) = KanbanResult.Success(Unit)
        override suspend fun delete(slug: String, id: String): KanbanResult<Unit> {
            writes++
            return KanbanResult.Success(Unit)
        }
    }
    private fun started(repo: Repo) = KanbanViewModel(repo).also {
        it.connectionChanged(true, "synthetic-connection")
        it.pageStarted()
    }

    @Test fun rememberedBoardAndHostOrderAreUsedWithoutChangingHostCurrent() {
        val repo = Repo(card)
        val vm = started(repo)
        assertEquals("beta", vm.uiState.value.selectedBoard)
        assertEquals("todo", vm.uiState.value.selectedLane)
        assertEquals(listOf("card-a"), vm.uiState.value.cards.map { it.id })
        assertEquals("alpha", repo.boardSlug)
        vm.selectBoard("alpha")
        assertEquals("alpha", repo.boardSlug)
        assertEquals("alpha", vm.uiState.value.selectedBoard)
    }

    @Test fun uncertainWriteIsSingleSendAndBlocksFurtherMutationUntilRefresh() {
        val repo = Repo(card).apply { patchResult = KanbanResult.Failure(KanbanFailure.UNKNOWN) }
        val vm = started(repo)
        vm.move("card-a", "ready")
        assertEquals(1, repo.writes)
        assertTrue(vm.uiState.value.needsReconciliation)
        vm.move("card-a", "done")
        assertEquals(1, repo.writes)
        vm.refresh()
        assertFalse(vm.uiState.value.needsReconciliation)
    }

    @Test fun createInSelectedLaneSendsTriageThenOneMoveAndNeverResendsOnFailure() {
        val repo = Repo(card).apply { patchResult = KanbanResult.Failure(KanbanFailure.UNKNOWN) }
        val vm = started(repo)
        vm.selectLane("ready")
        vm.create()
        vm.updateDraft(vm.uiState.value.draft!!.copy(title = "New card"))
        vm.save()
        assertEquals(2, repo.writes)
        assertTrue(vm.uiState.value.needsReconciliation)
        assertNull(vm.uiState.value.draft)
    }

    @Test fun blockedCreationRequiresReason() {
        val draft = KanbanDraft(title = "Synthetic", targetLane = "blocked")
        assertFalse(draft.valid())
        assertTrue(draft.copy(blockReason = "Waiting").valid())
    }

    @Test fun failedRefreshDisablesWritesAgainstStaleSnapshot() {
        val repo = Repo(card)
        val vm = started(repo)
        assertTrue(vm.uiState.value.canManage)
        repo.boardFailure = KanbanFailure.UNAVAILABLE
        vm.refresh()
        assertFalse(vm.uiState.value.canManage)
        vm.move("card-a", "ready")
        assertEquals(0, repo.writes)
    }

    @Test fun failedInitialCreateRetainsErrorAndDoesNotPretendCardWasCreated() {
        val repo = Repo(card).apply { createResult = KanbanResult.Failure(KanbanFailure.INVALID) }
        val vm = started(repo)
        vm.selectLane("triage")
        vm.create()
        vm.updateDraft(vm.uiState.value.draft!!.copy(title = "New card"))
        vm.save()
        assertEquals(KanbanFailure.INVALID, vm.uiState.value.failure)
        assertFalse(vm.uiState.value.createdInTriage)
        assertEquals(1, repo.writes)
    }

    @Test fun deleteClosesDetailWithoutOpeningDeletedCardAgain() {
        val repo = Repo(card)
        val vm = started(repo)
        vm.open("card-a")
        assertEquals(1, repo.detailReads)
        vm.delete("card-a")
        assertEquals(1, repo.detailReads)
        assertNull(vm.uiState.value.detail)
    }
}
