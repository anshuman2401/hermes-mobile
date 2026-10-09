package com.hermes.bot.data.context

import com.hermes.bot.data.history.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BotThreadPreviewRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun reader(f: HistoryFixture, transport: StoredHistoryHttpTransport = OkHttpStoredHistoryTransport(maxBodyBytes = 128 * 1024)) =
        DefaultBotThreadPreviewRepository(f.vault, f.auth, DefaultStoredHistoryProtocolAdapter(f.auth, transport))

    @Test fun recentAssistantTextUsesExplicitDefaultAndNeverResumesOrPersists() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            f.enqueue(HistoryFixture.page(limit = 30, count = 3, rows =
                HistoryFixture.row(1, "\"content\":\"synthetic earlier reply\"") + "," +
                    HistoryFixture.row(2, "\"content\":\"synthetic recent reply\"") + "," +
                    HistoryFixture.row(3, "\"content\":\"synthetic user text\"").replace("\"assistant\"", "\"user\"")))
            val result = reader(f).getReply("default", "synthetic-session")!!
            assertEquals("synthetic recent reply", result.text)
            assertFalse(result.toString().contains("synthetic"))
            val request = f.server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("default", request.requestUrl!!.queryParameter("profile"))
            assertEquals("30", request.requestUrl!!.queryParameter("limit"))
            assertEquals("latest", request.requestUrl!!.queryParameter("order"))
            f.assertReadOnly()
        }
    }

    @Test fun wrongOwnerDescendantMalformedAndNonAssistantNeverBecomeReply() = runBlocking {
        val bodies = listOf(
            HistoryFixture.page(limit = 30, profile = "other"),
            HistoryFixture.page(limit = 30, session = "synthetic-descendant"),
            HistoryFixture.page(limit = 30).replace("\"profile\":\"default\",", ""),
            HistoryFixture.page(limit = 30, rows = HistoryFixture.row().replace("\"assistant\"", "\"tool\"")),
            HistoryFixture.page(limit = 30, rows = HistoryFixture.row(extra = "\"content\":null")),
            "{"
        )
        for (body in bodies) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default"); f.enqueue(body)
            assertNull(reader(f).getReply("default", "synthetic-session")); f.assertReadOnly()
        }
    }

    @Test fun invalidTargetsAndDifferentSelectionNeverReachTransport() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            val reader = reader(f)
            assertNull(reader.getReply("other", "synthetic-session"))
            for (id in listOf("", "../synthetic", ".", "synthetic/other", "synthetic\n"))
                assertNull(reader.getReply("default", id))
            assertNull(reader.getReply("default?other", "synthetic-session"))
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun changedProfileAndCredentialWhileBodyWaitsDiscardOldContent() = runBlocking {
        for (changeCredential in listOf(false, true)) HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            val transport = DeferredHistoryBody(OkHttpStoredHistoryTransport())
            f.enqueue(HistoryFixture.page(limit = 30))
            val result = async { reader(f, transport).getReply("default", "synthetic-session") }
            transport.entered.await()
            if (changeCredential) f.vault.saveAuthCredential(f.credential("synthetic-replacement"))
            else f.vault.saveSelectedContextProfile("other")
            transport.release.complete(Unit)
            assertNull(result.await()); f.assertReadOnly()
        }
    }

    @Test fun acceptedCookieRotationAndTextCapUseExistingAuthExchange() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            f.enqueue(HistoryFixture.page(limit = 30, rows = HistoryFixture.row(extra = "\"content\":\"${"s".repeat(400)}\"")), cookie = true)
            assertEquals(280, reader(f).getReply("default", "synthetic-session")!!.text.length)
            assertEquals("synthetic-new", f.vault.getAuthCredential()!!.cookies["session"])
            f.assertReadOnly()
        }
    }

    @Test fun previewBodyCapReducesOnceThenFailsAndRedirectIsNeverFollowed() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            val oversized = HistoryFixture.page(limit = 30, rows = HistoryFixture.row(extra = "\"content\":\"${"s".repeat(128 * 1024)}\""))
            f.enqueue(oversized); f.enqueue(oversized)
            assertNull(reader(f).getReply("default", "synthetic-session"))
            assertEquals(2, f.server.requestCount); f.assertReadOnly()
        }
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            f.server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", "/synthetic-other"))
            assertNull(reader(f).getReply("default", "synthetic-session"))
            assertEquals(1, f.server.requestCount); f.assertReadOnly()
        }
    }

    @Test fun cancellationClosesBodyAndPropagates() = runBlocking {
        HistoryFixture(temp.newFolder()).use { f ->
            f.initialize(); f.vault.saveSelectedContextProfile("default")
            val transport = DeferredHistoryBody(OkHttpStoredHistoryTransport())
            f.enqueue(HistoryFixture.page(limit = 30))
            val job = launch { reader(f, transport).getReply("default", "synthetic-session") }
            transport.entered.await(); job.cancelAndJoin()
            assertTrue(transport.closed); f.assertReadOnly()
        }
    }
}
