package com.sticky.todo

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SyncResult(
    val ok: Boolean,
    val message: String,
    /** 이 기기의 목록이 실제로 달라졌는지 (화면·위젯을 다시 그릴지 판단) */
    val changed: Boolean = false
)

/**
 * 구글 드라이브의 '앱 전용 숨김 공간'에 목록 하나를 두고 주고받는다.
 *
 * 이 공간은 드라이브 화면에 보이지 않고 이 앱만 접근할 수 있다.
 * 무거운 구글 API 라이브러리 대신 REST 를 직접 부른다. 로그인만 구글 라이브러리를 쓴다.
 */
object DriveSync {

    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    private const val FILE_NAME = "sticky-todo.json"
    private const val PREF = "sticky_todo"

    private const val K_LAST_AT = "sync_last_at"
    private const val K_LAST_MSG = "sync_last_msg"
    private const val K_LAST_OK = "sync_last_ok"

    // ── 로그인 ────────────────────────────────────────────

    private fun options(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE))
            .build()

    fun client(ctx: Context): GoogleSignInClient = GoogleSignIn.getClient(ctx, options())

    fun account(ctx: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(ctx)

    fun connected(ctx: Context): Boolean = account(ctx)?.account != null

    fun email(ctx: Context): String? = account(ctx)?.email

    // ── 상태 기록 ─────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun lastSyncAt(ctx: Context): Long = prefs(ctx).getLong(K_LAST_AT, 0L)
    fun lastMessage(ctx: Context): String = prefs(ctx).getString(K_LAST_MSG, "") ?: ""
    fun lastOk(ctx: Context): Boolean = prefs(ctx).getBoolean(K_LAST_OK, false)

    private fun record(ctx: Context, r: SyncResult): SyncResult {
        prefs(ctx).edit()
            .putLong(K_LAST_AT, System.currentTimeMillis())
            .putString(K_LAST_MSG, r.message)
            .putBoolean(K_LAST_OK, r.ok)
            .apply()
        return r
    }

    // ── 본체 ──────────────────────────────────────────────

    /** 반드시 백그라운드 스레드에서 부를 것. 통신이 끝날 때까지 기다린다. */
    fun syncNow(ctx: Context): SyncResult {
        val app = ctx.applicationContext

        val acct = account(app)
        val androidAccount = acct?.account
            ?: return record(app, SyncResult(false, "구글 계정이 연결되지 않았습니다"))

        var token = try {
            GoogleAuthUtil.getToken(app, androidAccount, "oauth2:$SCOPE")
        } catch (e: UserRecoverableAuthException) {
            return record(app, SyncResult(false, "구글 권한 승인이 필요합니다. 설정에서 다시 연결해 주세요"))
        } catch (e: Exception) {
            return record(app, SyncResult(false, "인증 실패: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()))
        }

        try {
            var found = findFile(token)
            // 토큰이 만료돼 거절당하면 한 번만 새로 받아 다시 시도한다
            if (found.code == 401) {
                runCatching { GoogleAuthUtil.clearToken(app, token) }
                token = GoogleAuthUtil.getToken(app, androidAccount, "oauth2:$SCOPE")
                found = findFile(token)
            }
            if (found.code !in 200..299) {
                return record(app, SyncResult(false, "드라이브 조회 실패 (${found.code}) ${brief(found.body)}"))
            }

            val fileId = firstFileId(found.body)

            val remote: SyncData? = if (fileId == null) null else {
                val got = httpGet("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token)
                if (got.code !in 200..299) {
                    return record(app, SyncResult(false, "내려받기 실패 (${got.code}) ${brief(got.body)}"))
                }
                SyncData.parse(got.body)
            }

            val local = TodoRepo.snapshot(app)
            val merged = if (remote == null) local else Merge.merge(local, remote)

            val changedHere = TodoRepo.applyMerged(app, merged)
            val needUpload = remote == null || merged.signature() != remote.signature()

            if (needUpload) {
                val body = merged.toJson().toString()
                val up = if (fileId == null) createFile(token, body) else updateFile(token, fileId, body)
                if (up.code !in 200..299) {
                    return record(app, SyncResult(false, "올리기 실패 (${up.code}) ${brief(up.body)}", changedHere))
                }
            }

            val what = when {
                changedHere && needUpload -> "주고받음"
                changedHere -> "받아옴"
                needUpload -> "올림"
                else -> "변경 없음"
            }
            return record(app, SyncResult(true, "동기화 완료 · $what", changedHere))
        } catch (e: Exception) {
            return record(app, SyncResult(false, "통신 오류: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()))
        }
    }

    // ── 드라이브 REST ─────────────────────────────────────

    private data class Res(val code: Int, val body: String)

    private fun findFile(token: String): Res {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        return httpGet(
            "https://www.googleapis.com/drive/v3/files" +
                "?spaces=appDataFolder&pageSize=10&fields=files(id,modifiedTime)&q=$q",
            token
        )
    }

    private fun firstFileId(body: String): String? = try {
        val files = JSONObject(body).optJSONArray("files")
        if (files == null || files.length() == 0) null
        else files.optJSONObject(0)?.optString("id", "")?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun createFile(token: String, content: String): Res {
        val boundary = "sticky" + System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .toString()
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(meta).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }
        return http(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
            "POST", token, "multipart/related; boundary=$boundary", body
        )
    }

    private fun updateFile(token: String, fileId: String, content: String): Res = http(
        "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media",
        "PATCH", token, "application/json; charset=UTF-8", content
    )

    private fun httpGet(url: String, token: String): Res = http(url, "GET", token, null, null)

    private fun http(
        url: String,
        method: String,
        token: String,
        contentType: String?,
        body: String?
    ): Res {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $token")
                // PATCH 를 직접 지원하지 않는 기기가 있어 우회한다
                if (method == "PATCH") {
                    requestMethod = "POST"
                    setRequestProperty("X-HTTP-Method-Override", "PATCH")
                } else {
                    requestMethod = method
                }
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null) doOutput = true
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                ByteArrayOutputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                }
            } ?: ""
            return Res(code, text)
        } finally {
            conn?.disconnect()
        }
    }

    private fun brief(body: String): String {
        val t = body.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 120) t.substring(0, 120) + "…" else t
    }
}
