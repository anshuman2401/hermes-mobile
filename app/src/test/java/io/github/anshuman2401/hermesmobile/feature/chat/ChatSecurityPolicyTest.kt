package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSecurityPolicyTest {

    @Test
    fun isValidExternalUrl_acceptsValidHttpAndHttps() {
        assertTrue(ChatSecurityPolicy.isValidExternalUrl("https://example.com"))
        assertTrue(ChatSecurityPolicy.isValidExternalUrl("https://hermes-agent.nousresearch.com/docs"))
        assertTrue(ChatSecurityPolicy.isValidExternalUrl("http://localhost:8080/test?query=param#anchor"))
        assertTrue(ChatSecurityPolicy.isValidExternalUrl("HTTPS://EXAMPLE.COM/PATH"))
    }

    @Test
    fun isValidExternalUrl_rejectsDangerousAndLocalSchemes() {
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("javascript:alert(1)"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("JAVASCRIPT:alert(1)"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("file:///etc/passwd"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("content://media/external/images"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("vbscript:MsgBox(1)"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("ssh://git@github.com"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("custom-scheme://action"))
    }

    @Test
    fun isValidExternalUrl_rejectsEmptyOrMalformedUrls() {
        assertFalse(ChatSecurityPolicy.isValidExternalUrl(null))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl(""))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("   "))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("not a url at all"))
        assertFalse(ChatSecurityPolicy.isValidExternalUrl("https://"))
    }

    @Test
    fun sanitizeUrl_returnsTrimmedValidUrlOrNull() {
        assertEquals("https://example.com", ChatSecurityPolicy.sanitizeUrl("  https://example.com  "))
        assertNull(ChatSecurityPolicy.sanitizeUrl("javascript:alert(1)"))
    }

    @Test
    fun redactSensitiveData_redactsBearerTokens() {
        val input = "Authorization: Bearer secret_token_123456789.abc_def"
        val output = ChatSecurityPolicy.redactSensitiveData(input)
        assertEquals("Authorization: Bearer [REDACTED]", output)
    }

    @Test
    fun redactSensitiveData_redactsPasswords() {
        val input1 = "user login with password=\"superSecret123\" in session"
        val output1 = ChatSecurityPolicy.redactSensitiveData(input1)
        assertTrue(output1.contains("password: [REDACTED]"))
        assertFalse(output1.contains("superSecret123"))

        val input2 = "key: password: secretValue;"
        val output2 = ChatSecurityPolicy.redactSensitiveData(input2)
        assertTrue(output2.contains("password: [REDACTED]"))
        assertFalse(output2.contains("secretValue"))
    }

    @Test
    fun redactSensitiveData_redactsApiKeys() {
        val input = "Calling OpenAI with sk-1234567890abcdef1234567890 and GitHub ghp_1234567890abcdef1234567890"
        val output = ChatSecurityPolicy.redactSensitiveData(input)
        assertFalse(output.contains("sk-1234567890"))
        assertFalse(output.contains("ghp_1234567890"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun redactSensitiveData_preservesHarmlessText() {
        val input = "This is a normal assistant message discussing architecture."
        assertEquals(input, ChatSecurityPolicy.redactSensitiveData(input))
    }

    @Test
    fun escapeHtml_escapesSpecialCharacters() {
        val input = "<script>alert('test & demo \"quote\"')</script>"
        val output = ChatSecurityPolicy.escapeHtml(input)
        assertEquals(
            "&lt;script&gt;alert(&#x27;test &amp; demo &quot;quote&quot;&#x27;)&lt;/script&gt;",
            output
        )
    }

    @Test
    fun formatToolArgs_retainsFullTextWithoutDisplayRedaction() {
        // PRD v2 Decision #2 & #5: full text retained without display-level security redaction
        val args = mapOf(
            "host" to "api.hermes.com",
            "token" to "Bearer eyJhbGciOi...",
            "password" to "mySecretPassword",
            "count" to 42
        )
        val formatted = ChatSecurityPolicy.formatToolArgs(args)
        assertTrue(formatted.contains("eyJhbGciOi"))
        assertTrue(formatted.contains("mySecretPassword"))
        assertTrue(formatted.contains("host: \"api.hermes.com\""))
        assertTrue(formatted.contains("count: 42"))
    }
}
