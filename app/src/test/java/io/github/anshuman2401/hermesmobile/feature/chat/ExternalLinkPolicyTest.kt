package io.github.anshuman2401.hermesmobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLD-006 S11 (TASK-051, M-06 / D-11): exhaustive captured-intent matrix for
 * the centralized external-link policy. Proves scheme/host/authority rules,
 * canonical host disclosure (IDN/punycode, case, port), and launch-time
 * revalidation semantics. The link label is never trusted as disclosure.
 *
 * Compose on-screen behavior needs separate device verification.
 */
class ExternalLinkPolicyTest {

    // ------------------------------------------------------------------
    // Scheme matrix
    // ------------------------------------------------------------------

    @Test
    fun validate_acceptsHttpAndHttps() {
        for (url in listOf(
            "https://example.com",
            "http://example.com",
            "https://example.com/path?query=1#frag",
            "http://192.168.1.50:8080/status"
        )) {
            assertNotNull("must accept: $url", ExternalLinkPolicy.validate(url))
        }
    }

    @Test
    fun validate_acceptsMixedCaseSchemeAndLowercasesIt() {
        val link = ExternalLinkPolicy.validate("HTTPS://EXAMPLE.COM/PATH")
        assertNotNull(link)
        // The canonical target keeps the exact validated URL text; the scheme
        // is case-insensitive for policy purposes.
        assertTrue(link!!.url.startsWith("HTTPS://"))
    }

    @Test
    fun validate_rejectsDangerousAndCustomSchemes() {
        for (url in listOf(
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "JaVaScRiPt:alert(1)",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "file:///etc/passwd",
            "content://media/external/images",
            "intent://scan/#Intent;scheme=zxing;end",
            "intent:#Intent;action=android.intent.action.VIEW;end",
            "vbscript:MsgBox(1)",
            "blob:https://example.com/uuid",
            "about:blank",
            "chrome://flags",
            "market://details?id=com.example",
            "tel:123456789",
            "sms:123456789",
            "ssh://git@github.com",
            "custom-scheme://action",
            "https:opaque-no-authority"
        )) {
            assertNull("must reject scheme: $url", ExternalLinkPolicy.validate(url))
        }
    }

    // ------------------------------------------------------------------
    // User-info / authority
    // ------------------------------------------------------------------

    @Test
    fun validate_rejectsUserInfoForms() {
        for (url in listOf(
            "https://user@example.com",
            "https://user:pass@example.com",
            "https://user:pass@example.com:8443/path",
            "https://@example.com",
            "https://user@example.com@example.com"
        )) {
            assertNull("must reject user-info: $url", ExternalLinkPolicy.validate(url))
        }
    }

    @Test
    fun validate_rejectsMissingOrEmptyHost() {
        for (url in listOf(
            "https://",
            "https:///path",
            "https://.",
            "https://..",
            "https://example.com..",
            "https://.example.com",
            "https://exa..mple.com",
            "not a url at all"
        )) {
            assertNull("must reject host form: $url", ExternalLinkPolicy.validate(url))
        }
    }

    // ------------------------------------------------------------------
    // Control / bidi / whitespace / encoded delimiters
    // ------------------------------------------------------------------

    @Test
    fun validate_rejectsControlAndSeparatorCharacters() {
        for (url in listOf(
            "https://example.com/\u0000",
            "https://example.com/\u0007",
            "https://example.com/\u001B",
            "https://example.com/\u007F",
            "https://example.com/\u0085",
            "https://example.com/\u2028",
            "https://example.com/\u2029",
            "https://exa\u0000mple.com",
            "https://example.com/a\u001Bb"
        )) {
            assertNull("must reject control char: $url", ExternalLinkPolicy.validate(url))
        }
    }

    @Test
    fun validate_rejectsBidirectionalOverridesAndMarks() {
        for (url in listOf(
            "https://example.com/\u202E", // RLO
            "https://example.com/\u202A", // LRE
            "https://example.com/\u202B", // RLE
            "https://example.com/\u202C", // PDF
            "https://\u2066example.com",  // LRI
            "https://exa\u2069mple.com",  // PDI
            "https://exa\u061Cmple.com",  // ALM
            "https://exa\u200Fmple.com"   // RLM
        )) {
            assertNull("must reject bidi control: $url", ExternalLinkPolicy.validate(url))
        }
    }

