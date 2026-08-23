package com.example.kakaodiscordbridge

import android.util.Log
import okhttp3.*
import org.json.JSONObject

class DiscordGateway(
    private val botToken: String,
    private val myUserId: String,
    private val onMessage: (channelId: String, content: String, authorId: String, authorBot: Boolean) -> Unit,
    private val onInteraction: ((customId: String, interactionId: String, interactionToken: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "KDBridge"
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"
    }

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var heartbeatThread: Thread? = null
    private var lastSequence: Int? = null
    private var isConnected = false

    fun connect() {
        Log.d(TAG, "Gateway 연결 시작")
        val request = Request.Builder()
            .url(GATEWAY_URL)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gateway 연결됨")
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleGatewayMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Gateway 메시지 처리 오류: $e")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway 닫히는 중: $code $reason")
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway 닫힘: $code $reason")
                isConnected = false
                // 자동 재연결
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Gateway 오류: $t")
                isConnected = false
                reconnect()
            }
        })
    }

    private fun handleGatewayMessage(text: String) {
        val json = JSONObject(text)
        val op = json.getInt("op")
        val s = json.opt("s")
        if (s != null && s != JSONObject.NULL) {
            lastSequence = json.getInt("s")
        }

        when (op) {
            // Hello - heartbeat 시작 + identify
            10 -> {
                val heartbeatInterval = json.getJSONObject("d").getLong("heartbeat_interval")
                startHeartbeat(heartbeatInterval)
                sendIdentify()
            }
            // Heartbeat ACK
            11 -> {
                // 정상
            }
            // Dispatch (이벤트)
            0 -> {
                val t = json.optString("t", "")
                if (t == "MESSAGE_CREATE") {
                    handleMessageCreate(json.getJSONObject("d"))
                } else if (t == "READY") {
                    Log.d(TAG, "Gateway READY")
                } else if (t == "INTERACTION_CREATE") {
                    handleInteraction(json.getJSONObject("d"))
                }
            }
            // Reconnect 요청
            7 -> {
                Log.d(TAG, "Gateway 재연결 요청 수신")
                disconnect()
                reconnect()
            }
            // Invalid Session
            9 -> {
                Log.d(TAG, "Gateway 세션 무효")
                disconnect()
                Thread.sleep(3000)
                reconnect()
            }
        }
    }

    private fun handleInteraction(d: JSONObject) {
        val customId = d.optJSONObject("data")?.optString("custom_id") ?: return
        val interactionId = d.getString("id")
        val interactionToken = d.getString("token")
        onInteraction?.invoke(customId, interactionId, interactionToken)
    }

    private fun handleMessageCreate(d: JSONObject) {
        val channelId = d.getString("channel_id")
        val content = d.optString("content", "")
        val author = d.getJSONObject("author")
        val authorId = author.getString("id")
        val authorBot = author.optBoolean("bot", false)

        onMessage(channelId, content, authorId, authorBot)
    }

    private fun sendIdentify() {
        val identify = JSONObject()
            .put("op", 2)
            .put("d", JSONObject()
                .put("token", botToken)
                .put("intents", 512 + 32768)  // GUILD_MESSAGES (512) + MESSAGE_CONTENT (32768)
                .put("properties", JSONObject()
                    .put("os", "android")
                    .put("browser", "KakaoDiscordBridge")
                    .put("device", "KakaoDiscordBridge")
                )
            )
        webSocket?.send(identify.toString())
        Log.d(TAG, "Identify 전송")
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            try {
                while (isConnected) {
                    val heartbeat = JSONObject()
                        .put("op", 1)
                        .put("d", lastSequence ?: JSONObject.NULL)
                    webSocket?.send(heartbeat.toString())
                    Thread.sleep(intervalMs)
                }
            } catch (e: InterruptedException) {
                // 정상 종료
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun reconnect() {
        Thread {
            try {
                Thread.sleep(5000)
                Log.d(TAG, "Gateway 재연결 시도")
                connect()
            } catch (e: Exception) {
                Log.e(TAG, "재연결 오류: $e")
            }
        }.start()
    }

    fun disconnect() {
        isConnected = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
        webSocket?.close(1000, "정상 종료")
        webSocket = null
    }

    fun isConnected(): Boolean = isConnected
}