package com.sticky.todo

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 새 버전 확인.
 *
 * - 하루 두 번쯤, 뒤에서 도는 동기화 작업이 깨어날 때 함께 확인한다 (1 KB 남짓).
 *   GitHub 의 'latest' 릴리스 주소가 어느 빌드(build-N)로 넘겨 주는지만 본다.
 * - 이 앱의 versionCode 가 곧 빌드 번호이므로 숫자만 비교하면 된다.
 * - 설치는 앱이 하지 않는다. 업데이트를 누르면 브라우저가 APK 를 받고, 사용자가 받은 파일을 열어 설치한다.
 *   (앱이 APK 를 받아 직접 설치 화면에 넘기면 플레이 프로텍트가 유해한 앱으로 분류한다)
 * - 표시를 숨기면 그 빌드에 대해서만 숨긴다. 더 새 빌드가 나오면 다시 보인다.
 */
object Updater {

    private const val REPO = "https://github.com/Seokhui-Jeong/sticky-todo"
    private const val PREF = "sticky_todo"
    private const val K_LATEST = "upd_latest"        // GitHub 에서 본 가장 최근 빌드 번호
    private const val K_DISMISSED = "upd_dismissed"  // 표시를 숨긴 빌드 번호
    private const val K_CHECKED = "upd_checked_at"

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
        TodoWidget.refresh(ctx)
    }

    /** 최신 빌드의 APK 를 바로 받는 주소 (브라우저로 연다) */
    fun downloadUrl(ctx: Context): String =
        "$REPO/releases/download/build-${latestBuild(ctx)}/todo-widget.apk"

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

    /** 앞선 빌드가 미리 받아 두었던 APK 가 남아 있으면 지운다 */
    private fun cleanup(ctx: Context) {
        val dir = File(ctx.cacheDir, "updates")
        if (dir.exists()) dir.deleteRecursively()
        p(ctx).edit().remove("upd_ready").apply()
    }
}