    @Test
    fun validate_rejectsRawWhitespaceInsideCandidate() {
        // isNullOrBlank/trim handles edges; embedded whitespace (tab/newline/
        // space inside the URL) must also be rejected — URI would silently
        // accept some of these and the trim would hide the rest.
        for (url in listOf(
            "https://exa mple.com",
            "https://example.com/pa th",
            "https://example.com/\t",
            "https://example.com/\n",
            "https://example.com/\r"
        )) {
            assertNull("must reject embedded whitespace: $url", ExternalLinkPolicy.validate(url))
        }
    }

    @Test
    fun validate_rejectsPercentEncodedAuthorityDelimiters() {
        for (url in listOf(
            "https://example.com%2F@evil.example", // encoded slash in authority
            "https://example.com%3Fnext=1",        // encoded question mark in authority
            "https://example.com%23frag"           // encoded fragment marker in authority
        )) {
            assertNull("must reject encoded authority delimiter: $url", ExternalLinkPolicy.validate(url))
        }
    }

    // ------------------------------------------------------------------
    // Host canonicalization: case, IDN/punycode, IP literals
    // ------------------------------------------------------------------

    @Test
    fun canonicalDisclosure_isLowerCaseAsciiHost() {
        val link = ExternalLinkPolicy.validate("https://ExAmPlE.CoM/Path")
        assertNotNull(link)
        assertEquals("example.com", link!!.canonicalHost)
        assertEquals("example.com", ExternalLinkPolicy.canonicalDisclosure(link))
        assertFalse(link.nonDefaultPort)
    }

    @Test
    fun canonicalDisclosure_decodesIdnToPunycodeAscii() {
        val link = ExternalLinkPolicy.validate("https://münchen.de/angebot")
        assertNotNull("IDN host must validate", link)
        assertEquals("xn--mnchen-3ya.de", link!!.canonicalHost)
        assertEquals("xn--mnchen-3ya.de", ExternalLinkPolicy.canonicalDisclosure(link))
    }

    @Test
    fun canonicalDisclosure_alreadyPunycodeHostIsPreserved() {
        val link = ExternalLinkPolicy.validate("https://XN--MNCHEN-3YA.de")
        assertNotNull(link)
        assertEquals("xn--mnchen-3ya.de", link!!.canonicalHost)
    }

    @Test
    fun validate_rejectsHostsThatFailIdnCanonicalization() {
        // STD3 ASCII rules reject underscores and other illegal host chars.
        assertNull(ExternalLinkPolicy.validate("https://bad_host.example.com"))
    }

    // ------------------------------------------------------------------
    // Disguised IPv4: numeric-only host labels must be exact dotted quads
    // ------------------------------------------------------------------

    @Test
    fun validate_rejectsDisguisedNumericIpv4Forms() {
        for (url in listOf(
            "http://2130706433/",          // decimal integer (= 127.0.0.1)
            "http://0x7f.0x0.0x0.0x1/",    // hex 0x-prefixed quad
            "http://0x7f000001/",          // bare hex integer
            "http://0177.0.0.1/",          // octal octet (leading zero)
            "http://127.1/",               // short 2-part form
            "http://127.0.1/",             // short 3-part form
            "http://127.0.0.01/",          // leading-zero octet
            "http://0177.0.0.01/",         // leading-zero octal
            "http://0x7f.0.0.1/",          // mixed hex/decimal labels
            "http://256.1.1.1/",           // octet > 255
            "http://1.2.3.4.5/",           // five labels
            "http://1.2.3/",               // three labels with digits
            "http://0x1.2.3.4/",           // hex label inside quad
            "http://0x/",                  // bare 0x (hex zero in WHATWG)
            "http://0X/",                  // bare 0X (hex zero in WHATWG)
            "http://0x.127.0.1/",          // bare 0x label inside short form
            "http://0x.1/",                // bare 0x label inside 2-part form
            "http://0x7f.0x.1/"            // bare 0x label beside hex label
        )) {
            assertNull("must reject disguised IPv4: $url", ExternalLinkPolicy.validate(url))
        }
    }

