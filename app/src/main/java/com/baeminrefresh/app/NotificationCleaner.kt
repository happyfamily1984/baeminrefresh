package com.baeminrefresh.app

import android.service.notification.NotificationListenerService

/**
 * 알림 접근을 허용하면 연결되는 서비스. 폰 최적화 때 알림창에 떠 있는 알림을 한 번에 지운다.
 * (지울 수 없는 알림 — 음악 재생, 이 앱의 실행 중 알림 같은 상시 알림 — 은 그대로 남는다)
 */
class NotificationCleaner : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: NotificationCleaner? = null

        /** @return 지우기를 요청했으면 true (서비스가 연결돼 있지 않으면 false) */
        fun clearAll(): Boolean {
            val i = instance ?: return false
            return runCatching { i.cancelAllNotifications(); true }.getOrDefault(false)
        }
    }
}
