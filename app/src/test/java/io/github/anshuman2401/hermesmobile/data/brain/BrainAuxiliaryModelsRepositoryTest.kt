package io.github.anshuman2401.hermesmobile.data.brain

import io.github.anshuman2401.hermesmobile.data.auth.AuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.DefaultAuthRepository
import io.github.anshuman2401.hermesmobile.data.auth.OkHttpAuthTransport
import io.github.anshuman2401.hermesmobile.data.gateway.GatewayProtocolAdapter
import io.github.anshuman2401.hermesmobile.data.gateway.json.GatewayJson
import io.github.anshuman2401.hermesmobile.data.local.AesGcmCipherEngine
import io.github.anshuman2401.hermesmobile.data.local.AuthCredential
import io.github.anshuman2401.hermesmobile.data.local.ConnectionProfile
import io.github.anshuman2401.hermesmobile.data.local.EncryptedVault
import io.github.anshuman2401.hermesmobile.data.local.InMemoryMasterKeyProvider
import io.github.anshuman2401.hermesmobile.data.origin.CanonicalOrigin
import io.github.anshuman2401.hermesmobile.data.session.FakeGatewayProtocolAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class BrainAuxiliaryModelsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun allElevenRowsLoadTogetherAndPreserveAutoPinsAliasesAndUnknownAssignments() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val assignments = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("auto", "synthetic-legacy-pin")
                this[BrainAuxiliaryTask.Compression] = Value("main", "")
                this[BrainAuxiliaryTask.SkillsHub] = Value("synthetic-unknown-provider", "synthetic-unknown-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(assignments))

            val result = fixture.repository().load()
            val snapshot = (result as BrainAuxiliaryModelsLoadResult.Available).snapshot
            assertEquals(BrainAuxiliaryTask.entries, snapshot.tasks.map { it.task })
            assertEquals("auto", snapshot.assignment(BrainAuxiliaryTask.Vision).provider)
            assertEquals("synthetic-legacy-pin", snapshot.assignment(BrainAuxiliaryTask.Vision).model)
            assertEquals("main", snapshot.assignment(BrainAuxiliaryTask.Compression).provider)
            assertEquals("", snapshot.assignment(BrainAuxiliaryTask.Compression).model)
            assertEquals("synthetic-unknown-provider", snapshot.assignment(BrainAuxiliaryTask.SkillsHub).provider)
            assertFalse(snapshot.toString().contains("synthetic-private-url"))
        }
    }

    @Test
    fun missingKnownRowIsUnavailableAndDuplicateKnownRowsAreMalformed() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val assignments = baselineAssignments() - BrainAuxiliaryTask.Curator
            fixture.enqueueLoad(fixture.auxiliaryJson(assignments))
            val repository = fixture.repository()
            val partial = repository.load() as BrainAuxiliaryModelsLoadResult.Partial
            val missing = partial.snapshot.tasks.last()
            assertEquals(BrainAuxiliaryTask.Curator, missing.task)
            assertEquals(null, missing.assignment)
            assertEquals(BrainAuxiliaryTaskUnavailableReason.MISSING_FROM_HOST, missing.unavailableReason)
            val rejected = repository.apply(
                partial.snapshot.snapshotId,
                BrainAuxiliaryTask.Curator,
                BrainAuxiliaryDraft.Auto
            )
            assertEquals(BrainAuxiliaryModelsApplyFailureReason.TASK_UNAVAILABLE,
                (rejected as BrainAuxiliaryModelsApplyResult.Rejected).reason)
        }

        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(fixture.optionsJsonForTest()))
            fixture.server.enqueue(MockResponse().setBody(fixture.infoJsonForTest()))
            fixture.server.enqueue(MockResponse().setBody(
                "{\"tasks\":[{\"task\":\"vision\",\"provider\":\"synthetic-provider\",\"model\":\"synthetic-model\"},{\"task\":\"vision\",\"provider\":\"synthetic-provider\",\"model\":\"synthetic-alt\"}]}"
            ))
            val result = fixture.repository().load()
            assertEquals(BrainAuxiliaryModelsLoadFailureReason.MALFORMED,
                (result as BrainAuxiliaryModelsLoadResult.Unavailable).failure.reason)
        }
    }

    @Test
    fun catalogFailureStillReadsAuthoritativeAuxiliaryRowsButBlocksNewConcreteSelection() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(500))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(baselineAssignments())))

            val repository = fixture.repository()
            val result = repository.load()
            val snapshot = (result as BrainAuxiliaryModelsLoadResult.Partial).snapshot
            assertFalse(snapshot.catalogAvailable)
            assertEquals("synthetic-provider", snapshot.assignment(BrainAuxiliaryTask.Vision).provider)
            val rejected = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-alt")
            )
            assertEquals(
                BrainAuxiliaryModelsApplyFailureReason.CATALOG_UNAVAILABLE,
                (rejected as BrainAuxiliaryModelsApplyResult.Rejected).reason
            )
        }
    }

    @Test
    fun namedProfileRejectedPostRotationMustCarryCookieToNextExplicitApply() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("named_profile")
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository(fixture.connectedGateway())
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            val initialRequests = fixture.takeRequests(3)
            assertTrue(initialRequests.all { it?.method == "GET" })
            assertTrue(initialRequests.all { it?.requestUrl?.encodedQuery == "profile=named_profile" })

            fixture.server.enqueue(MockResponse().setResponseCode(400)
                .addHeader("Set-Cookie", "session=synthetic-rejected-successor; Path=/")
                .setBody("{\"ok\":false}"))
            val rejected = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(rejected is BrainAuxiliaryModelsApplyResult.Rejected)
            val rejectedPost = fixture.takeRequests(1).single()!!
            assertEquals("POST", rejectedPost.method)
            assertEquals("profile=named_profile", rejectedPost.requestUrl?.encodedQuery)
            assertEquals("session=synthetic-cookie", rejectedPost.getHeader("Cookie"))
            assertEquals(4, fixture.server.requestCount)

            val changed = initial.toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-model")
            }
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(changed)))
            val saved = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue("accepted rejected-response successor must keep the next apply usable", saved is BrainAuxiliaryModelsApplyResult.Saved)
            val nextPost = fixture.takeRequests(1).single()!!
            assertEquals("POST", nextPost.method)
            assertEquals("profile=named_profile", nextPost.requestUrl?.encodedQuery)
            assertEquals("session=synthetic-rejected-successor", nextPost.getHeader("Cookie"))
        }
    }

    @Test
    fun explicitConfirmationRefusalRotationIsCarriedToTheNextFreshApply() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("named_profile")
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository(fixture.connectedGateway())
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.takeRequests(3)

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":false,\"confirm_required\":true,\"confirm_message\":\"synthetic-private-url\"}"
            ).addHeader("Set-Cookie", "session=synthetic-warning-successor; Path=/"))
            val warning = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            ) as BrainAuxiliaryModelsApplyResult.ConfirmationRequired
            assertEquals(BrainAuxiliaryModelsRepository.CONFIRMATION_WARNING, warning.warning)
            assertFalse(warning.warning.contains("synthetic-private-url"))
            val warningPost = fixture.takeRequests(1).single()!!
            assertEquals("session=synthetic-cookie", warningPost.getHeader("Cookie"))

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":false,\"confirm_required\":true,\"confirm_message\":\"synthetic-private-url\"}"
            ).addHeader("Set-Cookie", "session=synthetic-confirm-successor; Path=/"))
            val refusal = repository.confirmWarning(snapshot.snapshotId)
            assertTrue(refusal is BrainAuxiliaryModelsApplyResult.Rejected)
            val refusalPost = fixture.takeRequests(1).single()!!
            assertEquals("session=synthetic-warning-successor", refusalPost.getHeader("Cookie"))
            assertEquals("profile=named_profile", refusalPost.requestUrl?.encodedQuery)
            assertEquals(5, fixture.server.requestCount)

            val changed = initial.toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-alt")
            }
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(changed)))
            val saved = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-alt")
            )
            assertTrue("latest refusal successor must remain usable", saved is BrainAuxiliaryModelsApplyResult.Saved)
            val nextPost = fixture.takeRequests(1).single()!!
            assertEquals("session=synthetic-confirm-successor", nextPost.getHeader("Cookie"))
            assertEquals("profile=named_profile", nextPost.requestUrl?.encodedQuery)
            val beforeRepeatedConfirm = fixture.server.requestCount
            assertTrue(repository.confirmWarning(snapshot.snapshotId) is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(beforeRepeatedConfirm, fixture.server.requestCount)
        }
    }

    @Test
    fun newApplyIntentInvalidatesOldWarningForDifferentTaskAndDraft() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            fixture.select("named_profile")
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository(fixture.connectedGateway())
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.takeRequests(3)
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":false,\"confirm_required\":true}"
            ))
            val firstWarning = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(firstWarning is BrainAuxiliaryModelsApplyResult.ConfirmationRequired)
            fixture.takeRequests(1)

            val afterChangedTask = initial.toMutableMap().apply {
                this[BrainAuxiliaryTask.Compression] = Value("auto", "")
            }
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"compression\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(afterChangedTask)))
            val afterChangedDraft = afterChangedTask.toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-alt")
            }
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(afterChangedDraft)))
            val changedTask = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Compression,
                BrainAuxiliaryDraft.Auto
            )
            val changedDraft = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-alt")
            )
            assertTrue("different task must not reuse the old warning", changedTask is BrainAuxiliaryModelsApplyResult.Saved)
            assertTrue("different draft must not reuse the old warning", changedDraft is BrainAuxiliaryModelsApplyResult.Saved)
            val beforeOldConfirm = fixture.server.requestCount
            assertTrue(repository.confirmWarning(snapshot.snapshotId) is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(beforeOldConfirm, fixture.server.requestCount)
        }
    }

    @Test
    fun wrongScopeAcknowledgementStaysNotConfirmedAfterAuthoritativeReadback() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository(fixture.connectedGateway())
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.takeRequests(3)
            val changed = initial.toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-model")
            }
            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"main\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(changed)))
            val result = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            ) as BrainAuxiliaryModelsApplyResult.NotConfirmed
            assertEquals(BrainAuxiliaryModelsApplyFailureReason.WRITE_REJECTED, result.reason)
            assertTrue(result.mayHaveSaved)
            assertEquals(5, fixture.server.requestCount)
            assertEquals("POST", fixture.takeRequests(1).single()?.method)
            assertEquals("GET", fixture.takeRequests(1).single()?.method)
            assertFalse(result.toString().contains("synthetic-private-url"))

            val beforeSameSnapshotApply = fixture.server.requestCount
            val sameSnapshotApply = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(sameSnapshotApply is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(beforeSameSnapshotApply, fixture.server.requestCount)

            fixture.server.enqueue(MockResponse().setResponseCode(500))
            fixture.server.enqueue(MockResponse().setResponseCode(500))
            val failedReload = repository.load()
            assertTrue(failedReload is BrainAuxiliaryModelsLoadResult.Unavailable)

            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val freshSnapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            assertTrue(freshSnapshot.snapshotId > snapshot.snapshotId)

            val beforeOldIdApply = fixture.server.requestCount
            val oldIdApply = repository.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(oldIdApply is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(beforeOldIdApply, fixture.server.requestCount)

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(changed)))
            val savedWithFreshSnapshot = repository.apply(
                freshSnapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(savedWithFreshSnapshot is BrainAuxiliaryModelsApplyResult.Saved)
        }
    }

    @Test
    fun dispatchedPostWithoutResponseHeadersIsPossibleSaveAndCannotReplayOldSnapshot() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository()
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.drain(3)
            val draft = BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")

            // Synthetic MockWebServer only: the request is received, then headers are lost.
            fixture.server.enqueue(
                MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            )
            val result = repository.apply(snapshot.snapshotId, BrainAuxiliaryTask.Vision, draft)
            val notConfirmed = result as BrainAuxiliaryModelsApplyResult.NotConfirmed
            assertEquals(BrainAuxiliaryModelsApplyFailureReason.AUTH_UNAVAILABLE, notConfirmed.reason)
            assertTrue("a dispatched POST may have reached the host", notConfirmed.mayHaveSaved)

            val attemptedPost = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(attemptedPost)
            assertEquals("POST", attemptedPost?.method)

            val beforeReplay = fixture.server.requestCount
            val replay = repository.apply(snapshot.snapshotId, BrainAuxiliaryTask.Vision, draft)
            assertTrue(replay is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(BrainAuxiliaryModelsApplyFailureReason.STALE,
                (replay as BrainAuxiliaryModelsApplyResult.NotConfirmed).reason)
            assertEquals(beforeReplay, fixture.server.requestCount)
        }
    }

    @Test
    fun concreteAndAutoWritesAreSparseAndOnlyReadbackConfirmsTheSameTarget() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val initial = baselineAssignments().toMutableMap()
            initial[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val actual = fixture.repository()
            val snapshot = (actual.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.drain(3)

            val endpointRejected = actual.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Review,
                BrainAuxiliaryDraft.Concrete("synthetic-endpoint-provider", "synthetic-endpoint-model")
            )
            assertEquals(
                BrainAuxiliaryModelsApplyFailureReason.ENDPOINT_UNSUPPORTED,
                (endpointRejected as BrainAuxiliaryModelsApplyResult.Rejected).reason
            )

            fixture.server.enqueue(MockResponse().setBody("{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"))
            val concreteAssignments = initial.toMutableMap()
            concreteAssignments[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-model")
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(concreteAssignments)))
            val savedConcrete = actual.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Vision,
                BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")
            )
            assertTrue(savedConcrete is BrainAuxiliaryModelsApplyResult.Saved)

            val firstPost = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(firstPost)
            assertEquals("POST", firstPost?.method)
            assertEquals("profile=default", firstPost?.requestUrl?.encodedQuery)
            val concreteBody = GatewayJson.parse(firstPost!!.body.readUtf8()).asObjectOrNull()!!
            assertEquals(setOf("scope", "task", "provider", "model"), concreteBody.map.keys)
            assertEquals("auxiliary", concreteBody["scope"]?.asStringOrNull())
            assertEquals("vision", concreteBody["task"]?.asStringOrNull())
            assertEquals(null, concreteBody["reasoning_effort"])
            fixture.drain(1)

            val autoAssignments = concreteAssignments.toMutableMap()
            autoAssignments[BrainAuxiliaryTask.Compression] = Value("auto", "")
            fixture.server.enqueue(MockResponse().setBody("{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"compression\"]}"))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(autoAssignments)))
            val savedAuto = actual.apply(
                snapshot.snapshotId,
                BrainAuxiliaryTask.Compression,
                BrainAuxiliaryDraft.Auto
            )
            assertTrue(savedAuto is BrainAuxiliaryModelsApplyResult.Saved)

            val secondPost = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(secondPost)
            val autoBody = GatewayJson.parse(secondPost!!.body.readUtf8()).asObjectOrNull()!!
            assertEquals(setOf("scope", "task", "provider", "model", "reasoning_effort"), autoBody.map.keys)
            assertEquals(null, autoBody["reasoning_effort"]?.asStringOrNull())
            assertEquals("auto", autoBody["provider"]?.asStringOrNull())
            assertEquals("", autoBody["model"]?.asStringOrNull())
            assertFalse(autoBody.map.containsKey("base_url"))
        }
    }

    @Test
    fun warningIsRefusalUntilOneExplicitAckAndFailedReadbackIsNotSavedOrReplayed() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository()
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.drain(3)
            val draft = BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":false,\"confirm_required\":true,\"confirm_message\":\"synthetic-private-url\"}"
            ))
            val warning = repository.apply(snapshot.snapshotId, BrainAuxiliaryTask.Vision, draft)
            assertTrue(warning is BrainAuxiliaryModelsApplyResult.ConfirmationRequired)
            val confirmation = warning as BrainAuxiliaryModelsApplyResult.ConfirmationRequired
            assertEquals(
                BrainAuxiliaryModelsRepository.CONFIRMATION_WARNING,
                confirmation.warning
            )
            assertFalse(confirmation.warning.contains("synthetic-private-url"))

            val repeatedApply = repository.apply(snapshot.snapshotId, BrainAuxiliaryTask.Vision, draft)
            assertTrue(repeatedApply is BrainAuxiliaryModelsApplyResult.ConfirmationRequired)
            assertEquals(4, fixture.server.requestCount)
            fixture.drain(1)

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            val changed = baselineAssignments().toMutableMap()
            changed[BrainAuxiliaryTask.Vision] = Value("synthetic-provider", "synthetic-model")
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(changed)))
            val saved = repository.confirmWarning(snapshot.snapshotId)
            assertTrue(saved is BrainAuxiliaryModelsApplyResult.Saved)

            val confirmedPost = fixture.server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(confirmedPost)
            val confirmedBody = GatewayJson.parse(confirmedPost!!.body.readUtf8()).asObjectOrNull()!!
            assertEquals(true, confirmedBody["confirm_expensive_model"]?.asBooleanOrNull())
            assertEquals(6, fixture.server.requestCount)

            val secondAck = repository.confirmWarning(snapshot.snapshotId)
            assertTrue(secondAck is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(6, fixture.server.requestCount)
        }
    }

    @Test
    fun mismatchedAndUnavailableReadbackNeverReportsSavedAndCancellationCancelsActualCall() = runBlocking {
        Fixture(temp.newFolder()).use { fixture ->
            val initial = baselineAssignments().toMutableMap().apply {
                this[BrainAuxiliaryTask.Vision] = Value("synthetic-old-provider", "synthetic-old-model")
            }
            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val repository = fixture.repository()
            val snapshot = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            val draft = BrainAuxiliaryDraft.Concrete("synthetic-provider", "synthetic-model")

            fixture.server.enqueue(MockResponse().setBody(
                "{\"ok\":true,\"scope\":\"auxiliary\",\"tasks\":[\"vision\"]}"
            ))
            fixture.server.enqueue(MockResponse().setBody(fixture.auxiliaryJson(initial)))
            val mismatch = repository.apply(snapshot.snapshotId, BrainAuxiliaryTask.Vision, draft)
            val notConfirmed = mismatch as BrainAuxiliaryModelsApplyResult.NotConfirmed
            assertEquals(
                BrainAuxiliaryModelsApplyFailureReason.READBACK_MISMATCH,
                notConfirmed.reason
            )
            assertNotNull(notConfirmed.authoritativeSnapshot)

            val beforeSameSnapshotApply = fixture.server.requestCount
            val sameSnapshotApply = repository.apply(snapshot.snapshotId,
                BrainAuxiliaryTask.Vision, draft)
            assertTrue(sameSnapshotApply is BrainAuxiliaryModelsApplyResult.NotConfirmed)
            assertEquals(beforeSameSnapshotApply, fixture.server.requestCount)

            fixture.enqueueLoad(fixture.auxiliaryJson(initial))
            val fresh = (repository.load() as BrainAuxiliaryModelsLoadResult.Available).snapshot
            fixture.server.enqueue(MockResponse().setResponseCode(500))
            fixture.server.enqueue(MockResponse().setResponseCode(503))
            val unavailable = repository.apply(fresh.snapshotId, BrainAuxiliaryTask.Vision, draft)
            assertEquals(
                BrainAuxiliaryModelsApplyFailureReason.READBACK_UNAVAILABLE,
                (unavailable as BrainAuxiliaryModelsApplyResult.NotConfirmed).reason
            )
            val disabled = repository.apply(fresh.snapshotId, BrainAuxiliaryTask.Vision, draft)
            assertEquals(BrainAuxiliaryModelsApplyFailureReason.STALE,
                (disabled as BrainAuxiliaryModelsApplyResult.NotConfirmed).reason)
        }

        Fixture(temp.newFolder()).use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(fixture.optionsJsonForTest()))
            fixture.server.enqueue(MockResponse().setBody(fixture.infoJsonForTest()))
            fixture.server.enqueue(
                MockResponse().setBody("partial")
                    .setHeader("Content-Length", "100000")
                    .setSocketPolicy(SocketPolicy.KEEP_OPEN)
            )
            val repository = fixture.repository()
            val loading = async { repository.load() }
            withContext(Dispatchers.IO) {
                assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
                assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
                assertNotNull(fixture.server.takeRequest(5, TimeUnit.SECONDS))
            }
            loading.cancelAndJoin()
            assertTrue(loading.isCancelled)
        }
    }

    private data class Value(val provider: String, val model: String)

    private fun baselineAssignments(): Map<BrainAuxiliaryTask, Value> =
        BrainAuxiliaryTask.entries.associateWith { Value("synthetic-provider", "synthetic-model") }

    private fun BrainAuxiliaryModelsSnapshot.assignment(task: BrainAuxiliaryTask): BrainAuxiliaryAssignment =
        tasks.first { it.task == task }.assignment!!

    private class Fixture(
        folder: java.io.File,
        private val client: OkHttpClient = OkHttpClient()
    ) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        private val origin = CanonicalOrigin.parse(server.url("/").toString().removeSuffix("/")).getOrThrow()
        private val vault = EncryptedVault(folder, AesGcmCipherEngine(InMemoryMasterKeyProvider()))
        private val auth: AuthRepository = DefaultAuthRepository(vault, OkHttpAuthTransport())

        init {
            runBlocking {
                vault.saveConnectionProfile(ConnectionProfile("synthetic-local", origin.originUrl, "Synthetic"))
                vault.setActiveProfileId("synthetic-local")
                vault.saveSelectedContextProfile("default")
                vault.saveAuthCredential(
                    AuthCredential(
                        "cookie-session",
                        "basic",
                        mapOf("session" to "synthetic-cookie"),
                        origin.originUrl
                    )
                )
            }
        }

        fun repository(): BrainAuxiliaryModelsRepository = BrainAuxiliaryModelsRepository(
            vaultStorage = vault,
            authRepository = auth,
            gatewayAdapter = null,
            transport = OkHttpBrainModelReadTransport(client)
        )

        fun repository(gatewayAdapter: GatewayProtocolAdapter): BrainAuxiliaryModelsRepository = BrainAuxiliaryModelsRepository(
            vaultStorage = vault,
            authRepository = auth,
            gatewayAdapter = gatewayAdapter,
            transport = OkHttpBrainModelReadTransport(client)
        )

        fun select(profile: String) = runBlocking { vault.saveSelectedContextProfile(profile) }

        fun connectedGateway(): GatewayProtocolAdapter {
            val fake = FakeGatewayProtocolAdapter()
            return object : GatewayProtocolAdapter by fake {
                override val connectionGeneration: Long get() = 1L

                override fun isScopedConnectionCurrent(
                    expectedOrigin: CanonicalOrigin,
                    generation: Long
                ): Boolean = generation == 1L && expectedOrigin == origin
            }
        }

        fun takeRequests(count: Int): List<okhttp3.mockwebserver.RecordedRequest?> =
            (0 until count).map { server.takeRequest(5, TimeUnit.SECONDS) }

        fun enqueueLoad(auxiliary: String) {
            server.enqueue(MockResponse().setBody(optionsJsonForTest()))
            server.enqueue(MockResponse().setBody(infoJsonForTest()))
            server.enqueue(MockResponse().setBody(auxiliary))
        }

        fun drain(count: Int) {
            repeat(count) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        }

        fun optionsJsonForTest() = optionsJson()
        fun infoJsonForTest() = infoJson()

        fun auxiliaryJson(
            assignments: Map<BrainAuxiliaryTask, Value>,
            effort: Map<BrainAuxiliaryTask, String?> = emptyMap(),
            endpointTasks: Set<BrainAuxiliaryTask> = emptySet()
        ): String = """{"main":{"provider":"synthetic-main","model":"synthetic-main-model"},"tasks":[${assignments.entries.joinToString(",") { (task, value) ->
            val effortField = if (effort.containsKey(task)) {
                ",\"reasoning_effort\":" + (effort[task]?.let { "\"$it\"" } ?: "null")
            } else ""
            val endpointField = if (task in endpointTasks) ",\"base_url\":\"synthetic-private-url\"" else ""
            "{\"task\":\"${task.wireKey}\",\"provider\":\"${value.provider}\",\"model\":\"${value.model}\"$effortField$endpointField}"
        }}]}"""

        private fun optionsJson() = """{"provider":"","model":"","providers":[{"slug":"synthetic-provider","authenticated":true,"models":["synthetic-model","synthetic-alt"]},{"slug":"synthetic-old-provider","authenticated":true,"models":["synthetic-old-model"]},{"slug":"synthetic-endpoint-provider","authenticated":true,"models":["synthetic-endpoint-model"],"api_url":"synthetic-private-url"},{"slug":"synthetic-unauthenticated","authenticated":false,"models":["synthetic-hidden"]}]}"""
        private fun infoJson() = """{"provider":"","model":""}"""

        override fun close() { server.shutdown() }
    }
}
