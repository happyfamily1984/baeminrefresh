package com.baeminrefresh.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 재부팅/업데이트 후, 사용자가 켜 두었던 플로팅 버튼을 다시 띄운다 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (Prefs(context).floatingEnabled && Settings.canDrawOverlays(context)) {
            runCatching { FloatingService.start(context) }
        }
    }
}
