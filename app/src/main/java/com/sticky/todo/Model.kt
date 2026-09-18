package com.sticky.todo

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 할 일 한 건.
 * date 는 해석에 성공하면 ISO(yyyy-MM-dd), 실패하면 사용자가 적은 원문 그대로 보관한다.
 */
data class Task(
    var id: String = UUID.randomUUID().toString().take(12),
    var text: String = "",
    var date: String = "",
    var done: Boolean = false,
    var seq: Long = 0,
    var doneAt: String? = null,
    var archivedAt: String? = null
) {
    fun localDate(): LocalDate? = Dates.parse(date)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("text", text)
        put("date", date)
        put("done", done)
        put("seq", seq)
        put("doneAt", doneAt ?: JSONObject.NULL)
        put("archivedAt", archivedAt ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject) = Task(
            id = o.optString("id", UUID.randomUUID().toString().take(12)),
            text = o.optString("text", ""),
            date = o.optString("date", ""),
            done = o.optBoolean("done", false),
            seq = o.optLong("seq", 0L),
            doneAt = o.optString("doneAt", "").ifEmpty { null },
            archivedAt = o.optString("archivedAt", "").ifEmpty { null }
        )

        fun now(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    }
}

enum class DateState { NONE, PAST, TODAY, FUTURE }

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

/** 정렬 / 자동 보관 규칙 (PC판과 동일) */
object Rules {

    /** true 로 바꾸면 완료하지 않은 할 일도 기한이 지나면 보관함으로 보낸다 */
    const val ARCHIVE_UNDONE_OVERDUE = false

    /** 미완료 먼저 → 기한 빠른 순 → 기한 없는 것 → 완료한 것은 맨 아래 */
    fun sorted(items: List<Task>): List<Task> = items.sortedWith(
        compareBy(
            { if (it.done) 1 else 0 },
            { if (it.localDate() == null) 1 else 0 },
            { it.localDate()?.toEpochDay() ?: 0L },
            { it.seq }
        )
    )

    /** 지금 보관함으로 옮겨야 하는 할 일인가? */
    fun shouldArchive(t: Task, today: LocalDate = LocalDate.now()): Boolean {
        val d = t.localDate()
        if (d != null && d.isBefore(today)) {
            return t.done || ARCHIVE_UNDONE_OVERDUE
        }
        return false
    }
}
