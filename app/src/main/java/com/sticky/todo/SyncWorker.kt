package com.sticky.todo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 뒤에서 조용히 도는 동기화.
 * 앱을 열지 않아도, 위젯을 건드리지 않아도 주기적으로 한 번씩 깨어난다.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val app = applicationContext
        if (!DriveSync.connected(app)) return Result.success()

        val r = try {
            DriveSync.syncNow(app)
        } catch (e: Exception) {
            return Result.success()   // 실패해도 계속 재시도하며 배터리를 먹지 않게 한다
        }

        // 다른 기기에서 바뀐 게 들어왔으면 위젯을 다시 그린다
        if (r.changed) {
            try {
                TodoRepo.runAutoArchive(app)
                TodoWidget.refresh(app)
            } catch (e: Exception) {
                // 그리기 실패는 조용히 넘긴다
            }
        }
        return Result.success()
    }
}

object SyncScheduler {

    private const val PERIODIC = "sticky-sync-periodic"
    private const val ONCE = "sticky-sync-once"

    private fun netOnly(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(
            if (Settings.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** 앱이나 위젯이 살아날 때마다 불러도 된다. 이미 등록돼 있으면 그대로 둔다. */
    fun ensurePeriodic(ctx: Context, replace: Boolean = false) {
        try {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(netOnly(ctx))
                .build()
            // 설정이 바뀌어 조건을 고쳐야 할 때만 UPDATE, 평소엔 이미 있으면 그대로 둔다
            val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC, policy, req)
        } catch (e: Exception) {
            // WorkManager 를 쓸 수 없는 상황이면 그냥 넘어간다
        }
    }

    /**
     * 뭔가 바뀐 직후 부른다. 몇 초 뒤에 한 번만 돈다.
     * 연달아 불러도 마지막 것 하나로 합쳐진다 (체크를 여러 개 할 때 매번 올리지 않도록).
     */
    fun soon(ctx: Context, delaySeconds: Long = 4) {
        try {
            if (!DriveSync.connected(ctx)) return
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(netOnly(ctx))
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        } catch (e: Exception) {
        }
    }

    fun cancelAll(ctx: Context) {
        try {
            val wm = WorkManager.getInstance(ctx.applicationContext)
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(ONCE)
        } catch (e: Exception) {
        }
    }
}
