# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop — 구글 드라이브 동기화.

폰 앱과 같은 구글 클라우드 프로젝트의 'OAuth 클라이언트(데스크톱 앱)'를 쓴다.
같은 프로젝트라서 드라이브의 앱 전용 숨김 공간(appDataFolder)을 폰과 함께 쓴다.

로그인 순서 (구글이 데스크톱 앱에 권하는 방식)
  1. 이 컴퓨터 안에서만 열리는 임시 주소(127.0.0.1)를 하나 연다
  2. 브라우저로 구글 로그인 화면을 띄운다
  3. 로그인을 마치면 구글이 그 임시 주소로 '승인 코드'를 돌려준다
  4. 코드를 토큰으로 바꾼다. 오래 쓰는 토큰(refresh token)만 저장해 둔다
     — 윈도우에서는 이 컴퓨터의 이 사용자만 풀 수 있게 암호화해서 저장한다

외부 라이브러리 없이 파이썬 기본 기능만 쓴다.
"""

import base64
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer

import todo_core as core
from store import data_dir

SCOPE = "https://www.googleapis.com/auth/drive.appdata openid email"
FILE_NAME = "sticky-todo.json"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
REVOKE_URL = "https://oauth2.googleapis.com/revoke"
LOGIN_TIMEOUT = 180


# ── 클라이언트 정보 ───────────────────────────────────────

def client_info():
    """(client_id, client_secret). 없으면 (None, None).

    exe 로 만들 때 GitHub Secret 에서 oauth_config.py 에 채워 넣는다.
    직접 .py 로 돌릴 때는 구글에서 받은 client_secret_*.json 을
    oauth_client.json 이라는 이름으로 데이터 폴더나 이 파일 옆에 두면 된다.
    """
    try:
        import oauth_config
        if getattr(oauth_config, "CLIENT_ID", ""):
            return oauth_config.CLIENT_ID, getattr(oauth_config, "CLIENT_SECRET", "")
    except ImportError:
        pass
    for folder in (data_dir(), os.path.dirname(os.path.abspath(__file__))):
        p = os.path.join(folder, "oauth_client.json")
        try:
            with open(p, "r", encoding="utf-8") as f:
                o = json.load(f)
            o = o.get("installed") or o.get("web") or o
            if o.get("client_id"):
                return o["client_id"], o.get("client_secret", "")
        except (OSError, ValueError, AttributeError):
            continue
    return None, None


def available():
    return client_info()[0] is not None


# ── 비밀 보관 (윈도우 DPAPI) ──────────────────────────────

def _dpapi(data, protect):
    import ctypes
    from ctypes import wintypes

    class BLOB(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]

    buf = ctypes.create_string_buffer(data, len(data))
    src = BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    dst = BLOB()
    crypt32 = ctypes.windll.crypt32
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    ok = fn(ctypes.byref(src), None, None, None, None, 0, ctypes.byref(dst))
    if not ok:
        raise OSError("DPAPI 실패")
    try:
        return ctypes.string_at(dst.pbData, dst.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(dst.pbData)


def _seal(text):
    raw = text.encode("utf-8")
    try:
        return "dpapi:" + base64.b64encode(_dpapi(raw, True)).decode("ascii")
    except Exception:
        return "plain:" + base64.b64encode(raw).decode("ascii")


def _unseal(value):
    try:
        kind, b = value.split(":", 1)
        raw = base64.b64decode(b)
        if kind == "dpapi":
            raw = _dpapi(raw, False)
        return raw.decode("utf-8")
    except Exception:
        return None


# ── 계정 정보 ─────────────────────────────────────────────

class Account(object):
    """account.json 에 저장되는 연결 정보와 마지막 동기화 상태"""

    def __init__(self):
        self.path = os.path.join(data_dir(), "account.json")
        self.email = None
        self.refresh_token = None
        self.last_at = 0
        self.last_msg = ""
        self.last_ok = False
        self.need_reconnect = False
        self._access = None
        self._access_until = 0
        self.lock = threading.Lock()
        self.load()

    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                o = json.load(f)
        except (OSError, ValueError):
            return
        self.email = o.get("email")
        tok = o.get("refresh")
        self.refresh_token = _unseal(tok) if tok else None
        self.last_at = o.get("last_at", 0)
        self.last_msg = o.get("last_msg", "")
        self.last_ok = bool(o.get("last_ok", False))
        self.need_reconnect = bool(o.get("need_reconnect", False))

    def save(self):
        o = {
            "email": self.email,
            "refresh": _seal(self.refresh_token) if self.refresh_token else None,
            "last_at": self.last_at,
            "last_msg": self.last_msg,
            "last_ok": self.last_ok,
            "need_reconnect": self.need_reconnect,
        }
        tmp = self.path + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(o, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
        except OSError:
            pass

    def connected(self):
        return bool(self.refresh_token)

    def record(self, ok, msg):
        self.last_at = int(time.time())
        self.last_ok = ok
        self.last_msg = msg
        self.save()

    def forget(self):
        tok = self.refresh_token
        self.email = None
        self.refresh_token = None
        self._access = None
        self.need_reconnect = False
        self.last_at = 0
        self.last_msg = ""
        self.save()
        if tok:
            try:
                _post(REVOKE_URL, {"token": tok}, timeout=8)
            except Exception:
                pass


# ── HTTP ──────────────────────────────────────────────────

class HttpError(Exception):
    def __init__(self, code, body):
        Exception.__init__(self, "%s %s" % (code, body[:200]))
        self.code = code
        self.body = body


def _request(url, method="GET", data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            body = ""
        return e.code, body


def _post(url, form, timeout=30):
    data = urllib.parse.urlencode(form).encode("ascii")
    code, body = _request(url, "POST", data,
                          {"Content-Type": "application/x-www-form-urlencoded"}, timeout)
    if code // 100 != 2:
        raise HttpError(code, body)
    return json.loads(body) if body else {}


def _brief(body):
    t = " ".join((body or "").split())
    return t[:120] + ("…" if len(t) > 120 else "")


# ── 로그인 ────────────────────────────────────────────────

_DONE_PAGE = u"""<!doctype html><meta charset="utf-8"><title>할 일 메모 Desktop</title>
<body style="font-family:'Malgun Gothic',sans-serif;text-align:center;padding-top:80px;color:#22252A">
<h2>%s</h2><p style="color:#9AA0A6">이 창은 닫아도 됩니다.</p></body>"""


def login(account):
    """브라우저로 구글 로그인. 끝날 때까지 기다린다 (백그라운드 스레드에서 부를 것).
    (성공 여부, 메시지)"""
    cid, secret = client_info()
    if not cid:
        return False, "이 버전에는 구글 연결 정보가 들어 있지 않습니다"

    got = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in q or "error" in q:
                got["code"] = (q.get("code") or [None])[0]
                got["error"] = (q.get("error") or [None])[0]
                got["state"] = (q.get("state") or [None])[0]
                msg = u"연결됐습니다" if got["code"] else u"연결하지 못했습니다"
                body = (_DONE_PAGE % msg).encode("utf-8")
                self.send_response(200)
            else:
                body = b""
                self.send_response(404)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    server.timeout = 1
    redirect = "http://127.0.0.1:%d" % server.server_address[1]

    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode("ascii")
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode("ascii")
    state = secrets.token_urlsafe(16)

    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": cid,
        "redirect_uri": redirect,
        "response_type": "code",
        "scope": SCOPE,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "state": state,
        "access_type": "offline",
        "prompt": "consent",
    })
    webbrowser.open(url)

    deadline = time.time() + LOGIN_TIMEOUT
    try:
        while "code" not in got and time.time() < deadline:
            server.handle_request()
    finally:
        server.server_close()

    if "code" not in got:
        return False, "시간 안에 로그인을 마치지 않았습니다"
    if got.get("state") != state:
        return False, "로그인 응답이 올바르지 않습니다"
    if not got.get("code"):
        err = got.get("error") or "취소됨"
        if err == "access_denied":
            return False, "승인하지 않았거나, 이 계정이 테스트 사용자 목록에 없습니다"
        return False, "로그인 실패: " + err

    try:
        tok = _post(TOKEN_URL, {
            "code": got["code"],
            "client_id": cid,
            "client_secret": secret or "",
            "redirect_uri": redirect,
            "grant_type": "authorization_code",
            "code_verifier": verifier,
        })
    except HttpError as e:
        return False, "토큰을 받지 못했습니다 (%s) %s" % (e.code, _brief(e.body))
    except Exception as e:
        return False, "통신 오류: %s" % e

    refresh = tok.get("refresh_token")
    if not refresh:
        return False, "구글이 장기 토큰을 주지 않았습니다. 다시 시도해 주세요"

    email = None
    idt = tok.get("id_token")
    if idt:
        try:
            part = idt.split(".")[1]
            part += "=" * (-len(part) % 4)
            email = json.loads(base64.urlsafe_b64decode(part)).get("email")
        except Exception:
            pass

    with account.lock:
        account.refresh_token = refresh
        account.email = email
        account.need_reconnect = False
        account._access = tok.get("access_token")
        account._access_until = time.time() + int(tok.get("expires_in", 0)) - 60
        account.save()
    return True, "연결됐습니다"


def _access_token(account, force=False):
    if not force and account._access and time.time() < account._access_until:
        return account._access
    cid, secret = client_info()
    try:
        tok = _post(TOKEN_URL, {
            "client_id": cid,
            "client_secret": secret or "",
            "refresh_token": account.refresh_token,
            "grant_type": "refresh_token",
        })
    except HttpError as e:
        if e.code in (400, 401) and "invalid_grant" in e.body:
            account.need_reconnect = True
            account.save()
            raise RuntimeError("구글 연결이 만료됐습니다. 설정에서 다시 연결해 주세요")
        raise RuntimeError("인증 실패 (%s) %s" % (e.code, _brief(e.body)))
    account._access = tok["access_token"]
    account._access_until = time.time() + int(tok.get("expires_in", 3600)) - 60
    return account._access


# ── 드라이브 ──────────────────────────────────────────────

def _drive(account, url, method="GET", data=None, ctype=None):
    """토큰이 거절되면 한 번만 새로 받아 다시 시도한다"""
    for attempt in (0, 1):
        headers = {"Authorization": "Bearer " + _access_token(account, force=attempt == 1)}
        if ctype:
            headers["Content-Type"] = ctype
        code, body = _request(url, method, data, headers)
        if code == 401 and attempt == 0:
            continue
        return code, body
    return code, body


def _find_file(account):
    q = urllib.parse.quote("name = '%s'" % FILE_NAME)
    code, body = _drive(account, "https://www.googleapis.com/drive/v3/files"
                        "?spaces=appDataFolder&pageSize=10&fields=files(id,modifiedTime)&q=" + q)
    if code // 100 != 2:
        raise RuntimeError("드라이브 조회 실패 (%s) %s" % (code, _brief(body)))
    files = json.loads(body).get("files") or []
    return files[0]["id"] if files else None


def _download(account, fid):
    code, body = _drive(account, "https://www.googleapis.com/drive/v3/files/%s?alt=media" % fid)
    if code // 100 != 2:
        raise RuntimeError("내려받기 실패 (%s) %s" % (code, _brief(body)))
    try:
        return core.SyncData.from_json(json.loads(body))
    except (ValueError, AttributeError):
        return None


def _upload(account, fid, content):
    payload = content.encode("utf-8")
    if fid:
        code, body = _drive(account,
                            "https://www.googleapis.com/upload/drive/v3/files/%s?uploadType=media" % fid,
                            "PATCH", payload, "application/json; charset=UTF-8")
    else:
        boundary = "sticky%d" % int(time.time() * 1000)
        meta = json.dumps({"name": FILE_NAME, "parents": ["appDataFolder"]})
        parts = (
            "--%s\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n%s\r\n"
            "--%s\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" % (boundary, meta, boundary)
        ).encode("utf-8") + payload + ("\r\n--%s--\r\n" % boundary).encode("utf-8")
        code, body = _drive(account,
                            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                            "POST", parts, "multipart/related; boundary=" + boundary)
    if code // 100 != 2:
        raise RuntimeError("올리기 실패 (%s) %s" % (code, _brief(body)))


def sync_once(account, local):
    """드라이브와 한 번 맞춘다. 통신이 끝날 때까지 기다린다.
    (성공 여부, 메시지, 병합 결과 또는 None)
    병합 결과는 '보낸 시점의 이 컴퓨터 목록 + 드라이브' 이다.
    그 사이 이 컴퓨터에서 또 바뀐 것은 부르는 쪽에서 한 번 더 합친다."""
    if not account.connected():
        return False, "구글 계정이 연결되지 않았습니다", None
    try:
        with account.lock:
            fid = _find_file(account)
            remote = _download(account, fid) if fid else None
            merged = core.merge(local, remote) if remote else local
            need_up = remote is None or merged.signature() != remote.signature()
            if need_up:
                _upload(account, fid, json.dumps(merged.to_json(), ensure_ascii=False))
        what = "올림" if need_up else "확인"
        account.record(True, "동기화 완료 · " + what)
        return True, account.last_msg, merged
    except RuntimeError as e:
        account.record(False, str(e))
        return False, str(e), None
    except Exception as e:
        msg = "통신 오류: %s" % (e.__class__.__name__,)
        account.record(False, msg)
        return False, msg, None
