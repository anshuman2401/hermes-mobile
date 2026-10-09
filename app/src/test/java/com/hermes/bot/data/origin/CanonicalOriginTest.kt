package com.hermes.bot.data.origin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalOriginTest {
    @Test
    fun canonicalHttpAndLegacyHttpsHaveExactDerivedOrigins() {
        val http = CanonicalOrigin.parse("http://Example.Local:9119").getOrThrow()
        val https = CanonicalOrigin.parse("https://example.local:8443").getOrThrow()

        assertEquals("http://example.local:9119", http.originUrl)
        assertEquals("ws://example.local:9119", http.webSocketUrl)
        assertEquals("https://example.local:8443", https.originUrl)
        assertEquals("wss://example.local:8443", https.webSocketUrl)
        assertFalse(http.isSameOrigin(https))
    }

    @Test
    fun rawOriginParserRejectsUnsafeAuthorityAndPathAmbiguity() {
        val invalid = listOf(
            "ftp://hermes.local", "ws://hermes.local", "http://user@hermes.local",
            "http://hermes.local?ticket=secret", "http://hermes.local#fragment",
            "http://hermes%2Elocal:9119", "http://hermes.local\\path",
            "http://hermes.local:0", "http://hermes.local:65536", "http://hermes.local/../admin"
        )
        invalid.forEach { assertTrue("Expected rejection for $it", CanonicalOrigin.parse(it).isFailure) }
    }

    @Test
    fun ipv6IsBracketedExactlyOnceAndSubpathsCannotEscapeOrigin() {
        val origin = CanonicalOrigin.parse("http://[2001:db8::1]:9119").getOrThrow()
        assertEquals("http://[2001:db8::1]:9119", origin.originUrl)
        assertEquals("ws://[2001:db8::1]:9119", origin.webSocketUrl)
        assertEquals("http://[2001:db8::1]:9119/api/status", origin.resolveHttp("/api/status"))
        assertTrue(runCatching { origin.resolveHttp("/../secret") }.isFailure)
    }

    @Test
    fun secureCookieIsNeverApplicableToHttp() {
        val https = CanonicalOrigin.parse("https://hermes.local:9119").getOrThrow()
        val http = CanonicalOrigin.parse("http://hermes.local:9119").getOrThrow()
        val cookie = OriginCookie("session", "synthetic", null, sourceOrigin = https.originUrl)

        assertTrue(cookie.isApplicableTo(https))
        assertFalse(cookie.isApplicableTo(http))
    }
}
