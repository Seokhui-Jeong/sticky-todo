package com.sticky.todo

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 동기화 설정 화면.
 * 구글 계정을 연결하면 할 일 목록이 드라이브의 앱 전용 숨김 공간에 보관되고,
 * 같은 계정으로 연결한 다른 기기와 자동으로 맞춰진다.
 */
class SyncActivity : BaseActivity() {

    private lateinit var accountView: TextView
    private lateinit var statusView: TextView
    private lateinit var connectBtn: TextView
    private lateinit var disconnectBtn: TextView
    private lateinit var nowBtn: TextView

    private var busy = false

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            // 성공 여부와 무관하게 예외를 던질 수 있어 감싼다
            GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(Exception::class.java)
            DriveSync.forgetSyncState(this)
            paint()
            runSync()
        } catch (e: Exception) {
            paint()
            toast(getString(R.string.sync_connect_failed, e.message ?: getString(R.string.sync_cancelled)))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        accountView = findViewById(R.id.account)
        statusView = findViewById(R.id.status)
        connectBtn = findViewById(R.id.btn_connect)
        disconnectBtn = findViewById(R.id.btn_disconnect)
        nowBtn = findViewById(R.id.btn_now)

        connectBtn.setOnClickListener { signIn.launch(DriveSync.client(this).signInIntent) }
        disconnectBtn.setOnClickListener { confirmDisconnect() }
        nowBtn.setOnClickListener { runSync() }

        paint()
    }

    override fun onResume() {
        super.onResume()
        paint()
    }

    private fun paint() {
        val on = DriveSync.connected(this)
        accountView.text = if (on) (DriveSync.email(this) ?: getString(R.string.sync_connected))
        else getString(R.string.sync_not_connected)
        connectBtn.visibility = if (on) View.GONE else View.VISIBLE
        disconnectBtn.visibility = if (on) View.VISIBLE else View.GONE
        nowBtn.visibility = if (on) View.VISIBLE else View.GONE

        val at = DriveSync.lastSyncAt(this)
        statusView.text = when {
            busy -> getString(R.string.sync_busy)
            at == 0L -> getString(R.string.sync_never)
            else -> {
                val f = SimpleDateFormat(
                    getString(R.string.sync_time_format),
                    if (Lang.english) Locale.ENGLISH else Locale.KOREAN
                ).format(Date(at))
                "$f · ${DriveSync.lastMessage(this)}"
            }
        }
        statusView.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (at != 0L && !DriveSync.lastOk(this)) R.color.overdue else R.color.text
            )
        )
    }

    private fun runSync() {
        if (busy) return
        if (!DriveSync.connected(this)) {
            toast(getString(R.string.sync_connect_first))
            return
        }
        busy = true
        paint()
        Thread {
            val r = DriveSync.syncNow(this)
            runOnUiThread {
                busy = false
                if (r.changed) TodoWidget.refresh(this)
                paint()
                if (!r.ok) toast(r.message)
            }
        }.start()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setMessage(R.string.sync_disconnect_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.sync_disconnect) { _, _ ->
                DriveSync.client(this).signOut().addOnCompleteListener {
                    SyncScheduler.cancelAll(this)
                    DriveSync.forgetSyncState(this)
                    paint()
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
