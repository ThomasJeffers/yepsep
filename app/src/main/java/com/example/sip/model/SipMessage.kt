package com.example.sip.model

data class SipMessage(
    val rawText: String,
    val isResponse: Boolean,
    val method: String = "", // e.g. "INVITE", "REGISTER", "MESSAGE", "INFO", "ACK", "BYE"
    val statusCode: Int = 0, // e.g. 200, 401, 180
    val statusText: String = "", // e.g. "OK", "Unauthorized"
    val startLine: String = "",
    val headers: Map<String, String> = emptyMap(),
    val multiHeaders: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val callId: String = "",
    val cseq: String = "",
    val from: String = "",
    val to: String = "",
    val contact: String = "",
    val contentType: String = ""
) {
    val isMcpttTagged: Boolean
        get() = rawText.contains("+g.3gpp.mcptt", ignoreCase = true) ||
                rawText.contains("3gpp-service.ims.icsi.mcptt", ignoreCase = true) ||
                rawText.contains("mcptt", ignoreCase = true)

    val floorControlState: String?
        get() {
            val lower = body.lowercase()
            for (line in lower.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("action=")) {
                    val act = trimmed.substringAfter("=").trim()
                    return when {
                        act.contains("grant") -> "GRANTED"
                        act.contains("request") -> "REQUEST"
                        act.contains("release") -> "RELEASE"
                        act.contains("taken") -> "TAKEN"
                        act.contains("idle") -> "IDLE"
                        else -> act.uppercase()
                    }
                }
            }
            if (lower.contains("floor-granted") || lower.contains("floor-grant")) return "GRANTED"
            if (lower.contains("floor-request")) return "REQUEST"
            if (lower.contains("floor-release")) return "RELEASE"
            if (lower.contains("floor-taken")) return "TAKEN"
            if (lower.contains("floor-idle")) return "IDLE"
            return null
        }

    fun getHeader(name: String): String = headers[name.lowercase().trim()] ?: ""

    fun getHeaders(name: String): List<String> = multiHeaders[name.lowercase().trim()] ?: emptyList()

    /**
     * Extracts Record-Route URIs in reverse order (P-CSCF first), as required by RFC 3261 UAC route sets.
     */
    fun extractUacRouteSet(): List<String> {
        val rr = getHeaders("record-route")
        val parsed = mutableListOf<String>()
        for (item in rr) {
            // Can be comma-separated or separate headers
            val tokens = item.split(",")
            for (token in tokens) {
                val trimmed = token.trim()
                if (trimmed.isNotEmpty()) {
                    val uri = if (trimmed.contains("<") && trimmed.contains(">")) {
                        trimmed.substringAfter("<").substringBefore(">")
                    } else {
                        trimmed.substringBefore(";")
                    }
                    parsed.add(uri)
                }
            }
        }
        return parsed.reversed()
    }

    /**
     * Extracts connection IP and audio port from SDP body if present.
     */
    fun extractSdpMedia(): Pair<String?, Int?> {
        var ip: String? = null
        var port: Int? = null
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("c=IN IP4 ")) {
                ip = trimmed.substring(9).trim().split(" ")[0]
            } else if (trimmed.startsWith("m=audio ")) {
                val parts = trimmed.split(" ")
                if (parts.size >= 2) {
                    port = parts[1].toIntOrNull()
                }
            }
        }
        return Pair(ip, port)
    }

    /**
     * Extracts the dialog contact URI (without angle brackets and parameter tags).
     */
    fun extractContactUri(): String {
        val raw = getHeader("contact")
        if (raw.isEmpty()) return ""
        return if (raw.contains("<") && raw.contains(">")) {
            raw.substringAfter("<").substringBefore(">")
        } else {
            raw.split(";")[0].trim()
        }
    }

    companion object {
        fun parse(rawText: String): SipMessage {
            val lines = rawText.lines()
            if (lines.isEmpty()) return SipMessage(rawText, false)

            val startLine = lines[0].trim()
            val isResponse = startLine.startsWith("SIP/2.0", ignoreCase = true)

            var method = ""
            var statusCode = 0
            var statusText = ""

            if (isResponse) {
                val parts = startLine.split(" ", limit = 3)
                if (parts.size >= 2) {
                    statusCode = parts[1].toIntOrNull() ?: 0
                }
                if (parts.size >= 3) {
                    statusText = parts[2]
                }
            } else {
                val parts = startLine.split(" ")
                if (parts.isNotEmpty()) {
                    method = parts[0].uppercase()
                }
            }

            val singleHeaders = mutableMapOf<String, String>()
            val multiHeaders = mutableMapOf<String, MutableList<String>>()
            var bodyStartIndex = -1

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty() || line == "\r") {
                    bodyStartIndex = i + 1
                    break
                }
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val headerName = line.substring(0, colonIndex).trim().lowercase()
                    val headerValue = line.substring(colonIndex + 1).trim()
                    if (!singleHeaders.containsKey(headerName)) {
                        singleHeaders[headerName] = headerValue
                    }
                    multiHeaders.getOrPut(headerName) { mutableListOf() }.add(headerValue)
                }
            }

            val body = if (bodyStartIndex in 1..lines.size) {
                lines.subList(bodyStartIndex, lines.size).joinToString("\n")
            } else ""

            val cseqVal = singleHeaders["cseq"] ?: ""
            if (isResponse && method.isEmpty() && cseqVal.isNotEmpty()) {
                val cseqParts = cseqVal.split(" ")
                if (cseqParts.size >= 2) {
                    method = cseqParts[1].uppercase()
                }
            }

            return SipMessage(
                rawText = rawText,
                isResponse = isResponse,
                method = method,
                statusCode = statusCode,
                statusText = statusText,
                startLine = startLine,
                headers = singleHeaders,
                multiHeaders = multiHeaders,
                body = body,
                callId = singleHeaders["call-id"] ?: "",
                cseq = cseqVal,
                from = singleHeaders["from"] ?: "",
                to = singleHeaders["to"] ?: "",
                contact = singleHeaders["contact"] ?: "",
                contentType = singleHeaders["content-type"] ?: ""
            )
        }
    }
}
