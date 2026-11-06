# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop — 새 버전 확인과 자동 업데이트.

exe 로 돌 때만 동작한다 (.py 로 직접 돌리면 아무것도 하지 않는다).

흐름
  1. 하루 두 번쯤 GitHub 의 desktop 릴리스에 있는 desktop-version.json(100바이트 남짓)을 읽어
     최신 빌드 번호를 본다. 이 exe 의 빌드 번호는 build_info.BUILD (빌드할 때 들어간다).
  2. 새 빌드가 있고 자동 업데이트가 켜져 있으면 새 exe 를 %APPDATA%\\StickyTodo\\update 에
     조용히 받아 두고, 크기와 SHA-256 지문이 맞는지 확인한다.
  3. 다음에 앱이 켜질 때(보통 윈도우 시작 때) 화면을 띄우기 전에 바꿔 끼운다.
       - 실행 중인 exe 는 지울 수는 없어도 이름은 바꿀 수 있다 → 지금 exe 를 .old 로 바꾸고
         그 자리에 새 exe 를 복사한 뒤 새 exe 를 켠다. 경로가 같으니 시작 프로그램 등록도 그대로다.
       - 새 exe 가 화면을 띄우고 '켜졌다' 표시를 남기면 옛 프로세스는 조용히 끝난다.
       - 새 exe 가 60초 안에 제대로 켜지지 않으면 원래 exe 로 되돌리고, 그 빌드는 다시 시도하지 않는다.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

from store import data_dir

try:
    from build_info import BUILD
except ImportError:
    BUILD = 0

REPO = "https://github.com/Seokhui-Jeong/sticky-todo"
VERSION_URL = REPO + "/releases/download/desktop/desktop-version.json"
EXE_URL = REPO + "/releases/download/desktop/sticky-todo.exe"
PAGE_URL = REPO + "#english"

CHECK_EVERY = 12 * 60 * 60          # 초
START_TIMEOUT = 60                  # 새 exe 가 이 안에 켜졌다고 알려야 한다

# 실행 인자
ARG_APPLY = "--apply-update"        # 자동 업데이트가 꺼져 있어도 이번엔 바꿔 끼운다
ARG_WAIT = "--wait-pid"             # 이 프로세스가 끝날 때까지 기다렸다가 시작
ARG_UPDATED = "--updated-from"      # 방금 업데이트로 켜졌다 (이전 빌드 번호)


def enabled():
    """exe 로 돌고 있고 빌드 번호를 알 때만 업데이트를 다룬다"""
    return bool(getattr(sys, "frozen", False)) and BUILD > 0


def _dir():
    path = os.path.join(data_dir(), "update")
    os.makedirs(path, exist_ok=True)
    return path


def _state_path():
    return os.path.join(_dir(), "state.json")


