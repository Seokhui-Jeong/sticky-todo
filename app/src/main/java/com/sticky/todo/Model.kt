package com.sticky.todo

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** 반복 방식 */
object Repeat {
    const val NONE = "none"   // 반복 없음
    const val DUE = "due"     // 기한 기준 — 기한으로부터 n일 뒤가 다음 차례 (기본)
    const val DONE = "done"   // 체크일 기준 — 체크한 날로부터 n일 뒤
    const val WEEK = "week"   // 고정 요일 — 정해둔 요일마다

    /** 월=1 … 일=64 비트 */
    fun bit(d: DayOfWeek): Int = 1 shl (d.value - 1)
    fun has(mask: Int, d: DayOfWeek): Boolean = mask and bit(d) != 0

    val SHORT = arrayOf("월", "화", "수", "목", "금", "토", "일")

    /** 요일 묶음을 '월·수·금' 처럼. 목록에서는 자리를 아끼려고 sep 을 비운다. */
    fun weekdayText(mask: Int, sep: String = "·"): String {
        if (mask == 0) return ""
        if (mask and 0b1111111 == 0b1111111) return "매일"
        return (1..7).filter { mask and (1 shl (it - 1)) != 0 }
            .joinToString(sep) { SHORT[it - 1] }
    }
}

/**
 * 할 일 한 건.
 * date 는 해석에 성공하면 ISO(yyyy-MM-dd), 실패하면 사용자가 적은 원문 그대로 보관한다.
 */
