package io.github.anshuman2401.hermesmobile.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule that replaces the Main dispatcher with [UnconfinedTestDispatcher] for the duration
 * of each test. This allows viewModelScope.launch blocks to execute eagerly and synchronously,
 * making ViewModel state assertions safe without explicit await/advanceUntilIdle calls.
 *
 * Test-isolation hardening (flake repair for kotlinx.coroutines.test
 * UncaughtExceptionsBeforeTest):
 *
 * 1. [finished] drains the test scheduler before the next test starts, so a
 *    leftover coroutine from this test (e.g. a still-live ViewModel collector
 *    resumed from a background thread) completes while a live Main dispatcher
 *    is installed instead of leaking into the next test's execution.
 *
 * 2. [finished] deliberately does NOT call `Dispatchers.resetMain()`. The
 *    un-mocked real Main is unusable on the JVM ("Module with the Main
 *    dispatcher had failed to initialize"); any leftover coroutine dispatched
 *    to Main after a reset throws that error, and the coroutines-test global
 *    exception collector attributes it to the NEXT runTest, failing an
 *    unrelated test (historically ChatRouteOwnerViewModelTest.clear_unknownKey_isNoOp
 *    and ChatViewModelTest.unattributedThinkingDelta). Leaving the last test
 *    Main installed for the remainder of the JVM run keeps every leftover
 *    dispatch on a live UnconfinedTestDispatcher; the next test's [starting]
 *    installs its own dispatcher anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {
    val testDispatcher = UnconfinedTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        // Drain leftover virtual-time work while Main is still this test's
        // dispatcher. Bounded so a self-rescheduling stray coroutine cannot
        // hang the suite.
        runBlocking {
            withTimeoutOrNull(DRAIN_TIMEOUT_MS) {
                testDispatcher.scheduler.advanceUntilIdle()
            }
        }
        // Intentionally no Dispatchers.resetMain() — see class KDoc.
    }

    private companion object {
        const val DRAIN_TIMEOUT_MS = 1_000L
    }
}