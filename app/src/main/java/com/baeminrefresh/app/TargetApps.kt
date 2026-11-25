package com.baeminrefresh.app

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

data class AppEntry(val label: String, val packageName: String)

object TargetApps {

    // ---------------- 멀티 프로필(업무 프로필 등) 앱 ----------------
    // 스티커에는 앱 아이디를 "패키지" 로 저장한다. 다른 프로필의 앱은 "패키지@프로필번호"
    // (UserManager 가 주는 프로필 고유 번호)로 저장해서 구분한다. 폴더 키도 "sticker:패키지@번호".

    fun isOtherProfile(id: String): Boolean = '@' in id && !isClone(id)

    // ---------------- 멀티로그인 개인용 복제본 ----------------
    // 멀티로그인 개인용(가상 공간)의 복제본은 "mlp:<가상사용자번호>:<패키지>" 로 저장한다.
    // 목록은 그 앱의 ContentProvider 에서 읽고, 실행은 그 앱의 ShortcutLaunchActivity 에 맡긴다.
    const val MLP_PKG = "com.happyfamily1984.multilogin.personal"
    private const val MLP_AUTH = "$MLP_PKG.clones"
    private const val MLP_LAUNCH = "$MLP_PKG.ShortcutLaunchActivity"
    private val cloneTitles = HashMap<String, String>()

    fun isClone(id: String): Boolean = id.startsWith("mlp:")
    private fun cloneUser(id: String): Int? = id.removePrefix("mlp:").substringBefore(':').toIntOrNull()
    private fun clonePkg(id: String): String = id.removePrefix("mlp:").substringAfter(':')

