package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.data.approval.*
import org.junit.Assert.*
import org.junit.Test

class ChatApprovalTest {
    @Test fun pendingApprovalsGateSendIncludingSlashButKeepDraftAndStopState() {
        val base=ChatUiState(composerDraft="Synthetic draft",runtimeSessionId="runtime",isStreaming=false)
        assertTrue(base.isComposerReady)
        val request=ApprovalRequest("queue","runtime","Synthetic",setOf(ApprovalChoice.ONCE,ApprovalChoice.DENY))
        for(phase in listOf(ApprovalPhase.PENDING,ApprovalPhase.SENDING,ApprovalPhase.UNKNOWN)){
            val state=base.copy(approvals=ApprovalState(listOf(request),phase,connected=true))
            assertFalse(state.isComposerReady);assertFalse(state.copy(composerDraft="/status").isComposerReady)
            assertEquals("Synthetic draft",state.composerDraft);assertEquals(base.isStreaming,state.isStreaming)
        }
        assertTrue(base.copy(approvals=ApprovalState()).isComposerReady)
    }
    @Test fun decisionReadinessRequiresConnectedConfirmedPendingQueue() {
        val request=ApprovalRequest("queue","runtime","Synthetic",ApprovalChoice.entries.toSet())
        for(phase in ApprovalPhase.entries)assertEquals(phase==ApprovalPhase.PENDING,
            ApprovalState(listOf(request),phase,connected=true).canDecide)
        assertFalse(ApprovalState(listOf(request),ApprovalPhase.PENDING,connected=false).canDecide)
        assertFalse(ApprovalState(phase=ApprovalPhase.PENDING,connected=true).canDecide)
    }
}
