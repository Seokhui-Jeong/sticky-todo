# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop — 자료 구조와 규칙.

안드로이드 앱(Model.kt, Sync.kt)과 똑같이 동작해야 한다.
같은 파일을 드라이브에 두고 주고받기 때문에, 저장 형식과 규칙이 조금이라도
다르면 기기끼리 서로 다른 결과를 만든다. 이 파일만 고칠 때는 양쪽을 같이 고칠 것.

화면이나 통신은 여기 없다. 그래서 따로 시험해 볼 수 있다.
"""

import datetime as _dt
import re
import time
import uuid

# ── 반복 방식 ─────────────────────────────────────────────

NONE = "none"   # 반복 없음
DUE = "due"     # 기한 기준 — 기한으로부터 n일 뒤가 다음 차례 (기본)
DONE = "done"   # 체크일 기준 — 체크한 날로부터 n일 뒤
WEEK = "week"   # 고정 요일 — 정해둔 요일마다

WEEKDAY_SHORT = ["월", "화", "수", "목", "금", "토", "일"]


def wd_bit(d):
    """월=1 … 일=64 비트 (date.weekday() 는 월=0)"""
    return 1 << d.weekday()


def wd_has(mask, d):
    return mask & wd_bit(d) != 0


def weekday_text(mask, sep="·"):
    if mask == 0:
        return ""
    if mask & 0b1111111 == 0b1111111:
        return "매일"
    return sep.join(WEEKDAY_SHORT[i] for i in range(7) if mask & (1 << i))


def now_ms():
    return int(time.time() * 1000)


def now_text():
    return _dt.datetime.now().strftime("%Y-%m-%dT%H:%M:%S")


# ── 할 일 한 건 ───────────────────────────────────────────

class Task(object):
    __slots__ = ("id", "text", "date", "done", "star", "repeatMode", "repeatDays",
                 "repeatWeekdays", "seq", "doneAt", "archivedAt", "updatedAt")

    def __init__(self, id=None, text="", date="", done=False, star=False,
                 repeatMode=NONE, repeatDays=0, repeatWeekdays=0, seq=0,
                 doneAt=None, archivedAt=None, updatedAt=0):
        self.id = id or uuid.uuid4().hex[:12]
        self.text = text
        self.date = date
        self.done = done
        self.star = star
        self.repeatMode = repeatMode
        self.repeatDays = repeatDays
        self.repeatWeekdays = repeatWeekdays
        self.seq = seq
        self.doneAt = doneAt
        self.archivedAt = archivedAt
        self.updatedAt = updatedAt

    def copy(self):
        return Task(**{k: getattr(self, k) for k in self.__slots__})

    def local_date(self, today=None):
        return parse_date(self.date, today)

    def touch(self, at=None):
        self.updatedAt = now_ms() if at is None else at

    def repeating(self):
        if self.repeatMode in (DUE, DONE):
            return self.repeatDays > 0
        if self.repeatMode == WEEK:
            return self.repeatWeekdays != 0
        return False

    def blank(self):
        return not (self.text or "").strip() and not (self.date or "").strip()

    def to_json(self):
        return {
            "id": self.id,
            "text": self.text,
            "date": self.date,
            "done": self.done,
            "star": self.star,
            "repeatMode": self.repeatMode,
            "repeatDays": self.repeatDays,
            "repeatWeekdays": self.repeatWeekdays,
            "seq": self.seq,
            "updatedAt": self.updatedAt,
            # 안드로이드 쪽이 null 대신 빈 글자를 쓴다. 맞춰 둔다.
            "doneAt": self.doneAt or "",
            "archivedAt": self.archivedAt or "",
        }

    @staticmethod
    def from_json(o):
        def _int(k, default=0):
            try:
                return int(o.get(k, default) or 0)
            except (TypeError, ValueError):
                return default

        def _str(k):
            v = o.get(k, "")
            return "" if v is None else str(v)

        days = _int("repeatDays")
        mode = _str("repeatMode") or (DUE if days > 0 else NONE)
        return Task(
            id=_str("id") or uuid.uuid4().hex[:12],
            text=_str("text"),
            date=_str("date"),
            done=bool(o.get("done", False)),
            star=bool(o.get("star", False)),
            repeatMode=mode,
            repeatDays=days,
            repeatWeekdays=_int("repeatWeekdays"),
            seq=_int("seq"),
            updatedAt=_int("updatedAt"),
            doneAt=_str("doneAt") or None,
            archivedAt=_str("archivedAt") or None,
        )


# ── 날짜 ──────────────────────────────────────────────────

_SEP = r"[./\-\s]"
_RE_YMD = re.compile(r"^(\d{2}|\d{4})%s(\d{1,2})%s(\d{1,2})\.?$" % (_SEP, _SEP))
_RE_YMD_KR = re.compile(r"^(\d{2}|\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일?$")
_RE_MD = re.compile(r"^(\d{1,2})%s(\d{1,2})\.?$" % _SEP)
_RE_MD_KR = re.compile(r"^(\d{1,2})\s*월\s*(\d{1,2})\s*일?$")
_RE_DDAY = re.compile(r"^\+(\d{1,3})$")


def _safe(y, m, d):
    try:
        return _dt.date(y, m, d)
    except ValueError:
        return None


def parse_date(raw, today=None):
    """9/18 · 9-18 · 9.18 · 9월18일 · 26.9.18 · 2026-09-18 · 오늘 · 내일 · 모레 · 글피 · +7"""
    if raw is None:
        return None
    s = str(raw).strip()
    if not s:
        return None
    today = today or _dt.date.today()

    compact = s.replace(" ", "")
    if compact in ("오늘", "today"):
        return today
    if compact in ("내일", "tomorrow"):
        return today + _dt.timedelta(days=1)
    if compact == "모레":
        return today + _dt.timedelta(days=2)
    if compact == "글피":
        return today + _dt.timedelta(days=3)

    m = _RE_DDAY.search(compact)
    if m:
        return today + _dt.timedelta(days=int(m.group(1)))

    for rx in (_RE_YMD, _RE_YMD_KR):
        m = rx.search(s)
        if m:
            y = int(m.group(1))
            if y < 100:
                y += 2000
            return _safe(y, int(m.group(2)), int(m.group(3)))

    for rx in (_RE_MD, _RE_MD_KR):
        m = rx.search(s)
        if m:
            mo, d = int(m.group(1)), int(m.group(2))
            if not 1 <= mo <= 12:
                return None
            last = None
            for y in (today.year, today.year + 1):
                cand = _safe(y, mo, d)
                if cand is None:
                    continue
                last = cand
                if not cand < today - _dt.timedelta(days=180):
                    return cand
            return last
    return None


def format_date(d, today=None):
    """올해면 9/18, 다른 해면 27.1.5"""
    if d is None:
        return ""
    today = today or _dt.date.today()
    if d.year != today.year:
        return "%02d.%d.%d" % (d.year % 100, d.month, d.day)
    return "%d/%d" % (d.month, d.day)


def dday(d, today=None):
    today = today or _dt.date.today()
    n = (d - today).days
    if n == 0:
        return "오늘"
    return "D-%d" % n if n > 0 else "D+%d" % -n


# 날짜 표시 방식
DATE_PLAIN = 0
DATE_DDAY = 1
DATE_BOTH = 2


def date_text(d, today=None, mode=DATE_PLAIN, weekday=False):
    plain = format_date(d, today) + ("(%s)" % WEEKDAY_SHORT[d.weekday()] if weekday else "")
    if mode == DATE_DDAY:
        return dday(d, today)
    if mode == DATE_BOTH:
        return "%s %s" % (plain, dday(d, today))
    return plain


def date_label(t, today=None, mode=DATE_PLAIN, weekday=False):
    """목록 오른쪽 글자. 기한과 반복을 함께."""
    d = t.local_date(today)
    base = date_text(d, today, mode, weekday) if d else t.date
    if not t.repeating():
        rep = ""
    elif t.repeatMode == WEEK:
        rep = "↻" + weekday_text(t.repeatWeekdays, "")
    else:
        rep = "↻%d" % t.repeatDays
    if not rep:
        return base
    if not base.strip():
        return rep
    return "%s %s" % (base, rep)


def normalize_date(raw):
    """저장용: 해석되면 ISO, 아니면 원문 그대로"""
    s = (raw or "").strip()
    d = parse_date(s)
    return d.isoformat() if d else s


def date_state(d, today=None):
    if d is None:
        return "none"
    today = today or _dt.date.today()
    if d < today:
        return "past"
    if d == today:
        return "today"
    return "future"


def _day_of(stamp):
    try:
        return _dt.date.fromisoformat(stamp[:10])
    except (TypeError, ValueError):
        return None


# ── 정렬 / 보관 / 반복 ────────────────────────────────────

ARCHIVE_UNDONE_OVERDUE = False


def sorted_tasks(items, today=None):
    """미완료 먼저 → 미완료 즐겨찾기 맨 위 → 기한 빠른 순 → 기한 없는 것 → 입력 순서"""
    def key(t):
        d = t.local_date(today)
        return (
            1 if t.done else 0,
            0 if (not t.done and t.star) else 1,
            1 if d is None else 0,
            d.toordinal() if d else 0,
            t.seq,
        )
    return sorted(items, key=key)


def should_archive(t, today=None):
    if t.repeating():
        return False
    today = today or _dt.date.today()
    d = t.local_date(today)
    if d is not None and d < today:
        return t.done or ARCHIVE_UNDONE_OVERDUE
    return False


def next_weekday_after(frm, mask):
    for i in range(1, 8):
        d = frm + _dt.timedelta(days=i)
        if wd_has(mask, d):
            return d
    return frm + _dt.timedelta(days=7)


def weekday_on_or_after(frm, mask):
    for i in range(0, 7):
        d = frm + _dt.timedelta(days=i)
        if wd_has(mask, d):
            return d
    return frm


def apply_repeat(t, today=None):
    """반복 항목의 체크를 풀 때가 됐으면 풀고 기한을 옮긴다. 바뀌었으면 True."""
    if not t.repeating() or not t.done:
        return False
    today = today or _dt.date.today()

    done_day = _day_of(t.doneAt)
    if done_day is None:
        t.doneAt = now_text()
        return True

    due = t.local_date(today)
    step = _dt.timedelta(days=t.repeatDays)

    if t.repeatMode == DONE:
        revive = done_day + step
    elif due is not None:
        revive = due + _dt.timedelta(days=1)
    elif t.repeatMode == WEEK:
        revive = next_weekday_after(done_day, t.repeatWeekdays)
    else:
        revive = done_day + step

    if today < revive:
        return False

    t.done = False
    t.doneAt = None

    if t.repeatMode == WEEK:
        base = today if today > done_day else done_day + _dt.timedelta(days=1)
        t.date = weekday_on_or_after(base, t.repeatWeekdays).isoformat()
    elif t.repeatMode == DONE and due is not None and t.repeatDays > 0:
        d = done_day + step
        while d < today:
            d += step
        t.date = d.isoformat()
    elif due is not None and t.repeatDays > 0:
        d = due + step
        while d < today:
            d += step
        t.date = d.isoformat()
    return True


# ── 동기화 묶음과 병합 ────────────────────────────────────

FORMAT = 1
TOMBSTONE_DAYS = 90
DAY_MS = 24 * 60 * 60 * 1000


class SyncData(object):
    def __init__(self, tasks=None, deleted=None):
        self.tasks = list(tasks or [])
        self.deleted = dict(deleted or {})

    def to_json(self):
        return {
            "format": FORMAT,
            "tasks": [t.to_json() for t in self.tasks],
            "deleted": dict(self.deleted),
            "savedAt": now_ms(),
        }

    def signature(self):
        parts = ["%s:%d;" % (t.id, t.updatedAt) for t in sorted(self.tasks, key=lambda t: t.id)]
        parts.append("#")
        parts += ["%s:%d;" % (k, self.deleted[k]) for k in sorted(self.deleted)]
        return "".join(parts)

    @staticmethod
    def from_json(o):
        tasks = [Task.from_json(x) for x in (o.get("tasks") or []) if isinstance(x, dict)]
        deleted = {}
        for k, v in (o.get("deleted") or {}).items():
            try:
                deleted[str(k)] = int(v)
            except (TypeError, ValueError):
                deleted[str(k)] = 0
        return SyncData(tasks, deleted)


def merge(local, remote, now=None):
    """안드로이드 Merge.merge 와 같은 규칙."""
    now = now_ms() if now is None else now

    deleted = dict(local.deleted)
    for k, at in remote.deleted.items():
        cur = deleted.get(k)
        if cur is None or at > cur:
            deleted[k] = at

    by_id = {}
    order = []
    for t in local.tasks:
        if t.id not in by_id:
            order.append(t.id)
        by_id[t.id] = t
    for t in remote.tasks:
        cur = by_id.get(t.id)
        if cur is None:
            order.append(t.id)
        if cur is None or t.updatedAt > cur.updatedAt:
            by_id[t.id] = t

    kept = []
    for i in order:
        t = by_id[i]
        gone = deleted.get(t.id)
        if gone is None or t.updatedAt > gone:
            kept.append(t)

    kept_ids = set(t.id for t in kept)
    cutoff = now - TOMBSTONE_DAYS * DAY_MS
    trimmed = {k: v for k, v in deleted.items() if k not in kept_ids and v >= cutoff}
    return SyncData(kept, trimmed)
