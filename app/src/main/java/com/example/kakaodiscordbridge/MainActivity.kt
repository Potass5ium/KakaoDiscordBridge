package com.example.kakaodiscordbridge

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.Activity

class MainActivity : Activity() {

    private lateinit var config: BridgeConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = BridgeConfig(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "KakaoDiscord Bridge 설정"
            textSize = 22f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(titleText)

        val etToken = addField(layout, "Bot Token", config.botToken)
        val etServerId = addField(layout, "Server ID", config.serverId)
        val etCategoryId = addField(layout, "카톡 카테고리 ID", config.categoryId)
        val etUserId = addField(layout, "내 Discord 사용자 ID", config.myUserId)
        val etLogId = addField(layout, "로그 채널 ID", config.logChannelId)
        val etSettingsId = addField(layout, "설정 채널 ID", config.settingsChannelId)
        val etAlertId = addField(layout, "알림 채널 ID", config.alertChannelId)

        val btnSave = Button(this).apply {
            text = "저장"
            setOnClickListener {
                config.botToken = etToken.text.toString().trim()
                config.serverId = etServerId.text.toString().trim()
                config.categoryId = etCategoryId.text.toString().trim()
                config.myUserId = etUserId.text.toString().trim()
                config.logChannelId = etLogId.text.toString().trim()
                config.settingsChannelId = etSettingsId.text.toString().trim()
                config.alertChannelId = etAlertId.text.toString().trim()
                Toast.makeText(this@MainActivity, "저장 완료. 앱을 재시작하세요.", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnSave)

        val btnPermission = Button(this).apply {
            text = "알림 접근 권한 설정"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        layout.addView(btnPermission)
        val btnOverlay = Button(this).apply {
            text = "다른 앱 위에 표시 권한"
            setOnClickListener {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        layout.addView(btnOverlay)

        val btnNotification = Button(this).apply {
            text = "알림 권한 설정"
            setOnClickListener {
                val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            }
        }
        layout.addView(btnNotification)
        val statusText = TextView(this).apply {
            text = if (config.isConfigured()) "상태: 설정 완료" else "상태: 설정 필요"
            textSize = 16f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(statusText)

        val btnStart = Button(this).apply {
            text = "사용 시작"
            setOnClickListener {
                NotificationListener.instance?.startService()
                Toast.makeText(this@MainActivity, "서비스 시작됨", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnStart)

        val btnStop = Button(this).apply {
            text = "사용 종료"
            setOnClickListener {
                NotificationListener.instance?.stopService()
                Toast.makeText(this@MainActivity, "서비스 종료됨", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnStop)

        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun addField(layout: android.widget.LinearLayout, label: String, value: String): EditText {
        val tv = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        layout.addView(tv)

        val et = EditText(this).apply {
            setText(value)
            isSingleLine = true
        }
        layout.addView(et)

        return et
    }
}