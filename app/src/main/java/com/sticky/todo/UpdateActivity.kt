package com.sticky.todo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/**
 * 업데이트 표시를 눌렀을 때 뜨는 작은 창 (뒤는 투명).
 *   [업데이트]    받아 둔 APK 로 설치 화면을 띄운다. 처음 한 번은 '알 수 없는 앱 설치' 허용을 안내한다.
 *   [표시 숨기기] 이번 빌드에 대해서는 위젯·앱의 표시를 없앤다 (설정 › 앱 정보에서는 계속 업데이트 가능)
 *   [나중에]      그냥 닫는다
 */
class UpdateActivity : BaseActivity() {

    companion object {
        fun open(ctx: Context) = Intent(ctx, UpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private var waitingForPermission = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.InvisibleTheme)
        super.onCreate(savedInstanceState)
        if (!Updater.available(this)) {
            toast(getString(R.string.upd_latest))
            finish()
            return
        }
        ask()
    }

    override fun onResume() {
        super.onResume()
        // '알 수 없는 앱 설치' 허용 화면에서 돌아왔을 때 이어서 진행한다
        if (waitingForPermission) {
            waitingForPermission = false
            if (packageManager.canRequestPackageInstalls()) install() else finish()
        }
    }

    private fun ask() {
        AlertDialog.Builder(this)
            .setTitle(R.string.upd_title)
            .setMessage(getString(R.string.upd_msg, Updater.latestBuild(this), Updater.currentBuild(this)))
            .setPositiveButton(R.string.upd_install) { _, _ -> install() }
            .setNeutralButton(R.string.upd_hide) { _, _ ->
                Updater.dismiss(this)
                toast(getString(R.string.upd_hidden))
                finish()
            }
            .setNegativeButton(R.string.upd_later) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun install() {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.upd_allow_title)
                .setMessage(R.string.upd_allow_msg)
                .setPositiveButton(R.string.upd_allow_go) { _, _ ->
                    waitingForPermission = true
                    startActivity(
                        Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }

        val ready = Updater.readyFile(this)
        if (ready != null) {
            launchInstaller(ready)
            return
        }

        // 미리 받아 두지 못했으면 지금 받는다 (1 MB 미만이라 금방 끝난다)
        if (busy) return
        busy = true
        val wait = AlertDialog.Builder(this)
            .setMessage(R.string.upd_downloading)
            .setCancelable(false)
            .show()
        Thread {
            val f = Updater.download(this)
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                wait.dismiss()
                if (f != null) launchInstaller(f) else {
                    toast(getString(R.string.upd_failed))
                    finish()
                }
            }
        }.start()
    }

    private fun launchInstaller(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(getString(R.string.upd_failed))
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