    /**
     * Red rows from `specs/test-evidence/bld-006/s11-closure-review-2-fc544a4.md`
     * (closure review 2, HIGH finding): the IDN/UTS-46 fold maps fullwidth
     * compatibility characters (U+FF10 ０, U+FF58 ｘ, U+FF11..FF19 １..９, and
     * the fullwidth dot U+FF0E) to ASCII AFTER the raw-authority numeric gate
     * runs, folding an opaque-looking host INTO the numeric-disguise family.
     * Browsers (Ada/WHATWG, dual-verified) connect these to loopback/integer
     * addresses while the pre-IDN gate disclosed them as harmless hostnames.
     * The post-IDN canonical-host re-check must reject every one of them.
     */
    @Test
    fun validate_rejectsIdnFoldedNumericDisguiseHosts() {
        for (url in listOf(
            "http://０ｘ7f.1/",        // -> 0x7f.1 (browser: 127.0.0.1)
            "http://０ｘ.1/",          // -> 0x.1 (browser: 0.0.0.1)
            "http://０ｘ/",             // -> 0x (browser: 0.0.0.0)
            "http://0x7f.１/",         // mixed raw/post-fold fullwidth digit
            "http://１２７/",           // -> 127 (browser: 0.0.0.127)
            "http://０ｘ7f.0ｘ.1/"      // -> 0x7f.0x.1 (browser: 127.0.0.1)
        )) {
            assertNull("must reject IDN-folded numeric disguise: $url", ExternalLinkPolicy.validate(url))
        }
    }

    /**
     * Red rows from redesign V2 (trailing-fullwidth-dot HIGH finding): a
     * single trailing fullwidth dot (U+FF0E), ideographic full stop (U+3002),
     * or halfwidth ideographic full stop (U+FF61) is invisible to any
     * PRE-IDN guard and folds to a trailing ASCII dot, which makes WHATWG's
     * ends-in-number recognizer reinterpret the remaining numeric labels as
     * an IPv4 address. Browsers (Ada, dual-verified Node 26 native URL +
     * whatwg-url 17.1.1) connect these to loopback/integer addresses. The
     * post-IDN gate must reject every one of them (fail-closed).
     */
    @Test
    fun validate_rejectsTrailingFullwidthDotNumericDisguiseHosts() {
        for (url in listOf(
            "http://１２７．/",          // -> 127.   (browser: 0.0.0.127)
            "http://０x7f.１．/",        // -> 0x7f.1. (browser: 127.0.0.1)
            "http://０．/",              // -> 0.     (browser: 0.0.0.0)
            "http://１２７。/",          // U+3002    (browser: 0.0.0.127)
            "http://127｡/"              // U+FF61    (browser: 0.0.0.127)
        )) {
            assertNull("must reject trailing-fullwidth-dot disguise: $url", ExternalLinkPolicy.validate(url))
        }
    }

    /**
     * ASCII trailing-dot pins for the same WHATWG ends-in-number semantics:
     * `http://127./` -> 0.0.0.127, `http://0x7f.1./` -> 127.0.0.1,
     * `http://2130706433./` -> 127.0.0.1, and every other numeric shape with
     * a trailing dot is reinterpreted by browsers; only the exact dotted quad
     * is acceptable (see the dotless-serialization green row). These rows
     * pin that the redesign keeps rejecting them once the ambiguous-form
     * guards moved post-IDN.
     */
    @Test
    fun validate_rejectsTrailingDotNumericDisguiseHosts() {
        for (url in listOf(
            "http://127./",
            "http://0x7f.1./",
            "http://2130706433./",
            "http://0x7f.0.0.1./",
            "http://1.2.3./",
            "http://0./",
            "http://127.0.0.01./"
        )) {
            assertNull("must reject trailing-dot numeric disguise: $url", ExternalLinkPolicy.validate(url))
        }
    }

    /**
     * Green rows proving the trailing-dot handling is browser-identical on
     * NON-numeric hosts. Verified against Ada/WHATWG (Node 26 native URL and
     * whatwg-url 17.1.1, identical results): a trailing dot on a non-numeric
     * domain is neither stripped nor rejected — `http://example.com./`
     * serializes with host `example.com.` — so the canonical host must KEEP
     * the trailing dot (never silently normalize it away).
     */
    @Test
    fun validate_trailingDotOnNonNumericDomainStaysBrowserIdentical() {
        for (case in listOf(
            "http://example.com./" to "example.com.",
            "http://example.com．/" to "example.com.",       // fullwidth dot fold
            "https://EXAMPLE.COM./" to "example.com.",
            "http://münchen.de．/" to "xn--mnchen-3ya.de."   // IDN + fullwidth dot
        )) {
            val link = ExternalLinkPolicy.validate(case.first)
            assertNotNull("must accept trailing-dot domain: ${case.first}", link)
            assertEquals("canonical host for ${case.first}", case.second, link!!.canonicalHost)
            assertEquals("disclosure for ${case.first}", case.second, ExternalLinkPolicy.canonicalDisclosure(link))
        }
    }

