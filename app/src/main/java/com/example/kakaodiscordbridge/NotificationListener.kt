package com.example.kakaodiscordbridge

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "KDBridge"
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        var instance: NotificationListener? = null
    }

    private var api: DiscordApi? = null
    private var channelManager: ChannelManager? = null
    private var config: BridgeConfig? = null
    private var gateway: DiscordGateway? = null
    private var isSetup = false
    private var setupComplete = false
    private var isServiceActive = true
    private var alertEmbedMessageId: String? = null
    private var cleanupThread: Thread? = null

    private val pendingMessages = java.util.concurrent.LinkedBlockingQueue<Triple<String, String, Notification>>()
    private val replyActions = ConcurrentHashMap<String, Notification.Action>()
    private val sentMessages = mutableListOf<Triple<String, String, Long>>()
    private val channelNameCache = ConcurrentHashMap<String, String>()
    private val lastSenderByRoom = ConcurrentHashMap<String, String>()

    // ======================== 초기화 ========================

    override fun onCreate() {
        super.onCreate()
        instance = this
        setup()
    }

    private fun setup() {
        if (isSetup) return

        config = BridgeConfig(this)
        if (config?.isConfigured() != true) return

        api = DiscordApi(config!!.botToken)
        channelManager = ChannelManager(
            this, api!!, config!!.serverId,
            config!!.categoryId, config!!.logChannelId
        )

        Thread {
            channelManager?.loadFromPrefs()
            channelManager?.syncFromServer()
            api?.sendLog(config!!.logChannelId, "봇 시작됨")

            // Reply Action 복구
            try {
                for (sbn in activeNotifications) {
                    if (sbn.packageName == KAKAO_PACKAGE) {
                        val extras = sbn.notification.extras
                        val title = extras.getString("android.title") ?: ""
                        val isGroup = extras.getBoolean("android.isGroupConversation", false)
                        val room = if (isGroup) (sbn.tag ?: "") else title
                        sbn.notification.actions?.forEach { action ->
                            if (action.remoteInputs?.isNotEmpty() == true && room.isNotEmpty()) {
                                replyActions[room] = action
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            setupComplete = true
            startGateway()
            startCleanupThread()

            // 알림 채널에서 기존 봇 메시지 찾기
            try {
                val alertMessages = api?.getMessages(config!!.alertChannelId, limit = 10)
                if (alertMessages != null) {
                    for (i in 0 until alertMessages.length()) {
                        val msg = alertMessages.getJSONObject(i)
                        if (msg.optJSONObject("author")?.optBoolean("bot", false) == true) {
                            alertEmbedMessageId = msg.getString("id")
                            break
                        }
                    }
                }
            } catch (_: Exception) {}

            // 대기 중인 메시지 처리
            while (pendingMessages.isNotEmpty()) {
                val (room, sender, notif) = pendingMessages.poll() ?: break
                val text = notif.extras.getCharSequence("android.text")?.toString() ?: ""
                forwardToDiscord(room, sender, text)
            }
        }.start()

        isSetup = true
    }

    // ======================== Gateway ========================

    private fun startGateway() {
        gateway = DiscordGateway(
            botToken = config!!.botToken,
            myUserId = config!!.myUserId,
            onMessage = { channelId, content, authorId, authorBot ->
                if (authorBot) return@DiscordGateway
                if (authorId != config!!.myUserId) return@DiscordGateway

                if (channelId == config!!.settingsChannelId) {
                    handleCommand(content.trim())
                    return@DiscordGateway
                }

                if (content.isBlank()) return@DiscordGateway

                val room = channelManager?.getRoomByChannelId(channelId) ?: return@DiscordGateway

                val success = replyToKakao(room, content)
                if (success) {
                    api?.sendLog(config!!.logChannelId, "답장 성공: [$room] $content")
                } else {
                    api?.sendMessage(channelId, "⚠ 답장 실패: 해당 채팅방에서 카톡이 한번 와야 답장이 가능합니다.")
                }
            },
            onInteraction = { _, _, _ -> }
        )
        gateway?.connect()
    }

    // ======================== 알림 처리 ========================

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isServiceActive) return
        if (sbn.packageName == "com.example.kakaodiscordbridge") return
        if (!isSetup) setup()
        if (config == null) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        if (sbn.packageName == KAKAO_PACKAGE) {
            handleKakao(title, text, extras, sbn.notification, sbn)
        }
        syncAlertPanel()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isServiceActive) return
        syncAlertPanel()
    }

    // ======================== 카톡 처리 ========================

    private fun handleKakao(title: String, text: String, extras: Bundle, notification: Notification, sbn: StatusBarNotification) {
        if (text.contains("보이스톡") || text.contains("통화 중") || text.contains("연결 중")) return

        val isGroup = extras.getBoolean("android.isGroupConversation", false)
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
        val sender = title
        val room = if (isGroup) (sbn.tag ?: "group_unknown") else if (subText.isNotEmpty()) subText else title

        // Reply Action 저장
        notification.actions?.forEach { action ->
            if (action.remoteInputs?.isNotEmpty() == true) {
                replyActions[room] = action
            }
        }

        // 그룹채팅 참여자 이름 조합
        var displayName: String? = null
        if (isGroup) {
            val msgs = extras.getParcelableArray("android.messages")
            if (msgs != null) {
                val senders = mutableSetOf<String>()
                for (msg in msgs) {
                    (msg as? Bundle)?.getCharSequence("sender")?.toString()?.let { senders.add(it) }
                }
                if (senders.isNotEmpty()) displayName = senders.joinToString("-")
            }
        }

        if (setupComplete) {
            Thread {
                // 그룹채팅: 발신자 바뀌면 색상 구분 임베드 추가
                if (isGroup) {
                    val lastSender = lastSenderByRoom[room]
                    if (lastSender != null && lastSender != sender) {
                        // 발신자 변경: 새 발신자 색상의 빈 구분선 임베드
                        val channelId = channelManager?.getOrCreateChannel(room, displayName) ?: return@Thread
                        api?.sendDivider(channelId, sender)
                    }
                    lastSenderByRoom[room] = sender
                }
                forwardToDiscord(room, sender, text, displayName)
            }.start()
        } else {
            pendingMessages.add(Triple(room, sender, notification))
        }
    }

    // ======================== Discord 포워딩 ========================

    private fun forwardToDiscord(room: String, sender: String, message: String, displayName: String? = null) {
        try {
            val channelId = channelManager?.getOrCreateChannel(room, displayName) ?: return
            val result = api?.sendEmbed(channelId, sender, message, sender)
            if (result?.has("id") == true) {
                synchronized(sentMessages) {
                    sentMessages.add(Triple(channelId, result.getString("id"), System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "포워딩 오류: $e")
        }
    }

    // ======================== 카톡 답장 ========================

    fun replyToKakao(room: String, message: String): Boolean {
        val action = replyActions[room] ?: return false
        return try {
            val remoteInput = action.remoteInputs?.firstOrNull() ?: return false
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(remoteInput.resultKey, message)
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "답장 오류: $room - $e")
            false
        }
    }

    // ======================== 알림창 미러링 ========================

    private fun syncAlertPanel() {
        if (!setupComplete) return
        if (config?.alertChannelId.isNullOrEmpty()) return

        Thread {
            try {
                val messages = mutableListOf<String>()
                val pinned = mutableListOf<String>()

                for (sbn in activeNotifications) {
                    val pkg = sbn.packageName
                    if (pkg == "com.example.kakaodiscordbridge") continue

                    val extras = sbn.notification.extras
                    val nTitle = extras.getString("android.title") ?: ""
                    val nText = extras.getCharSequence("android.text")?.toString() ?: ""
                    if (nTitle.isEmpty() && nText.isEmpty()) continue

                    val template = extras.getString("android.template") ?: ""
                    if (template.contains("MediaStyle")) continue
                    if (pkg == "com.android.systemui" && nTitle.matches(Regex("^[a-zA-Z\\s]+$"))) continue

                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        pkg.substringAfterLast(".")
                    }

                    val isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
                    val time = java.text.SimpleDateFormat("HH:mm").format(java.util.Date(sbn.postTime))

                    var displayTitle = nTitle
                    val displayText = nText
                    if (pkg == KAKAO_PACKAGE) {
                        val isGroup = extras.getBoolean("android.isGroupConversation", false)
                        if (isGroup) {
                            val chatId = sbn.tag ?: ""
                            val channelId = channelManager?.getChannelId(chatId)
                            if (channelId != null) {
                                val cachedName = channelNameCache[channelId]
                                if (cachedName != null) {
                                    displayTitle = "[$cachedName] $nTitle"
                                } else {
                                    try {
                                        val channelInfo = api?.getChannel(channelId)
                                        val channelName = channelInfo?.optString("name", chatId) ?: chatId
                                        channelNameCache[channelId] = channelName
                                        displayTitle = "[$channelName] $nTitle"
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                    }

                    if (isOngoing) {
                        pinned.add("📌 **$appName** | $displayTitle: $displayText")
                    } else {
                        messages.add("$time **$appName** | $displayTitle: $displayText")
                    }
                }

                val description = StringBuilder()
                if (messages.isNotEmpty()) {
                    description.appendLine("🔔 **알림**")
                    messages.forEach { description.appendLine(it) }
                }
                if (pinned.isNotEmpty()) {
                    if (messages.isNotEmpty()) description.appendLine()
                    description.appendLine("📌 **고정**")
                    pinned.forEach { description.appendLine(it) }
                }
                if (messages.isEmpty() && pinned.isEmpty()) {
                    description.append("알림 없음")
                }

                val embed = org.json.JSONObject()
                    .put("title", "알림창")
                    .put("description", description.toString())
                    .put("footer", org.json.JSONObject().put("text",
                        "동기화: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"))
                    .put("color", 0x2F3136)

                val json = org.json.JSONObject().put("embeds", org.json.JSONArray().put(embed))

                if (alertEmbedMessageId != null) {
                    val editResult = api?.editEmbed(config!!.alertChannelId, alertEmbedMessageId!!, embed)
                    if (editResult == null || editResult.has("code")) {
                        try { api?.deleteMessage(config!!.alertChannelId, alertEmbedMessageId!!) } catch (_: Exception) {}
                        alertEmbedMessageId = null
                        val result = api?.sendRawJson(config!!.alertChannelId, json)
                        alertEmbedMessageId = result?.optString("id")
                    }
                } else {
                    try {
                        val oldMessages = api?.getMessages(config!!.alertChannelId, limit = 10)
                        if (oldMessages != null) {
                            for (i in 0 until oldMessages.length()) {
                                val msg = oldMessages.getJSONObject(i)
                                if (msg.optJSONObject("author")?.optBoolean("bot", false) == true) {
                                    api?.deleteMessage(config!!.alertChannelId, msg.getString("id"))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    val result = api?.sendRawJson(config!!.alertChannelId, json)
                    alertEmbedMessageId = result?.optString("id")
                }
            } catch (e: Exception) {
                Log.e(TAG, "알림 동기화 오류: $e")
            }
        }.start()
    }

    // ======================== 명령어 ========================

    private fun handleCommand(cmd: String) {
        when (cmd) {
            "!상태" -> {
                api?.sendMessage(config!!.settingsChannelId,
                    "**봇 상태**\n" +
                            "Gateway: ${if (gateway?.isConnected() == true) "연결됨" else "끊김"}\n" +
                            "등록된 채널: ${channelManager?.size() ?: 0}개\n" +
                            "Reply Action: ${replyActions.size}개"
                )
            }
            "!알림" -> {
                Thread { syncAlertPanel() }.start()
            }
            "!도움" -> {
                api?.sendMessage(config!!.settingsChannelId,
                    "**명령어**\n" +
                            "!상태 - 봇 상태 확인\n" +
                            "!알림 - 알림 패널 갱신\n" +
                            "!도움 - 도움말"
                )
            }
        }
    }

    // ======================== 자동 삭제 ========================

    private fun startCleanupThread() {
        cleanupThread = Thread {
            while (true) {
                try {
                    Thread.sleep(60 * 60 * 1000)
                    val now = System.currentTimeMillis()
                    val expired = mutableListOf<Triple<String, String, Long>>()
                    synchronized(sentMessages) {
                        val iter = sentMessages.iterator()
                        while (iter.hasNext()) {
                            val msg = iter.next()
                            if (now - msg.third > 24 * 60 * 60 * 1000) {
                                expired.add(msg)
                                iter.remove()
                            }
                        }
                    }
                    for ((chId, msgId, _) in expired) {
                        api?.deleteMessage(chId, msgId)
                        Thread.sleep(500)
                    }
                } catch (_: InterruptedException) { break }
                catch (e: Exception) { Log.e(TAG, "클린업 오류: $e") }
            }
        }.apply { isDaemon = true; start() }
    }

    // ======================== 서비스 제어 ========================

    fun startService() {
        isServiceActive = true
        Thread {
            api?.sendLog(config!!.logChannelId, "서비스 시작")
            syncAlertPanel()
        }.start()
    }

    fun stopService() {
        isServiceActive = false
        Thread {
            api?.sendLog(config!!.logChannelId, "서비스 종료")
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        gateway?.disconnect()
    }
}