package com.hermes.bot.data.gateway.fake

import com.hermes.bot.data.gateway.json.GatewayJson
import com.hermes.bot.data.gateway.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FakeGatewayFixturesProvenanceTest {

    @Test
    fun manifest_loadsAndValidatesAllCertifiedFixtures() {
        val manifestJson = FakeGatewayFixtures.loadFixture("manifest.json")
        val manifest = GatewayJson.parseOrNull(manifestJson) as? JsonObject
        assertNotNull("manifest.json must parse as JsonObject", manifest)

        val sourceCommit = manifest?.get("source_commit")?.asStringOrNull()
        assertEquals("476fa2c2897e1f6dcbb2b5982756e907b86e9578", sourceCommit)

        val fixtures = manifest?.get("fixtures")?.asArrayOrNull()
        assertNotNull("manifest must contain fixtures array", fixtures)
        assertTrue("manifest must contain at least 14 fixtures", (fixtures?.list?.size ?: 0) >= 14)

        for (item in fixtures!!.list) {
            val obj = item as JsonObject
            val fname = obj["file"]!!.asStringOrNull()!!
            val expectedSha256 = obj["sha256"]!!.asStringOrNull()!!

            val content = FakeGatewayFixtures.loadFixture(fname)
            val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            val actualSha256 = digest.joinToString("") { "%02x".format(it) }

            assertEquals("SHA-256 hash mismatch for fixture $fname", expectedSha256, actualSha256)
        }
    }

    @Test
    fun readyFrame_isSourcedFromCertifiedFixtureResource() {
        val readyFrame = FakeGatewayFixtures.READY_FRAME
        val parsed = GatewayJson.parseOrNull(readyFrame) as? JsonObject
        assertNotNull(parsed)

        val params = parsed?.get("params") as? JsonObject
        assertEquals("gateway.ready", params?.get("type")?.asStringOrNull())

        val payload = params?.get("payload") as? JsonObject
        val epoch = payload?.get("replay_epoch")?.asStringOrNull()
        assertNotNull(epoch)
        assertEquals(FakeGatewayFixtures.TEST_EPOCH_1, epoch)
    }

    @Test
    fun sessionListResponse_isSourcedFromCertifiedFixture() {
        val listJson = FakeGatewayFixtures.SESSION_LIST_RESPONSE
        val parsed = GatewayJson.parseOrNull(listJson) as? JsonObject
        assertNotNull(parsed)
        val sessions = parsed?.get("sessions")?.asArrayOrNull()
        assertNotNull(sessions)
        assertTrue(sessions!!.list.isNotEmpty())
    }

    @Test
    fun replaySinceResponse_isSourcedFromCertifiedFixture() {
        val replayJson = FakeGatewayFixtures.REPLAY_SINCE_RESPONSE
        val parsed = GatewayJson.parseOrNull(replayJson) as? JsonObject
        assertNotNull(parsed)
        val events = parsed?.get("events")?.asArrayOrNull()
        assertNotNull(events)
        assertEquals(3, events!!.list.size)
    }
}
