package com.example.sip.model

data class SipHeader(val name: String, val value: String)

data class SipMessage(
    val rawText: String = "",
    val isResponse: Boolean = false,
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
            val lowerBody = body.lowercase()
            for (line in lowerBody.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("action=")) {
                    val act = trimmed.substringAfter("=").trim()
                    return when {
                        act.contains("grant") -> "GRANTED"
                        act.contains("taken") -> "TAKEN"
                        act.contains("idle") -> "IDLE"
                        act.contains("release") -> "RELEASE"
                        act.contains("deny") || act.contains("busy") || act.contains("reject") -> "DENIED"
                        act.contains("request") -> "REQUEST"
                        else -> act.uppercase()
                    }
                }
            }
            val lowerRaw = (body + "\n" + rawText).lowercase()
            if (lowerRaw.contains("floor-granted") || lowerRaw.contains("floor-grant")) return "GRANTED"
            if (lowerRaw.contains("floor-taken")) return "TAKEN"
            if (lowerRaw.contains("floor-idle")) return "IDLE"
            if (lowerRaw.contains("floor-release")) return "RELEASE"
            if (lowerRaw.contains("floor-deny") || lowerRaw.contains("floor-busy") || lowerRaw.contains("floor-reject")) return "DENIED"
            if (lowerRaw.contains("floor-request")) return "REQUEST"
            return null
        }

    val cseqNumber: Int
        get() = cseq.trim().split(Regex("""\s+""")).firstOrNull()?.toIntOrNull() ?: 0

    fun getHeader(name: String): String = headers[name.lowercase().trim()] ?: ""

    fun getHeaders(name: String): List<String> = multiHeaders[name.lowercase().trim()] ?: emptyList()

    /**
     * Extracts Contact URI from SIP message Contact header.
     * RFC 3261: Contact: <sip:user@host:port> -> sip:user@host:port
     */
    fun extractContactUri(): String {
        val contactVal = getHeader("contact").ifEmpty { contact }
        if (contactVal.isBlank()) return ""
        val start = contactVal.indexOf('<')
        val end = contactVal.indexOf('>')
        return if (start != -1 && end > start) {
            contactVal.substring(start + 1, end).trim()
        } else {
            contactVal.substringBefore(";").trim()
        }
    }

    /**
     * Extracts RFC 3261 UAC route set from Record-Route headers.
     * When receiving 200 OK to INVITE, the UAC builds the route set by reversing Record-Route.
     */
    fun extractUacRouteSet(): List<String> {
        val rrHeaders = getHeaders("record-route")
        if (rrHeaders.isEmpty()) {
            val single = getHeader("record-route")
            if (single.isBlank()) return emptyList()
            // Split comma-separated Record-Route entries if present
            return single.split(",").map { cleanRouteUri(it) }.reversed()
        }
        return rrHeaders.map { cleanRouteUri(it) }.reversed()
    }

    private fun cleanRouteUri(raw: String): String {
        val start = raw.indexOf('<')
        val end = raw.indexOf('>')
        return if (start != -1 && end > start) {
            raw.substring(start + 1, end).trim()
        } else {
            raw.trim()
        }
    }

    fun extractSdpMedia(): Pair<String?, Int?> {
        var remoteIp: String? = null
        var remotePort: Int? = null

        val lines = body.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("c=IN IP4 ")) {
                val ip = trimmed.removePrefix("c=IN IP4 ").trim()
                if (ip.isNotEmpty()) {
                    remoteIp = ip
                }
            } else if (trimmed.startsWith("m=audio ")) {
                val parts = trimmed.split(" ")
                if (parts.size >= 2) {
                    val port = parts[1].toIntOrNull()
                    if (port != null && port > 0) {
                        remotePort = port
                    }
                }
            }
        }
        return Pair(remoteIp, remotePort)
    }

    companion object {
        fun parse(rawText: String): SipMessage {
            val lines = rawText.lines()
            if (lines.isEmpty()) {
                return SipMessage(rawText = rawText, isResponse = false)
            }

            val startLine = lines.first().trim()
            val isResponse = startLine.startsWith("SIP/2.0 ", ignoreCase = true)

            var method = ""
            var statusCode = 0
            var statusText = ""

            if (isResponse) {
                val parts = startLine.split(Regex("""\s+"""), limit = 3)
                if (parts.size >= 2) {
                    statusCode = parts[1].toIntOrNull() ?: 0
                }
                if (parts.size >= 3) {
                    statusText = parts[2]
                }
            } else {
                val parts = startLine.split(Regex("""\s+"""), limit = 3)
                if (parts.isNotEmpty()) {
                    method = parts[0].uppercase()
                }
            }

            val singleHeaders = mutableMapOf<String, String>()
            val multiHeaders = mutableMapOf<String, MutableList<String>>()

            // Locate header / body separation (either \r\n\r\n or \n\n)
            var separatorLength = 4
            var blankLineIndex = rawText.indexOf("\r\n\r\n")
            if (blankLineIndex == -1) {
                blankLineIndex = rawText.indexOf("\n\n")
                separatorLength = 2
            }

            val headerText = if (blankLineIndex != -1) {
                rawText.substring(0, blankLineIndex)
            } else {
                rawText
            }

            val headerLines = headerText.lines()
            // Skip startLine
            var i = 1
            while (i < headerLines.size) {
                var line = headerLines[i]
                if (line.isBlank()) {
                    i++
                    continue
                }
                // Check continuation / multiline headers
                var fullLine = line.trim()
                while (i + 1 < headerLines.size && (headerLines[i + 1].startsWith(" ") || headerLines[i + 1].startsWith("\t"))) {
                    i++
                    fullLine += " " + headerLines[i].trim()
                }

                val colonIdx = fullLine.indexOf(':')
                if (colonIdx != -1) {
                    val hName = fullLine.substring(0, colonIdx).trim().lowercase()
                    val hVal = fullLine.substring(colonIdx + 1).trim()
                    singleHeaders[hName] = hVal
                    val list = multiHeaders.getOrPut(hName) { mutableListOf() }
                    list.add(hVal)
                }
                i++
            }

            // Accurate byte-length body parsing adhering to Content-Length
            val declaredContentLength = singleHeaders["content-length"]?.trim()?.toIntOrNull()
            val body = if (blankLineIndex != -1) {
                if (declaredContentLength != null && declaredContentLength <= 0) {
                    ""
                } else {
                    val rawBytes = rawText.toByteArray(Charsets.UTF_8)
                    val headerBytes = headerText.toByteArray(Charsets.UTF_8)
                    val bodyStartByteIndex = headerBytes.size + separatorLength
                    if (bodyStartByteIndex < rawBytes.size) {
                        val availableBytes = rawBytes.size - bodyStartByteIndex
                        val bytesToExtract = if (declaredContentLength != null) {
                            minOf(declaredContentLength, availableBytes)
                        } else {
                            availableBytes
                        }
                        if (bytesToExtract > 0) {
                            String(rawBytes, bodyStartByteIndex, bytesToExtract, Charsets.UTF_8)
                        } else {
                            ""
                        }
                    } else {
                        ""
                    }
                }
            } else {
                ""
            }

            val cseqVal = singleHeaders["cseq"] ?: ""
            if (isResponse && method.isEmpty() && cseqVal.isNotEmpty()) {
                val cseqParts = cseqVal.trim().split(Regex("""\s+"""))
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