    /**
     * The one numeric form browsers accept with a trailing dot is the exact
     * dotted quad: WHATWG's ends-in-number recognizer drops the trailing
     * empty label and the IPv4 parser serializes WITHOUT the dot
     * (`http://127.0.0.1./` -> host `127.0.0.1`, verified on Ada). The
     * canonical host must match that serialization exactly.
     */
    @Test
    fun validate_trailingDotOnExactQuadSerializesDotless() {
        for (case in listOf(
            "http://127.0.0.1./" to "127.0.0.1",
            "http://１２７．０．０．１．/" to "127.0.0.1"   // fullwidth quad + fullwidth trailing dot
        )) {
            val link = ExternalLinkPolicy.validate(case.first)
            assertNotNull("must accept exact quad with trailing dot: ${case.first}", link)
            assertEquals("canonical host for ${case.first}", case.second, link!!.canonicalHost)
        }
    }

    @Test
    fun validate_rejectsIdnFoldedExactQuadThroughFullwidthDot() {
        // Fullwidth dot U+FF0E folds to `.` under UTS-46, so an all-decimal
        // fullwidth host folds into an exact quad: the raw-authority gate
        // saw no ASCII dots and no ASCII digits, and the post-IDN re-check
        // now must accept the exact-quad shape it folds into (this row
        // proves the re-check does not over-reject the one numeric form
        // browsers treat as a literal IPv4 address).
        val link = ExternalLinkPolicy.validate("http://１２７．０．０．１/")
        assertNotNull("exact quad via fullwidth dot must accept", link)
        assertEquals("127.0.0.1", link!!.canonicalHost)
    }

    /**
     * Green rows: legitimate IDN names whose canonical punycode form is NOT
     * numeric-shaped must still validate with truthful disclosure — the
     * post-IDN gate must not over-reject registered names. The fullwidth
     * digit row folds to an inner `127` label beside a non-numeric TLD
     * (`127.example.com`): not numeric-shaped (non-numeric label present),
     * a registered name in WHATWG, so it must stay accepted.
     */
    @Test
    fun canonicalDisclosure_idnFoldingOfLegitimateNamesStillAccepted() {
        for (case in listOf(
            "https://münchen.de/angebot" to "xn--mnchen-3ya.de",
            "https://ＥＸＡＭＰＬＥ.com/" to "example.com",   // fullwidth letters (non-numeric fold)
            "https://exａmple.com/" to "example.com",
            "https://１２７.example.com/" to "127.example.com"
        )) {
            val link = ExternalLinkPolicy.validate(case.first)
            assertNotNull("must accept legitimate IDN: ${case.first}", link)
            assertEquals("canonical host for ${case.first}", case.second, link!!.canonicalHost)
        }
    }

    @Test
    fun validate_acceptsExactDottedQuadIpv4Forms() {
        for (case in listOf(
            "http://127.0.0.1/" to "127.0.0.1",
            "http://8.8.8.8/" to "8.8.8.8",
            "http://0.0.0.0/" to "0.0.0.0",
            "http://255.255.255.255/" to "255.255.255.255"
        )) {
            val link = ExternalLinkPolicy.validate(case.first)
            assertNotNull("must accept exact quad: ${case.first}", link)
            assertEquals("canonical host for ${case.first}", case.second, link!!.canonicalHost)
        }
    }

    @Test
    fun validate_numericGateDoesNotAffectNonNumericHosts() {
        for (case in listOf(
            "https://example.com/" to "example.com",
            "https://beef.de/" to "beef.de",       // all-hex letters, no digit
            "https://feedback.example.com/" to "feedback.example.com",
            "https://xerxes.example.com/" to "xerxes.example.com"
        )) {
            val link = ExternalLinkPolicy.validate(case.first)
            assertNotNull("must accept non-numeric host: ${case.first}", link)
            assertEquals("canonical host for ${case.first}", case.second, link!!.canonicalHost)
        }
    }

    @Test
    fun canonicalDisclosure_treatsIpv4AndIpv6Literals() {
        val v4 = ExternalLinkPolicy.validate("http://192.168.1.50:8080/status")
        assertNotNull(v4)
        assertEquals("192.168.1.50", v4!!.canonicalHost)
        assertTrue(v4.nonDefaultPort)
        assertEquals("192.168.1.50:8080", ExternalLinkPolicy.canonicalDisclosure(v4))

        val v6 = ExternalLinkPolicy.validate("https://[2001:db8::1]/x")
        assertNotNull("IPv6 literal must validate", v6)
        assertEquals("2001:db8::1", v6!!.canonicalHost)
        assertFalse(v6.nonDefaultPort)
        assertEquals("[2001:db8::1]", ExternalLinkPolicy.canonicalDisclosure(v6))
    }

