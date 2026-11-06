package com.sticky.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * 업데이트 표시를 눌렀을 때 뜨는 작은 창 (뒤는 투명).
 *   [업데이트]    브라우저로 새 APK 를 받는다. 받은 파일을 열면 설치된다 (목록은 그대로).
 *   [표시 숨기기] 이번 빌드에 대해서는 위젯·앱의 표시를 없앤다 (설정 › 앱 정보에서는 계속 업데이트 가능)
 *   [나중에]      그냥 닫는다
 */
class UpdateActivity : BaseActivity() {

    companion object {
        fun open(ctx: Context) = Intent(ctx, UpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.InvisibleTheme)
        super.onCreate(savedInstanceState)
        if (!Updater.available(this)) {
            toast(getString(R.string.upd_latest))
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.upd_title)
            .setMessage(getString(R.string.upd_msg, Updater.latestBuild(this), Updater.currentBuild(this)))
            .setPositiveButton(R.string.upd_install) { _, _ -> openDownload() }
            .setNeutralButton(R.string.upd_hide) { _, _ ->
                Updater.dismiss(this)
                toast(getString(R.string.upd_hidden))
                finish()
            }
            .setNegativeButton(R.string.upd_later) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openDownload() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Updater.downloadUrl(this)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            toast(getString(R.string.upd_open_file))
        } catch (e: Exception) {
            toast(getString(R.string.upd_failed))
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
