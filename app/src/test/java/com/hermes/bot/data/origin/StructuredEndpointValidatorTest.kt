package com.hermes.bot.data.origin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredEndpointValidatorTest {
    @Test
    fun validHostnameIpv4AndIpv6ConstructExactHttpAndWsEndpoints() {
        val cases = listOf(
            "Hermes.Local" to "http://hermes.local:9119",
            "192.0.2.11" to "http://192.0.2.11:1",
            "[2001:db8::1]" to "http://[2001:db8::1]:65535"
        )
        val ports = listOf("9119", "1", "65535")
        cases.forEachIndexed { index, (host, expected) ->
            val result = StructuredEndpointValidator.parse(ConnectProtocol.HTTP, host, ports[index])
            assertTrue(result is StructuredEndpointValidator.ValidationResult.Success)
            val origin = (result as StructuredEndpointValidator.ValidationResult.Success).origin
            assertEquals(expected, origin.originUrl)
            assertEquals(expected.replaceFirst("http://", "ws://"), origin.webSocketUrl)
        }
    }

    @Test
    fun hostileHostAndPortDraftsAreRejectedBeforeTransport() {
        val hostileHosts = listOf(
            " http-host", "http://host", "host:9119", "host/path", "host?x=1",
            "user@host", "host%2Eexample", "host\\path", "host\u202Eevil", "fe80::1%wlan0",
            "999.1.1.1", "-bad.example", "bad..example"
        )
        hostileHosts.forEach {
            assertTrue("Expected host rejection for $it", StructuredEndpointValidator.parse(ConnectProtocol.HTTP, it, "9119") is StructuredEndpointValidator.ValidationResult.HostError)
        }
        listOf("", "0", "65536", "-1", "+1", "1.0", " 9119", "９１１９").forEach {
            assertTrue("Expected port rejection for $it", StructuredEndpointValidator.parse(ConnectProtocol.HTTP, "hermes", it) is StructuredEndpointValidator.ValidationResult.PortError)
        }
    }

    @Test
    fun ipv4CanonicalizationStripsLeadingZeroes() {
        val result = StructuredEndpointValidator.parse(ConnectProtocol.HTTP, "010.000.000.001", "9119")
        val origin = (result as StructuredEndpointValidator.ValidationResult.Success).origin
        assertEquals("10.0.0.1", origin.host)
    }
}
