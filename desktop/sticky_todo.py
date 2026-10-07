# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop
------------------
윈도우 바탕화면에 붙여 두는 할 일 메모. 폰의 '할 일' 앱·위젯과 같은 목록을
구글 드라이브로 주고받는다.

  [v] ★ 할 일 내용 ..............  10/5 ↻7

- 체크한 할 일은 맨 아래로, 즐겨찾기(★)는 맨 위로
- 기한이 지난 완료 항목은 보관함으로 (반복 항목은 체크가 풀리며 다음 기한으로)
- 창 가장자리·모서리를 끌어 크기 조절, 머리글을 끌어 이동, 두 번 눌러 접기
- 줄의 ⋯ 또는 오른쪽 클릭: 반복·즐겨찾기 설정

규칙은 todo_core.py, 저장은 store.py, 동기화는 drive_sync.py 에 있다.
"""

import datetime as _dt
import os
import queue
import socket
import sys
import threading
import time
import tkinter as tk

import todo_core as core
import store as st
import drive_sync as ds

APP_TITLE = "할 일 메모 Desktop"
HEADER_TITLE = "할 일"
RUN_NAME = "StickyTodo"           # 윈도우 시작 프로그램 등록 이름
OLD_RUN_NAME = "DesktopTodo"      # 처음 만든 바탕화면 메모
INSTANCE_PORT = 47613             # 두 번 실행했을 때 먼저 켜진 창을 불러오는 용도
FONT_FAMILY = "맑은 고딕"

SYNC_DELAY_MS = 4000              # 바꾼 뒤 이만큼 기다렸다가 올린다 (연달아 바꾸면 한 번만)
SYNC_EVERY_MS = 5 * 60 * 1000     # 켜져 있는 동안 주기적으로 받아오기
TICK_MS = 30 * 1000               # 날짜가 바뀌었는지 확인

RESIZE_EDGE = 4          # 가장자리에서 이만큼 안쪽까지 잡힌다 (눈에는 안 보임)
RESIZE_CORNER = 12       # 모서리는 이만큼 사각형
EDGE_TAG = "StickyEdge"
EDGE_CURSORS = {
    "n": "sb_v_double_arrow", "s": "sb_v_double_arrow",
    "w": "sb_h_double_arrow", "e": "sb_h_double_arrow",
    "nw": "size_nw_se", "se": "size_nw_se", "ne": "size_ne_sw", "sw": "size_ne_sw",
}
MIN_W, MIN_H = 220, 140

LIGHT = dict(
    bg="#FFFFFF", border="#E3E5E8", head="#FAFAFB", text="#22252A", muted="#9AA0A6",
    done="#B9BEC4", accent="#3B7DEA", over="#E0463E", hover="#F2F4F7", divider="#EFF1F4",
    star="#F0A82E", check="#C7CBD1", edge_hover="#D4D7DB", nodate="#C9CDD3",
    button="#EDEFF2", field="#F5F6F8",
)
DARK = dict(
    bg="#1A1D21", border="#2C3036", head="#202328", text="#E6E8EB", muted="#8B9198",
    done="#5E646B", accent="#5B95F0", over="#F0645C", hover="#24282D", divider="#2A2E33",
    star="#F0A82E", check="#5A6068", edge_hover="#3A3F45", nodate="#4A4F55",
    button="#2A2E34", field="#24282D",
)


def resource(name):
    """exe 안에 함께 묶인 파일 경로"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def system_dark():
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                            r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize") as k:
            v, _ = winreg.QueryValueEx(k, "AppsUseLightTheme")
        return v == 0
    except Exception:
        return False


# ── 시작 프로그램 등록 ─────────────────────────────────────

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def _startup_command():
    if getattr(sys, "frozen", False):
        return '"%s"' % sys.executable
    pyw = sys.executable
    if pyw.lower().endswith("python.exe"):
        cand = pyw[: -len("python.exe")] + "pythonw.exe"
        if os.path.exists(cand):
            pyw = cand
    return '"%s" "%s"' % (pyw, os.path.abspath(__file__))


def _run_value(name):
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            return winreg.QueryValueEx(k, name)[0]
    except Exception:
        return None


def startup_enabled():
    return _run_value(RUN_NAME) is not None


def set_startup(enable, name=RUN_NAME):
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if enable:
                winreg.SetValueEx(k, name, 0, winreg.REG_SZ, _startup_command())
            else:
                try:
                    winreg.DeleteValue(k, name)
                except FileNotFoundError:
                    pass
        return True
    except Exception:
        return False


def replace_old_startup():
    """예전 바탕화면 메모가 시작 프로그램에 있으면 이 앱으로 바꿔 단다. 바꿨으면 True."""
    if _run_value(OLD_RUN_NAME) is None:
        return False
    set_startup(False, OLD_RUN_NAME)
    set_startup(True)
    return True


# ── 저장해 둔 창 위치가 지금 화면에 보이는가 ──────────────

def visible_on_monitor(x, y, w, h):
    """머리글 부분이 어느 모니터에든 걸쳐 보이면 True, 아니면 False.
    윈도우가 아니라 알 수 없으면 None.
    (보조 모니터는 주 모니터 밖 좌표 — 음수거나 주 모니터 폭보다 큼 — 에 있으므로
     주 모니터 크기만으로 판단하면 보조 모니터에 둔 창이 엉뚱한 곳으로 끌려온다)"""
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
    except Exception:
        return None
    try:
        user32.MonitorFromRect.restype = wintypes.HANDLE
        user32.MonitorFromRect.argtypes = [ctypes.POINTER(wintypes.RECT), wintypes.DWORD]
        head = wintypes.RECT(int(x) + 20, int(y), int(x) + max(40, int(w) - 20), int(y) + 24)
        MONITOR_DEFAULTTONULL = 0
        return bool(user32.MonitorFromRect(ctypes.byref(head), MONITOR_DEFAULTTONULL))
    except Exception:
        return None


# ── 작업 표시줄에서 숨기기 ────────────────────────────────

def hide_from_taskbar(root):
    """바탕화면 메모처럼 쓰도록 작업 표시줄과 Alt+Tab 목록에서 뺀다 (윈도우 전용).
    '도구 창' 표시를 달고, 작업 표시줄이 다시 읽도록 한 번 숨겼다 보여준다."""
    try:
        import ctypes
        from ctypes import wintypes
        user32 = ctypes.windll.user32
    except Exception:
        return
    try:
        GA_ROOT, GWL_EXSTYLE = 2, -20
        WS_EX_TOOLWINDOW, WS_EX_APPWINDOW = 0x00000080, 0x00040000
        SW_HIDE, SW_SHOWNOACTIVATE = 0, 4
        get = getattr(user32, "GetWindowLongPtrW", user32.GetWindowLongW)
        put = getattr(user32, "SetWindowLongPtrW", user32.SetWindowLongW)
        get.restype = ctypes.c_ssize_t
        get.argtypes = [wintypes.HWND, ctypes.c_int]
        put.restype = ctypes.c_ssize_t
        put.argtypes = [wintypes.HWND, ctypes.c_int, ctypes.c_ssize_t]
        user32.GetAncestor.restype = wintypes.HWND
        user32.GetAncestor.argtypes = [wintypes.HWND, ctypes.c_uint]

        root.update_idletasks()
        hwnd = user32.GetAncestor(root.winfo_id(), GA_ROOT)
        if not hwnd:
            return
        style = get(hwnd, GWL_EXSTYLE)
        new = (style | WS_EX_TOOLWINDOW) & ~WS_EX_APPWINDOW
        if new != style:
            put(hwnd, GWL_EXSTYLE, new)
            user32.ShowWindow(hwnd, SW_HIDE)
            user32.ShowWindow(hwnd, SW_SHOWNOACTIVATE)
    except Exception:
        pass


# ── 한 번만 실행 ──────────────────────────────────────────

def claim_single_instance(on_show):
    """이미 켜져 있으면 그 창을 앞으로 불러오고 False. 처음이면 True."""
    try:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.bind(("127.0.0.1", INSTANCE_PORT))
        srv.listen(2)
    except OSError:
        try:
            c = socket.create_connection(("127.0.0.1", INSTANCE_PORT), timeout=2)
            c.sendall(b"show")
            c.close()
        except OSError:
            pass
        return False

    def serve():
        while True:
            try:
                conn, _ = srv.accept()
                conn.close()
                on_show()
            except OSError:
                return

    threading.Thread(target=serve, daemon=True).start()
    return True


# ── 트레이 아이콘 ─────────────────────────────────────────

