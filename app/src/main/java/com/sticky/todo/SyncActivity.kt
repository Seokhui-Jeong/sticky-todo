package com.sticky.todo

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 동기화 설정 화면.
 * 구글 계정을 연결하면 할 일 목록이 드라이브의 앱 전용 숨김 공간에 보관되고,
 * 같은 계정으로 연결한 다른 기기와 자동으로 맞춰진다.
 */
class SyncActivity : AppCompatActivity() {

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
            paint()
            runSync()
        } catch (e: Exception) {
            paint()
            toast("연결하지 못했습니다: ${e.message ?: "취소됨"}")
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
        accountView.text = if (on) (DriveSync.email(this) ?: "연결됨")
        else getString(R.string.sync_not_connected)
        connectBtn.visibility = if (on) View.GONE else View.VISIBLE
        disconnectBtn.visibility = if (on) View.VISIBLE else View.GONE
        nowBtn.visibility = if (on) View.VISIBLE else View.GONE

        val at = DriveSync.lastSyncAt(this)
        statusView.text = when {
            busy -> "동기화 중…"
            at == 0L -> getString(R.string.sync_never)
            else -> {
                val f = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(at))
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
            toast("먼저 구글 계정을 연결해 주세요")
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
            .setMessage("구글 계정 연결을 끊을까요?\n이 기기의 할 일은 그대로 남습니다.")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("연결 끊기") { _, _ ->
                DriveSync.client(this).signOut().addOnCompleteListener {
                    SyncScheduler.cancelAll(this)
                    paint()
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
