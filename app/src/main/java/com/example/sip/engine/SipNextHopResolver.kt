package com.example.sip.engine

import android.util.Log
import com.example.sip.discovery.SelectedPcscf
import com.example.sip.model.SipMessage

/**
 * Represents the resolved UDP transport next-hop destination for a SIP packet.
 */
data class SipDestination(
    val host: String,
    val port: Int,
    val description: String
) {
    fun toHostPort(): String = "$host:$port"
}

/**
 * Clean architectural layer separating P-CSCF selection from SIP transport next-hop routing.
 *
 * Implements standard RFC 3261 next-hop resolution:
 *
 * 1. OUT-OF-DIALOG / INITIAL REQUESTS (REGISTER, initial INVITE, initial SUBSCRIBE, standalone MESSAGE):
 *    - Next hop is the authoritative Selected P-CSCF (or legacy fallback).
 *
 * 2. IN-DIALOG REQUESTS (ACK, BYE, INFO):
 *    - MUST follow dialog state:
 *      - If Route set is present (from Record-Route): next hop is derived from the first Route header.
 *      - If Route set is empty: next hop is derived from remote target URI (from Contact header).
 *    - Does NOT blindly send to P-CSCF if dialog routing specifies another next hop.
 *
 * 3. SIP RESPONSES (200 OK, 180, 4xx):
 *    - MUST be sent back to the transaction source/top Via next hop (sent-by / received / rport or packet origin).
 *    - Does NOT blindly send responses to the P-CSCF.
 */
object SipNextHopResolver {

    private const val TAG = "SipNextHopResolver"

    /**
     * Resolves the next-hop destination for an out-of-dialog request.
     */
    fun resolveOutOfDialogDestination(
        selectedPcscf: SelectedPcscf?,
        fallbackHost: String,
        fallbackPort: Int
    ): SipDestination {
        val host = selectedPcscf?.host ?: fallbackHost
        val port = selectedPcscf?.port ?: fallbackPort
        val srcName = selectedPcscf?.source?.displayName ?: "Configured Fallback"
        return SipDestination(
            host = host,
            port = port,
            description = "Out-of-dialog IMS next-hop (P-CSCF: $host:$port [$srcName])"
        )
    }

    /**
     * Resolves next-hop for in-dialog requests (ACK, BYE, INFO) according to RFC 3261 Section 12.2.
     *
     * @param routeSet Active dialog route set (ordered list of Route URIs).
     * @param remoteTargetUri Active dialog remote target URI (from Contact).
     * @param selectedPcscf Authoritative P-CSCF to use if Route URI or Contact hostname requires fallback.
     */
    fun resolveInDialogDestination(
        routeSet: List<String>,
        remoteTargetUri: String?,
        selectedPcscf: SelectedPcscf?,
        fallbackHost: String,
        fallbackPort: Int
    ): SipDestination {
        // RFC 3261 12.2.1.1: If the route set is not empty, and the first URI in the route set
        // contains the lr parameter, the UAC MUST set the Request-URI to the remote target URI
        // and MUST include a Route header field containing the route set...
        // The UAC MUST send the request to the address and port in the top Route header field.
        if (routeSet.isNotEmpty()) {
            val topRoute = routeSet.first()
            val parsed = parseUriHostPort(topRoute)
            if (parsed != null) {
                Log.d(TAG, "In-dialog request next-hop resolved from top Route: <$topRoute> -> ${parsed.host}:${parsed.port}")
                return SipDestination(
                    host = parsed.host,
                    port = parsed.port,
                    description = "In-dialog top Route (${parsed.host}:${parsed.port})"
                )
            }
        }

        // If Route set is empty, request next hop is remote target URI (from Contact)
        if (!remoteTargetUri.isNullOrBlank()) {
            val parsed = parseUriHostPort(remoteTargetUri)
            if (parsed != null) {
                Log.d(TAG, "In-dialog request next-hop resolved from remote target URI: <$remoteTargetUri> -> ${parsed.host}:${parsed.port}")
                return SipDestination(
                    host = parsed.host,
                    port = parsed.port,
                    description = "In-dialog remote target Contact (${parsed.host}:${parsed.port})"
                )
            }
        }

        // Fallback to authoritative P-CSCF
        val host = selectedPcscf?.host ?: fallbackHost
        val port = selectedPcscf?.port ?: fallbackPort
        Log.d(TAG, "In-dialog next-hop using P-CSCF fallback: $host:$port")
        return SipDestination(
            host = host,
            port = port,
            description = "In-dialog P-CSCF fallback ($host:$port)"
        )
    }

