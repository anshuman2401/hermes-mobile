package com.hermes.bot.feature.chat.markdown

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownPreparationCoordinatorTest {
    @Test fun burstsKeepOnlyLatestAtFixedCadenceAndSealWakesTimer() = runTest {
        val origin = testScheduler.currentTime
        val starts = mutableListOf<Pair<Long, String>>()
        val published = mutableListOf<String>()
        val worker = MarkdownPreparationCoordinator<String>({ text, _, _ ->
            starts += (testScheduler.currentTime - origin) to text; text
        }, { testScheduler.currentTime })
        backgroundScope.launch { worker.run(published::add) }
        worker.submit("a", false); runCurrent()
        repeat(9) { advanceTimeBy(10); worker.submit("a".repeat(it + 2), false); runCurrent() }
        assertEquals(1, starts.size)
        advanceTimeBy(10); runCurrent()
        assertEquals(listOf(0L, 100L), starts.map { it.first })
        assertEquals("a".repeat(10), published.last())
        advanceTimeBy(1); worker.submit("final", true); runCurrent()
        assertEquals(101L to "final", starts.last())
    }

    @Test fun slowContinuousPreparationPublishesPrefixesWithoutStarvation() = runTest {
        val published = mutableListOf<String>()
        var active = 0
        var peak = 0
        val worker = MarkdownPreparationCoordinator<String>({ text, _, _ ->
            active++; peak = maxOf(peak, active); delay(250); active--; text
        }, { testScheduler.currentTime })
        backgroundScope.launch { worker.run(published::add) }
        worker.submit("a", false); runCurrent()
        repeat(50) { advanceTimeBy(20); worker.submit("a".repeat(it + 2), false); runCurrent() }
        assertEquals(1, peak)
        assertTrue(published.size >= 3)
        worker.submit("a".repeat(51), true)
        advanceTimeBy(500); runCurrent()
        assertEquals("a".repeat(51), published.last())
    }

    @Test fun replacementAndAbaRejectOldWorkAndCache() = runTest {
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val caches = mutableListOf<String?>()
        val worker = MarkdownPreparationCoordinator<String>({ text, _, old ->
            caches += old
            if (caches.size == 1) gate.await()
            text
        }, { testScheduler.currentTime })
        backgroundScope.launch { worker.run(published::add) }
        worker.submit("old", false); runCurrent()
        worker.submit("new", false); worker.submit("old plus", true)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("old plus"), published)
        assertEquals(listOf(null, null), caches)
    }

    @Test fun disposalRejectsNonCooperativeResult() = runTest {
        val published = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val worker = MarkdownPreparationCoordinator<String>({ text, _, _ ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }; text
        }, { testScheduler.currentTime })
        val job = backgroundScope.launch { worker.run(published::add) }
        worker.submit("synthetic", false); runCurrent(); job.cancel(); gate.complete(Unit); runCurrent()
        assertTrue(published.isEmpty())
    }

    @Test fun sameSourceSealingRepreparesAndDuplicateSubmissionDoesNothing() = runTest {
        val calls = mutableListOf<Boolean>()
        val worker = MarkdownPreparationCoordinator<String>({ text, sealed, _ -> calls += sealed; text }, { testScheduler.currentTime })
        backgroundScope.launch { worker.run {} }
        worker.submit("a", false); runCurrent(); worker.submit("a", false); runCurrent()
        worker.submit("a", true); runCurrent()
        assertEquals(listOf(false, true), calls)
    }

    @Test fun reuseAndFinalEquivalenceIncludeReferencesAndNestedStructure() = kotlinx.coroutines.runBlocking {
        val color = Color.Blue
        val prefix = "# Heading\n\n- stable **item**\n\n[reference][later]\n\n"
        val initial = prepareMarkdown(prefix + "tail", false, color, color, null)
        val appended = prepareMarkdown(prefix + "tail grows", false, color, color, null, initial)
        assertSame(initial.nodes[0], appended.nodes[0])
        assertSame(initial.nodes[1], appended.nodes[1])
        assertNotSame(initial.nodes.last(), appended.nodes.last())
        val source = prefix + "tail grows\n\n[later]: https://example.com\n\n```kotlin\nval x = 1\n"
        val final = prepareMarkdown(source, true, color, color, null, appended)
        assertEquals(prepareMarkdown(source, true, color, color, null), final)
        assertNotSame(appended.nodes[2], final.nodes[2])
        assertNotNull((final.nodes.last().node as MarkdownNode.CodeBlock).notice)
    }
}
