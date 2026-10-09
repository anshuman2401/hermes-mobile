package com.hermes.bot.feature.chat

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * BLD-006 S11 (TASK-049, D-11 / SEC-003): the single centralized external-link
 * policy for the Chat surface. Every rendered link must be routed through
 * [validate] before any clickable surface exists, and re-validated through
 * [revalidateForLaunch] immediately before an `ACTION_VIEW` intent is created.
 *
 * Hardening beyond the base `ChatSecurityPolicy.isValidExternalUrl`:
 * - userinfo (`user:pass@host`) is rejected outright;
 * - control characters, bidirectional overrides/marks/isolates, line/paragraph
 *   separators, and any whitespace anywhere in the candidate are rejected
 *   before parsing, so edge trimming can never hide a forbidden code point;
 * - percent-encoded `/`, `?`, `#`, and `@` bytes in the authority are treated
 *   as encoded delimiters and rejected;
 * - the host must canonicalize to lower-case ASCII: registered names through
 *   IDN/punycode ([IDN.USE_STD3_ASCII_RULES]), IPv4 through the exact dotted
 *   quad form (rejecting octal/hex/leading-zero and non-quad decimal forms),
 *   and IPv6 through [InetAddress] canonicalization (rejecting zone IDs) —
 *   hosts that fail canonicalization are rejected, never passed through
 *   ambiguous (RISK-007 stop condition);
 * - the numeric-disguise gate re-runs on the POST-IDN canonical host in both
 *   host paths: IDN/UTS-46 compatibility folding (e.g. fullwidth ０ｘ -> 0x)
 *   and punycode decoding happen after the raw-authority gate, and a host
 *   that folds into a disguised-IPv4 form is rejected (fail-closed);
 * - ambiguous host forms (leading/trailing dot, empty label, `..`, trailing
 *   backslash in the authority) are rejected — every guard runs on the
 *   POST-IDN canonical host in both host paths (normalize first, then
 *   guard), so no compatibility dot variant (U+FF0E/U+3002/U+FF61) can
 *   fold past a shape decision; the trailing-dot branch follows WHATWG
 *   serialization (kept on non-numeric domains, dotless on exact quads);
 * - a non-default port must be carried in the canonical disclosure.
 *
 * The canonical disclosure ([canonicalDisclosure]: host lower-cased to ASCII
 * punycode + non-default port) is the ONLY thing the confirmation UI may
 * present as the destination; the link label and the raw URL text are never
 * trusted as disclosure.
 */
object ExternalLinkPolicy {

    data class CanonicalLink(
        val url: String,
        val canonicalHost: String,
        val effectivePort: Int,
        val nonDefaultPort: Boolean
    )

    private val ALLOWED_SCHEMES = setOf("http", "https")
    private const val HTTP_DEFAULT_PORT = 80
    private const val HTTPS_DEFAULT_PORT = 443

    /** Control chars (C0/C1), DEL, bidi overrides/marks/isolates/pops, U+2028/2029, U+FFFD. */
    private val FORBIDDEN_CODE_POINTS = buildSet {
        for (cp in 0x00..0x1F) add(cp)        // C0 controls (incl. \t \n \r)
        add(0x7F)                              // DEL
        for (cp in 0x80..0x9F) add(cp)        // C1 controls
        add(0x2028)                            // line separator
        add(0x2029)                            // paragraph separator
        add(0xFFFD)                            // replacement char (unpaired surrogate residue)
        add(0x061C)                            // Arabic letter mark
        add(0x200E)                            // LRM
        add(0x200F)                            // RLM
        add(0x202A)                            // LRE
        add(0x202B)                            // RLE
        add(0x202C)                            // PDF
        add(0x202D)                            // LRO
        add(0x202E)                            // RLO
        add(0x2066)                            // LRI
        add(0x2067)                            // RLI
        add(0x2068)                            // FSI
        add(0x2069)                            // PDI
    }

