package io.github.anshuman2401.hermesmobile.feature.chat

import io.github.anshuman2401.hermesmobile.domain.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRestoreNoticePolicyTest {
    @Test fun missingRuntimeOffersRestore() {
        val state = ChatUiState(
            headerConnectionStatus = ConnectionStatus.CONNECTED,
            submitBlockedReason = SubmitBlockedReason.NO_RUNTIME
        )
        val notice = ChatRestoreNoticePolicy.forState(state)!!
        assertEquals(NO_RUNTIME_NOTICE_TEXT, notice.message)
        assertEquals(NO_RUNTIME_ACTION_LABEL, notice.action)
        assertEquals(NO_RUNTIME_SEMANTICS, notice.semantics)
    }

    @Test fun attachedRunningTurnOffersStatusCheck() {
        val state = ChatUiState(
            runtimeSessionId = "synthetic-runtime",
            headerConnectionStatus = ConnectionStatus.CONNECTED,
            composerDraft = "synthetic draft",
            isAttachmentConnectionAvailable = true,
            isStreaming = true,
            submitBlockedReason = SubmitBlockedReason.NO_RUNTIME
        )
        val notice = ChatRestoreNoticePolicy.forState(state)!!
        assertTrue(notice.message.contains("still working"))
        assertEquals("Check status", notice.action)
        assertTrue(notice.semantics.contains("Session is active"))
        assertTrue(!state.isComposerReady)
    }

    @Test fun attachedUnconfirmedTurnDoesNotClaimMissingRuntime() {
        val notice = ChatRestoreNoticePolicy.forState(ChatUiState(
            runtimeSessionId = "synthetic-runtime",
            submitBlockedReason = SubmitBlockedReason.NO_RUNTIME
        ))!!
        assertTrue(notice.message.contains("could not be confirmed"))
        assertEquals("Check status", notice.action)
    }

    @Test fun recoveredSessionHasNoNotice() {
        assertNull(ChatRestoreNoticePolicy.forState(ChatUiState(
            runtimeSessionId = "synthetic-runtime", submitBlockedReason = null
        )))
        assertNull(ChatRestoreNoticePolicy.forState(null))
    }

}
