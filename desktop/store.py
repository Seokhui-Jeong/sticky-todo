# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop — 이 컴퓨터에 저장하는 부분.

  %APPDATA%\\StickyTodo\\data.json     할 일, 보관함, 지운 기록, 설정, 창 위치
  %APPDATA%\\StickyTodo\\account.json  구글 연결 정보 (drive_sync.py 가 관리)

동작 규칙은 todo_core.py 에 있고, 여기서는 그 규칙을 목록에 적용하고 저장만 한다.
"""

import datetime as _dt
import json
import os

import todo_core as core

APP_DIR_NAME = "StickyTodo"
OLD_APP_DIR_NAME = "DesktopTodo"   # 처음에 만든 바탕화면 메모


def data_dir():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    path = os.path.join(base, APP_DIR_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def old_data_file():
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    return os.path.join(base, OLD_APP_DIR_NAME, "todo.json")


# ── 설정 ──────────────────────────────────────────────────

OPACITY = [1.0, 0.95, 0.9, 0.8, 0.7, 0.6]
OPACITY_LABELS = ["100% (불투명)", "95%", "90%", "80%", "70%", "60%"]

FONT_SIZES = [9, 10, 11, 12]
FONT_LABELS = ["작게", "보통 (기본)", "크게", "아주 크게"]

SPACING = [0, 2, 4, 7]
SPACING_LABELS = ["좁게", "보통 (기본)", "넓게", "아주 넓게"]

THEME_AUTO, THEME_LIGHT, THEME_DARK = 0, 1, 2
THEME_LABELS = ["윈도우 설정 따르기", "밝게", "어둡게"]

DATE_LABELS = ["날짜 (10/5)", "남은 날 (D-3)", "둘 다 (10/5 D-3)"]

ARCHIVE_KEEP_DAYS = [0, 30, 90]
ARCHIVE_KEEP_LABELS = ["끔 (계속 보관)", "30일 지나면 삭제", "90일 지나면 삭제"]

DEFAULT_SETTINGS = {
    "topmost": True,
    "opacity": 0,
    "font": 1,
    "spacing": 1,
    "theme": THEME_AUTO,
    "date_mode": core.DATE_PLAIN,
    "weekday": False,
    "archive_keep": 0,
    "tray_hint_shown": False,
}

DEFAULT_W, DEFAULT_H = 300, 380


class Store(object):

    def __init__(self, path=None):
        self.path = path or os.path.join(data_dir(), "data.json")
        self.items = []
        self.archive = []
        self.deleted = {}
        self.seq = 0
        self.settings = dict(DEFAULT_SETTINGS)
        self.window = {"x": None, "y": None, "w": DEFAULT_W, "h": DEFAULT_H}
        self.first_run = not os.path.exists(self.path)
        self.load()

    # ── 읽고 쓰기 ─────────────────────────────────────────

    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                root = json.load(f)
        except (OSError, ValueError):
            return
        if not isinstance(root, dict):
            return
        self.items = [core.Task.from_json(o) for o in root.get("items") or [] if isinstance(o, dict)]
        self.archive = [core.Task.from_json(o) for o in root.get("archive") or [] if isinstance(o, dict)]
        self.deleted = {}
        for k, v in (root.get("deleted") or {}).items():
            try:
                self.deleted[str(k)] = int(v)
            except (TypeError, ValueError):
                pass
        try:
            self.seq = int(root.get("seq", 0))
        except (TypeError, ValueError):
            self.seq = 0
        s = root.get("settings")
        if isinstance(s, dict):
            for k in DEFAULT_SETTINGS:
                if k in s:
                    self.settings[k] = s[k]
        w = root.get("window")
        if isinstance(w, dict):
            self.window.update(w)

    def save(self):
        root = {
            "items": [t.to_json() for t in self.items],
            "archive": [t.to_json() for t in self.archive],
            "deleted": self.deleted,
            "seq": self.seq,
            "settings": self.settings,
            "window": self.window,
        }
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(root, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            pass

    def setting(self, key):
        return self.settings.get(key, DEFAULT_SETTINGS.get(key))

    def set_setting(self, key, value):
        self.settings[key] = value
        self.save()

    def _level(self, key, table):
        try:
            return max(0, min(len(table) - 1, int(self.setting(key))))
        except (TypeError, ValueError):
            return DEFAULT_SETTINGS[key]

    def opacity(self):
        return OPACITY[self._level("opacity", OPACITY)]

    def font_size(self):
        return FONT_SIZES[self._level("font", FONT_SIZES)]

    def spacing(self):
        return SPACING[self._level("spacing", SPACING)]

    def archive_keep_days(self):
        return ARCHIVE_KEEP_DAYS[self._level("archive_keep", ARCHIVE_KEEP_DAYS)]

    # ── 예전 바탕화면 메모에서 가져오기 ───────────────────

    def old_data_count(self):
        try:
            with open(old_data_file(), "r", encoding="utf-8") as f:
                root = json.load(f)
            return len(root.get("items") or []) + len(root.get("archive") or [])
        except (OSError, ValueError, AttributeError):
            return 0

    def import_old(self):
        """처음 만든 바탕화면 메모(DesktopTodo)의 목록과 창 위치를 가져온다."""
        try:
            with open(old_data_file(), "r", encoding="utf-8") as f:
                root = json.load(f)
        except (OSError, ValueError):
            return 0
        now = core.now_ms()

        def conv(o, archived):
            o = dict(o)
            o["doneAt"] = o.get("doneAt") or o.get("done_at") or ""
            o["archivedAt"] = o.get("archivedAt") or o.get("archived_at") or ""
            t = core.Task.from_json(o)
            if archived and not t.archivedAt:
                t.archivedAt = core.now_text()
            t.touch(now)
            return t

        n = 0
        have = set(t.id for t in self.items + self.archive)
        for o in root.get("items") or []:
            if isinstance(o, dict):
                t = conv(o, False)
                if t.id not in have and not t.blank():
                    self.seq += 1
                    t.seq = self.seq
                    self.items.append(t)
                    n += 1
        for o in root.get("archive") or []:
            if isinstance(o, dict):
                t = conv(o, True)
                if t.id not in have:
                    self.archive.append(t)
                    n += 1
        w = root.get("window")
        if isinstance(w, dict):
            self.window.update({k: w.get(k) for k in ("x", "y", "w", "h") if w.get(k) is not None})
        if "topmost" in root:
            self.settings["topmost"] = bool(root.get("topmost"))
        self.save()
        return n

    # ── 조회 ──────────────────────────────────────────────

    def sorted_items(self):
        return core.sorted_tasks(self.items)

    def remaining(self):
        return sum(1 for t in self.items if not t.done and not t.blank())

    def find(self, tid):
        for t in self.items:
            if t.id == tid:
                return t
        return None

    # ── 변경 ──────────────────────────────────────────────

    def _bury(self, tid, at=None):
        self.deleted[tid] = core.now_ms() if at is None else at

    def add(self, text="", date="", mode=core.NONE, days=0, weekdays=0, star=False):
        self.seq += 1
        t = core.Task(text=text.strip(), date=core.normalize_date(date), star=star,
                      repeatMode=mode, repeatDays=max(0, int(days)),
                      repeatWeekdays=weekdays, seq=self.seq)
        t.touch()
        self.items.append(t)
        self.save()
        return t

    def update(self, tid, text, date, mode, days, weekdays, star):
        t = self.find(tid)
        if t is None:
            return
        t.text = text.strip()
        t.date = core.normalize_date(date)
        t.repeatMode = mode
        t.repeatDays = max(0, int(days))
        t.repeatWeekdays = weekdays
        t.star = star
        t.touch()
        self.save()

    def set_text_date(self, tid, text, date_raw):
        """목록에서 바로 고친 글자와 날짜. 실제로 바뀐 경우만 수정 시각을 올린다."""
        t = self.find(tid)
        if t is None:
            return False
        new_text = text.strip()
        new_date = core.normalize_date(date_raw)
        if new_text == t.text and new_date == t.date:
            return False
        t.text = new_text
        t.date = new_date
        t.touch()
        self.save()
        return True

    def toggle(self, tid):
        t = self.find(tid)
        if t is None:
            return
        t.done = not t.done
        t.doneAt = core.now_text() if t.done else None
        t.touch()
        self.save()

    def toggle_star(self, tid):
        t = self.find(tid)
        if t is None:
            return
        t.star = not t.star
        t.touch()
        self.save()

    def delete(self, tid):
        before = len(self.items)
        self.items = [t for t in self.items if t.id != tid]
        if len(self.items) != before:
            self._bury(tid)
        self.save()

    # ── 보관함 ────────────────────────────────────────────

    def run_auto_archive(self, today=None):
        """반복 항목 되살리기 → 기한 지난 완료 항목 보관 → 오래된 보관함 비우기"""
        today = today or _dt.date.today()
        changed = False
        for t in self.items:
            if core.apply_repeat(t, today):
                t.touch()
                changed = True

        move = [t for t in self.items if core.should_archive(t, today)]
        for t in move:
            t.archivedAt = core.now_text()
            t.touch()
            self.archive.insert(0, t)
        if move:
            ids = set(t.id for t in move)
            self.items = [t for t in self.items if t.id not in ids]

        purged = False
        keep = self.archive_keep_days()
        if keep > 0 and self.archive:
            cutoff = today - _dt.timedelta(days=keep)
            old = [t for t in self.archive
                   if core._day_of(t.archivedAt) is not None and core._day_of(t.archivedAt) < cutoff]
            if old:
                now = core.now_ms()
                for t in old:
                    self._bury(t.id, now)
                ids = set(t.id for t in old)
                self.archive = [t for t in self.archive if t.id not in ids]
                purged = True

        if changed or move or purged:
            self.save()
        return changed or bool(move) or purged

    def archive_all_done(self):
        done = [t for t in self.items if t.done]
        for t in done:
            t.archivedAt = core.now_text()
            t.touch()
            self.archive.insert(0, t)
        if done:
            self.items = [t for t in self.items if not t.done]
            self.save()
        return len(done)

    def restore(self, tid):
        rec = next((t for t in self.archive if t.id == tid), None)
        if rec is None:
            return
        self.archive.remove(rec)
        self.seq += 1
        rec.done = False
        rec.doneAt = None
        rec.archivedAt = None
        rec.seq = self.seq
        rec.touch()
        self.items.append(rec)
        self.save()

    def remove_archived(self, tid):
        before = len(self.archive)
        self.archive = [t for t in self.archive if t.id != tid]
        if len(self.archive) != before:
            self._bury(tid)
        self.save()

    def clear_archive(self):
        now = core.now_ms()
        for t in self.archive:
            self._bury(t.id, now)
        self.archive = []
        self.save()

    # ── 동기화용 ──────────────────────────────────────────

    def snapshot(self):
        """올릴 내용. 막 추가해서 아직 아무것도 안 적은 줄은 뺀다."""
        tasks = [t.copy() for t in self.items if not t.blank()]
        tasks += [t.copy() for t in self.archive]
        return core.SyncData(tasks, dict(self.deleted))

    def _signature(self):
        parts = sorted("%s:%d" % (t.id, t.updatedAt) for t in self.items + self.archive if not t.blank())
        return ";".join(parts) + "#" + ",".join(sorted(self.deleted))

    def apply_merged(self, data):
        """병합 결과를 적용한다. 내용이 달라졌으면 True."""
        before = self._signature()
        blanks = [t for t in self.items if t.blank()]
        items, archive = [], []
        for t in data.tasks:
            (archive if t.archivedAt else items).append(t.copy())
        archive.sort(key=lambda t: t.archivedAt or "", reverse=True)
        ids = set(t.id for t in items)
        items += [t for t in blanks if t.id not in ids]
        self.items = items
        self.archive = archive
        self.deleted = dict(data.deleted)
        self.seq = max([self.seq] + [t.seq for t in self.items + self.archive])
        self.save()
        return before != self._signature()