def load_state():
    try:
        with open(_state_path(), "r", encoding="utf-8") as f:
            o = json.load(f)
            return o if isinstance(o, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_state(o):
    tmp = _state_path() + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(o, f)
        os.replace(tmp, _state_path())
    except OSError:
        pass


def _exe_for(build):
    return os.path.join(_dir(), "sticky-todo-%d.exe" % build)


def _marker(build):
    return os.path.join(_dir(), "started-%d" % build)


# ── 상태 ──────────────────────────────────────────────────

def latest_build():
    return int(load_state().get("latest") or 0)


def available():
    """설치할 수 있는 새 빌드가 있는가 (적용에 실패한 빌드는 빼고)"""
    s = load_state()
    n = int(s.get("latest") or 0)
    return enabled() and n > BUILD and n not in (s.get("failed") or [])


def ready_build():
    """받아서 확인까지 마친 새 빌드 번호, 없으면 0"""
    s = load_state()
    n = int(s.get("ready") or 0)
    if n > BUILD and n not in (s.get("failed") or []) and os.path.isfile(_exe_for(n)):
        return n
    return 0


def failed_build():
    """적용하지 못한 최신 빌드 (설정에서 직접 받으라고 안내할 때)"""
    s = load_state()
    n = int(s.get("latest") or 0)
    return n if n in (s.get("failed") or []) else 0


def due():
    return time.time() - float(load_state().get("checked_at") or 0) > CHECK_EVERY


# ── 확인·내려받기 (백그라운드 스레드에서) ─────────────────

def _get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "sticky-todo-desktop"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def check():
    """최신 빌드 정보를 받아 저장한다. {'build', 'sha256', 'size'} 또는 실패하면 None"""
    if not enabled():
        return None
    try:
        info = json.loads(_get(VERSION_URL).decode("utf-8"))
        build = int(info["build"])
    except Exception:
        return None
    s = load_state()
    s.update(latest=build, checked_at=time.time(), sha256=info.get("sha256"), size=info.get("size"))
    _save_state(s)
    return info


def download():
    """최신 빌드 exe 를 받아 지문을 확인한다. 성공하면 빌드 번호, 아니면 0"""
    if not available():
        return 0
    if ready_build():
        return ready_build()
    s = load_state()
    build, want_hash, want_size = int(s["latest"]), s.get("sha256"), s.get("size")
    dest = _exe_for(build)
    part = dest + ".part"
    try:
        req = urllib.request.Request(EXE_URL, headers={"User-Agent": "sticky-todo-desktop"})
        h = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(req, timeout=60) as r, open(part, "wb") as f:
            while True:
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                h.update(chunk)
                size += len(chunk)
                f.write(chunk)
        # 올라가는 도중이라 버전 정보와 exe 가 아직 안 맞을 수 있다 → 다음 확인 때 다시
        if not want_hash or h.hexdigest() != want_hash or (want_size and size != want_size):
            os.remove(part)
            return 0
        os.replace(part, dest)
    except Exception:
        try:
            os.remove(part)
        except OSError:
            pass
        return 0
    s = load_state()
    s["ready"] = build
    _save_state(s)
    return build


def cleanup():
    """지난 업데이트가 남긴 파일을 치운다 (옛 exe, 이미 설치한 빌드의 파일, 표시 파일)"""
    if not enabled():
        return
    try:
        os.remove(sys.executable + ".old")
    except OSError:
        pass
    try:
        for name in os.listdir(_dir()):
            path = os.path.join(_dir(), name)
            num = "".join(ch for ch in name if ch.isdigit())
            if name.startswith("started-") or (
                    name.startswith("sticky-todo-") and num and int(num) <= BUILD):
                try:
                    os.remove(path)
                except OSError:
                    pass
    except OSError:
        pass


# ── 바꿔 끼우기 (화면을 띄우기 전, main 에서) ───────────────

def _clean_env():
    """새 exe 를 '처음 켠 것처럼' 띄우기 위해 PyInstaller 가 남긴 환경 변수를 지운다"""
    env = dict(os.environ)
    for k in list(env):
        if k.startswith("_PYI_") or k in ("_MEIPASS2", "TCL_LIBRARY", "TK_LIBRARY"):
            env.pop(k, None)
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    return env


def spawn(args):
    """이 exe 를 따로 띄운다 (창도 콘솔도 없이, 이 프로세스와 무관하게)"""
    flags = 0
    for name in ("DETACHED_PROCESS", "CREATE_NEW_PROCESS_GROUP"):
        flags |= getattr(subprocess, name, 0)
    return subprocess.Popen([sys.executable] + list(args), env=_clean_env(), close_fds=True,
                            creationflags=flags, cwd=os.path.dirname(sys.executable))


def wait_for_pid(argv, limit=20):
    """--wait-pid N 이 있으면 N 이 끝날 때까지 기다린다 (다시 시작할 때 자리 비켜 주기)"""
    if ARG_WAIT not in argv:
        return
    try:
        pid = int(argv[argv.index(ARG_WAIT) + 1])
    except (ValueError, IndexError):
        return
    try:
        import ctypes
        k = ctypes.windll.kernel32
        handle = k.OpenProcess(0x00100000, False, pid)        # SYNCHRONIZE
        if handle:
            k.WaitForSingleObject(handle, int(limit * 1000))
            k.CloseHandle(handle)
            return
    except Exception:
        pass
    time.sleep(3)


def _mark_failed(build, why, log):
    s = load_state()
    failed = s.get("failed") or []
    if build not in failed:
        failed.append(build)
    s["failed"] = failed
    s.pop("ready", None)
    _save_state(s)
    if log:
        log("업데이트 적용 실패 (빌드 %d): %s" % (build, why))


def apply_pending(argv, auto, log=None):
    """받아 둔 새 빌드가 있으면 바꿔 끼우고 새 exe 에게 넘긴다.
    넘겼으면 True (부른 쪽은 바로 끝나야 한다). 아무 일도 없었거나 실패해서 되돌렸으면 False."""
    if not enabled() or not (auto or ARG_APPLY in argv):
        return False
    build = ready_build()
    if not build:
        return False

    exe = sys.executable
    old = exe + ".old"
    new_file = _exe_for(build)
    try:
        if os.path.exists(old):
            os.remove(old)
        os.rename(exe, old)            # 실행 중인 exe 도 이름은 바꿀 수 있다
    except OSError as e:
        _mark_failed(build, "이 위치의 exe 를 바꿀 수 없음 (%s)" % e, log)
        return False
    try:
        shutil.copyfile(new_file, exe)
    except OSError as e:
        try:
            os.rename(old, exe)
        except OSError:
            pass
        _mark_failed(build, "새 exe 복사 실패 (%s)" % e, log)
        return False

    try:
        os.remove(_marker(build))
    except OSError:
        pass
    proc = None
    try:
        proc = spawn([ARG_UPDATED, str(BUILD)])
        deadline = time.time() + START_TIMEOUT
        while time.time() < deadline:
            if os.path.exists(_marker(build)):
                return True            # 새 버전이 잘 켜졌다 → 이 프로세스는 끝낸다
            if proc.poll() is not None:
                break                  # 새 exe 가 켜지자마자 꺼졌다
            time.sleep(0.3)
        why = "새 버전이 제대로 켜지지 않음"
    except OSError as e:
        why = "새 버전 실행 실패 (%s)" % e

    # 되돌리기: 새 exe 를 끄고 원래 exe 를 제자리에
    try:
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait(10)
    except Exception:
        pass
    for _ in range(20):
        try:
            if os.path.exists(exe):
                os.remove(exe)
            os.rename(old, exe)
            break
        except OSError:
            time.sleep(0.5)
    _mark_failed(build, why, log)
    return False


def mark_started():
    """새 버전으로 켜진 뒤 화면까지 띄웠으면 부른다 → 옛 프로세스가 보고 끝난다"""
    if not enabled():
        return
    try:
        with open(_marker(BUILD), "w") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass
    s = load_state()
    if int(s.get("ready") or 0) <= BUILD:
        s.pop("ready", None)
        _save_state(s)