class Tray(object):
    """작업 표시줄 오른쪽 알림 영역(^)의 작은 아이콘.
    누르면 메모를 앞으로, 오른쪽 클릭하면 메뉴.
    pystray 가 없으면(직접 .py 로 돌릴 때 등) 조용히 꺼진다."""

    def __init__(self, app):
        self.app = app
        self.icon = None
        try:
            import pystray
            from PIL import Image
        except Exception:
            return
        try:
            image = Image.open(resource("icon.ico"))
            image.load()
        except Exception:
            try:
                image = Image.new("RGBA", (64, 64), (59, 125, 234, 255))
            except Exception:
                return

        def post(kind):
            # 이 함수들은 트레이 쪽 스레드에서 불린다 → 화면 스레드로 넘긴다
            return lambda icon, item: app.events.put((kind,))

        M = pystray.MenuItem
        menu = pystray.Menu(
            M("메모 열기", post("show"), default=True),
            M("트레이로 숨기기", post("hide"), visible=lambda item: not app.hidden),
            pystray.Menu.SEPARATOR,
            M("지금 동기화", post("sync"), visible=lambda item: app.account.connected()),
            M("보관함", post("archive")),
            M("설정", post("settings")),
            pystray.Menu.SEPARATOR,
            M("종료", post("quit")),
        )
        try:
            self.icon = pystray.Icon("StickyTodo", image, APP_TITLE, menu)
            threading.Thread(target=self.icon.run, daemon=True).start()
        except Exception:
            self.icon = None

    def available(self):
        return self.icon is not None

    def notify(self, text):
        try:
            if self.icon is not None:
                self.icon.notify(text, APP_TITLE)
        except Exception:
            pass

    def stop(self):
        try:
            if self.icon is not None:
                self.icon.stop()
        except Exception:
            pass


# ── 작은 위젯들 ───────────────────────────────────────────

