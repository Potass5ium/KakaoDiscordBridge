package com.example.kakaodiscordbridge

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class DiscordApi(private val botToken: String) {

    companion object {
        private const val TAG = "KDBridge"
        private const val BASE_URL = "https://discord.com/api/v10"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    private val client = OkHttpClient()

    // ======================== 기본 HTTP ========================

    private fun getArray(endpoint: String): JSONArray? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL$endpoint")
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", "DiscordBot (https://github.com/user/bot, 1.0)")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            JSONArray(body)
        } catch (e: Exception) {
            Log.e(TAG, "GET Array 오류: $endpoint - $e")
            null
        }
    }

    private fun post(endpoint: String, json: JSONObject): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL$endpoint")
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", "DiscordBot (https://github.com/user/bot, 1.0)")
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "POST 오류: $endpoint - $e")
            null
        }
    }

    private fun patch(endpoint: String, json: JSONObject): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL$endpoint")
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", "DiscordBot (https://github.com/user/bot, 1.0)")
                .patch(json.toString().toRequestBody(JSON_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "PATCH 오류: $endpoint - $e")
            null
        }
    }

    // ======================== 메시지 ========================

    fun sendMessage(channelId: String, content: String): JSONObject? {
        return post("/channels/$channelId/messages", JSONObject().put("content", content))
    }

    fun sendEmbed(channelId: String, author: String, description: String, colorKey: String = ""): JSONObject? {
        val color = generateColor(if (colorKey.isNotEmpty()) colorKey else if (author.isNotEmpty()) author else description)
        val embed = JSONObject()
            .put("color", color)
            .put("footer", JSONObject().put("text",
                java.text.SimpleDateFormat("HH:mm").format(java.util.Date())))
        val content = if (author.isNotEmpty()) "$author: $description" else description
        val json = JSONObject()
            .put("content", content)
            .put("embeds", JSONArray().put(embed))
        return post("/channels/$channelId/messages", json)
    }

    fun sendRawJson(channelId: String, json: JSONObject): JSONObject? {
        return post("/channels/$channelId/messages", json)
    }

    fun deleteMessage(channelId: String, messageId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId/messages/$messageId")
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", "DiscordBot (https://github.com/user/bot, 1.0)")
                .delete()
                .build()
            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "DELETE 오류: $e")
            false
        }
    }

    fun editEmbed(channelId: String, messageId: String, embed: JSONObject): JSONObject? {
        return patch("/channels/$channelId/messages/$messageId",
            JSONObject().put("embeds", JSONArray().put(embed)))
    }

    fun getMessages(channelId: String, afterId: String? = null, limit: Int = 5): JSONArray? {
        var endpoint = "/channels/$channelId/messages?limit=$limit"
        if (afterId != null) endpoint += "&after=$afterId"
        return getArray(endpoint)
    }

    // ======================== 채널 ========================

    fun createChannel(serverId: String, name: String, categoryId: String, topic: String): JSONObject? {
        return post("/guilds/$serverId/channels", JSONObject()
            .put("name", name).put("type", 0)
            .put("parent_id", categoryId).put("topic", topic))
    }

    fun getChannels(serverId: String): JSONArray? {
        return getArray("/guilds/$serverId/channels")
    }

    fun getChannel(channelId: String): JSONObject? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/channels/$channelId")
                .header("Authorization", "Bot $botToken")
                .header("User-Agent", "DiscordBot (https://github.com/user/bot, 1.0)")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "채널 조회 오류: $e")
            null
        }
    }

    // ======================== 유틸리티 ========================

    fun sendLog(channelId: String, message: String) {
        try {
            sendMessage(channelId, "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}] $message")
        } catch (e: Exception) {
            Log.e(TAG, "로그 전송 실패: $e")
        }
    }

    private fun generateColor(name: String): Int {
        val hash = name.hashCode()
        val colors = intArrayOf(
            0xE74C3C, 0x3498DB, 0xE67E22, 0x9B59B6,
            0x1ABC9C, 0xF1C40F, 0xE91E63, 0x2ECC71,
            0x00BCD4, 0xFF5722, 0x795548, 0x607D8B
        )
        return colors[Math.abs(hash) % colors.size]
    }
}