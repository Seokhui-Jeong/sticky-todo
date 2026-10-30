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

    // 드라이브 파일을 마지막으로 맞췄을 때의 상태.
    // 다음 동기화 때 드라이브 파일의 내용 지문(md5)이 이것과 같으면 내려받지 않는다.
    private const val K_FILE_ID = "sync_file_id"
    private const val K_HASH = "sync_hash"
    private const val K_SIG = "sync_sig"

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

    /** 계정을 새로 연결하거나 끊을 때 부른다. 다음 동기화는 처음부터 전부 받는다. */
    fun forgetSyncState(ctx: Context) {
        prefs(ctx).edit().remove(K_FILE_ID).remove(K_HASH).remove(K_SIG).apply()
    }

    private fun saveSyncState(ctx: Context, fileId: String, hash: String?, sig: String) {
        if (hash.isNullOrEmpty()) {
            forgetSyncState(ctx)   // 지문을 모르면 다음번엔 한 번 내려받는다
            return
        }
        prefs(ctx).edit()
            .putString(K_FILE_ID, fileId)
            .putString(K_HASH, hash)
            .putString(K_SIG, sig)
            .apply()
    }

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
        val res = Lang.wrap(app)   // 기록해 둘 메시지를 고른 언어로

        val acct = account(app)
        val androidAccount = acct?.account
            ?: return record(app, SyncResult(false, res.getString(R.string.sync_err_no_account)))

        var token = try {
            GoogleAuthUtil.getToken(app, androidAccount, "oauth2:$SCOPE")
        } catch (e: UserRecoverableAuthException) {
            return record(app, SyncResult(false, res.getString(R.string.sync_err_consent)))
        } catch (e: Exception) {
            return record(app, SyncResult(false, res.getString(R.string.sync_err_auth, errText(e))))
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
                return record(app, SyncResult(false, res.getString(R.string.sync_err_lookup, found.code, brief(found.body)).trim()))
            }

            val remoteFile = firstFile(found.body)
            val local = TodoRepo.snapshot(app)
            val localSig = local.signature()

            // ── 빠른 길: 드라이브 파일 내용이 지난번 그대로라면 내려받지 않는다 ──
            // 지난번에 맞춘 뒤로 다른 기기가 바꾼 게 없다는 뜻이고, 그 내용은
            // 이미 이 기기 목록에 다 들어 있다. 목록 조회 한 번(수백 바이트)으로 끝난다.
            val p = prefs(app)
            val knownId = p.getString(K_FILE_ID, null)
            val knownHash = p.getString(K_HASH, null)
            val knownSig = p.getString(K_SIG, null)
            if (remoteFile != null && remoteFile.id == knownId && knownSig != null &&
                !remoteFile.hash.isNullOrEmpty() && remoteFile.hash == knownHash
            ) {
                if (localSig == knownSig) {
                    return record(app, SyncResult(true, res.getString(R.string.sync_done, res.getString(R.string.sync_what_none))))
                }
                // 이 기기만 바뀌었다 → 올리기만
                val up = updateFile(token, remoteFile.id, local.toBytes())
                if (up.code !in 200..299) {
                    return record(app, SyncResult(false, res.getString(R.string.sync_err_upload, up.code, brief(up.body)).trim()))
                }
                saveSyncState(app, remoteFile.id, hashOf(up.body), localSig)
                return record(app, SyncResult(true, res.getString(R.string.sync_done, res.getString(R.string.sync_what_up))))
            }

            // ── 보통 길: 내려받아 합친다 ──
            val fileId = remoteFile?.id
            val remote: SyncData? = if (fileId == null) null else {
                val got = httpGet("https://www.googleapis.com/drive/v3/files/$fileId?alt=media", token)
                if (got.code !in 200..299) {
                    return record(app, SyncResult(false, res.getString(R.string.sync_err_download, got.code, brief(got.body)).trim()))
                }
                SyncData.parseBytes(got.bytes)   // 압축 파일과 예전 그냥 JSON 둘 다 읽는다
            }

            val merged = if (remote == null) local else Merge.merge(local, remote)

            val changedHere = TodoRepo.applyMerged(app, merged)
            val needUpload = remote == null || merged.signature() != remote.signature()

            var newId = fileId
            var newHash = remoteFile?.hash
            if (needUpload) {
                val body = merged.toBytes()
                val up = if (fileId == null) createFile(token, body) else updateFile(token, fileId, body)
                if (up.code !in 200..299) {
                    forgetSyncState(app)
                    return record(app, SyncResult(false, res.getString(R.string.sync_err_upload, up.code, brief(up.body)).trim(), changedHere))
                }
                newId = idOf(up.body) ?: fileId
                newHash = hashOf(up.body)
            }
            if (newId != null) saveSyncState(app, newId, newHash, merged.signature())

            val what = when {
                changedHere && needUpload -> R.string.sync_what_both
                changedHere -> R.string.sync_what_down
                needUpload -> R.string.sync_what_up
                else -> R.string.sync_what_none
            }
            return record(app, SyncResult(true, res.getString(R.string.sync_done, res.getString(what)), changedHere))
        } catch (e: Exception) {
            return record(app, SyncResult(false, res.getString(R.string.sync_err_network, errText(e))))
        }
    }

    // ── 드라이브 REST ─────────────────────────────────────

    /** bytes: 받은 그대로. body: 글자로 읽은 것 (오류 메시지·목록 응답용) */
    private class Res(val code: Int, val bytes: ByteArray) {
        val body: String get() = String(bytes, Charsets.UTF_8)
    }

    private fun findFile(token: String): Res {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        return httpGet(
            "https://www.googleapis.com/drive/v3/files" +
                "?spaces=appDataFolder&pageSize=10&fields=files(id,md5Checksum)&q=$q",
            token
        )
    }

    /** hash: 드라이브가 계산해 주는 파일 내용의 md5. 내용이 같으면 같다. */
    private data class RemoteFile(val id: String, val hash: String?)

    private fun firstFile(body: String): RemoteFile? = try {
        val files = JSONObject(body).optJSONArray("files")
        val f = if (files == null || files.length() == 0) null else files.optJSONObject(0)
        val id = f?.optString("id", "")
        if (f == null || id.isNullOrEmpty()) null
        else RemoteFile(id, f.optString("md5Checksum", "").ifEmpty { null })
    } catch (e: Exception) {
        null
    }

    /** 올린 뒤 응답에서 새 내용 지문. 모르면 null (다음번에 한 번 내려받는다) */
    private fun hashOf(body: String): String? = try {
        JSONObject(body).optString("md5Checksum", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun idOf(body: String): String? = try {
        JSONObject(body).optString("id", "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun createFile(token: String, content: ByteArray): Res {
        val boundary = "sticky" + System.currentTimeMillis()
        val meta = JSONObject()
            .put("name", FILE_NAME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
            .toString()
        val out = ByteArrayOutputStream()
        fun w(t: String) = out.write(t.toByteArray(Charsets.UTF_8))
        w("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n")
        w("--$boundary\r\nContent-Type: application/gzip\r\n\r\n")
        out.write(content)
        w("\r\n--$boundary--\r\n")
        return http(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,md5Checksum",
            "POST", token, "multipart/related; boundary=$boundary", out.toByteArray()
        )
    }

    private fun updateFile(token: String, fileId: String, content: ByteArray): Res = http(
        "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media&fields=id,md5Checksum",
        "PATCH", token, "application/gzip", content
    )

    private fun httpGet(url: String, token: String): Res = http(url, "GET", token, null, null)

    private fun http(
        url: String,
        method: String,
        token: String,
        contentType: String?,
        body: ByteArray?
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
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Res(code, bytes)
        } finally {
            conn?.disconnect()
        }
    }

    private fun errText(e: Exception) = "${e.javaClass.simpleName} ${e.message ?: ""}".trim()

    private fun brief(body: String): String {
        val t = body.replace(Regex("\\s+"), " ").trim()
        return if (t.length > 120) t.substring(0, 120) + "…" else t
    }
}