class CheckBox(tk.Canvas):
    def __init__(self, master, pal, size, checked=False, command=None, bg=None):
        tk.Canvas.__init__(self, master, width=size, height=size, bg=bg or pal["bg"],
                           highlightthickness=0, bd=0, cursor="hand2")
        self.pal = pal
        self.size = size
        self.checked = checked
        self.command = command
        self.bind("<Button-1>", lambda e: command and command())
        self.redraw()

    def set_bg(self, color):
        self.configure(bg=color)
        self.redraw()

    def redraw(self):
        self.delete("all")
        s = self.size
        a, b = s * 0.17, s * 0.89
        if self.checked:
            self.create_rectangle(a, a, b, b, outline=self.pal["accent"], fill=self.pal["accent"])
            self.create_line(s * 0.33, s * 0.55, s * 0.47, s * 0.69, fill="white",
                             width=max(2, s // 9), capstyle="round")
            self.create_line(s * 0.47, s * 0.69, s * 0.72, s * 0.36, fill="white",
                             width=max(2, s // 9), capstyle="round")
        else:
            self.create_rectangle(a, a, b, b, outline=self.pal["check"], fill=self["bg"])


class Switch(tk.Canvas):
    """설정 화면의 켜고 끄는 단추"""

    def __init__(self, master, pal, value, command, bg):
        tk.Canvas.__init__(self, master, width=36, height=20, bg=bg,
                           highlightthickness=0, bd=0, cursor="hand2")
        self.pal = pal
        self.value = value
        self.command = command
        self.bind("<Button-1>", self._click)
        self.redraw()

    def _click(self, _e=None):
        self.value = not self.value
        self.redraw()
        self.command(self.value)

    def redraw(self):
        self.delete("all")
        on = self.value
        fill = self.pal["accent"] if on else self.pal["check"]
        self.create_oval(2, 2, 18, 18, fill=fill, outline=fill)
        self.create_oval(18, 2, 34, 18, fill=fill, outline=fill)
        self.create_rectangle(10, 2, 26, 18, fill=fill, outline=fill)
        x = 26 if on else 10
        self.create_oval(x - 6, 4, x + 6, 16, fill="white", outline="white")


def flat_button(parent, text, command, pal, fg=None, bg=None, font=None, padx=10, pady=4):
    bg = bg or pal["button"]
    b = tk.Label(parent, text=text, bg=bg, fg=fg or pal["text"], font=font,
                 cursor="hand2", padx=padx, pady=pady)
    b.bind("<Button-1>", lambda e: command())
    b.bind("<Enter>", lambda e: b.configure(bg=pal["edge_hover"]))
    b.bind("<Leave>", lambda e: b.configure(bg=bg))
    return b


# ──────────────────────────────────────────────────────────────
# 본 창
# ──────────────────────────────────────────────────────────────

class App(object):

    def __init__(self, root):
        self.root = root
        self.store = st.Store()
        self.account = ds.Account()
        self.events = queue.Queue()      # 다른 스레드 → 화면 스레드
        self.rows = []
        self.collapsed = False
        self._drag = None
        self._rs = None
        self._today = _dt.date.today()
        self._focus_after = None
        self._view_dirty = False
        self._sync_job = None
        self._syncing = False
        self._sync_again = False
        self._last_synced_sig = None
        self.settings_win = None
        self.archive_win = None
        self.edit_win = None
        self.hidden = False              # 트레이로 숨긴 상태
        self.tray = Tray(self)

        self._build_window()
        self.build_ui()

        self.root.after(10, lambda: hide_from_taskbar(self.root))
        self.root.after(60, self._restore_position)
        self.root.after(200, self._first_run)
        self.root.after(150, self._pump)
        self.root.after(TICK_MS, self._tick)
        self.root.after(1500, lambda: self.request_sync(0))
        self.root.after(SYNC_EVERY_MS, self._periodic_sync)

    # ── 모양 ─────────────────────────────────────────────

    def _palette(self):
        theme = self.store.setting("theme")
        dark = theme == st.THEME_DARK or (theme == st.THEME_AUTO and system_dark())
        return DARK if dark else LIGHT

    def _fonts(self):
        n = self.store.font_size()
        self.font = (FONT_FAMILY, n)
        self.font_done = (FONT_FAMILY, n, "overstrike")
        self.font_small = (FONT_FAMILY, max(8, n - 1))
        self.font_title = (FONT_FAMILY, n, "bold")
        self.font_big = (FONT_FAMILY, n + 2, "bold")

    def _build_window(self):
        r = self.root
        r.title(APP_TITLE)
        r.overrideredirect(True)
        w = self.store.window
        width = max(MIN_W, int(w.get("w") or st.DEFAULT_W))
        height = max(MIN_H, int(w.get("h") or st.DEFAULT_H))
        x, y = w.get("x"), w.get("y")
        self._keep_saved_pos = False
        if x is not None and y is not None:
            x, y = int(x), int(y)
            on = visible_on_monitor(x, y, width, height)
            if on is None:
                # 윈도우가 아닐 때: 주 화면 밖으로 나가지 않게만
                x = max(0, min(x, r.winfo_screenwidth() - 60))
                y = max(0, min(y, r.winfo_screenheight() - 40))
            elif not on:
                # 그 모니터가 지금 없다 (떼어냈거나 아직 인식 전).
                # 기본 자리에 띄우되, 저장된 자리는 지우지 않는다 — 모니터를 다시 꽂으면 돌아간다.
                self._keep_saved_pos = True
                x = y = None
        if x is None or y is None:
            x = r.winfo_screenwidth() - width - 60
            y = 80
        self._start_geometry = "%dx%d+%d+%d" % (width, height, x, y)
        r.geometry(self._start_geometry)
        self.apply_window_settings()

    def apply_window_settings(self):
        r = self.root
        r.attributes("-topmost", bool(self.store.setting("topmost")))
        try:
            r.attributes("-alpha", self.store.opacity())
        except tk.TclError:
            pass

    def build_ui(self):
        """테마·글자 크기가 바뀌면 통째로 다시 만든다"""
        for w in self.root.winfo_children():
            if not isinstance(w, tk.Toplevel):
                w.destroy()
        self.pal = p = self._palette()
        self._fonts()
        self.root.configure(bg=p["border"])

        outer = tk.Frame(self.root, bg=p["bg"])
        outer.pack(fill="both", expand=True, padx=1, pady=1)
        self.outer = outer

        head = tk.Frame(outer, bg=p["head"], height=self.store.font_size() * 3)
        head.pack(fill="x", side="top")
        head.pack_propagate(False)
        self.head = head

        self.title_lbl = tk.Label(head, text=HEADER_TITLE, bg=p["head"], fg=p["text"],
                                  font=self.font_title, anchor="w")
        self.title_lbl.pack(side="left", padx=(10, 4))
        self.count_lbl = tk.Label(head, text="", bg=p["head"], fg=p["muted"], font=self.font_small)
        self.count_lbl.pack(side="left")
        self.sync_lbl = tk.Label(head, text="", bg=p["head"], fg=p["muted"],
                                 font=self.font_small, cursor="hand2")
        self.sync_lbl.pack(side="left", padx=(6, 0))
        self.sync_lbl.bind("<Button-1>", lambda e: self.open_settings())

        self._head_btn(head, "✕", self.quit, p["over"])
        if self.tray.available():
            self._head_btn(head, "–", self.minimize, p["muted"])
        self._head_btn(head, "▾", self.toggle_collapse, p["muted"])
        self._head_btn(head, "≡", self.show_menu, p["muted"])

        for w in (head, self.title_lbl, self.count_lbl):
            w.bind("<ButtonPress-1>", self._drag_start)
            w.bind("<B1-Motion>", self._drag_move)
            w.bind("<ButtonRelease-1>", self._drag_end)
            w.bind("<Double-Button-1>", lambda e: self.toggle_collapse())
            w.bind("<Button-3>", self.show_menu)

        tk.Frame(outer, bg=p["border"], height=1).pack(fill="x")

        # 본문은 하단 바를 먼저 배치한 뒤에 pack 한다. 창을 줄여도 하단 바가 남도록.
        self.body = tk.Frame(outer, bg=p["bg"])
        self.canvas = tk.Canvas(self.body, bg=p["bg"], highlightthickness=0, bd=0,
                                width=10, height=10)
        self.canvas.pack(side="left", fill="both", expand=True)
        self.scroll = tk.Scrollbar(self.body, orient="vertical", command=self.canvas.yview,
                                   width=10, troughcolor=p["bg"], bd=0, highlightthickness=0)
        self.canvas.configure(yscrollcommand=self._on_scroll)
        self.list_frame = tk.Frame(self.canvas, bg=p["bg"])
        self._win = self.canvas.create_window((0, 0), window=self.list_frame, anchor="nw")
        self.list_frame.bind("<Configure>",
                             lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfig(self._win, width=e.width))
        self.canvas.bind("<Double-Button-1>", lambda e: self.add_item())
        # 빈 곳을 누르면 입력칸에서 빠져나와 저장되게
        self.canvas.bind("<Button-1>", lambda e: self.root.focus_set())
        self.root.bind_all("<MouseWheel>", self._on_wheel)

        foot = tk.Frame(outer, bg=p["bg"])
        foot.pack(fill="x", side="bottom")
        tk.Frame(foot, bg=p["divider"], height=1).pack(fill="x")
        add = tk.Label(foot, text="＋  할 일 추가", bg=p["bg"], fg=p["muted"],
                       font=self.font_small, cursor="hand2", anchor="w")
        add.pack(side="left", padx=10, pady=6)
        add.bind("<Button-1>", lambda e: self.add_item())
        add.bind("<Enter>", lambda e: add.configure(fg=p["accent"]))
        add.bind("<Leave>", lambda e: add.configure(fg=p["muted"]))
        self.foot = foot

        if not self.collapsed:
            self.body.pack(fill="both", expand=True)
        else:
            foot.pack_forget()

        self._build_edges()
        self.root.bind("<Control-n>", lambda e: self.add_item())
        self.root.bind("<Escape>", lambda e: self.root.focus_set())
        self.refresh(force=True)
        self.paint_sync()

    def _head_btn(self, parent, text, cmd, color):
        p = self.pal
        b = tk.Label(parent, text=text, bg=p["head"], fg=color, font=(FONT_FAMILY, 9),
                     cursor="hand2", width=3)
        b.pack(side="right", fill="y")
        b.bind("<Button-1>", lambda e: cmd(e) if cmd == self.show_menu else cmd())
        b.bind("<Enter>", lambda e: b.configure(bg=p["button"]))
        b.bind("<Leave>", lambda e: b.configure(bg=p["head"]))
        return b

    def _on_scroll(self, first, last):
        if float(first) <= 0.0 and float(last) >= 1.0:
            self.scroll.pack_forget()
        else:
            self.scroll.pack(side="right", fill="y", padx=(0, RESIZE_EDGE))
        self.scroll.set(first, last)

    def _on_wheel(self, event):
        # 다른 창(설정 등) 위에서 굴린 건 무시
        try:
            if event.widget.winfo_toplevel() is not self.root:
                return
            self.canvas.yview_scroll(int(-event.delta / 120), "units")
        except (tk.TclError, AttributeError):
            pass

    # ── 이동 / 크기 ──────────────────────────────────────

    def _drag_start(self, e):
        self._drag = (e.x_root - self.root.winfo_x(), e.y_root - self.root.winfo_y())

    def _drag_move(self, e):
        if self._drag:
            self.root.geometry("+%d+%d" % (e.x_root - self._drag[0], e.y_root - self._drag[1]))

    def _drag_end(self, _e):
        self._drag = None
        self.save_window(moved=True)

    # 창 가장자리 크기 조절.
    # 예전에는 가장자리에 얇은 틀을 깔았는데, 그 틀이 눈에 보였다.
    # 이제는 틀 없이 마우스가 가장자리 몇 픽셀 안에 있는지만 보고 판단한다.
    # 모든 위젯 맨 앞에 EDGE_TAG 를 붙여, 가장자리에서 누른 경우에는
    # 원래 위젯(입력칸·체크박스 등)보다 먼저 가로채서 크기 조절로 쓴다.

    def _build_edges(self):
        r = self.root
        r.bind_class(EDGE_TAG, "<Motion>", self._edge_motion)
        r.bind_class(EDGE_TAG, "<Leave>", self._edge_leave)
        r.bind_class(EDGE_TAG, "<ButtonPress-1>", self._edge_press)
        r.bind_class(EDGE_TAG, "<B1-Motion>", self._edge_drag)
        r.bind_class(EDGE_TAG, "<ButtonRelease-1>", self._edge_release)
        self._cursor_saved = None
        self._tag_tree(r)

    def _tag_tree(self, w):
        try:
            tags = w.bindtags()
            if EDGE_TAG not in tags:
                w.bindtags((EDGE_TAG,) + tuple(tags))
            for c in w.winfo_children():
                if not isinstance(c, (tk.Toplevel, tk.Menu)):
                    self._tag_tree(c)
        except tk.TclError:
            pass

    def _edge_mode(self, e):
        """마우스가 있는 가장자리. 가장자리가 아니면 None"""
        r = self.root
        try:
            if e.widget.winfo_toplevel() is not r:
                return None
            x = e.x_root - r.winfo_rootx()
            y = e.y_root - r.winfo_rooty()
            w, h = r.winfo_width(), r.winfo_height()
        except (tk.TclError, AttributeError):
            return None
        E, C = RESIZE_EDGE, RESIZE_CORNER
        if x < 0 or y < 0 or x >= w or y >= h:
            return None
        if self.collapsed:   # 접혀 있을 때는 가로만
            return "w" if x < E else ("e" if x >= w - E else None)
        v = "n" if y < C else ("s" if y >= h - C else "")
        hz = "w" if x < C else ("e" if x >= w - C else "")
        if v and hz:
            return v + hz            # 모서리
        if x < E:
            return "w"
        if x >= w - E:
            return "e"
        if y < E:
            return "n"
        if y >= h - E:
            return "s"
        return None

    def _set_cursor(self, widget, mode):
        saved = self._cursor_saved
        if saved and (saved[0] is not widget or mode is None):
            try:
                saved[0].configure(cursor=saved[1])
            except tk.TclError:
                pass
            self._cursor_saved = saved = None
        if mode is None:
            return
        try:
            if saved is None:
                self._cursor_saved = (widget, widget.cget("cursor"))
            try:
                widget.configure(cursor=EDGE_CURSORS[mode])
            except tk.TclError:
                # size_nw_se 같은 모서리 커서는 윈도우에만 있다
                widget.configure(cursor="fleur")
        except tk.TclError:
            pass

    def _edge_motion(self, e):
        if self._rs is None:
            self._set_cursor(e.widget, self._edge_mode(e))

    def _edge_leave(self, e):
        if self._rs is None:
            self._set_cursor(e.widget, None)

    def _edge_press(self, e):
        mode = self._edge_mode(e)
        if mode is None:
            return None
        self._resize_start(e, mode)
        return "break"

    def _edge_drag(self, e):
        if self._rs is None:
            return None
        self._resize_move(e)
        return "break"

    def _edge_release(self, e):
        if self._rs is None:
            return None
        self._resize_end(e)
        self._set_cursor(e.widget, self._edge_mode(e))
        return "break"

    def _resize_start(self, e, mode):
        self._rs = dict(mode=mode, mx=e.x_root, my=e.y_root,
                        w=self.root.winfo_width(), h=self.root.winfo_height(),
                        x=self.root.winfo_x(), y=self.root.winfo_y())

    def _resize_move(self, e):
        s = self._rs
        if not s:
            return
        m = s["mode"]
        dx, dy = e.x_root - s["mx"], e.y_root - s["my"]
        x, y, w, h = s["x"], s["y"], s["w"], s["h"]
        if "e" in m:
            w = s["w"] + dx
        if "w" in m:
            w = s["w"] - dx
            x = s["x"] + dx
        if "s" in m and not self.collapsed:
            h = s["h"] + dy
        if "n" in m and not self.collapsed:
            h = s["h"] - dy
            y = s["y"] + dy
        if w < MIN_W:
            if "w" in m:
                x = s["x"] + s["w"] - MIN_W
            w = MIN_W
        if h < MIN_H and not self.collapsed:
            if "n" in m:
                y = s["y"] + s["h"] - MIN_H
            h = MIN_H
        self.root.geometry("%dx%d+%d+%d" % (w, h, x, y))

    def _resize_end(self, _e=None):
        self._rs = None
        self.save_window(moved=True)

    def save_window(self, moved=False):
        if self.hidden:
            self.store.save()
            return
        if moved:
            self._keep_saved_pos = False
        new = dict(w=self.root.winfo_width())
        if not self.collapsed:
            new["h"] = self.root.winfo_height()
        if not getattr(self, "_keep_saved_pos", False):
            new.update(x=self.root.winfo_x(), y=self.root.winfo_y())
        self.store.window.update(new)
        self.store.save()

    def _restore_saved_spot(self):
        """숨겼다 다시 띄울 때 원래 자리로"""
        w = self.store.window
        try:
            if w.get("x") is not None and w.get("y") is not None:
                self.root.geometry("+%d+%d" % (int(w["x"]), int(w["y"])))
        except (tk.TclError, ValueError):
            pass

    def _restore_position(self):
        """창이 화면에 뜬 뒤 한 번 더 제자리로. (윈도우에서 테두리 없는 창은
        처음 띄울 때 지정한 위치를 무시하는 경우가 있어 확실히 해 둔다)"""
        try:
            want = self._start_geometry.split("+", 1)[1]
            if "%d+%d" % (self.root.winfo_x(), self.root.winfo_y()) != want:
                self.root.geometry("+" + want)
        except (tk.TclError, AttributeError, IndexError):
            pass

    def reset_size(self):
        if self.collapsed:
            self.toggle_collapse()
        x = max(0, min(self.root.winfo_x(), self.root.winfo_screenwidth() - st.DEFAULT_W))
        y = max(0, min(self.root.winfo_y(), self.root.winfo_screenheight() - st.DEFAULT_H))
        self.root.geometry("%dx%d+%d+%d" % (st.DEFAULT_W, st.DEFAULT_H, x, y))
        self.root.after(50, lambda: self.save_window(moved=True))

    def toggle_collapse(self):
        self.collapsed = not self.collapsed
        if self.collapsed:
            self.commit_focused()
            self._h_before = self.root.winfo_height()
            self.body.pack_forget()
            self.foot.pack_forget()
            self.root.geometry("%dx%d" % (self.root.winfo_width(), self.head.winfo_height() + 3))
        else:
            self.foot.pack(fill="x", side="bottom")
            self.body.pack(fill="both", expand=True)
            h = max(MIN_H, getattr(self, "_h_before", None) or self.store.window.get("h") or st.DEFAULT_H)
            self.root.geometry("%dx%d" % (self.root.winfo_width(), h))
            self.refresh()

    def minimize(self):
        """트레이로 숨긴다. 알림 영역 아이콘을 누르면 다시 나온다."""
        if not self.tray.available():
            return
        self.commit_focused()
        try:
            self.save_window()
        except tk.TclError:
            pass
        self.hidden = True
        self.root.withdraw()
        if not self.store.setting("tray_hint_shown"):
            self.store.set_setting("tray_hint_shown", True)
            self.tray.notify("메모를 트레이로 숨겼습니다. 작업 표시줄 오른쪽 ^ 의 아이콘을 누르면 다시 열립니다.")

    def show_window(self):
        """트레이 아이콘을 누르거나 두 번째로 실행했을 때 — 숨어 있던 창을 앞으로"""
        was_hidden = self.hidden
        self.hidden = False
        self.root.deiconify()
        hide_from_taskbar(self.root)
        if was_hidden:
            self.root.after(30, self._restore_saved_spot)
        self.root.lift()
        self.root.attributes("-topmost", True)
        if not self.store.setting("topmost"):
            self.root.after(300, lambda: self.root.attributes("-topmost", False))

    # ── 메뉴 ─────────────────────────────────────────────

    def show_menu(self, event=None):
        m = tk.Menu(self.root, tearoff=0, font=self.font_small)
        m.add_command(label="보관함 열기  (%d)" % len(self.store.archive), command=self.open_archive)
        m.add_command(label="완료 항목 모두 보관", command=self.archive_all_done)
        m.add_separator()
        m.add_command(label="설정…", command=self.open_settings)
        if self.tray.available():
            m.add_command(label="트레이로 숨기기", command=self.minimize)
        if self.account.connected():
            m.add_command(label="지금 동기화", command=lambda: self.request_sync(0))
        m.add_separator()
        m.add_command(label="크기 초기화 (%d × %d)" % (st.DEFAULT_W, st.DEFAULT_H),
                      command=self.reset_size)
        m.add_command(label="저장 폴더 열기", command=self.open_folder)
        m.add_separator()
        m.add_command(label="종료", command=self.quit)
        try:
            if event is not None and getattr(event, "x_root", None):
                m.tk_popup(event.x_root, event.y_root)
            else:
                m.tk_popup(self.root.winfo_rootx() + self.root.winfo_width() - 90,
                           self.root.winfo_rooty() + 30)
        finally:
            m.grab_release()

    def open_folder(self):
        try:
            os.startfile(st.data_dir())
        except Exception:
            pass

    # ── 목록 그리기 ──────────────────────────────────────

    def _focus(self):
        # 메뉴가 떠 있을 때 focus_get 이 KeyError 를 내는 tk 버그가 있다
        try:
            return self.root.focus_get()
        except Exception:
            return None

    def _editing(self):
        """목록 안의 입력칸에서 글자를 고치는 중인가"""
        w = self._focus()
        try:
            return w is not None and str(w).startswith(str(self.list_frame))
        except Exception:
            return False

    def refresh(self, force=False):
        if not force and self._editing():
            self._view_dirty = True   # 다 쓰고 나면 다시 그린다
            return
        self._view_dirty = False
        try:
            top = self.canvas.yview()[0]
        except tk.TclError:
            top = 0
        for w in self.list_frame.winfo_children():
            w.destroy()
        self.rows = []

        today = _dt.date.today()
        items = self.store.sorted_items()
        for t in items:
            self.rows.append(self._make_row(t, today))
        if not items:
            empty = tk.Label(self.list_frame, text="할 일이 없습니다\n두 번 눌러 추가", bg=self.pal["bg"],
                             fg=self.pal["nodate"], font=self.font_small, justify="center")
            empty.pack(pady=24)
            empty.bind("<Double-Button-1>", lambda e: self.add_item())

        left = self.store.remaining()
        self.count_lbl.configure(text=str(left) if left else "")

        if self._focus_after:
            r = self._row(self._focus_after)
            if r:
                r["te"].focus_set()
            self._focus_after = None

        self.list_frame.update_idletasks()
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))
        self.canvas.yview_moveto(top)
        self._tag_tree(self.list_frame)

    def _date_style(self):
        return self.store.setting("date_mode"), bool(self.store.setting("weekday"))

    def _make_row(self, t, today):
        p = self.pal
        pad = self.store.spacing()
        size = self.store.font_size() * 2
        bg = p["bg"]
        row = tk.Frame(self.list_frame, bg=bg)
        row.pack(fill="x")

        cb = CheckBox(row, p, size, checked=t.done, command=lambda i=t.id: self.toggle_done(i))
        cb.grid(row=0, column=0, padx=(8, 0), pady=pad + 1)

        star = tk.Label(row, text="★" if t.star else "☆", bg=bg,
                        fg=(p["star"] if t.star else bg), font=self.font_small, cursor="hand2")
        star.grid(row=0, column=1, padx=(2, 0))
        star.bind("<Button-1>", lambda e, i=t.id: self.toggle_star(i))

        tvar = tk.StringVar(value=t.text)
        te = tk.Entry(row, textvariable=tvar, bd=0, highlightthickness=0, bg=bg,
                      fg=(p["done"] if t.done else p["text"]),
                      font=(self.font_done if t.done else self.font),
                      insertbackground=p["text"], disabledbackground=bg)
        te.grid(row=0, column=2, sticky="ew", padx=(2, 6), pady=pad + 1)

        d = t.local_date(today)
        mode, wd = self._date_style()
        label = core.date_label(t, today, mode, wd)
        color = {"past": p["over"], "today": p["accent"], "future": p["muted"],
                 "none": p["nodate"]}[core.date_state(d, today)]
        dvar = tk.StringVar(value=label)
        de = tk.Entry(row, textvariable=dvar, bd=0, highlightthickness=0, bg=bg,
                      fg=(p["done"] if t.done else color), font=self.font_small,
                      width=max(6, min(16, len(label) + 1)), justify="right",
                      insertbackground=p["text"])
        de.grid(row=0, column=3, padx=(0, 2), pady=pad + 1)

        more = tk.Label(row, text="⋯", bg=bg, fg=bg, font=self.font_small, cursor="hand2")
        more.grid(row=0, column=4, padx=(0, 0))
        more.bind("<Button-1>", lambda e, i=t.id: self.open_editor(i))
        x = tk.Label(row, text="×", bg=bg, fg=bg, font=self.font_small, cursor="hand2", width=2)
        x.grid(row=0, column=5, padx=(0, 6))
        x.bind("<Button-1>", lambda e, i=t.id: self.delete_item(i))
        row.grid_columnconfigure(2, weight=1)

        rec = dict(id=t.id, task=t, frame=row, cb=cb, te=te, de=de, tvar=tvar, dvar=dvar,
                   date_editing=False, date_color=(p["done"] if t.done else color))

        def enter(_e=None):
            for w in (row, te, de, more, x, star):
                w.configure(bg=p["hover"])
            cb.set_bg(p["hover"])
            more.configure(fg=p["muted"])
            x.configure(fg=p["muted"])
            if not t.star:
                star.configure(fg=p["check"])

        def leave(_e=None):
            for w in (row, te, de, more, x, star):
                w.configure(bg=bg)
            cb.set_bg(bg)
            more.configure(fg=bg)
            x.configure(fg=bg)
            if not t.star:
                star.configure(fg=bg)

        for w in (row, te, de, more, x, cb, star):
            w.bind("<Enter>", enter, add="+")
            w.bind("<Leave>", leave, add="+")
            w.bind("<Button-3>", lambda e, i=t.id: self.row_menu(e, i))

        def date_focus_in(_e=None):
            # 고칠 때는 꾸밈 없이 날짜만 보여준다
            if not rec["date_editing"]:
                rec["date_editing"] = True
                dd = t.local_date()
                dvar.set(core.format_date(dd) if dd else t.date)
                de.configure(fg=p["text"], width=9)
                de.select_range(0, "end")

        de.bind("<FocusIn>", date_focus_in)
        te.bind("<Return>", lambda e, i=t.id: self._commit(i, True))
        te.bind("<FocusOut>", lambda e, i=t.id: self._commit(i, False))
        te.bind("<Down>", lambda e, i=t.id: self._move_focus(i, 1))
        te.bind("<Up>", lambda e, i=t.id: self._move_focus(i, -1))
        te.bind("<Tab>", lambda e: (de.focus_set(), "break")[1])
        de.bind("<Return>", lambda e, i=t.id: self._commit(i, True))
        de.bind("<FocusOut>", lambda e, i=t.id: self._commit(i, True))
        de.bind("<Escape>", lambda e, i=t.id: self._cancel_date(i))
        return rec

    def _row(self, tid):
        for r in self.rows:
            if r["id"] == tid:
                return r
        return None

    def _cancel_date(self, tid):
        r = self._row(tid)
        if r:
            r["date_editing"] = False
            r["dvar"].set(r["task"].date)
            self.root.focus_set()

    def _commit(self, tid, rebuild):
        r = self._row(tid)
        if not r:
            return
        t = self.store.find(tid)
        if t is None:
            return
        try:
            text = r["tvar"].get()
            date_raw = r["dvar"].get() if r["date_editing"] else t.date
        except tk.TclError:
            return
        r["date_editing"] = False
        if not text.strip() and not date_raw.strip():
            self.root.after_idle(lambda: self.delete_item(tid))
            return
        if self.store.set_text_date(tid, text, date_raw):
            self.store.run_auto_archive()
            self.request_sync()
            rebuild = True
        if rebuild or self._view_dirty:
            # 포커스가 다른 줄로 옮겨가는 중이면 그 줄 작업이 끝난 뒤 다시 그린다
            self.root.after_idle(self._refresh_after_commit)

    def _refresh_after_commit(self):
        if self._editing():
            self._view_dirty = True
            # 날짜 칸 꾸밈은 바로 돌려 놓는다
            focus = self._focus()
            for r in self.rows:
                if r["de"] is not focus and not r["date_editing"]:
                    t = self.store.find(r["id"])
                    if t is not None:
                        mode, wd = self._date_style()
                        r["dvar"].set(core.date_label(t, None, mode, wd))
                        r["de"].configure(fg=r["date_color"])
            return
        self.refresh(force=True)

    def commit_focused(self):
        w = self._focus()
        for r in self.rows:
            if w in (r["te"], r["de"]):
                self.root.focus_set()
                self._commit(r["id"], False)

    def _move_focus(self, tid, delta):
        ids = [r["id"] for r in self.rows]
        if tid in ids:
            i = ids.index(tid) + delta
            if 0 <= i < len(ids):
                self.rows[i]["te"].focus_set()
        return "break"

    def row_menu(self, event, tid):
        t = self.store.find(tid)
        if t is None:
            return
        m = tk.Menu(self.root, tearoff=0, font=self.font_small)
        m.add_command(label="자세히 편집 (반복 · 즐겨찾기)…", command=lambda: self.open_editor(tid))
        m.add_command(label="즐겨찾기 해제" if t.star else "즐겨찾기", command=lambda: self.toggle_star(tid))
        m.add_command(label="체크 해제" if t.done else "완료로 체크", command=lambda: self.toggle_done(tid))
        m.add_separator()
        m.add_command(label="삭제", command=lambda: self.delete_item(tid))
        try:
            m.tk_popup(event.x_root, event.y_root)
        finally:
            m.grab_release()
        return "break"

    # ── 할 일 바꾸기 ─────────────────────────────────────

    def changed(self):
        self.store.run_auto_archive()
        self.refresh(force=True)
        self.request_sync()
        if self.archive_win:
            self.archive_win.render()

    def add_item(self):
        if self.collapsed:
            self.toggle_collapse()
        # 이미 비어 있는 새 줄이 있으면 그걸 쓴다
        blank = next((t for t in self.store.items if t.blank()), None)
        t = blank or self.store.add("")
        self._focus_after = t.id
        self.refresh(force=True)

    def toggle_done(self, tid):
        self.commit_focused()
        self.store.toggle(tid)
        self.changed()

    def toggle_star(self, tid):
        self.commit_focused()
        self.store.toggle_star(tid)
        self.changed()

    def delete_item(self, tid):
        self.store.delete(tid)
        self.changed()

    def archive_all_done(self):
        self.commit_focused()
        self.store.archive_all_done()
        self.changed()

    def open_editor(self, tid=None):
        self.commit_focused()
        if self.edit_win is not None:
            try:
                self.edit_win.win.destroy()
            except tk.TclError:
                pass
        self.edit_win = EditDialog(self, tid)

    def open_archive(self):
        if self.archive_win is not None:
            try:
                self.archive_win.win.lift()
                return
            except tk.TclError:
                pass
        self.archive_win = ArchiveWindow(self)

    def open_settings(self):
        if self.settings_win is not None:
            try:
                self.settings_win.win.lift()
                return
            except tk.TclError:
                pass
        self.settings_win = SettingsWindow(self)

    # ── 동기화 ───────────────────────────────────────────

    def request_sync(self, delay_ms=SYNC_DELAY_MS):
        """바뀐 직후 부른다. 연달아 불러도 마지막 것 하나만 돈다."""
        if not self.account.connected() or self.account.need_reconnect:
            return
        if self._sync_job is not None:
            try:
                self.root.after_cancel(self._sync_job)
            except tk.TclError:
                pass
        self._sync_job = self.root.after(delay_ms, self._start_sync)

    def _start_sync(self):
        self._sync_job = None
        if self._syncing:
            self._sync_again = True
            return
        if not self.account.connected():
            return
        self._syncing = True
        self.paint_sync()
        snap = self.store.snapshot()

        def work():
            ok, msg, merged = ds.sync_once(self.account, snap)
            self.events.put(("synced", ok, msg, merged))

        threading.Thread(target=work, daemon=True).start()

    def _sync_done(self, ok, msg, merged):
        self._syncing = False
        if merged is not None:
            # 통신하는 동안 이 컴퓨터에서 또 바뀐 게 있을 수 있어 한 번 더 합친다
            final = core.merge(self.store.snapshot(), merged)
            changed = self.store.apply_merged(final)
            if changed:
                self.store.run_auto_archive()
                self.account.record(True, "동기화 완료 · 받아옴")
                self.refresh()
                if self.archive_win:
                    self.archive_win.render()
            if final.signature() != merged.signature():
                self._sync_again = True
            self._last_synced_sig = self.store.snapshot().signature()
        self.paint_sync()
        if self.settings_win:
            self.settings_win.paint_account()
        if self._sync_again:
            self._sync_again = False
            self.request_sync(1000)

    def _periodic_sync(self):
        self.request_sync(0)
        self.root.after(SYNC_EVERY_MS, self._periodic_sync)

    def paint_sync(self):
        """머리글의 작은 표시: 도는 중 ↻, 문제 있으면 !"""
        if not hasattr(self, "sync_lbl"):
            return
        a = self.account
        if not a.connected():
            text, color = "", self.pal["muted"]
        elif self._syncing:
            text, color = "↻", self.pal["muted"]
        elif a.need_reconnect or (a.last_at and not a.last_ok):
            text, color = "!", self.pal["over"]
        else:
            text, color = "", self.pal["muted"]
        try:
            self.sync_lbl.configure(text=text, fg=color)
        except tk.TclError:
            pass

    def connect_account(self, done):
        def work():
            ok, msg = ds.login(self.account)
            self.events.put(("login", ok, msg, done))
        threading.Thread(target=work, daemon=True).start()

    def disconnect_account(self):
        self.account.forget()
        self.paint_sync()

    # ── 주기 작업 ────────────────────────────────────────

    def _pump(self):
        """다른 스레드에서 넣은 일을 화면 스레드에서 처리한다"""
        try:
            while True:
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "synced":
                    self._sync_done(ev[1], ev[2], ev[3])
                elif kind == "login":
                    _, ok, msg, done = ev
                    if ok:
                        self.request_sync(0)
                    self.paint_sync()
                    if done:
                        done(ok, msg)
                elif kind == "show":
                    self.show_window()
                elif kind == "hide":
                    self.minimize()
                elif kind == "sync":
                    self.request_sync(0)
                elif kind == "archive":
                    self.open_archive()
                elif kind == "settings":
                    self.open_settings()
                elif kind == "quit":
                    self.quit()
                    return
        except queue.Empty:
            pass
        self.root.after(150, self._pump)

    def _tick(self):
        today = _dt.date.today()
        if today != self._today:
            self._today = today
            self.store.run_auto_archive(today)
            self.refresh()
            self.request_sync(2000)
        elif self._view_dirty and not self._editing():
            self.refresh()
        self.root.after(TICK_MS, self._tick)

    def _first_run(self):
        self.store.run_auto_archive()
        self.refresh()
        if not self.store.first_run:
            return
        self.store.save()   # 다음부터는 첫 실행이 아니다
        msgs = []
        n = self.store.old_data_count()
        if n:
            from tkinter import messagebox
            if messagebox.askyesno(
                    APP_TITLE,
                    "예전에 쓰던 바탕화면 메모의 할 일 %d건이 있습니다.\n이 앱으로 가져올까요?\n\n"
                    "구글 계정을 연결하면 폰 목록에도 함께 들어갑니다." % n,
                    parent=self.root):
                self.store.import_old()
                self.store.run_auto_archive()
                self._build_window()
                self.build_ui()
                hide_from_taskbar(self.root)
        if replace_old_startup():
            msgs.append("윈도우를 켤 때 예전 메모 대신 이 앱이 열리도록 바꿨습니다.")
        if msgs:
            from tkinter import messagebox
            messagebox.showinfo(APP_TITLE, "\n".join(msgs), parent=self.root)

    def quit(self):
        self.commit_focused()
        try:
            self.save_window()
        except tk.TclError:
            pass
        # 아직 안 올린 변경이 있으면 잠깐 기다렸다가 올리고 끈다
        if (self.account.connected() and not self.account.need_reconnect
                and self.store.snapshot().signature() != self._last_synced_sig):
            self.root.withdraw()
            snap = self.store.snapshot()
            t = threading.Thread(target=lambda: ds.sync_once(self.account, snap), daemon=True)
            t.start()
            t.join(10)
        self.tray.stop()
        self.root.destroy()


# ──────────────────────────────────────────────────────────────
# 보조 창 공통
# ──────────────────────────────────────────────────────────────

class Popup(object):
    def __init__(self, app, title, w, h, modal=False):
        self.app = app
        self.pal = app.pal
        win = tk.Toplevel(app.root)
        self.win = win
        win.title(title)
        win.configure(bg=self.pal["bg"])
        try:
            win.iconbitmap(resource("icon.ico"))
        except tk.TclError:
            pass
        r = app.root
        x = r.winfo_x() - w - 12
        if x < 0:
            x = min(r.winfo_x() + r.winfo_width() + 12, r.winfo_screenwidth() - w)
        y = max(0, min(r.winfo_y(), r.winfo_screenheight() - h - 40))
        win.geometry("%dx%d+%d+%d" % (w, h, x, y))
        win.attributes("-topmost", bool(app.store.setting("topmost")))
        win.protocol("WM_DELETE_WINDOW", self.close)
        win.bind("<Escape>", lambda e: self.close())
        if modal:
            win.transient(app.root)
            win.grab_set()

    def close(self):
        try:
            self.win.grab_release()
        except tk.TclError:
            pass
        self.win.destroy()

    def label(self, parent, text, muted=False, bold=False, small=False, **kw):
        a = self.app
        font = a.font_title if bold else (a.font_small if small else a.font)
        return tk.Label(parent, text=text, bg=kw.pop("bg", self.pal["bg"]),
                        fg=self.pal["muted"] if muted else self.pal["text"], font=font, **kw)

    def scroller(self, parent):
        p = self.pal
        cv = tk.Canvas(parent, bg=p["bg"], highlightthickness=0)
        sb = tk.Scrollbar(parent, orient="vertical", command=cv.yview, width=10)
        cv.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        cv.pack(side="left", fill="both", expand=True)
        inner = tk.Frame(cv, bg=p["bg"])
        wid = cv.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>", lambda e: cv.configure(scrollregion=cv.bbox("all")))
        cv.bind("<Configure>", lambda e: cv.itemconfig(wid, width=e.width))

        def wheel(e):
            try:
                if e.widget.winfo_toplevel() is self.win:
                    cv.yview_scroll(int(-e.delta / 120), "units")
            except (tk.TclError, AttributeError):
                pass
        self.win.bind("<MouseWheel>", wheel)
        return inner


# ──────────────────────────────────────────────────────────────
# 자세히 편집 (반복 · 즐겨찾기)
# ──────────────────────────────────────────────────────────────

REPEAT_CHOICES = [(core.NONE, "없음"), (core.DUE, "기한 기준"), (core.DONE, "체크일 기준"),
                  (core.WEEK, "고정 요일")]


class EditDialog(Popup):
    def __init__(self, app, tid):
        Popup.__init__(self, app, "할 일 편집" if tid else "할 일 추가", 350, 440, modal=True)
        self.tid = tid
        t = app.store.find(tid) if tid else None
        self.t = t
        p = self.pal
        f = tk.Frame(self.win, bg=p["bg"])
        f.pack(fill="both", expand=True, padx=16, pady=14)

        self.label(f, "할 일", muted=True, small=True).pack(anchor="w")
        self.text = tk.Entry(f, bd=0, bg=p["field"], fg=p["text"], insertbackground=p["text"],
                             font=app.font, highlightthickness=1, highlightbackground=p["border"],
                             highlightcolor=p["accent"])
        self.text.pack(fill="x", ipady=5, pady=(2, 10))
        self.text.insert(0, t.text if t else "")

        self.label(f, "기한", muted=True, small=True).pack(anchor="w")
        self.date = tk.Entry(f, bd=0, bg=p["field"], fg=p["text"], insertbackground=p["text"],
                             font=app.font, highlightthickness=1, highlightbackground=p["border"],
                             highlightcolor=p["accent"])
        self.date.pack(fill="x", ipady=5, pady=(2, 2))
        if t:
            d = t.local_date()
            self.date.insert(0, core.format_date(d) if d else t.date)
        self.label(f, "9/18 · 9-18 · 26.9.18 · 오늘 · 내일 · +7  (비우면 기한 없음)",
                   muted=True, small=True, wraplength=310, justify="left").pack(anchor="w", pady=(0, 10))

        # 즐겨찾기
        self.star = bool(t.star) if t else False
        srow = tk.Frame(f, bg=p["bg"])
        srow.pack(fill="x", pady=(0, 10))
        self.star_lbl = tk.Label(srow, bg=p["bg"], font=app.font, cursor="hand2")
        self.star_lbl.pack(side="left")
        self.star_lbl.bind("<Button-1>", lambda e: self._toggle_star())
        self._paint_star()

        # 반복
        self.mode = t.repeatMode if (t and t.repeating()) else core.NONE
        self.days = tk.StringVar(value=str(t.repeatDays) if (t and t.repeatDays) else "7")
        self.mask = t.repeatWeekdays if t else 0
        self.label(f, "반복", muted=True, small=True).pack(anchor="w")
        mrow = tk.Frame(f, bg=p["bg"])
        mrow.pack(fill="x", pady=(2, 6))
        self.mode_btns = {}
        for key, name in REPEAT_CHOICES:
            b = tk.Label(mrow, text=name, font=app.font_small, cursor="hand2", padx=7, pady=3)
            b.pack(side="left", padx=(0, 4))
            b.bind("<Button-1>", lambda e, k=key: self._set_mode(k))
            self.mode_btns[key] = b

        self.detail = tk.Frame(f, bg=p["bg"])
        self.detail.pack(fill="x")
        # 며칠마다
        self.days_row = tk.Frame(self.detail, bg=p["bg"])
        vcmd = (self.win.register(lambda v: v == "" or (v.isdigit() and len(v) <= 3)), "%P")
        self.days_entry = tk.Entry(self.days_row, textvariable=self.days, width=4, bd=0,
                                   bg=p["field"], fg=p["text"], insertbackground=p["text"],
                                   font=app.font, justify="center", validate="key",
                                   validatecommand=vcmd, highlightthickness=1,
                                   highlightbackground=p["border"], highlightcolor=p["accent"])
        self.days_entry.pack(side="left", ipady=3)
        self.label(self.days_row, " 일마다").pack(side="left")
        # 요일
        self.week_row = tk.Frame(self.detail, bg=p["bg"])
        self.wd_btns = []
        for i, name in enumerate(core.WEEKDAY_SHORT):
            b = tk.Label(self.week_row, text=name, font=app.font_small, cursor="hand2",
                         width=3, pady=3)
            b.pack(side="left", padx=(0, 3))
            b.bind("<Button-1>", lambda e, i=i: self._toggle_wd(i))
            self.wd_btns.append(b)
        self.help = self.label(self.detail, "", muted=True, small=True, justify="left",
                               wraplength=300)
        self._paint_mode()

        # 단추
        brow = tk.Frame(self.win, bg=p["bg"])
        brow.pack(fill="x", side="bottom", padx=16, pady=(0, 14))
        flat_button(brow, "저장", self.save, p, fg="white", bg=p["accent"],
                    font=app.font_title).pack(side="right")
        flat_button(brow, "취소", self.close, p, font=app.font).pack(side="right", padx=6)
        if t:
            flat_button(brow, "삭제", self.delete, p, fg=p["over"], font=app.font).pack(side="left")

        self.win.bind("<Return>", lambda e: self.save())
        self.text.focus_set()

    def _toggle_star(self):
        self.star = not self.star
        self._paint_star()

    def _paint_star(self):
        p = self.pal
        if self.star:
            self.star_lbl.configure(text="★  즐겨찾기 — 맨 위에 표시", fg=p["star"])
        else:
            self.star_lbl.configure(text="☆  즐겨찾기 꺼짐", fg=p["muted"])

    def _set_mode(self, key):
        self.mode = key
        if key == core.WEEK and self.mask == 0:
            d = core.parse_date(self.date.get()) or _dt.date.today()
            self.mask = core.wd_bit(d)
        self._paint_mode()

    def _toggle_wd(self, i):
        self.mask ^= 1 << i
        self._paint_mode()

    def _paint_mode(self):
        p = self.pal
        for key, b in self.mode_btns.items():
            on = key == self.mode
            b.configure(bg=p["accent"] if on else p["button"], fg="white" if on else p["text"])
        self.days_row.pack_forget()
        self.week_row.pack_forget()
        self.help.pack_forget()
        if self.mode in (core.DUE, core.DONE):
            self.days_row.pack(anchor="w", pady=(2, 4))
            self.help.configure(text=(
                "기한이 지나면 다음 날 체크가 풀리고 기한이 n일 뒤로 옮겨집니다. 늦게 체크해도 주기가 밀리지 않습니다."
                if self.mode == core.DUE else
                "체크한 날로부터 n일이 지나면 다시 할 일로 돌아옵니다."))
            self.help.pack(anchor="w")
        elif self.mode == core.WEEK:
            self.week_row.pack(anchor="w", pady=(2, 4))
            for i, b in enumerate(self.wd_btns):
                on = self.mask & (1 << i)
                b.configure(bg=p["accent"] if on else p["button"], fg="white" if on else p["text"])
            self.help.configure(text="정해둔 요일마다 돌아옵니다. 반복 항목은 보관함으로 가지 않습니다.")
            self.help.pack(anchor="w")

    def save(self):
        text = self.text.get().strip()
        date = self.date.get().strip()
        if not text and not date:
            self.close()
            return
        mode = self.mode
        try:
            days = int(self.days.get() or 0)
        except ValueError:
            days = 0
        mask = self.mask
        if mode in (core.DUE, core.DONE) and days <= 0:
            mode = core.NONE
        if mode == core.WEEK and mask == 0:
            mode = core.NONE
        if mode != core.WEEK:
            mask = 0
        if mode not in (core.DUE, core.DONE):
            days = 0
        s = self.app.store
        if self.tid and s.find(self.tid):
            s.update(self.tid, text, date, mode, days, mask, self.star)
        else:
            s.add(text, date, mode, days, mask, self.star)
        self.close()
        self.app.changed()

    def delete(self):
        self.close()
        self.app.delete_item(self.tid)


# ──────────────────────────────────────────────────────────────
# 보관함
# ──────────────────────────────────────────────────────────────

class ArchiveWindow(Popup):
    def __init__(self, app):
        Popup.__init__(self, app, "보관함 — " + APP_TITLE, 360, 440)
        p = self.pal
        head = tk.Frame(self.win, bg=p["bg"])
        head.pack(fill="x", padx=14, pady=(12, 6))
        tk.Label(head, text="보관함", bg=p["bg"], fg=p["text"], font=app.font_big).pack(side="left")
        clear = tk.Label(head, text="전체 비우기", bg=p["bg"], fg=p["muted"],
                         font=app.font_small, cursor="hand2")
        clear.pack(side="right")
        clear.bind("<Button-1>", lambda e: self.clear())
        body = tk.Frame(self.win, bg=p["bg"])
        body.pack(fill="both", expand=True, padx=8, pady=(0, 10))
        self.inner = self.scroller(body)
        self.render()

    def close(self):
        self.app.archive_win = None
        Popup.close(self)

    def render(self):
        try:
            for w in self.inner.winfo_children():
                w.destroy()
        except tk.TclError:
            return
        p = self.pal
        a = self.app
        arch = a.store.archive
        if not arch:
            tk.Label(self.inner, text="보관된 할 일이 없습니다", bg=p["bg"], fg=p["muted"],
                     font=a.font_small).pack(pady=24)
        mode, wd = a._date_style()
        for t in arch:
            fr = tk.Frame(self.inner, bg=p["bg"])
            fr.pack(fill="x", pady=1)
            tk.Label(fr, text="✓" if t.done else "·", bg=p["bg"],
                     fg=p["accent"] if t.done else p["muted"], font=a.font_small,
                     width=2).pack(side="left")
            tk.Label(fr, text=t.text or "(내용 없음)", bg=p["bg"], fg=p["done"], font=a.font_small,
                     anchor="w").pack(side="left", fill="x", expand=True)
            tk.Label(fr, text=core.date_label(t, None, mode, wd), bg=p["bg"], fg=p["muted"],
                     font=a.font_small).pack(side="left", padx=6)
            rb = tk.Label(fr, text="복원", bg=p["bg"], fg=p["accent"], font=a.font_small,
                          cursor="hand2")
            rb.pack(side="left", padx=(0, 4))
            db = tk.Label(fr, text="삭제", bg=p["bg"], fg=p["over"], font=a.font_small,
                          cursor="hand2")
            db.pack(side="left", padx=(0, 6))
            rb.bind("<Button-1>", lambda e, i=t.id: self._restore(i))
            db.bind("<Button-1>", lambda e, i=t.id: self._remove(i))

    def _restore(self, tid):
        self.app.store.restore(tid)
        self.app.changed()

    def _remove(self, tid):
        self.app.store.remove_archived(tid)
        self.app.changed()

    def clear(self):
        from tkinter import messagebox
        if not self.app.store.archive:
            return
        if messagebox.askyesno("보관함", "보관함을 모두 비울까요?", parent=self.win):
            self.app.store.clear_archive()
            self.app.changed()


# ──────────────────────────────────────────────────────────────
# 설정
# ──────────────────────────────────────────────────────────────

class SettingsWindow(Popup):
    def __init__(self, app):
        Popup.__init__(self, app, "설정 — " + APP_TITLE, 380, 600)
        self.build()

    def close(self):
        self.app.settings_win = None
        Popup.close(self)

    def build(self):
        for w in self.win.winfo_children():
            w.destroy()
        self.pal = p = self.app.pal
        a = self.app
        s = a.store
        self.win.configure(bg=p["bg"])
        self.box = self.scroller(self.win)

        self.section("창")
        self.pick("창 순서", ["항상 앞 (기본)", "다른 창과 순서 바뀜"],
                  lambda: 0 if s.setting("topmost") else 1,
                  lambda i: self._set_topmost(i == 0))
        self.choice("창 불투명도", st.OPACITY_LABELS, "opacity", self._window_changed)
        self.choice("테마", st.THEME_LABELS, "theme", self._look_changed)
        self.choice("글자 크기", st.FONT_LABELS, "font", self._look_changed)
        self.choice("줄 간격", st.SPACING_LABELS, "spacing", lambda: a.refresh(force=True))
        self.toggle("윈도우 시작 시 실행", "", startup_enabled, lambda v: set_startup(v))

        self.section("날짜")
        self.choice("표시 방식", st.DATE_LABELS, "date_mode", lambda: self._dates_changed())
        self.toggle("요일 함께 표시", "예: 10/5(월)", lambda: s.setting("weekday"),
                    lambda v: (s.set_setting("weekday", v), self._dates_changed()))

        self.section("정리")
        self.choice("보관함 자동 비우기", st.ARCHIVE_KEEP_LABELS, "archive_keep",
                    lambda: a.changed())

        self.section("동기화")
        self.acct = tk.Frame(self.box, bg=p["bg"])
        self.acct.pack(fill="x", padx=16, pady=(4, 0))
        self.paint_account()

    # ── 줄 만들기 ────────────────────────────────────────

    def section(self, title):
        p = self.pal
        if self.box.winfo_children():
            tk.Frame(self.box, bg=p["divider"], height=1).pack(fill="x", pady=(10, 0))
        tk.Label(self.box, text=title, bg=p["bg"], fg=p["accent"], font=self.app.font_title,
                 anchor="w").pack(fill="x", padx=16, pady=(12, 2))

    def _row(self, title, sub):
        p = self.pal
        row = tk.Frame(self.box, bg=p["bg"])
        row.pack(fill="x", padx=16, pady=4)
        texts = tk.Frame(row, bg=p["bg"])
        texts.pack(side="left", fill="x", expand=True)
        tk.Label(texts, text=title, bg=p["bg"], fg=p["text"], font=self.app.font,
                 anchor="w").pack(fill="x")
        if sub:
            tk.Label(texts, text=sub, bg=p["bg"], fg=p["muted"], font=self.app.font_small,
                     anchor="w").pack(fill="x")
        return row

    def toggle(self, title, sub, get, set_):
        row = self._row(title, sub)
        Switch(row, self.pal, bool(get()), set_, self.pal["bg"]).pack(side="right")

    def choice(self, title, labels, key, after):
        """설정 값(번호)을 고르는 줄"""
        s = self.app.store

        def set_(i):
            s.set_setting(key, i)
            after()

        self.pick(title, labels, lambda: s._level(key, labels), set_)

    def pick(self, title, labels, get, set_):
        p = self.pal
        row = self._row(title, "")
        cur = max(0, min(len(labels) - 1, int(get())))
        val = tk.Label(row, text=labels[cur] + "  ▾", bg=p["button"], fg=p["text"],
                       font=self.app.font_small, cursor="hand2", padx=8, pady=3)
        val.pack(side="right")

        def choose(i):
            val.configure(text=labels[i] + "  ▾")
            set_(i)

        def popup(e):
            m = tk.Menu(self.win, tearoff=0, font=self.app.font_small)
            v = tk.IntVar(value=int(get()))
            for i, lab in enumerate(labels):
                m.add_radiobutton(label=lab, variable=v, value=i, command=lambda i=i: choose(i))
            try:
                m.tk_popup(e.x_root, e.y_root)
            finally:
                m.grab_release()

        val.bind("<Button-1>", popup)

    # ── 바뀐 설정 적용 ───────────────────────────────────

    def _set_topmost(self, v):
        self.app.store.set_setting("topmost", v)
        self._window_changed()

    def _window_changed(self):
        self.app.apply_window_settings()
        self.win.attributes("-topmost", bool(self.app.store.setting("topmost")))

    def _look_changed(self):
        self.app.build_ui()
        self.build()

    def _dates_changed(self):
        self.app.refresh(force=True)
        if self.app.archive_win:
            self.app.archive_win.render()

    # ── 구글 계정 ────────────────────────────────────────

    def paint_account(self):
        try:
            box = self.acct
            for w in box.winfo_children():
                w.destroy()
        except (tk.TclError, AttributeError):
            return
        p = self.pal
        a = self.app
        acc = a.account
        f = a.font
        if not ds.available():
            tk.Label(box, text="이 버전에는 구글 연결 정보가 들어 있지 않습니다.", bg=p["bg"],
                     fg=p["muted"], font=a.font_small, wraplength=320, justify="left",
                     anchor="w").pack(fill="x")
            return

        tk.Label(box, text="구글 계정", bg=p["bg"], fg=p["muted"], font=a.font_small,
                 anchor="w").pack(fill="x")
        tk.Label(box, text=(acc.email or "연결됨") if acc.connected() else "연결 안 됨",
                 bg=p["bg"], fg=p["text"], font=a.font_big, anchor="w").pack(fill="x", pady=(0, 6))

        if acc.connected():
            lt = time.localtime(acc.last_at)
            when = ("%d월 %d일 %02d:%02d" % (lt.tm_mon, lt.tm_mday, lt.tm_hour, lt.tm_min)) \
                if acc.last_at else "아직 한 적 없음"
            status = "동기화 중…" if a._syncing else (
                when + (" · " + acc.last_msg if acc.last_msg else ""))
            tk.Label(box, text="마지막 동기화", bg=p["bg"], fg=p["muted"], font=a.font_small,
                     anchor="w").pack(fill="x")
            tk.Label(box, text=status, bg=p["bg"],
                     fg=p["over"] if (acc.last_at and not acc.last_ok) else p["text"],
                     font=a.font_small, anchor="w", wraplength=330, justify="left").pack(fill="x")
            btns = tk.Frame(box, bg=p["bg"])
            btns.pack(fill="x", pady=(8, 0))
            if acc.need_reconnect:
                flat_button(btns, "다시 연결", self._connect, p, fg="white", bg=p["accent"],
                            font=f).pack(side="left")
            else:
                flat_button(btns, "지금 동기화", lambda: a.request_sync(0), p, fg=p["accent"],
                            font=f).pack(side="left")
            flat_button(btns, "연결 끊기", self._disconnect, p, fg=p["over"],
                        font=f).pack(side="left", padx=6)
        else:
            flat_button(box, "구글 계정 연결", self._connect, p, fg="white", bg=p["accent"],
                        font=f, pady=6).pack(fill="x")
            self.login_msg = tk.Label(box, text="", bg=p["bg"], fg=p["muted"], font=a.font_small,
                                      anchor="w", wraplength=330, justify="left")
            self.login_msg.pack(fill="x", pady=(4, 0))

        tk.Label(box, text=(
            "폰의 할 일 앱과 같은 구글 계정으로 연결하면 목록이 자동으로 맞춰집니다. "
            "목록은 드라이브의 앱 전용 숨김 공간에만 저장됩니다.\n\n"
            "바꾼 내용은 몇 초 안에 올라가고, 다른 기기의 변경은 켤 때와 5분마다 받아옵니다."),
            bg=p["bg"], fg=p["muted"], font=a.font_small, wraplength=330, justify="left",
            anchor="w").pack(fill="x", pady=(14, 10))

    def _connect(self):
        try:
            self.login_msg.configure(text="브라우저에서 구글 로그인을 마쳐 주세요…")
        except (tk.TclError, AttributeError):
            pass

        def done(ok, msg):
            self.paint_account()
            if not ok:
                try:
                    self.login_msg.configure(text=msg, fg=self.pal["over"])
                except (tk.TclError, AttributeError):
                    pass

        self.app.connect_account(done)

    def _disconnect(self):
        from tkinter import messagebox
        if messagebox.askyesno("동기화", "구글 계정 연결을 끊을까요?\n이 컴퓨터의 목록은 그대로 남습니다.",
                               parent=self.win):
            self.app.disconnect_account()
            self.paint_account()


# ──────────────────────────────────────────────────────────────

def main():
    try:
        from ctypes import windll
        windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass

    holder = {}
    if not claim_single_instance(lambda: holder["app"].events.put(("show",)) if "app" in holder else None):
        return

    root = tk.Tk()
    try:
        root.iconbitmap(resource("icon.ico"))
    except tk.TclError:
        pass
    holder["app"] = App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