    /**
     * Resolves next-hop destination for SIP responses to incoming requests (RFC 3261 Section 18.2.2).
     *
     * @param requestMsg The parsed incoming SIP request.
     * @param packetSourceHost The actual IP address from which the UDP datagram was received.
     * @param packetSourcePort The actual UDP port from which the UDP datagram was received.
     * @param defaultPcscfHost Fallback host if parsing fails.
     * @param defaultPcscfPort Fallback port if parsing fails.
     */
    fun resolveResponseDestination(
        requestMsg: SipMessage,
        packetSourceHost: String,
        packetSourcePort: Int,
        defaultPcscfHost: String,
        defaultPcscfPort: Int
    ): SipDestination {
        // RFC 3261 18.2.2:
        // Top Via header:
        // 1. If 'received' parameter is present, send to that IP. Otherwise send to host in Via sent-by.
        // 2. If 'rport' parameter has a value, send to that port. Otherwise if 'rport' is present without value,
        //    send to source port of the packet. If no rport, send to port in Via sent-by (or 5060).
        // 3. For reliable UDP NAT traversal, if packetSourceHost is valid, it represents the real transport origin.
        val topVia = requestMsg.getHeaders("via").firstOrNull() ?: requestMsg.getHeader("via")

        if (topVia.isNotBlank()) {
            var destHost = packetSourceHost
            var destPort = packetSourcePort

            // Check received parameter
            val receivedRegex = Regex("received=([^;\\s]+)", RegexOption.IGNORE_CASE)
            val receivedMatch = receivedRegex.find(topVia)
            if (receivedMatch != null) {
                destHost = receivedMatch.groupValues[1].trim()
            }

            // Check rport parameter
            val rportRegex = Regex("rport(=(\\d+))?", RegexOption.IGNORE_CASE)
            val rportMatch = rportRegex.find(topVia)
            if (rportMatch != null) {
                val rportVal = rportMatch.groupValues.getOrNull(2)
                if (!rportVal.isNullOrBlank()) {
                    destPort = rportVal.toIntOrNull() ?: packetSourcePort
                } else {
                    // rport without value: RFC 3581 says send back to source port of the request
                    destPort = packetSourcePort
                }
            } else {
                // If no rport, check sent-by port
                val sentBy = topVia.substringAfter("SIP/2.0/UDP").trim().substringBefore(";").trim()
                if (sentBy.contains(":")) {
                    val p = sentBy.substringAfter(":").trim().toIntOrNull()
                    if (p != null && p > 0) destPort = p
                }
            }

            if (destHost.isNotBlank() && destHost != "0.0.0.0" && destHost != "unknown" && destPort > 0) {
                return SipDestination(
                    host = destHost,
                    port = destPort,
                    description = "Top Via transaction response target ($destHost:$destPort)"
                )
            }
        }

        // Use actual datagram source if available
        if (packetSourceHost.isNotBlank() && packetSourceHost != "0.0.0.0" && packetSourceHost != "unknown" && packetSourcePort > 0) {
            return SipDestination(
                host = packetSourceHost,
                port = packetSourcePort,
                description = "Incoming datagram origin ($packetSourceHost:$packetSourcePort)"
            )
        }

        // Safe fallback
        return SipDestination(
            host = defaultPcscfHost,
            port = defaultPcscfPort,
            description = "Response P-CSCF default fallback ($defaultPcscfHost:$defaultPcscfPort)"
        )
    }

    /**
     * Parses a SIP URI into host and port.
     * Supports IPv4, IPv6 (with or without brackets), and hostnames.
     */
    fun parseUriHostPort(uriStr: String): ParsedHostPort? {
        val clean = uriStr.trim().removeSurrounding("<", ">")
        val withoutScheme = if (clean.startsWith("sip:", ignoreCase = true)) {
            clean.substring(4)
        } else if (clean.startsWith("sips:", ignoreCase = true)) {
            clean.substring(5)
        } else {
            clean
        }

        // Remove parameters (e.g. ;lr, ;transport=udp)
        val hostPartWithUser = withoutScheme.substringBefore(";").substringBefore("?").trim()
        val hostPart = if (hostPartWithUser.contains("@")) {
            hostPartWithUser.substringAfter("@").trim()
        } else {
            hostPartWithUser
        }

        if (hostPart.isBlank()) return null

        // IPv6 bracketed host e.g. [2001:db8::1]:5060 or [2001:db8::1]
        if (hostPart.startsWith("[")) {
            val closeBracket = hostPart.indexOf("]")
            if (closeBracket > 0) {
                val rawHost = hostPart.substring(1, closeBracket).trim()
                val remainder = hostPart.substring(closeBracket + 1)
                val port = if (remainder.startsWith(":")) {
                    remainder.substring(1).trim().toIntOrNull() ?: 5060
                } else {
                    5060
                }
                return ParsedHostPort(rawHost, port)
            }
        }

        // IPv4 or hostname: host:port or host
        if (hostPart.contains(":")) {
            val parts = hostPart.split(":")
            if (parts.size == 2) {
                val h = parts[0].trim()
                val p = parts[1].trim().toIntOrNull() ?: 5060
                return ParsedHostPort(h, p)
            }
        }

        return ParsedHostPort(hostPart, 5060)
    }

    data class ParsedHostPort(val host: String, val port: Int)
}
