package com.baeminrefresh.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class AppEntry(val label: String, val packageName: String)

object TargetApps {

    /** 실행 가능한(런처에 보이는) 앱 목록. 배민커넥트로 보이는 앱이 맨 위로 온다. */
    fun launchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<AppEntry> { score(it) }.thenBy { it.label })
    }

    /** 설치된 앱 중 배민커넥트를 자동으로 찾는다. 못 찾으면 null */
    fun detectBaeminConnect(context: Context): AppEntry? =
        launchableApps(context).firstOrNull { score(it) > 0 }

    fun isBaeminConnectLike(entry: AppEntry) = score(entry) > 0

    private fun score(e: AppEntry): Int {
        val label = e.label.replace(" ", "").lowercase()
        val pkg = e.packageName.lowercase()
        return when {
            label.contains("배민커넥트") -> 3
            pkg.startsWith("com.woowahan") && (label.contains("커넥트") || label.contains("connect")) -> 2
            pkg.startsWith("com.woowahan") && pkg.contains("connect") -> 1
            else -> 0
        }
    }

    fun labelOf(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
