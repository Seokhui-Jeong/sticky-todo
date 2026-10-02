# -*- coding: utf-8 -*-
"""규칙 확인. 폰 앱(Kotlin)에서 확인한 결과와 같아야 한다.  python test_core.py"""
import datetime as dt
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import todo_core as c

fails = 0


def eq(label, got, want):
    global fails
    ok = got == want
    if not ok:
        fails += 1
    print(("  OK   " if ok else "  FAIL ") + "%-34s %r%s" % (label, got, "" if ok else "  (기대 %r)" % (want,)))


D = dt.date
today = D(2026, 10, 2)   # 금요일

print("[날짜 해석]")
eq("9/18 (지난 지 2주)", c.parse_date("9/18", today), D(2026, 9, 18))
eq("3/31 (반년 넘게 지남 → 내년)", c.parse_date("3/31", today), D(2027, 3, 31))
eq("26.9.18", c.parse_date("26.9.18", today), D(2026, 9, 18))
eq("9월 18일", c.parse_date("9월 18일", today), D(2026, 9, 18))
eq("+ 3", c.parse_date("+ 3", today), D(2026, 10, 5))
eq("글피", c.parse_date("글피", today), D(2026, 10, 5))
eq("2/30 (없는 날)", c.parse_date("2/30", today), None)
eq("abc", c.parse_date("abc", today), None)

print("[날짜 표시]")
eq("날짜", c.date_text(D(2026, 10, 5), today), "10/5")
eq("요일", c.date_text(D(2026, 10, 5), today, c.DATE_PLAIN, True), "10/5(월)")
eq("D-day", c.date_text(D(2026, 10, 5), today, c.DATE_DDAY), "D-3")
eq("둘 다 + 요일", c.date_text(D(2026, 9, 30), today, c.DATE_BOTH, True), "9/30(수) D+2")
eq("내년", c.date_text(D(2027, 1, 5), today), "27.1.5")
eq("반복 표시", c.date_label(c.Task(date="2026-10-05", repeatMode=c.DUE, repeatDays=7), today), "10/5 ↻7")
eq("요일 반복", c.date_label(c.Task(repeatMode=c.WEEK, repeatWeekdays=21), today), "↻월수금")

print("[반복]")
t = c.Task(date="2026-09-28", done=True, repeatMode=c.DUE, repeatDays=7, doneAt="2026-09-27T10:00:00")
eq("기한 기준: 기한 다음 날 풀림", (c.apply_repeat(t, D(2026, 9, 29)), t.done, t.date),
   (True, False, "2026-10-05"))
t = c.Task(date="2026-09-28", done=True, repeatMode=c.DUE, repeatDays=7, doneAt="2026-09-27T10:00:00")
eq("기한 기준: 기한 당일엔 그대로", c.apply_repeat(t, D(2026, 9, 28)), False)
t = c.Task(date="2026-09-20", done=True, repeatMode=c.DUE, repeatDays=7, doneAt="2026-10-01T10:00:00")
eq("늦게 낸 경우 바로 다음 차례", (c.apply_repeat(t, D(2026, 10, 2)), t.date), (True, "2026-10-04"))
t = c.Task(date="2026-10-02", done=True, repeatMode=c.DONE, repeatDays=3, doneAt="2026-10-02T09:00:00")
eq("체크일 기준: 3일 뒤", (c.apply_repeat(t, D(2026, 10, 4)), c.apply_repeat(t, D(2026, 10, 5)), t.date),
   (False, True, "2026-10-05"))
t = c.Task(date="2026-10-02", done=True, repeatMode=c.WEEK, repeatWeekdays=1 | 16, doneAt="2026-10-02T09:00:00")
eq("고정 요일(월·금): 금 체크 → 월", (c.apply_repeat(t, D(2026, 10, 3)), c.apply_repeat(t, D(2026, 10, 5)), t.date),
   (True, False, "2026-10-05"))
eq("반복은 보관 안 함", c.should_archive(c.Task(date="2026-09-01", done=True, repeatMode=c.DUE, repeatDays=1), today), False)
eq("기한 지난 완료 → 보관", c.should_archive(c.Task(date="2026-10-01", done=True), today), True)
eq("기한 지난 미완료 → 남김", c.should_archive(c.Task(date="2026-10-01"), today), False)

print("[정렬]")
items = [c.Task(id="a", done=True, seq=1), c.Task(id="b", date="2026-10-09", seq=2),
         c.Task(id="c", date="2026-10-03", seq=3), c.Task(id="d", seq=4),
         c.Task(id="e", star=True, seq=5), c.Task(id="f", star=True, done=True, seq=6)]
eq("즐겨찾기 → 기한순 → 기한 없음 → 완료", [x.id for x in c.sorted_tasks(items, today)],
   ["e", "c", "b", "d", "a", "f"])

print("[병합]")
now = 1800000000000
A = c.SyncData([c.Task(id="x", text="A", updatedAt=now - 10), c.Task(id="y", text="A", updatedAt=now - 5)], {})
B = c.SyncData([c.Task(id="x", text="B", updatedAt=now - 1)], {"y": now - 2, "z": now - 200 * c.DAY_MS})
m = c.merge(A, B, now)
eq("나중에 고친 쪽", [(t.id, t.text) for t in m.tasks], [("x", "B")])
eq("지운 기록 (오래된 건 버림)", m.deleted, {"y": now - 2})
m2 = c.merge(c.SyncData([c.Task(id="y", text="되살림", updatedAt=now)], {}), B, now)
eq("지운 뒤 고치면 되살아남", ([t.id for t in m2.tasks], m2.deleted), (["y", "x"], {}))
r = c.SyncData.from_json(m.to_json())
eq("저장 형식 왕복", r.signature(), m.signature())
o = c.Task(id="k", doneAt=None).to_json()
eq("빈 시각은 빈 글자로 (폰과 같게)", (o["doneAt"], o["archivedAt"]), ("", ""))
eq("예전 자료: 방식 없는 주기 → 기한 기준", c.Task.from_json({"id": "q", "repeatDays": 3}).repeatMode, c.DUE)

print("\n실패 %d건" % fails)
sys.exit(1 if fails else 0)
