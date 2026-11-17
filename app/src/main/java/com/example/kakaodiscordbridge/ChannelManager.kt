package com.example.kakaodiscordbridge

import android.content.Context
import android.util.Log

class ChannelManager(
    private val context: Context,
    private val api: DiscordApi,
    private val serverId: String,
    private val categoryId: String,
    private val logChannelId: String
) {
    companion object {
        private const val TAG = "KDBridge"
        private const val PREFS_NAME = "channel_map"
    }

    // 카톡 방 이름 → Discord 채널 ID
    private val channelMap = mutableMapOf<String, String>()

    // SharedPreferences에서 저장된 매핑 로드
    fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        channelMap.clear()
        for ((key, value) in all) {
            if (value is String) {
                channelMap[key] = value
            }
        }
        Log.d(TAG, "채널맵 로드: ${channelMap.size}개")
    }

    // SharedPreferences에 매핑 저장
    private fun saveToPrefs(room: String, channelId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(room, channelId).apply()
    }

    // 서버에서 기존 채널 목록 동기화
    fun syncFromServer() {
        try {
            val channels = api.getChannels(serverId) ?: return
            channelMap.clear()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()

            for (i in 0 until channels.length()) {
                val ch = channels.getJSONObject(i)
                val type = ch.getInt("type")
                val parentId = ch.optString("parent_id", "")
                if (type == 0 && parentId == categoryId) {
                    val topic = ch.optString("topic", "")
                    val name = ch.getString("name")
                    val id = ch.getString("id")
                    val room = if (topic.isNotEmpty()) topic else name
                    channelMap[room] = id
                    editor.putString(room, id)
                }
            }
            editor.apply()
            Log.d(TAG, "서버 동기화 완료: ${channelMap.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "서버 동기화 오류: $e")
        }
    }

    // 채널 ID 가져오기 (없으면 생성)
    fun getOrCreateChannel(room: String, displayName: String? = null): String? {
        Log.d(TAG, "채널 조회: room='$room' 맵크기=${channelMap.size} 존재=${channelMap.containsKey(room)}")

        channelMap[room]?.let { return it }

        // 채널 이름: displayName이 있으면 사용, 없으면 room 사용
        val nameSource = displayName ?: room
        val isGroup = room.matches(Regex("^\\d+$"))
        var channelName = nameSource.replace("\\s+".toRegex(), "-").lowercase()
        if (isGroup) channelName = "그룹채팅-$channelName"
        if (channelName.length > 100) channelName = channelName.substring(0, 100)

        val result = api.createChannel(serverId, channelName, categoryId, room)
        if (result != null && result.has("id")) {
            val channelId = result.getString("id")
            channelMap[room] = channelId
            saveToPrefs(room, channelId)
            api.sendLog(logChannelId, "채널 생성: #$channelName ← $room")
            return channelId
        }

        api.sendLog(logChannelId, "채널 생성 실패: $room")
        return null
    }

    // 채팅방 이름으로 채널 ID 조회
    fun getChannelId(room: String): String? = channelMap[room]

    // 채널 ID로 채팅방 이름 역조회
    fun getRoomByChannelId(channelId: String): String? {
        return channelMap.entries.find { it.value == channelId }?.key
    }

    // 모든 채널 ID 목록
    fun getAllChannelIds(): Map<String, String> = channelMap.toMap()

    // 채널 수
    fun size(): Int = channelMap.size

    fun removeChannel(room: String) {
        channelMap.remove(room)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(room).apply()
    }
}