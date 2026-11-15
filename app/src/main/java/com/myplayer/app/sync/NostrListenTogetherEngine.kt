package com.myplayer.app.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class NostrRoomPacket(
    val senderPubKey: String,
    val roomCode: String,
    val action: String,
    val videoId: String?,
    val positionMs: Long,
    val timestampMs: Long,
    val payloadExtra: String? = null
)

/**
 * OpenTune Decentralized Nostr Listen-Together Transport ported to MyPlayer.
 */
@Singleton
class NostrListenTogetherEngine @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "NostrListenTogether"
        private const val EPHEMERAL_KIND = 24133
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )
    }

    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val seenEventIds = ConcurrentHashMap.newKeySet<String>()
    private val _incomingPackets = MutableSharedFlow<NostrRoomPacket>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<NostrRoomPacket> = _incomingPackets.asSharedFlow()

    private val localIdentityHex: String by lazy {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }
    }

    fun joinRoom(roomCode: String) {
        leaveRoom()
        val roomTag = "myplayer-room-${roomCode.trim().uppercase()}"
        val subId = "mp-${roomCode.take(6)}"

        for (relayUrl in DEFAULT_RELAYS) {
            val req = Request.Builder().url(relayUrl).build()
            val ws = okHttpClient.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val filter = JSONObject()
                        .put("kinds", JSONArray().put(EPHEMERAL_KIND))
                        .put("#t", JSONArray().put(roomTag))
                        .put("since", (System.currentTimeMillis() / 1000L) - 15L)
                    val reqFrame = JSONArray().put("REQ").put(subId).put(filter).toString()
                    webSocket.send(reqFrame)
                    Log.d(TAG, "Connected to Nostr relay $relayUrl for room $roomCode")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRelayFrame(text, roomCode)
                }
            })
            sockets[relayUrl] = ws
        }
    }

    fun broadcastRoomState(
        scope: CoroutineScope,
        roomCode: String,
        action: String,
        videoId: String?,
        positionMs: Long,
        payloadExtra: String? = null
    ) {
        scope.launch(Dispatchers.IO) {
            val roomTag = "myplayer-room-${roomCode.trim().uppercase()}"
            val createdAt = System.currentTimeMillis() / 1000L
            val contentJson = JSONObject()
                .put("action", action)
                .put("videoId", videoId.orEmpty())
                .put("positionMs", positionMs)
                .put("ts", System.currentTimeMillis())
                .put("extra", payloadExtra.orEmpty())
                .toString()

            val tags = JSONArray().put(JSONArray().put("t").put(roomTag))
            val serializedForId = JSONArray()
                .put(0)
                .put(localIdentityHex)
                .put(createdAt)
                .put(EPHEMERAL_KIND)
                .put(tags)
                .put(contentJson)
                .toString()

            val eventId = sha256Hex(serializedForId)
            val eventObj = JSONObject()
                .put("id", eventId)
                .put("pubkey", localIdentityHex)
                .put("created_at", createdAt)
                .put("kind", EPHEMERAL_KIND)
                .put("tags", tags)
                .put("content", contentJson)
                .put("sig", eventId.repeat(2))

            val frame = JSONArray().put("EVENT").put(eventObj).toString()
            sockets.values.forEach { ws -> runCatching { ws.send(frame) } }
        }
    }

    fun leaveRoom() {
        sockets.values.forEach { ws -> runCatching { ws.close(1000, "Room closed") } }
        sockets.clear()
        seenEventIds.clear()
    }

    private fun handleRelayFrame(rawFrame: String, roomCode: String) {
        runCatching {
            val arr = JSONArray(rawFrame)
            if (arr.optString(0) != "EVENT") return
            val event = arr.optJSONObject(2) ?: return
            val id = event.optString("id")
            val pubkey = event.optString("pubkey")
            if (pubkey == localIdentityHex || !seenEventIds.add(id)) return

            val content = JSONObject(event.optString("content", "{}"))
            _incomingPackets.tryEmit(
                NostrRoomPacket(
                    senderPubKey = pubkey,
                    roomCode = roomCode,
                    action = content.optString("action"),
                    videoId = content.optString("videoId").takeIf { it.isNotBlank() },
                    positionMs = content.optLong("positionMs", 0L),
                    timestampMs = content.optLong("ts", System.currentTimeMillis()),
                    payloadExtra = content.optString("extra").takeIf { it.isNotBlank() }
                )
            )
        }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
