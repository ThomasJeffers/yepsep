package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MessageStatus {
    SENT,
    DELIVERED,
    RECEIVED,
    FAILED
}

enum class TacticalMessageType {
    TEXT,
    SDS_DATA,
    EMERGENCY_ALERT
}

data class TacticalParticipant(
    val id: String,
    val displayName: String,
    val callsign: String,
    val role: String = "Tactical Operator"
)

data class TacticalMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderCallsign: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean,
    val status: MessageStatus = if (isOutgoing) MessageStatus.SENT else MessageStatus.RECEIVED,
    val messageType: TacticalMessageType = TacticalMessageType.TEXT
)

data class TacticalConversation(
    val id: String, // SIP URI (e.g. sip:group1@... or sip:491234567890124@...)
    val title: String,
    val subtitle: String,
    val isGroup: Boolean,
    val participants: List<TacticalParticipant>,
    val lastMessageText: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0
)

/**
 * Tactical Messaging Repository providing MCData Short Data Service (SDS) inspired
 * 1-to-1 and group conversation architecture.
 */
class TacticalMessagingRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tactical_messaging_prefs", Context.MODE_PRIVATE)

    private val _conversations = MutableStateFlow<List<TacticalConversation>>(emptyList())
    val conversations: StateFlow<List<TacticalConversation>> = _conversations.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<TacticalMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<TacticalMessage>>> = _messages.asStateFlow()

    private val _activeConversationId = MutableStateFlow("sip:group1@ims.mnc070.mcc901.3gppnetwork.org")
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        val convsRaw = prefs.getString("conversations_json", null)
        val msgsRaw = prefs.getString("messages_json", null)

        val convList = if (!convsRaw.isNullOrBlank()) {
            try {
                parseConversations(convsRaw)
            } catch (e: Exception) {
                defaultConversations()
            }
        } else {
            defaultConversations()
        }

        val msgMap = if (!msgsRaw.isNullOrBlank()) {
            try {
                parseMessages(msgsRaw)
            } catch (e: Exception) {
                defaultMessages()
            }
        } else {
            defaultMessages()
        }

        _conversations.value = convList
        _messages.value = msgMap
    }

    private fun defaultConversations(): List<TacticalConversation> {
        return listOf(
            TacticalConversation(
                id = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                title = "Group 1 - Tactical Ops",
                subtitle = "Active Tactical Channel • 4 Members",
                isGroup = true,
                participants = listOf(
                    TacticalParticipant("sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org", "MCPTT UE-1", "ALPHA-1", "Field Unit"),
                    TacticalParticipant("sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org", "MCPTT UE-2", "BRAVO-2", "Field Unit"),
                    TacticalParticipant("sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org", "Command Central", "BASE-01", "Dispatch"),
                    TacticalParticipant("sip:patrol4@ims.mnc070.mcc901.3gppnetwork.org", "Patrol Unit 4", "DELTA-4", "Patrol")
                ),
                lastMessageText = "Status check: All units maintain radio discipline.",
                lastMessageTimestamp = System.currentTimeMillis() - 120_000,
                unreadCount = 0
            ),
            TacticalConversation(
                id = "sip:group2@ims.mnc070.mcc901.3gppnetwork.org",
                title = "Group 2 - Fire / Rescue",
                subtitle = "Emergency Support Channel • 3 Members",
                isGroup = true,
                participants = listOf(
                    TacticalParticipant("sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org", "MCPTT UE-1", "ALPHA-1", "Field Unit"),
                    TacticalParticipant("sip:rescue1@ims.mnc070.mcc901.3gppnetwork.org", "Rescue Lead", "RESCUE-1", "First Responder"),
                    TacticalParticipant("sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org", "Command Central", "BASE-01", "Dispatch")
                ),
                lastMessageText = "Perimeter secure at sector B.",
                lastMessageTimestamp = System.currentTimeMillis() - 600_000,
                unreadCount = 0
            ),
            TacticalConversation(
                id = "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org",
                title = "Command Dispatcher",
                subtitle = "Direct Line • Operations Control",
                isGroup = false,
                participants = listOf(
                    TacticalParticipant("sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org", "Command Central", "BASE-01", "Dispatch")
                ),
                lastMessageText = "Report location upon arrival at checkpoint.",
                lastMessageTimestamp = System.currentTimeMillis() - 300_000,
                unreadCount = 1
            ),
            TacticalConversation(
                id = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                title = "Tactical Unit 2 (Bravo)",
                subtitle = "Direct Line • Patrol Partner",
                isGroup = false,
                participants = listOf(
                    TacticalParticipant("sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org", "MCPTT UE-2", "BRAVO-2", "Field Unit")
                ),
                lastMessageText = "Copy that. Standing by on primary voice channel.",
                lastMessageTimestamp = System.currentTimeMillis() - 450_000,
                unreadCount = 0
            )
        )
    }

    private fun defaultMessages(): Map<String, List<TacticalMessage>> {
        val now = System.currentTimeMillis()
        return mapOf(
            "sip:group1@ims.mnc070.mcc901.3gppnetwork.org" to listOf(
                TacticalMessage(
                    conversationId = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org",
                    senderName = "Command Central",
                    senderCallsign = "BASE-01",
                    text = "All units, tactical net is now live on MCPTT bearer.",
                    timestamp = now - 600_000,
                    isOutgoing = false
                ),
                TacticalMessage(
                    conversationId = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                    senderName = "MCPTT UE-2",
                    senderCallsign = "BRAVO-2",
                    text = "Bravo-2 acknowledging. Audio link verified loud and clear.",
                    timestamp = now - 400_000,
                    isOutgoing = false
                ),
                TacticalMessage(
                    conversationId = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "local_ue",
                    senderName = "MCPTT UE-1",
                    senderCallsign = "ALPHA-1",
                    text = "Alpha-1 ready. Holding position at perimeter North.",
                    timestamp = now - 250_000,
                    isOutgoing = true
                ),
                TacticalMessage(
                    conversationId = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org",
                    senderName = "Command Central",
                    senderCallsign = "BASE-01",
                    text = "Status check: All units maintain radio discipline.",
                    timestamp = now - 120_000,
                    isOutgoing = false
                )
            ),
            "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org" to listOf(
                TacticalMessage(
                    conversationId = "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "sip:dispatcher@ims.mnc070.mcc901.3gppnetwork.org",
                    senderName = "Command Central",
                    senderCallsign = "BASE-01",
                    text = "Report location upon arrival at checkpoint.",
                    timestamp = now - 300_000,
                    isOutgoing = false
                )
            ),
            "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org" to listOf(
                TacticalMessage(
                    conversationId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "local_ue",
                    senderName = "MCPTT UE-1",
                    senderCallsign = "ALPHA-1",
                    text = "Bravo-2, do you have eyes on sector 3?",
                    timestamp = now - 500_000,
                    isOutgoing = true
                ),
                TacticalMessage(
                    conversationId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                    senderId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                    senderName = "MCPTT UE-2",
                    senderCallsign = "BRAVO-2",
                    text = "Copy that. Standing by on primary voice channel.",
                    timestamp = now - 450_000,
                    isOutgoing = false
                )
            )
        )
    }

    fun selectConversation(id: String) {
        _activeConversationId.value = id
        markConversationRead(id)
    }

    fun markConversationRead(conversationId: String) {
        val updated = _conversations.value.map { conv ->
            if (conv.id == conversationId) conv.copy(unreadCount = 0) else conv
        }
        _conversations.value = updated
        persistConversations(updated)
    }

    fun addOutgoingMessage(
        conversationId: String,
        text: String,
        senderName: String,
        senderCallsign: String
    ): TacticalMessage {
        val msg = TacticalMessage(
            conversationId = conversationId,
            senderId = "local_ue",
            senderName = senderName,
            senderCallsign = senderCallsign,
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            status = MessageStatus.SENT
        )

        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[conversationId] ?: emptyList()).toMutableList()
        list.add(msg)
        currentMap[conversationId] = list
        _messages.value = currentMap

        // Update conversation summary
        val convs = _conversations.value.map { conv ->
            if (conv.id == conversationId) {
                conv.copy(
                    lastMessageText = text.trim(),
                    lastMessageTimestamp = msg.timestamp
                )
            } else conv
        }
        _conversations.value = convs

        persistMessages(currentMap)
        persistConversations(convs)
        return msg
    }

    fun addIncomingMessage(
        senderUri: String,
        text: String,
        targetGroupUri: String? = null
    ): TacticalMessage {
        val targetConversationId = if (!targetGroupUri.isNullOrBlank()) {
            targetGroupUri
        } else {
            // Find 1-to-1 conversation matching sender or default to active
            val found = _conversations.value.find { it.id.equals(senderUri, ignoreCase = true) }
            found?.id ?: _activeConversationId.value
        }

        val cleanSender = senderUri.substringAfter("sip:").substringBefore("@")
        val senderCallsign = cleanSender.takeLast(4).uppercase()

        val msg = TacticalMessage(
            conversationId = targetConversationId,
            senderId = senderUri,
            senderName = cleanSender,
            senderCallsign = senderCallsign,
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isOutgoing = false,
            status = MessageStatus.RECEIVED
        )

        val currentMap = _messages.value.toMutableMap()
        val list = (currentMap[targetConversationId] ?: emptyList()).toMutableList()
        list.add(msg)
        currentMap[targetConversationId] = list
        _messages.value = currentMap

        // Update conversation summary & unread count
        val isCurrentlyActive = _activeConversationId.value == targetConversationId
        val convs = _conversations.value.map { conv ->
            if (conv.id == targetConversationId) {
                conv.copy(
                    lastMessageText = text.trim(),
                    lastMessageTimestamp = msg.timestamp,
                    unreadCount = if (isCurrentlyActive) 0 else conv.unreadCount + 1
                )
            } else conv
        }
        _conversations.value = convs

        persistMessages(currentMap)
        persistConversations(convs)
        return msg
    }

    fun createConversation(
        targetUri: String,
        title: String,
        isGroup: Boolean,
        participants: List<TacticalParticipant> = emptyList()
    ): TacticalConversation {
        val existing = _conversations.value.find { it.id.equals(targetUri, ignoreCase = true) }
        if (existing != null) {
            selectConversation(existing.id)
            return existing
        }

        val newConv = TacticalConversation(
            id = targetUri,
            title = title,
            subtitle = if (isGroup) "Tactical Group Channel" else "Direct Comms Line",
            isGroup = isGroup,
            participants = participants,
            lastMessageText = "Conversation initialized",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0
        )

        val updated = listOf(newConv) + _conversations.value
        _conversations.value = updated
        _activeConversationId.value = newConv.id
        persistConversations(updated)
        return newConv
    }

    private fun persistConversations(convs: List<TacticalConversation>) {
        try {
            val jsonArray = JSONArray()
            for (c in convs) {
                val obj = JSONObject()
                obj.put("id", c.id)
                obj.put("title", c.title)
                obj.put("subtitle", c.subtitle)
                obj.put("isGroup", c.isGroup)
                obj.put("lastMessageText", c.lastMessageText)
                obj.put("lastMessageTimestamp", c.lastMessageTimestamp)
                obj.put("unreadCount", c.unreadCount)

                val partsArray = JSONArray()
                for (p in c.participants) {
                    val pObj = JSONObject()
                    pObj.put("id", p.id)
                    pObj.put("displayName", p.displayName)
                    pObj.put("callsign", p.callsign)
                    pObj.put("role", p.role)
                    partsArray.put(pObj)
                }
                obj.put("participants", partsArray)
                jsonArray.put(obj)
            }
            prefs.edit().putString("conversations_json", jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("TacticalMessagingRepo", "Failed to persist conversations: ${e.message}")
        }
    }

    private fun persistMessages(map: Map<String, List<TacticalMessage>>) {
        try {
            val root = JSONObject()
            for ((convId, list) in map) {
                val array = JSONArray()
                for (m in list.takeLast(100)) { // Keep last 100 per thread
                    val obj = JSONObject()
                    obj.put("id", m.id)
                    obj.put("conversationId", m.conversationId)
                    obj.put("senderId", m.senderId)
                    obj.put("senderName", m.senderName)
                    obj.put("senderCallsign", m.senderCallsign)
                    obj.put("text", m.text)
                    obj.put("timestamp", m.timestamp)
                    obj.put("isOutgoing", m.isOutgoing)
                    obj.put("status", m.status.name)
                    array.put(obj)
                }
                root.put(convId, array)
            }
            prefs.edit().putString("messages_json", root.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("TacticalMessagingRepo", "Failed to persist messages: ${e.message}")
        }
    }

    private fun parseConversations(jsonStr: String): List<TacticalConversation> {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<TacticalConversation>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val partsArray = obj.optJSONArray("participants") ?: JSONArray()
            val participants = mutableListOf<TacticalParticipant>()
            for (j in 0 until partsArray.length()) {
                val pObj = partsArray.getJSONObject(j)
                participants.add(
                    TacticalParticipant(
                        id = pObj.getString("id"),
                        displayName = pObj.getString("displayName"),
                        callsign = pObj.getString("callsign"),
                        role = pObj.optString("role", "Operator")
                    )
                )
            }

            list.add(
                TacticalConversation(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    subtitle = obj.optString("subtitle", ""),
                    isGroup = obj.getBoolean("isGroup"),
                    participants = participants,
                    lastMessageText = obj.optString("lastMessageText", ""),
                    lastMessageTimestamp = obj.optLong("lastMessageTimestamp", 0L),
                    unreadCount = obj.optInt("unreadCount", 0)
                )
            )
        }
        return if (list.isNotEmpty()) list else defaultConversations()
    }

    private fun parseMessages(jsonStr: String): Map<String, List<TacticalMessage>> {
        val root = JSONObject(jsonStr)
        val map = mutableMapOf<String, List<TacticalMessage>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val convId = keys.next()
            val array = root.getJSONArray(convId)
            val list = mutableListOf<TacticalMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TacticalMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        conversationId = obj.getString("conversationId"),
                        senderId = obj.getString("senderId"),
                        senderName = obj.getString("senderName"),
                        senderCallsign = obj.optString("senderCallsign", "UNIT"),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        isOutgoing = obj.getBoolean("isOutgoing"),
                        status = try {
                            MessageStatus.valueOf(obj.optString("status", "SENT"))
                        } catch (e: Exception) {
                            MessageStatus.SENT
                        }
                    )
                )
            }
            map[convId] = list
        }
        return map
    }
}
