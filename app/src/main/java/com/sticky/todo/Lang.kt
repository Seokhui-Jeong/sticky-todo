package com.sticky.todo

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * 화면 언어 (시스템 설정 따르기 / 한국어 / English).
 *
 * 글자는 res/values(영어, 기본)와 res/values-ko(한국어)에 있다.
 * 언어를 직접 고른 경우에는 화면·위젯·동기화가 Lang.wrap(ctx) 로 감싼 Context 에서
 * 글자를 꺼내야 고른 언어가 나온다. 날짜·요일처럼 코드에서 만드는 글자는 Lang.english 를 본다.
 */
object Lang {

    const val SYSTEM = 0
    const val KO = 1
    const val EN = 2

    /** 언어 고르는 칸은 어느 언어로 보든 알아볼 수 있게 두 말로 적는다 */
    val LABELS = arrayOf("System default (시스템 설정)", "한국어", "English")

    private const val PREF = "sticky_todo"
    private const val KEY = "app_lang"

    @Volatile
    var mode: Int = SYSTEM
        private set

    /** 앱이 켜질 때 한 번 (App.onCreate) */
    fun init(ctx: Context) {
        mode = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY, SYSTEM).coerceIn(SYSTEM, EN)
    }

    fun setMode(ctx: Context, v: Int) {
        mode = v.coerceIn(SYSTEM, EN)
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
    }

    /** 지금 화면이 영어인가. 시스템이 한국어가 아니면 기본 글자(영어)가 나오므로 영어로 본다. */
    val english: Boolean
        get() = when (mode) {
            EN -> true
            KO -> false
            else -> Resources.getSystem().configuration.locales[0].language != "ko"
        }

    /** 고른 언어로 글자를 꺼내는 Context. 시스템 설정을 따르면 그대로 돌려준다. */
    fun wrap(ctx: Context): Context {
        val loc = when (mode) {
            EN -> Locale.ENGLISH
            KO -> Locale.KOREAN
            else -> return ctx
        }
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocale(loc)
        return ctx.createConfigurationContext(cfg)
    }
}

/** 프로세스가 뜰 때 언어 설정을 먼저 읽어 둔다 (위젯·백그라운드 동기화도 이 뒤에 돈다) */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}

/** 모든 화면의 바탕. 고른 언어로 글자를 꺼내고, 언어가 바뀌었으면 다시 그린다. */
open class BaseActivity : AppCompatActivity() {

    private var langShown = -1

    override fun attachBaseContext(newBase: Context) {
        langShown = Lang.mode
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 언어를 바꾸고 돌아오면 뒤에 있던 화면도 새 언어로
        if (langShown != Lang.mode) recreate()
    }
}
