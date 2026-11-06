package com.sticky.todo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 새 버전 확인과 미리 받아 두기.
 *
 * - 하루 두 번쯤, 뒤에서 도는 동기화 작업이 깨어날 때 함께 확인한다 (1 KB 남짓).
 *   GitHub 의 'latest' 릴리스 주소가 어느 빌드(build-N)로 넘겨 주는지만 본다.
 * - 이 앱의 versionCode 가 곧 빌드 번호이므로 숫자만 비교하면 된다.
 * - 새 버전이 있고 와이파이처럼 요금이 안 드는 망이면 APK(1 MB 미만)를 미리 받아 둔다.
 *   그래야 업데이트를 눌렀을 때 기다림 없이 바로 설치 화면이 뜬다.
 * - 표시를 숨기면 그 빌드에 대해서만 숨긴다. 더 새 빌드가 나오면 다시 보인다.
 */
object Updater {

    private const val REPO = "https://github.com/Seokhui-Jeong/sticky-todo"
    private const val PREF = "sticky_todo"
    private const val K_LATEST = "upd_latest"        // GitHub 에서 본 가장 최근 빌드 번호
    private const val K_DISMISSED = "upd_dismissed"  // 표시를 숨긴 빌드 번호
    private const val K_CHECKED = "upd_checked_at"
    private const val K_READY = "upd_ready"          // 미리 받아 둔 APK 의 빌드 번호

    private const val CHECK_EVERY = 12 * 60 * 60 * 1000L

    private fun p(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 지금 설치된 빌드 번호 (= versionCode) */
    fun currentBuild(ctx: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        ).toInt()
    } catch (e: Exception) {
        0
    }

    fun latestBuild(ctx: Context): Int = p(ctx).getInt(K_LATEST, 0)

    /** 설치할 수 있는 새 빌드가 있는가 (숨겼어도 true) */
    fun available(ctx: Context): Boolean = latestBuild(ctx) > currentBuild(ctx)

    /** 위젯·앱 머리글에 작은 표시를 띄울지 */
    fun showBadge(ctx: Context): Boolean =
        available(ctx) && p(ctx).getInt(K_DISMISSED, 0) < latestBuild(ctx)

    /** 이번 빌드에 대해서는 표시를 숨긴다 */
    fun dismiss(ctx: Context) {
        p(ctx).edit().putInt(K_DISMISSED, latestBuild(ctx)).apply()
        apkFile(ctx).delete()
        p(ctx).edit().remove(K_READY).apply()
        TodoWidget.refresh(ctx)
    }

    // ── 확인 ──────────────────────────────────────────────

    /** 뒤에서 도는 작업에서 부른다. 마지막 확인 뒤 12시간이 지났을 때만 통신한다. */
    fun checkIfDue(ctx: Context) {
        cleanup(ctx)
        if (System.currentTimeMillis() - p(ctx).getLong(K_CHECKED, 0L) < CHECK_EVERY) return
        check(ctx)
    }

    /**
     * 지금 바로 확인한다. 반드시 백그라운드 스레드에서 부를 것.
     * 최신 빌드 번호를 돌려주고, 통신에 실패하면 null.
     */
    fun check(ctx: Context): Int? {
        val latest = fetchLatestBuild() ?: return null
        val before = latestBuild(ctx)
        p(ctx).edit()
            .putInt(K_LATEST, latest)
            .putLong(K_CHECKED, System.currentTimeMillis())
            .apply()
        if (latest != before) TodoWidget.refresh(ctx)

        // 숨기지 않은 새 버전이면, 요금 안 드는 망에서 미리 받아 둔다
        if (showBadge(ctx) && readyFile(ctx) == null && unmetered(ctx)) {
            download(ctx)
        }
        return latest
    }

    /** github.com/…/releases/latest 가 넘겨 주는 주소 끝의 build-N 에서 N 을 읽는다 */
    private fun fetchLatestBuild(): Int? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL("$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 15000
                readTimeout = 15000
            }
            c.responseCode
            val loc = c.getHeaderField("Location") ?: return null
            Regex("/tag/build-(\\d+)").find(loc)?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (e: Exception) {
        false
    }

    // ── 내려받기 ──────────────────────────────────────────

    fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "updates"), "todo-widget.apk")

    /** 최신 빌드의 APK 를 이미 받아 두었으면 그 파일 */
    fun readyFile(ctx: Context): File? {
        val f = apkFile(ctx)
        val build = latestBuild(ctx)
        return if (build > 0 && p(ctx).getInt(K_READY, 0) == build && f.isFile) f else null
    }

    /**
     * 최신 빌드의 APK 를 받아 확인까지 마친다. 반드시 백그라운드 스레드에서 부를 것.
     * 받은 파일이 정말 이 앱의 그 빌드인지 확인하고, 아니면 버린다.
     * (서명은 설치할 때 안드로이드가 다시 확인한다. 다른 키로 서명된 파일은 설치되지 않는다)
     */
    @Synchronized
    fun download(ctx: Context): File? {
        readyFile(ctx)?.let { return it }
        val build = latestBuild(ctx)
        if (build <= currentBuild(ctx)) return null

        val dest = apkFile(ctx)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "download.tmp")
        var c: HttpURLConnection? = null
        try {
            c = (URL("$REPO/releases/download/build-$build/todo-widget.apk")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
            }
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }

            val info = ctx.packageManager.getPackageArchiveInfo(tmp.path, 0)
            if (info == null || info.packageName != ctx.packageName ||
                PackageInfoCompat.getLongVersionCode(info).toInt() != build
            ) {
                tmp.delete()
                return null
            }
            dest.delete()
            if (!tmp.renameTo(dest)) return null
            p(ctx).edit().putInt(K_READY, build).apply()
            return dest
        } catch (e: Exception) {
            tmp.delete()
            return null
        } finally {
            c?.disconnect()
        }
    }

    /** 업데이트가 끝났으면 받아 둔 파일을 지운다 */
    private fun cleanup(ctx: Context) {
        if (p(ctx).getInt(K_READY, 0) in 1..currentBuild(ctx)) {
            apkFile(ctx).delete()
            p(ctx).edit().remove(K_READY).apply()
        }
    }
}