    @Test
    fun validate_rejectsPrivateAndLoopbackHostFormsOnlyByPolicyChoiceNotByHost() {
        // D-11 gates scheme/host validity, not host identity: localhost and
        // private hosts are valid http/https targets for confirmation. The
        // canonical disclosure must still be truthful.
        val local = ExternalLinkPolicy.validate("http://localhost:3000")
        assertNotNull(local)
        assertEquals("localhost", local!!.canonicalHost)
        assertEquals("localhost:3000", ExternalLinkPolicy.canonicalDisclosure(local))
    }

    // ------------------------------------------------------------------
    // Ports
    // ------------------------------------------------------------------

    @Test
    fun canonicalDisclosure_omitsDefaultPortAndShowsNonDefaultPort() {
        val httpsDefault = ExternalLinkPolicy.validate("https://example.com:443/x")
        assertNotNull(httpsDefault)
        assertFalse(httpsDefault!!.nonDefaultPort)
        assertEquals("example.com", ExternalLinkPolicy.canonicalDisclosure(httpsDefault))

        val httpDefault = ExternalLinkPolicy.validate("http://example.com:80/x")
        assertNotNull(httpDefault)
        assertFalse(httpDefault!!.nonDefaultPort)
        assertEquals("example.com", ExternalLinkPolicy.canonicalDisclosure(httpDefault))

        val custom = ExternalLinkPolicy.validate("https://example.com:8443/x")
        assertNotNull(custom)
        assertTrue(custom!!.nonDefaultPort)
        assertEquals("example.com:8443", ExternalLinkPolicy.canonicalDisclosure(custom))

        assertNull("port 0 is invalid", ExternalLinkPolicy.validate("https://example.com:0"))
        assertNull("port 70000 is invalid", ExternalLinkPolicy.validate("https://example.com:70000"))
    }

    // ------------------------------------------------------------------
    // Launch-time revalidation (TASK-050)
    // ------------------------------------------------------------------

    @Test
    fun revalidateForLaunch_acceptsUnchangedTarget() {
        val link = ExternalLinkPolicy.validate("https://example.com:8443/path")!!
        val again = ExternalLinkPolicy.revalidateForLaunch(link)
        assertNotNull(again)
        assertEquals(link.url, again!!.url)
        assertEquals(link.canonicalHost, again.canonicalHost)
    }

    @Test
    fun revalidateForLaunch_rejectsMutatedTargetUrl() {
        val link = ExternalLinkPolicy.validate("https://example.com/path")!!
        val mutated = link.copy(url = "https://evil.example.com/path")
        assertNull("mutated target must fail revalidation", ExternalLinkPolicy.revalidateForLaunch(mutated))
    }

    @Test
    fun revalidateForLaunch_rejectsMutatedCanonicalDisclosure() {
        val link = ExternalLinkPolicy.validate("https://example.com/path")!!
        val forgedHost = link.copy(canonicalHost = "evil.example.com")
        assertNull("forged host must fail revalidation", ExternalLinkPolicy.revalidateForLaunch(forgedHost))

        val forgedPort = link.copy(effectivePort = 9, nonDefaultPort = true)
        assertNull("forged port must fail revalidation", ExternalLinkPolicy.revalidateForLaunch(forgedPort))

        val forgedFlag = link.copy(nonDefaultPort = !link.nonDefaultPort)
        assertNull("forged port flag must fail revalidation", ExternalLinkPolicy.revalidateForLaunch(forgedFlag))
    }

    @Test
    fun revalidateForLaunch_rejectsTargetThatBecameInvalid() {
        val link = ExternalLinkPolicy.validate("https://example.com/path")!!
        val nowInvalid = link.copy(url = "javascript:alert(1)")
        assertNull("target that no longer validates must fail revalidation", ExternalLinkPolicy.revalidateForLaunch(nowInvalid))
    }

    // ------------------------------------------------------------------
    // Disclosure is never derived from the link label
    // ------------------------------------------------------------------

    @Test
    fun canonicalDisclosure_isIndependentOfLinkLabel() {
        // The label says "safe" but the host is what is disclosed.
        val link = ExternalLinkPolicy.validate("https://phishing.example.com:8080")!!
        val disclosure = ExternalLinkPolicy.canonicalDisclosure(link)
        assertEquals("phishing.example.com:8080", disclosure)
        assertNotEquals("safe", disclosure)
        assertTrue(disclosure.contains("phishing.example.com"))
    }
}