    /**
     * Validates a candidate external URL and returns its canonical form,
     * or null when D-11 forbids it. This is the gate for any rendered link.
     */
    fun validate(raw: String?): CanonicalLink? {
        if (raw.isNullOrBlank()) return null

        // Whole-string scan BEFORE any trimming so edge whitespace cannot hide
        // a forbidden code point: no whitespace/control/bidi character is
        // accepted anywhere in a validated external link.
        for (ch in raw) {
            if (ch.code in FORBIDDEN_CODE_POINTS || ch.isWhitespace()) return null
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null

        // Malformed/absent authority: an opaque URI (e.g. `https:opaque`) or
        // any candidate without a real authority is rejected.
        val rawAuthority = uri.rawAuthority ?: return null
        if (rawAuthority.isBlank()) return null
        if ('\\' in rawAuthority) return null
        if ("@" in rawAuthority) return null // user-info at authority level

        // Percent-encoded delimiters inside the authority can disguise the
        // authority boundary; forbid the delimiters' escapes outright.
        val lowerAuthority = rawAuthority.lowercase(Locale.ROOT)
        for (encoded in listOf("%2f", "%3f", "%23", "%40")) {
            if (encoded in lowerAuthority) return null
        }

        if (authorityPort(rawAuthority) < -1) return null
        val uriPort = uri.port
        val uriHost = uri.host // null for non-ASCII (IDN) hosts
        if (uriHost != null) {
            // `URI.getHost` hands back bracketed IPv6 literals verbatim; the
            // brackets are transport framing, not part of the host.
            val parsed = uriHost.removeSurrounding("[", "]")
            if (parsed.startsWith("[")) return null // malformed nesting
            if (parsed != uriHost) {
                // Bracketed literal (IPv6): canonicalize through the same
                // bracketed path; zone IDs are rejected there.
                val canonicalHost = canonicalizeAuthorityHost(uriHost) ?: return null
                return finishLink(trimmed, canonicalHost, uriPort)
            }
            if (parsed.isBlank()) return null
            // Ambiguous-form guards and the numeric-disguise gate run on the
            // POST-IDN canonical host only (see validateCanonicalHost):
            // IDN/UTS-46 folds compatibility characters (fullwidth digits and
            // all dot variants) to ASCII BEFORE any shape decision, so the
            // guards always see the exact form browsers recognize.
            val canonicalHost = try {
                IDN.toASCII(uriHost, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: Exception) {
                // IDN/punycode failure is a rejection, never a passthrough.
                return null
            }
            return validateCanonicalHost(canonicalHost)?.let { finishLink(trimmed, it, uriPort) }
        }

        // java.net.URI cannot extract a host for non-ASCII (IDN) or
        // non-RFC-3986 (IPv6 literal) authorities; the exact raw authority is
        // what gets host-validated here.
        val authorityPort = authorityPort(rawAuthority)
        val hostAuthority = if (rawAuthority.startsWith("[")) {
            val end = rawAuthority.indexOf(']')
            if (end < 0) return null
            rawAuthority.substring(0, end + 1)
        } else if (':' in rawAuthority) {
            rawAuthority.substringBeforeLast(':')
        } else rawAuthority
        val canonicalHost = canonicalizeAuthorityHost(hostAuthority) ?: return null
        return finishLink(trimmed, canonicalHost, authorityPort)
    }

    /**
     * The exact disclosure string for the confirmation UI: canonical
     * lower-case ASCII/punycode host, plus the port only when it is not the
     * scheme default. IPv6 hosts are shown in their bracketed URI-authority
     * form. Never derived from the link label or raw text.
     */
    fun canonicalDisclosure(link: CanonicalLink): String {
        val host = if (":" in link.canonicalHost) "[${link.canonicalHost}]" else link.canonicalHost
        return if (link.nonDefaultPort) {
            "$host:${link.effectivePort}"
        } else {
            host
        }
    }

    /**
     * Launch-time revalidation (TASK-050): must be called immediately before
     * creating the `ACTION_VIEW` intent, on the exact target that is about to
     * launch. The previously validated [CanonicalLink] is not trusted — the
     * target may have been mutated between confirmation and launch, and the
     * canonical disclosure must still match the revalidated target.
     */
    fun revalidateForLaunch(link: CanonicalLink): CanonicalLink? {
        val now = validate(link.url) ?: return null
        return if (now.canonicalHost == link.canonicalHost &&
            now.effectivePort == link.effectivePort &&
            now.nonDefaultPort == link.nonDefaultPort
        ) {
            now
        } else {
            null
        }
    }

    // ---------------------------------------------------------------------

    /** Triple of (canonical host, effective port, non-default port) or null when rejected. */
    private fun finishLink(
        trimmedUrl: String,
        canonicalHost: String,
        rawPort: Int
    ): CanonicalLink? {
        val effectivePort = if (rawPort == -1) defaultPortFor(trimmedUrl) else rawPort
        if (effectivePort !in 1..65535) return null
        val nonDefaultPort = effectivePort != defaultPortFor(trimmedUrl)
        return CanonicalLink(
            url = trimmedUrl,
            canonicalHost = canonicalHost,
            effectivePort = effectivePort,
            nonDefaultPort = nonDefaultPort
        )
    }

    private fun defaultPortFor(trimmedUrl: String): Int {
        return if (trimmedUrl.startsWith("https:", ignoreCase = true)) HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    }

    /**
     * True when the host is shaped like a numeric IPv4 disguise: every label
     * parses as a number in the browser sense — a plain decimal label
     * (`0`-prefixed labels are the octal disguise) or a `0x`/`0X`-prefixed
     * hex label — with at least one label present. This is exactly the
     * family browsers reinterpret as an IPv4 address (decimal integer, hex,
     * octal, short 2-3 part forms). Hex-letter words without a `0x` prefix
     * (e.g. `cafe` in `1.cafe.de`) do not parse as numbers in browsers and
     * stay legitimate registered names.
     */
    private fun isNumericShapedHost(host: String): Boolean {
        val labels = host.split(".")
        if (labels.isEmpty()) return false
        return labels.all { label ->
            if (label.isEmpty()) return@all false
            if (label.length >= 2 && (label[0] == '0') && (label[1] == 'x' || label[1] == 'X')) {
                // 0x/0X-prefixed hex label (bare `0x`/`0X` is hex zero in
                // WHATWG, so it is numeric-shaped; labels longer than 2 keep
                // the hex-digit check).
                return@all label.substring(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            }
            label.all { it in '0'..'9' }
        }
    }

    /**
     * Strict IPv4 dotted-quad gate: exactly four labels, each a decimal
     * octet in 0..255 with no leading zero (except the single digit `0`
     * itself). Any other numeric form — single integer, hex (with or
     * without `0x`), octal, short 2-3 part, or leading-zero octets —
     * fails and the candidate host is rejected.
     */
    private fun isStrictDottedQuad(host: String): Boolean {
        val labels = host.split(".")
        if (labels.size != 4) return false
        return labels.all { label ->
            if (label.isEmpty() || label.length > 3) return@all false
            if (label.any { it !in '0'..'9' }) return@all false
            if (label.length > 1 && label[0] == '0') return@all false
            label.toInt() in 0..255
        }
    }

    /**
     * Canonicalizes the host embedded in a raw authority that `java.net.URI`
     * could not parse as a host (IDN names, bracketed IPv6 literals).
     * Returns the lower-case ASCII canonical host, or null when rejected.
     */
    private fun canonicalizeAuthorityHost(rawAuthority: String): String? {
        if (rawAuthority.startsWith("[")) {
            // IPv6 literal: must be a closed bracketed literal that
            // InetAddress canonicalizes; zone IDs (`%25eth0`) are rejected.
            val end = rawAuthority.indexOf(']')
            if (end != rawAuthority.length - 1) return null
            if (rawAuthority.length <= 2) return null
            val literal = rawAuthority.substring(1, end)
            if (literal.isEmpty() || '%' in literal) return null
            return try {
                // Wrap in brackets so a bare IPv4 text is not misparsed as an
                // integer host address; this path is IPv6-only by shape.
                val addr = InetAddress.getByName("[$literal]").hostAddress
                if (addr != null && addr.contains(":")) {
                    // Disclose the RFC 5952 compressed lower-case form so the
                    // confirmation UI never shows an ambiguous expansion.
                    compressIpv6(addr).lowercase(Locale.ROOT)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
        // Registered name (possibly IDN). Canonicalize first so the
        // ambiguous-form guards and the numeric gate always run on the exact
        // ASCII form browsers recognize.
        return try {
            val ascii = IDN.toASCII(rawAuthority, IDN.USE_STD3_ASCII_RULES)
            if (ascii.isBlank()) null else validateCanonicalHost(ascii.lowercase(Locale.ROOT))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The single post-IDN host gate: runs the ambiguous-form guards
     * (leading dot, trailing dot, empty label / `..`) and the
     * numeric-disguise gate on the canonical (POST-IDN) host, in that
     * order, before any acceptance. Normalization happens FIRST; every
     * guard then sees the exact ASCII form browsers recognize.
     *
     * Trailing-dot semantics are pinned to WHATWG (browser-verified on
     * Node 26 Ada URL and whatwg-url 17.1.1, identical): a single trailing
     * dot on a NON-numeric domain is kept (not stripped, not rejected —
     * `example.com.` stays `example.com.`), the same trailing dot after a
     * numeric-shaped host triggers the ends-in-number recognizer and the
     * browser reinterprets the remaining labels as an IPv4 address
     * (`127.` -> 0.0.0.127, `1.2.3.` -> 1.2.0.3), and an exact dotted quad
     * with a trailing dot serializes WITHOUT the dot
     * (`127.0.0.1.` -> `127.0.0.1`). Accordingly: non-numeric trailing-dot
     * domains stay accepted with the trailing dot preserved in the
     * canonical disclosure (truthful, browser-identical), every other
     * trailing-dot form is rejected (fail-closed), and the exact-quad form
     * is canonicalized to WHATWG's dotless serialization. Because the fold
     * maps fullwidth/ideographic dots to ASCII before this gate, no
     * trailing-variant dot can escape the pre-IDN blind spot any more.
     */
    private fun validateCanonicalHost(canonicalHost: String): String? {
        // java.net.URI accepts host forms that fail WHATWG domain shape:
        // leading dot and empty labels (`..`). IDNA/UTS-46 keeps some of
        // them verbatim (e.g. a bare `.`), so the post-IDN canonical host
        // must still be re-guarded here — anything browsers would not
        // recognize as a domain is fail-closed.
        if (canonicalHost.startsWith(".") || ".." in canonicalHost) return null
        if (canonicalHost.endsWith(".")) {
            // WHATWG trailing-dot semantics (browser-verified, see above):
            // the only acceptable forms are a single trailing dot on a
            // non-numeric domain (kept verbatim) or on an exact dotted quad
            // (WHATWG strips it in the serialized host). Every other
            // trailing-dot form — the bare `.`, a numeric disguise, or a
            // second trailing/empty label — is fail-closed.
            val stripped = canonicalHost.removeSuffix(".")
            if (stripped.isEmpty() || stripped.endsWith(".")) return null
            if (isNumericShapedHost(stripped)) {
                // Exact quad with trailing dot: canonicalize to WHATWG's
                // dotless serialization; any other numeric shape is rejected.
                return if (isStrictDottedQuad(stripped)) stripped else null
            }
            return canonicalHost
        }
        if (canonicalHost.split(".").any { it.isEmpty() }) return null
        if (isNumericShapedHost(canonicalHost) && !isStrictDottedQuad(canonicalHost)) {
            // Disguised IPv4 (decimal integer, hex, octal, short 2-3 part,
            // or leading-zero forms): browsers reinterpret these, so they
            // are rejected outright — only the exact a.b.c.d quad passes.
            return null
        }
        return canonicalHost
    }

    /**
     * Extracts the explicit port from a raw authority (`host:port` or
     * `[v6]:port`). Returns -1 when no port is present and -2 for an
     * invalid explicit port. URI parsing alone does not reject every invalid
     * domain authority. A `:` inside brackets is part of the IPv6 literal,
     * never a port separator.
     */
    private fun authorityPort(rawAuthority: String): Int {
        val colon = if (rawAuthority.startsWith("[")) {
            val end = rawAuthority.indexOf(']')
            if (end == -1 || end == rawAuthority.length - 1) return -1
            if (rawAuthority[end + 1] != ':') return -1
            end + 1
        } else {
            val last = rawAuthority.lastIndexOf(':')
            if (last == -1) return -1
            last
        }
        val portText = rawAuthority.substring(colon + 1)
        if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return -2
        val parsed = portText.toIntOrNull() ?: return -2
        return if (parsed in 1..65535) parsed else -2
    }

    /**
     * RFC 5952 compression of the fully expanded (8-group) IPv6 form emitted
     * by [InetAddress]: zero runs of length ≥ 2 become exactly one `::`
     * (rightmost when tied), single zero groups stay literal. Input groups
     * are assumed `:`-separated with no brackets.
     */
    private fun compressIpv6(expanded: String): String {
        val groups = expanded.split(":")
        if (groups.size != 8) return expanded
        var bestStart = -1
        var bestLen = 0
        var start = -1
        for (i in 0..8) {
            val zero = i < 8 && groups[i] == "0"
            if (zero && start == -1) start = i
            if (!zero && start != -1) {
                if (i - start > bestLen) {
                    bestLen = i - start
                    bestStart = start
                }
                start = -1
            }
        }
        if (bestLen < 2) return expanded
        val left = groups.subList(0, bestStart).joinToString(":")
        val right = groups.subList(bestStart + bestLen, 8).joinToString(":")
        return when {
            bestStart == 0 -> "::$right"
            bestStart + bestLen == 8 -> "$left::"
            else -> "$left::$right"
        }
    }
}
