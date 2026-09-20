package com.example.sip.engine

import java.security.MessageDigest
import java.util.UUID

/**
 * RFC 2617 / RFC 3261 MD5 Digest authentication helper for SIP REGISTER.
 * Follows the exact flow verified on the private LTE/IMS testbed (exp5_uac.py).
 */
object SipAuthHelper {

    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts an authentication parameter from a WWW-Authenticate or Proxy-Authenticate header.
     * Supports quoted values (param="value") and unquoted tokens (param=token).
     * Handles whitespace, commas, and case-insensitivity.
     */
    fun extractAuthParam(authHeader: String, paramName: String): String {
        if (authHeader.isBlank()) return ""
        val regex = Regex("""\b$paramName\s*=\s*(?:"([^"]*)"|([^,\s]+))""", RegexOption.IGNORE_CASE)
        val match = regex.find(authHeader) ?: return ""
        return (match.groups[1]?.value ?: match.groups[2]?.value ?: "").trim()
    }

    /**
     * Selects the appropriate qop from the challenge (e.g. "auth,auth-int" -> "auth").
     */
    fun selectQop(rawQop: String): String {
        val clean = rawQop.trim().trim('"')
        if (clean.isEmpty()) return ""
        val tokens = clean.split(",").map { it.trim().lowercase() }
        return if (tokens.contains("auth")) "auth" else tokens.firstOrNull() ?: ""
    }

    /**
     * Calculates RFC 2617 MD5 Digest response:
     * HA1 = MD5(username:realm:password)
     * HA2 = MD5(method:uri)
     * If qop="auth":
     *   response = MD5(HA1:nonce:nc:cnonce:qop:HA2)
     * Else:
     *   response = MD5(HA1:nonce:HA2)
     */
    fun calculateDigestResponse(
        username: String,
        realm: String,
        password: String,
        method: String,
        uri: String,
        nonce: String,
        qop: String,
        nc: String = "00000001",
        cnonce: String = UUID.randomUUID().toString().replace("-", "").take(8)
    ): Pair<String, String> {
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val response = if (qop.isNotEmpty()) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }
        return Pair(response, cnonce)
    }

    /**
     * Builds the complete "Authorization: Digest ..." header value matching exp5_uac.py:
     * Digest username="{username}", uri="{uri}", algorithm=MD5, realm="{realm}", nonce="{nonce}", qop={qop}, nc={nc}, cnonce="{cnonce}", response="{resp}"
     */
    fun buildAuthorizationHeader(
        username: String,
        realm: String,
        password: String,
        method: String,
        uri: String,
        nonce: String,
        rawQop: String,
        opaque: String = "",
        nc: String = "00000001",
        forcedCnonce: String? = null
    ): String {
        val qop = selectQop(rawQop)
        val cnonce = forcedCnonce ?: UUID.randomUUID().toString().replace("-", "").take(8)
        val (response, _) = calculateDigestResponse(
            username = username,
            realm = realm,
            password = password,
            method = method,
            uri = uri,
            nonce = nonce,
            qop = qop,
            nc = nc,
            cnonce = cnonce
        )

        val qopPart = if (qop.isNotEmpty()) {
            ", qop=$qop, nc=$nc, cnonce=\"$cnonce\""
        } else ""

        val opaquePart = if (opaque.isNotEmpty()) {
            ", opaque=\"$opaque\""
        } else ""

        return buildString {
            append("Digest username=\"$username\", ")
            append("uri=\"$uri\", ")
            append("algorithm=MD5, ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\"")
            append(qopPart)
            append(", response=\"$response\"")
            append(opaquePart)
        }
    }
}