    /** 멀티로그인 개인용의 복제본들 — 이름 끝에 🔗 (앱이 없으면 빈 목록) */
    fun cloneApps(ctx: Context): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        runCatching {
            ctx.contentResolver.query(android.net.Uri.parse("content://$MLP_AUTH"), null, null, null, null)?.use { c ->
                val iPkg = c.getColumnIndex("pkg"); val iUser = c.getColumnIndex("user"); val iTitle = c.getColumnIndex("title")
                while (c.moveToNext()) {
                    val id = "mlp:${c.getInt(iUser)}:${c.getString(iPkg)}"
                    val title = c.getString(iTitle)
                    synchronized(cloneTitles) { cloneTitles[id] = title }
                    out += AppEntry("$title 🔗", id)
                }
            }
        }
        return out
    }

    private fun cloneLabel(ctx: Context, id: String): String? {
        synchronized(cloneTitles) { cloneTitles[id] }?.let { return "$it 🔗" }
        cloneApps(ctx)
        synchronized(cloneTitles) { cloneTitles[id] }?.let { return "$it 🔗" }
        return labelOf(ctx, clonePkg(id))?.let { "$it 복제 🔗" }
    }

    /** 복제본 번호: "피클 -2" 의 2. 모르면 가상사용자번호+1 */
    private fun cloneIndex(ctx: Context, id: String): String {
        val title = synchronized(cloneTitles) { cloneTitles[id] } ?: run { cloneApps(ctx); synchronized(cloneTitles) { cloneTitles[id] } }
        return title?.let { Regex("-(\\d+)$").find(it.trim())?.groupValues?.get(1) } ?: ((cloneUser(id) ?: 0) + 1).toString()
    }

    /** 원래 앱 아이콘 오른쪽 아래에 보라색 번호 배지를 붙인다 (원본 앱 스티커와 구분) */
    private fun withCloneBadge(ctx: Context, base: Drawable, number: String): Drawable {
        val size = 192
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        base.setBounds(0, 0, size, size)
        base.draw(c)
        val r = size * 0.23f
        val cx = size - r - 2f
        val cy = size - r - 2f
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.WHITE
        c.drawCircle(cx, cy, r + 6f, p)
        p.shader = android.graphics.LinearGradient(cx - r, cy - r, cx + r, cy + r,
            0xFFA594FA.toInt(), 0xFF6150E6.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = android.graphics.Color.WHITE
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = if (number.length >= 2) r * 1.05f else r * 1.35f
        c.drawText(number, cx, cy - (p.descent() + p.ascent()) / 2f, p)
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }

    private fun launchClone(ctx: Context, id: String): Boolean {
        val user = cloneUser(id) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).setClassName(MLP_PKG, MLP_LAUNCH)
            .putExtra("pkg", clonePkg(id)).putExtra("user", user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent) }.isSuccess
    }

    fun pkgOf(id: String): String = id.substringBefore('@')

    private fun userOf(ctx: Context, id: String): UserHandle? {
        if (!isOtherProfile(id)) return Process.myUserHandle()
        val serial = id.substringAfter('@').toLongOrNull() ?: return null
        return runCatching { ctx.getSystemService(UserManager::class.java).getUserForSerialNumber(serial) }.getOrNull()
    }

    /** 내 프로필을 뺀 다른 프로필(업무 프로필 등) 목록 */
    private fun otherProfiles(ctx: Context): List<UserHandle> = runCatching {
        val me = Process.myUserHandle()
        ctx.getSystemService(LauncherApps::class.java).profiles.filter { it != me }
    }.getOrDefault(emptyList())

    /** 다른 프로필의 실행 가능한 앱들 — 이름 끝에 💼 */
    private fun otherProfileApps(ctx: Context): List<AppEntry> {
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val um = ctx.getSystemService(UserManager::class.java)
        val out = ArrayList<AppEntry>()
        for (u in otherProfiles(ctx)) {
            val serial = runCatching { um.getSerialNumberForUser(u) }.getOrNull() ?: continue
            val list = runCatching { la.getActivityList(null, u) }.getOrNull() ?: continue
            for (a in list) {
                val pkg = a.componentName.packageName
                out += AppEntry("${a.label} 💼", "$pkg@$serial")
            }
        }
        return out.distinctBy { it.packageName }.sortedBy { it.label }
    }

    /** 앱 아이콘 (다른 프로필 앱은 💼 표시가 붙은 아이콘). 없으면 null */
    fun iconOf(ctx: Context, id: String): Drawable? {
        if (isClone(id)) {
            val base = runCatching { ctx.packageManager.getApplicationIcon(clonePkg(id)) }.getOrNull() ?: return null
            return withCloneBadge(ctx, base, cloneIndex(ctx, id))
        }
        if (!isOtherProfile(id)) return runCatching { ctx.packageManager.getApplicationIcon(id) }.getOrNull()
        val user = userOf(ctx, id) ?: return null
        val a = runCatching {
            ctx.getSystemService(LauncherApps::class.java).getActivityList(pkgOf(id), user).firstOrNull()
        }.getOrNull() ?: return null
        return runCatching { a.getBadgedIcon(0) }.getOrNull()
    }

    /** 앱 이름 (다른 프로필 앱은 끝에 💼). 없으면 null */
    fun labelOfId(ctx: Context, id: String): String? {
        if (isClone(id)) return cloneLabel(ctx, id)
        if (!isOtherProfile(id)) return labelOf(ctx, id)
        val user = userOf(ctx, id) ?: return null
        val a = runCatching {
            ctx.getSystemService(LauncherApps::class.java).getActivityList(pkgOf(id), user).firstOrNull()
        }.getOrNull() ?: return null
        return "${a.label} 💼"
    }

    /** 앱이 아직 있는지 (다른 프로필이 잠깐 꺼져 있으면 있는 것으로 본다 — 스티커를 지우지 않게) */
    fun exists(ctx: Context, id: String): Boolean {
        // 복제본: 멀티로그인 개인용과 원본 앱이 설치돼 있으면 있는 것으로 본다
        if (isClone(id)) return labelOf(ctx, MLP_PKG) != null && labelOf(ctx, clonePkg(id)) != null
        if (!isOtherProfile(id)) return labelOf(ctx, id) != null
        val user = userOf(ctx, id) ?: return false
        val um = ctx.getSystemService(UserManager::class.java)
        if (runCatching { um.isQuietModeEnabled(user) }.getOrDefault(false)) return true
        return labelOfId(ctx, id) != null
    }

    /** 앱 실행. 다른 프로필 앱은 LauncherApps 로 그 프로필에서 연다. 성공하면 true */
    fun launch(ctx: Context, id: String): Boolean {
        if (isClone(id)) return launchClone(ctx, id)
        if (!isOtherProfile(id)) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(id) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { ctx.startActivity(intent) }.isSuccess
        }
        val user = userOf(ctx, id) ?: return false
        val la = ctx.getSystemService(LauncherApps::class.java)
        val um = ctx.getSystemService(UserManager::class.java)
        // 업무 프로필이 일시 중지돼 있으면 켜 달라고 요청(시스템이 확인 창을 띄움)
        if (android.os.Build.VERSION.SDK_INT >= 28 && runCatching { um.isQuietModeEnabled(user) }.getOrDefault(false)) {
            runCatching { um.requestQuietModeEnabled(false, user) }
        }
        val a = runCatching { la.getActivityList(pkgOf(id), user).firstOrNull() }.getOrNull() ?: return false
        return runCatching { la.startMainActivity(a.componentName, user, null, null) }.isSuccess
    }

    /** 스티커로 고를 수 있는 앱: 내 프로필 앱 + 다른 프로필(업무 등) 앱 + 멀티로그인 개인용 복제본 */
    fun launchableAppsAllProfiles(context: Context): List<AppEntry> =
        launchableApps(context) + otherProfileApps(context) + cloneApps(context)

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

    /** 카카오퀵(기사용) 앱으로 보이는 앱을 찾는다. 못 찾으면 null */
    fun detectKakaoQuick(context: Context): AppEntry? =
        launchableApps(context).filter { kakaoScore(it) > 0 }.maxByOrNull { kakaoScore(it) }

    fun kakaoScore(e: AppEntry): Int {
        val label = e.label.replace(" ", "").lowercase()
        val pkg = e.packageName.lowercase()
        return when {
            label.contains("카카오") && label.contains("픽커") -> 5 // 카카오 T 픽커
            label.contains("카카오") && label.contains("퀵") -> 4
            pkg.contains("kakao") && (pkg.contains("quick") || pkg.contains("picker")) -> 3
            label.contains("퀵") && (label.contains("기사") || label.contains("픽커")) -> 2
            pkg.contains("kakao") && pkg.contains("driver") -> 1
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
