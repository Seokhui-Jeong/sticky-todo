package com.sticky.todo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 자정이 지나거나(시간 변경/재부팅 포함) 하면
 * 기한이 지난 완료 항목을 보관함으로 옮기고 위젯을 새로 그린다.
 */
class DateChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TodoRepo.runAutoArchive(context)
        TodoWidget.refresh(context)
    }
}
