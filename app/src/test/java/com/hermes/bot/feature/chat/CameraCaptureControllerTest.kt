package com.hermes.bot.feature.chat

import com.hermes.bot.data.attachments.CameraPhoto
import com.hermes.bot.data.attachments.CameraPhotoStore
import com.hermes.bot.data.attachments.CameraPhotoTooLargeException
import com.hermes.bot.data.attachments.CameraUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraCaptureControllerTest {
    private class Store : CameraPhotoStore {
        val created = mutableListOf<CameraPhoto>()
        val discarded = mutableListOf<CameraPhoto>()
        var createFailure: Exception? = null
        var previewFailure: Exception? = null
        var createGate: CompletableDeferred<Unit>? = null
        var previewGate: CompletableDeferred<Unit>? = null
        override suspend fun create(): CameraPhoto {
            createGate?.await()
            createFailure?.let { throw it }
            return CameraPhoto("<camera-selection-${created.size}>").also(created::add)
        }
        override suspend fun preview(photo: CameraPhoto): ByteArray {
            withContext(NonCancellable) { previewGate?.await() }
            previewFailure?.let { throw it }
            return byteArrayOf(1, 2, 3)
        }
        override fun discard(photo: CameraPhoto) { if (photo !in discarded) discarded += photo }
    }

    private class Fixture(scope: TestScope, val store: Store = Store(), available: Boolean = true) {
        val ui = MutableStateFlow(ChatUiState(durableSessionId = "<durable>", runtimeSessionId = "<runtime>", composerDraft = "<draft>"))
        val attached = mutableListOf<String>()
        var upload: Job? = null
        val controller = CameraCaptureController(scope.backgroundScope, ui, if (available) store else null,
            StandardTestDispatcher(scope.testScheduler), attach = { attached += it; upload })
        fun capture(scope: TestScope): Long {
            controller.start()
            scope.runCurrent()
            val token = controller.state.value.token!!
            assertNotNull(controller.claimLaunch(token))
            return token
        }
        fun review(scope: TestScope): Long {
            val token = capture(scope)
            controller.result(token, true)
            scope.runCurrent()
            assertEquals(CameraPhase.REVIEW, controller.state.value.phase)
            return token
        }
    }

    @Test fun captureRequiresReview_andUsePhotoDelegatesOnce_andCleansAfterUpload() = runTest {
        val f = Fixture(this)
        val upload = Job()
        f.upload = upload
        val token = f.capture(this)
        assertTrue(f.ui.value.cameraBusy)
        assertFalse(f.ui.value.isComposerReady)
        assertFalse(f.ui.value.canAttachImage)
        assertNull(f.controller.claimLaunch(token))
        f.controller.usePhoto()
        assertTrue(f.attached.isEmpty())
        f.controller.result(token, true)
        runCurrent()
        assertEquals(CameraPhase.REVIEW, f.controller.state.value.phase)
        assertTrue(f.attached.isEmpty())
        f.controller.result(token, true)
        f.controller.usePhoto()
        f.controller.usePhoto()
        assertEquals(listOf(f.store.created.single().uri), f.attached)
        assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
        assertFalse(f.ui.value.cameraBusy)
        assertTrue(f.store.discarded.isEmpty())
        upload.complete()
        assertEquals(f.store.created, f.store.discarded)
    }

    @Test fun cameraCancellation_andLaunchFailureDeleteOriginal_withoutUploading() = runTest {
        for (launchFailed in listOf(false, true)) {
            val f = Fixture(this)
            val token = f.capture(this)
            f.controller.result(token, false, launchFailed)
            assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
            assertEquals(f.store.created, f.store.discarded)
            assertTrue(f.attached.isEmpty())
            assertFalse(f.ui.value.cameraBusy)
            assertEquals(launchFailed, f.controller.state.value.error != null)
            f.controller.dismissError()
            assertNull(f.controller.state.value.error)
        }
    }

    @Test fun retakeDeletesOriginal_andOldResultCannotAffectNewCapture() = runTest {
        val f = Fixture(this)
        val oldToken = f.review(this)
        f.controller.retake()
        runCurrent()
        val token = f.controller.state.value.token!!
        assertNotEquals(oldToken, token)
        assertEquals(listOf(f.store.created.first()), f.store.discarded)
        f.controller.claimLaunch(token)
        f.controller.result(oldToken, true)
        assertEquals(CameraPhase.CAPTURING, f.controller.state.value.phase)
        f.controller.result(token, true)
        runCurrent()
        assertEquals(CameraPhase.REVIEW, f.controller.state.value.phase)
        assertTrue(f.attached.isEmpty())
        f.controller.cancel()
        assertEquals(f.store.created, f.store.discarded)
    }

    @Test fun ownershipOrReadinessLossCancelsCapture_andRejectsLateResults() = runTest {
        val invalidations: List<(ChatUiState) -> ChatUiState> = listOf(
            { it.copy(runtimeSessionId = "<different-runtime>") },
            { it.copy(durableSessionId = "<different-durable>") },
            { it.copy(isAttachmentConnectionAvailable = false) },
            { it.copy(savedSnapshotCapturedAtMillis = 1) },
            { it.copy(isRefreshing = true) },
            { it.copy(isStreaming = true) }
        )
        for (invalidate in invalidations) {
            val f = Fixture(this)
            val token = f.capture(this)
            f.ui.value = invalidate(f.ui.value)
            f.controller.reconcile()
            f.controller.result(token, true)
            f.controller.usePhoto()
            assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
            assertEquals(f.store.created, f.store.discarded)
            assertTrue(f.attached.isEmpty())
        }
    }

    @Test fun ownerIsRecheckedBeforeUse_evenWithoutObserver() = runTest {
        val f = Fixture(this)
        f.review(this)
        f.ui.value = f.ui.value.copy(runtimeSessionId = "<new-runtime>")
        f.controller.usePhoto()
        assertTrue(f.attached.isEmpty())
        assertEquals(f.store.created, f.store.discarded)
        assertFalse(f.ui.value.cameraBusy)
    }

    @Test fun externalCameraSurvivesBackground_andRotation_doesNotLaunchAgain() = runTest {
        val f = Fixture(this)
        val token = f.capture(this)
        f.controller.background(changingConfigurations = false)
        f.controller.background(changingConfigurations = true)
        assertEquals(CameraPhase.CAPTURING, f.controller.state.value.phase)
        assertNull(f.controller.claimLaunch(token))
        f.controller.result(token, true)
        runCurrent()
        f.controller.background(changingConfigurations = true)
        assertEquals(CameraPhase.REVIEW, f.controller.state.value.phase)
        f.controller.background(changingConfigurations = false)
        assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
        assertEquals(f.store.created, f.store.discarded)
    }

    @Test fun staleCreationStillCleansFile_whenCancellationRacesStoreWork() = runTest {
        val f = Fixture(this)
        f.store.createGate = CompletableDeferred()
        f.controller.start()
        runCurrent()
        f.controller.cancel()
        f.store.createGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, f.store.created.size)
        assertEquals(f.store.created, f.store.discarded)
        assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
        assertFalse(f.ui.value.cameraBusy)
    }

    @Test fun latePreviewCannotResurrectCancelledReview() = runTest {
        val f = Fixture(this)
        val token = f.capture(this)
        f.store.previewGate = CompletableDeferred()
        f.controller.result(token, true)
        runCurrent()
        f.controller.cancel()
        f.store.previewGate!!.complete(Unit)
        runCurrent()
        assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
        assertNull(f.controller.state.value.preview)
        assertEquals(f.store.created, f.store.discarded)
        assertTrue(f.attached.isEmpty())
    }

    @Test fun captureAndPreviewErrorsAreRedacted_andCleaned() = runTest {
        for (failure in listOf(CameraPhotoTooLargeException(), IllegalStateException("<failure-detail>"))) {
            val f = Fixture(this)
            val token = f.capture(this)
            f.store.previewFailure = failure
            f.controller.result(token, true)
            runCurrent()
            assertEquals(CameraPhase.IDLE, f.controller.state.value.phase)
            assertNotNull(f.controller.state.value.error)
            assertFalse(f.controller.state.value.error!!.contains("<failure-detail>"))
            assertEquals(f.store.created, f.store.discarded)
        }
        val f = Fixture(this)
        f.store.createFailure = CameraUnavailableException()
        f.controller.start()
        runCurrent()
        assertEquals("Camera is unavailable on this device. Choose Photos instead.", f.controller.state.value.error)
        assertFalse(f.ui.value.cameraBusy)
    }

    @Test fun cancelledOrRejectedUploadAlwaysReleasesTemporaryOriginal() = runTest {
        val f = Fixture(this)
        f.upload = Job()
        f.review(this)
        f.controller.usePhoto()
        f.upload!!.cancel()
        assertEquals(f.store.created, f.store.discarded)
        val rejected = Fixture(this)
        rejected.review(this)
        rejected.controller.usePhoto()
        assertEquals(rejected.store.created, rejected.store.discarded)
    }

    @Test fun missingStoreOrOfflineStateDoesNotCreateOrUpload() = runTest {
        val absent = Fixture(this, available = false)
        absent.controller.start()
        assertNotNull(absent.controller.state.value.error)
        assertFalse(absent.ui.value.cameraBusy)
        val offline = Fixture(this)
        offline.ui.value = offline.ui.value.copy(isAttachmentConnectionAvailable = false)
        offline.controller.start()
        runCurrent()
        assertTrue(offline.store.created.isEmpty())
        assertTrue(offline.attached.isEmpty())
    }

    @Test fun cameraBusyGatesOrdinaryAndQueuedSend_commandsAndDictation() {
        val base = ChatUiState(runtimeSessionId = "<runtime>", composerDraft = "<draft>", dictationAvailable = true)
        assertTrue(base.isComposerReady)
        assertTrue(base.canStartDictation)
        val busy = base.copy(cameraBusy = true)
        assertFalse(busy.isComposerReady)
        assertFalse(busy.canAttachImage)
        assertFalse(busy.canStartDictation)
        val queued = busy.copy(queueEnabled = true, queue = busy.queue.copy(loaded = true), composerDraft = "/stop")
        assertFalse(queued.canEnqueue)
        assertFalse(queued.canQueueCommand)
        assertFalse(queued.isComposerReady)
    }
}
