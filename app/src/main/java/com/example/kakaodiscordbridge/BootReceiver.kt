package com.example.kakaodiscordbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("KDBridge", "부팅 완료 - 서비스 시작")
            // NotificationListenerService는 시스템이 자동으로 재시작하므로
            // 별도 시작 코드 불필요. 알림 접근 권한이 있으면 자동 복구됨.
        }
    }
}