data class Task(
    var id: String = UUID.randomUUID().toString().take(12),
    var text: String = "",
    var date: String = "",
    var done: Boolean = false,
    var star: Boolean = false,
    var repeatMode: String = Repeat.NONE,
    var repeatDays: Int = 0,
    var repeatWeekdays: Int = 0,
    var seq: Long = 0,
    var doneAt: String? = null,
    var archivedAt: String? = null
) {
    fun localDate(): LocalDate? = Dates.parse(date)

    /** 실제로 반복이 걸려 있는가 */
    fun repeating(): Boolean = when (repeatMode) {
        Repeat.DUE, Repeat.DONE -> repeatDays > 0
        Repeat.WEEK -> repeatWeekdays != 0
        else -> false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("date", date)
        put("done", done)
        put("star", star)
        put("repeatMode", repeatMode)
        put("repeatDays", repeatDays)
        put("repeatWeekdays", repeatWeekdays)
        put("seq", seq)
        // JSONObject.NULL 로 넣으면 다시 읽을 때 "null" 이라는 글자가 되어버린다
        put("doneAt", doneAt ?: "")
        put("archivedAt", archivedAt ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): Task {
            val days = o.optInt("repeatDays", 0)
            // 예전 자료에는 방식 구분이 없었다. 주기만 있던 항목은 기한 기준으로 본다.
            val mode = o.optString("repeatMode", "").ifEmpty {
                if (days > 0) Repeat.DUE else Repeat.NONE
            }
            return Task(
                id = o.optString("id", UUID.randomUUID().toString().take(12)),
                text = o.optString("text", ""),
                date = o.optString("date", ""),
                done = o.optBoolean("done", false),
                star = o.optBoolean("star", false),
                repeatMode = mode,
                repeatDays = days,
                repeatWeekdays = o.optInt("repeatWeekdays", 0),
                seq = o.optLong("seq", 0L),
                doneAt = o.optString("doneAt", "").ifEmpty { null },
                archivedAt = o.optString("archivedAt", "").ifEmpty { null }
            )
        }

        fun now(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
}

enum class DateState { NONE, PAST, TODAY, FUTURE }

/** 목록 오른쪽에 보일 글자. 기한과 반복을 함께 담는다. */
fun dateLabel(t: Task, today: LocalDate = LocalDate.now()): String {
    val d = t.localDate()
    val base = if (d != null) Dates.format(d, today) else t.date
    val rep = when {
        !t.repeating() -> ""
        t.repeatMode == Repeat.WEEK -> "↻" + Repeat.weekdayText(t.repeatWeekdays, "")
        else -> "↻${t.repeatDays}"
    }
    return when {
        rep.isEmpty() -> base
        base.isBlank() -> rep
        else -> "$base $rep"
    }
}

/**
 * 사람이 적은 날짜 문자열을 해석한다. PC판과 동일한 규칙.
 *   9/18 · 9-18 · 9.18 · 9월18일 · 26.9.18 · 2026-09-18 · 26년 9월 18일
 *   오늘 · 내일 · 모레 · 글피 · +7(7일 뒤)
 * 연도를 안 적으면 올해로 보되, 6개월 넘게 지난 날짜는 내년으로 해석한다.
 */
object Dates {

    private const val SEP = "[./\\-\\s]"
    private val RE_YMD = Regex("^(\\d{2}|\\d{4})$SEP(\\d{1,2})$SEP(\\d{1,2})\\.?$")
    private val RE_YMD_KR = Regex("^(\\d{2}|\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일?$")
    private val RE_MD = Regex("^(\\d{1,2})$SEP(\\d{1,2})\\.?$")
    private val RE_MD_KR = Regex("^(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일?$")
    private val RE_DDAY = Regex("^\\+(\\d{1,3})$")

    fun parse(raw: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null

        when (s.replace(" ", "")) {
            "오늘", "today" -> return today
            "내일", "tomorrow" -> return today.plusDays(1)
            "모레" -> return today.plusDays(2)
            "글피" -> return today.plusDays(3)
        }

        RE_DDAY.find(s.replace(" ", ""))?.let {
            return today.plusDays(it.groupValues[1].toLong())
        }

        for (rx in listOf(RE_YMD, RE_YMD_KR)) {
            val m = rx.find(s) ?: continue
            var y = m.groupValues[1].toInt()
            if (y < 100) y += 2000
            return safe(y, m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        for (rx in listOf(RE_MD, RE_MD_KR)) {
            val m = rx.find(s) ?: continue
            val mo = m.groupValues[1].toInt()
            val d = m.groupValues[2].toInt()
            if (mo !in 1..12) return null
            var last: LocalDate? = null
            for (y in listOf(today.year, today.year + 1)) {
                val cand = safe(y, mo, d) ?: continue
                last = cand
                if (!cand.isBefore(today.minusDays(180))) return cand
            }
            return last
        }
        return null
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    /** 화면에 보일 짧은 형태. 올해면 9/18, 다른 해면 27.1.5 */
    fun format(d: LocalDate?, today: LocalDate = LocalDate.now()): String {
        if (d == null) return ""
        return if (d.year != today.year)
            String.format("%02d.%d.%d", d.year % 100, d.monthValue, d.dayOfMonth)
        else
            String.format("%d/%d", d.monthValue, d.dayOfMonth)
    }

    /** 저장용 문자열: 해석되면 ISO, 아니면 원문 유지 */
    fun normalize(raw: String?): String {
        val s = raw?.trim() ?: return ""
        val d = parse(s) ?: return s
        return d.toString()
    }

    fun state(d: LocalDate?, today: LocalDate = LocalDate.now()): DateState = when {
        d == null -> DateState.NONE
        d.isBefore(today) -> DateState.PAST
        d.isEqual(today) -> DateState.TODAY
        else -> DateState.FUTURE
    }
}

/** 정렬 / 자동 보관 / 반복 규칙 */
object Rules {

    /** true 로 바꾸면 완료하지 않은 할 일도 기한이 지나면 보관함으로 보낸다 */
    const val ARCHIVE_UNDONE_OVERDUE = false

    /**
     * 정렬 순서
     *   1. 완료하지 않은 것 먼저 (완료한 것은 맨 아래)
     *   2. 미완료 중에서는 즐겨찾기가 가장 위 — 체크하는 순간 이 특권은 사라진다
     *   3. 기한 빠른 순 → 기한 없는 것 → 입력한 순서
     */
    fun sorted(items: List<Task>): List<Task> = items.sortedWith(
        compareBy<Task>(
            { if (it.done) 1 else 0 },
            { if (!it.done && it.star) 0 else 1 },
            { if (it.localDate() == null) 1 else 0 },
            { it.localDate()?.toEpochDay() ?: 0L },
            { it.seq }
        )
    )

    /** 지금 보관함으로 옮겨야 하는 할 일인가? */
    fun shouldArchive(t: Task, today: LocalDate = LocalDate.now()): Boolean {
        // 반복 항목은 계속 돌아와야 하므로 보관하지 않는다
        if (t.repeating()) return false
        val d = t.localDate()
        if (d != null && d.isBefore(today)) {
            return t.done || ARCHIVE_UNDONE_OVERDUE
        }
        return false
    }

    /** 기준일 다음으로 오는, 정해둔 요일 */
    fun nextWeekdayAfter(from: LocalDate, mask: Int): LocalDate {
        for (i in 1..7) {
            val d = from.plusDays(i.toLong())
            if (Repeat.has(mask, d.dayOfWeek)) return d
        }
        return from.plusDays(7)
    }

    /** 기준일 포함, 그날부터 처음 오는 정해둔 요일 */
    fun weekdayOnOrAfter(from: LocalDate, mask: Int): LocalDate {
        for (i in 0..6) {
            val d = from.plusDays(i.toLong())
            if (Repeat.has(mask, d.dayOfWeek)) return d
        }
        return from
    }

    /**
     * 반복 항목의 체크를 풀어줄 때가 됐는지 본다. 무언가 바뀌었으면 true.
     *
     *  기한 기준  : 기한 + n일 이 다음 차례. 늦게 체크해도 주기가 밀리지 않는다.
     *  체크일 기준: 체크한 날 + n일. 마지막으로 한 시점이 중요한 일에 맞다.
     *  고정 요일  : 정해둔 요일 중 다음 것.
     */
    fun applyRepeat(t: Task, today: LocalDate = LocalDate.now()): Boolean {
        if (!t.repeating() || !t.done) return false

        val doneDay = t.doneAt?.let {
            try { LocalDate.parse(it.substring(0, 10)) } catch (e: Exception) { null }
        }
        if (doneDay == null) {
            // 완료 시각을 모르면 오늘부터 센다
            t.doneAt = Task.now()
            return true
        }

        val due = t.localDate()
        val step = t.repeatDays.toLong()

        val next: LocalDate = when (t.repeatMode) {
            Repeat.WEEK -> nextWeekdayAfter(due ?: doneDay, t.repeatWeekdays)
            Repeat.DONE -> doneDay.plusDays(step)
            // 기한 기준. 기한을 안 적었으면 체크한 날을 기준으로 삼는다.
            else -> (due ?: doneDay).plusDays(step)
        }

        if (today.isBefore(next)) return false

        t.done = false
        t.doneAt = null

        // 기한을 다음 차례로 옮긴다. 오래 지났으면 오늘 이후로 따라잡는다.
        if (t.repeatMode == Repeat.WEEK) {
            t.date = weekdayOnOrAfter(today, t.repeatWeekdays).toString()
        } else if (due != null && step > 0) {
            var d = next
            while (d.isBefore(today)) d = d.plusDays(step)
            t.date = d.toString()
        }
        return true
    }
}
