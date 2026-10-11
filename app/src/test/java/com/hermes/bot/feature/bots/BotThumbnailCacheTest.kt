package com.hermes.bot.feature.bots

import com.hermes.bot.data.bots.*
import com.hermes.bot.data.gateway.BotAvatar
import com.hermes.bot.data.origin.CanonicalOrigin
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotThumbnailCacheTest {
    private val origin = CanonicalOrigin.parse("https://test.hermes.local").getOrThrow()
    private val scope = BotScope(BotTarget("synthetic", origin.originUrl), origin, 1)
    private fun image(value: Int) = BotAvatar(byteArrayOf(value.toByte()), "image/png")

    @Test fun repeatedPresentationSharesDecodedContentAndChangedBytesDecodeAgain() = runTest {
        val memory = BotMemoryCache(); val cache = BotThumbnailCache<String>(memory, { true }, { it.length })
        var calls = 0
        val decoder: suspend (ByteArray) -> String? = { calls++; "image${it.single()}" }
        assertEquals("image1", cache.get(scope, "bot", image(1), decoder))
        assertEquals("image1", cache.get(scope, "bot", image(1), decoder))
        assertEquals(1, calls)
        assertEquals("image2", cache.get(scope, "bot", image(2), decoder)); assertEquals(2, calls)
        cache.close()
    }

    @Test fun overlappingPresentationsShareOneDecode() = runTest {
        val memory = BotMemoryCache(); val cache = BotThumbnailCache<String>(memory, { true }, { it.length })
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val decoder: suspend (ByteArray) -> String? = { calls++; entered.complete(Unit); release.await(); "image" }
        val first = async { cache.get(scope, "bot", image(1), decoder) }; entered.await()
        val second = async { cache.get(scope, "bot", image(1), decoder) }
        release.complete(Unit)
        assertEquals("image", first.await()); assertEquals("image", second.await()); assertEquals(1, calls)
        cache.close()
    }

    @Test fun evictionFailureAndMissingAvatarRemainBoundedAndSafe() = runTest {
        val cache = BotThumbnailCache<String>(BotMemoryCache(), { true }, { it.length }, maxBytes = 2)
        var calls = 0; val decoder: suspend (ByteArray) -> String? = { calls++; "ab" }
        cache.get(scope, "first", image(1), decoder); cache.get(scope, "second", image(2), decoder)
        cache.get(scope, "first", image(1), decoder); assertEquals(3, calls)
        assertNull(cache.get(scope, "absent", null, decoder)); assertEquals(3, calls)
        assertNull(cache.get(scope, "bad", image(3)) { error("Synthetic decode failure") })
        cache.close()
    }

    @Test fun clearRejectsLateNonCooperativeDecodeAndDropsPreviouslyDecodedImages() = runTest {
        val memory = BotMemoryCache(); var current = true
        val cache = BotThumbnailCache<String>(memory, { current }, { it.length })
        cache.get(scope, "old", image(1)) { "old" }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pending = async {
            cache.get(scope, "bot", image(2)) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; "late" }
        }
        entered.await(); memory.clear(); release.complete(Unit); assertNull(pending.await())
        var decoded = 0; cache.get(scope, "old", image(1)) { decoded++; "new" }; assertEquals(1, decoded)
        current = false; assertNull(cache.get(scope, "old", image(1)) { error("Must not decode") })
        cache.close()
    }

    @Test fun refreshedRosterPrunesRemovedAndDisabledAvatars() = runTest {
        val memory = BotMemoryCache(); memory.bind(scope.target, scope.generation)
        memory.rememberRoster(scope, listOf(com.hermes.bot.data.gateway.BotProfile("bot", "Bot", true)), 0) { true }
        val cache = BotThumbnailCache<String>(memory, { true }, { it.length })
        var calls = 0; val decoder: suspend (ByteArray) -> String? = { calls++; "image" }
        cache.get(scope, "bot", image(1), decoder)
        memory.rememberRoster(scope, listOf(com.hermes.bot.data.gateway.BotProfile("bot", "Bot", false)), 1) { true }
        assertNull(cache.get(scope, "bot", image(1), decoder)); assertEquals(1, calls)
        memory.rememberRoster(scope, listOf(com.hermes.bot.data.gateway.BotProfile("bot", "Bot", true)), 2) { true }
        assertEquals("image", cache.get(scope, "bot", image(1), decoder)); assertEquals(2, calls)
        memory.rememberRoster(scope, emptyList(), 3) { true }
        assertNull(cache.get(scope, "bot", image(1), decoder)); cache.close()
    }

    @Test fun cancellingOnePresentationDoesNotPoisonAnotherVisibleConsumer() = runTest {
        val cache = BotThumbnailCache<String>(BotMemoryCache(), { true }, { it.length },
            digestDispatcher = StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val decoder: suspend (ByteArray) -> String? = { calls++; entered.complete(Unit); release.await(); "image" }
        val first = launch { cache.get(scope, "bot", image(1), decoder) }; entered.await()
        val second = async { cache.get(scope, "bot", image(1), decoder) }; runCurrent()
        assertEquals(1, calls)
        first.cancelAndJoin(); runCurrent()
        release.complete(Unit); assertEquals("image", second.await()); assertEquals(2, calls)
        cache.close()
    }

    @Test fun cancellationPropagatesWithoutPoisoningNextDecode() = runTest {
        val cache = BotThumbnailCache<String>(BotMemoryCache(), { true }, { it.length })
        val entered = CompletableDeferred<Unit>()
        val pending = launch { cache.get(scope, "bot", image(1)) { entered.complete(Unit); CompletableDeferred<String>().await() } }
        entered.await(); pending.cancelAndJoin()
        assertEquals("new", cache.get(scope, "bot", image(1)) { "new" })
        cache.close()
    }
}
