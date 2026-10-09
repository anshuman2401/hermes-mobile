package com.hermes.bot.data.voice

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun audioWipedAndCaptureReleasedOnSuccessFailureAndCancellation() = runBlocking {
        for (kind in 0..2) {
            val f = VoiceTestFixture(temp.newFolder()); f.initialize()
            val owner = VoiceOwner(f.sessions.captureSendActivityOwner("synthetic-stored", "synthetic-runtime")!!)
            var cancelled = false; val bytes = VoiceLimits.wav(byteArrayOf(1, 2))
            val entered = CompletableDeferred<Unit>()
            val repository = object : VoiceRepository {
                override suspend fun capture(storedId: String, runtimeId: String) = owner
                override suspend fun isCurrent(owner: VoiceOwner) = true
                override suspend fun transcribe(owner: VoiceOwner, wav: ByteArray): VoiceResult {
                    entered.complete(Unit)
                    return when (kind) {
                        0 -> VoiceResult.Transcript("synthetic")
                        1 -> VoiceResult.Failed(VoiceFailure.NETWORK)
                        else -> { awaitCancellation() }
                    }
                }
            }
            val coordinator = DefaultVoiceDictationCoordinator(repository, object : VoiceRecorder {
                override suspend fun start() = object : VoiceCapture {
                    override suspend fun stop() = bytes
                    override fun cancel() { cancelled = true }
                }
            })
            val operation = coordinator.start("synthetic-stored", "synthetic-runtime")
            val job = async { coordinator.stopAndTranscribe(operation) }
            entered.await()
            if (kind == 2) job.cancelAndJoin() else job.await()
            assertTrue(cancelled); assertTrue(bytes.all { it == 0.toByte() })
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellationAfterRecorderAcquisitionReleasesLateCapture() = kotlinx.coroutines.test.runTest {
        val f = VoiceTestFixture(temp.newFolder(), dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)); f.initialize()
        val owner = VoiceOwner(f.sessions.captureSendActivityOwner("synthetic-stored", "synthetic-runtime")!!)
        var released = false
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val repository = object : VoiceRepository {
            override suspend fun capture(storedId: String, runtimeId: String) = owner
            override suspend fun isCurrent(owner: VoiceOwner) = true
            override suspend fun transcribe(owner: VoiceOwner, wav: ByteArray): VoiceResult = error("must not transcribe")
        }
        val coordinator = DefaultVoiceDictationCoordinator(repository, object : VoiceRecorder {
            override suspend fun start(): VoiceCapture {
                withContext(NonCancellable) { entered.complete(Unit); gate.await() }
                return object : VoiceCapture {
                    override suspend fun stop(): ByteArray = error("must not stop/upload")
                    override fun cancel() { released = true }
                }
            }
        })
        val start = async { coordinator.start("synthetic-stored", "synthetic-runtime") }
        entered.await(); start.cancel(); gate.complete(Unit); start.join()
        assertTrue(released)
    }

}
