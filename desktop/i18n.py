# -*- coding: utf-8 -*-
"""
할 일 메모 Desktop — 화면 글자의 영어 번역.

코드에는 한국어를 그대로 쓰고 T("한국어") 로 감싼다. 영어로 설정돼 있으면
아래 EN 표에서 찾은 영어를, 아니면 한국어를 그대로 돌려준다.
표에 없는 글자는 한국어 그대로 나온다 (빠뜨려도 앱이 멈추지는 않는다).
"""

import locale
import os

LANG_SYSTEM, LANG_KO, LANG_EN = 0, 1, 2
# 언어 고르는 칸은 어느 언어로 보든 알아볼 수 있게 두 말로 적는다
LANG_LABELS = ["시스템 설정 따르기 (System)", "한국어", "English"]

_english = False


def system_is_korean():
    """윈도우 표시 언어가 한국어인가 (윈도우가 아니면 환경의 locale 로 본다)"""
    try:
        import ctypes
        langid = ctypes.windll.kernel32.GetUserDefaultUILanguage()
        return (langid & 0x3FF) == 0x12          # 0x12 = 한국어
    except Exception:
        pass
    for key in ("LC_ALL", "LC_MESSAGES", "LANG"):
        v = os.environ.get(key)
        if v:
            return v.lower().startswith("ko")
    try:
        return (locale.getlocale()[0] or "").lower().startswith("ko")
    except Exception:
        return True


def set_lang(choice):
    """설정값(0 시스템, 1 한국어, 2 영어)에 맞춰 화면 언어를 정한다. 영어면 True"""
    global _english
    if choice == LANG_EN:
        _english = True
    elif choice == LANG_KO:
        _english = False
    else:
        _english = not system_is_korean()
    try:
        import todo_core
        todo_core.ENGLISH = _english          # 요일·'오늘' 같은 날짜 글자
    except ImportError:
        pass
    return _english


def is_english():
    return _english


def T(text, en=None):
    """화면 글자. 같은 한국어가 자리마다 다른 영어가 돼야 할 때는 en 으로 직접 준다."""
    if not _english:
        return text
    if en is not None:
        return en
    return EN.get(text, text)


EN = {
    # 이름
    "할 일 메모 Desktop": "Sticky Todo Desktop",
    "할 일": "To-do",

    # 트레이·메뉴
    "메모 열기": "Open memo",
    "트레이로 숨기기": "Hide to tray",
    "지금 동기화": "Sync now",
    "보관함": "Archive",
    "설정": "Settings",
    "설정…": "Settings…",
    "종료": "Quit",
    "메모를 트레이로 숨겼습니다. 작업 표시줄 오른쪽 ^ 의 아이콘을 누르면 다시 열립니다.":
        "The memo is hidden in the tray. Click its icon under ^ on the taskbar to bring it back.",
    "보관함 열기  (%d)": "Open archive  (%d)",
    "완료 항목 모두 보관": "Archive all finished",
    "크기 초기화 (%d × %d)": "Reset size (%d × %d)",
    "저장 폴더 열기": "Open data folder",

    # 목록
    "＋  할 일 추가": "＋  Add a task",
    "할 일이 없습니다\n두 번 눌러 추가": "Nothing to do\nDouble-click to add",
    "자세히 편집 (반복 · 즐겨찾기)…": "Edit details (repeat · star)…",
    "즐겨찾기 해제": "Remove star",
    "즐겨찾기": "Star",
    "체크 해제": "Uncheck",
    "완료로 체크": "Mark as done",
    "삭제": "Delete",
    "(내용 없음)": "(empty)",

    # 처음 실행
    "예전에 쓰던 바탕화면 메모의 할 일 %d건이 있습니다.\n이 앱으로 가져올까요?\n\n"
    "구글 계정을 연결하면 폰 목록에도 함께 들어갑니다.":
        "Found %d tasks from the old desktop memo.\nImport them into this app?\n\n"
        "If you connect a Google account, they will also appear on your phone.",
    "윈도우를 켤 때 예전 메모 대신 이 앱이 열리도록 바꿨습니다.":
        "This app will now open at Windows startup instead of the old memo.",

    # 편집 창
    "할 일 편집": "Edit task",
    "할 일 추가": "Add task",
    "기한": "Due date",
    "9/18 · 9-18 · 26.9.18 · 오늘 · 내일 · +7  (비우면 기한 없음)":
        "9/18 · 9-18 · 26.9.18 · today · tomorrow · +7  (leave empty for no due date)",
    "반복": "Repeat",
    "없음": "None",
    "기한 기준": "From due date",
    "체크일 기준": "From check date",
    "고정 요일": "Weekdays",
    " 일마다": " days",
    "저장": "Save",
    "취소": "Cancel",
    "★  즐겨찾기 — 맨 위에 표시": "★  Starred — stays on top",
    "☆  즐겨찾기 꺼짐": "☆  Not starred",
    "기한이 지나면 다음 날 체크가 풀리고 기한이 n일 뒤로 옮겨집니다. 늦게 체크해도 주기가 밀리지 않습니다.":
        "The day after the due date, it unchecks and the due date moves n days later. "
        "Checking late doesn’t shift the cycle.",
    "체크한 날로부터 n일이 지나면 다시 할 일로 돌아옵니다.":
        "It comes back n days after you check it.",
    "정해둔 요일마다 돌아옵니다. 반복 항목은 보관함으로 가지 않습니다.":
        "It comes back on the chosen weekdays. Repeating tasks never go to the archive.",

    # 보관함
    "보관함 — ": "Archive — ",
    "전체 비우기": "Clear all",
    "보관된 할 일이 없습니다": "The archive is empty",
    "복원": "Restore",
    "보관함을 모두 비울까요?": "Clear the whole archive?",

    # 설정
    "설정 — ": "Settings — ",
    "언어": "Language",
    "화면 언어": "Display language",
    "창": "Window",
    "창 순서": "Window order",
    "항상 앞 (기본)": "Always on top (default)",
    "다른 창과 순서 바뀜": "Normal (can go behind)",
    "창 불투명도": "Window opacity",
    "테마": "Theme",
    "글자 크기": "Font size",
    "줄 간격": "Line spacing",
    "윈도우 시작 시 실행": "Start with Windows",
    "작업 관리자 '시작 앱'에서 꺼져 있습니다. 켜면 함께 풀립니다":
        "Disabled in Task Manager › Startup apps. Turning it on re-enables it.",
    "날짜": "Dates",
    "표시 방식": "Format",
    "요일 함께 표시": "Show weekday",
    "예: 10/5(월)": "e.g. 10/5(Mon)",
    "정리": "Cleanup",
    "보관함 자동 비우기": "Auto-clear archive",
    "동기화": "Sync",
    "이 버전에는 구글 연결 정보가 들어 있지 않습니다.": "This build has no Google sign-in info.",
    "구글 계정": "Google account",
    "연결 안 됨": "Not connected",
    "연결됨": "Connected",
    "아직 한 적 없음": "Never",
    "동기화 중…": "Syncing…",
    "마지막 동기화": "Last sync",
    "다시 연결": "Reconnect",
    "연결 끊기": "Disconnect",
    "구글 계정 연결": "Connect Google account",
    "폰의 할 일 앱과 같은 구글 계정으로 연결하면 목록이 자동으로 맞춰집니다. "
    "목록은 드라이브의 앱 전용 숨김 공간에만 저장됩니다.\n\n"
    "바꾼 내용은 몇 초 안에 올라가고, 다른 기기의 변경은 켤 때와 5분마다 받아옵니다.":
        "Connect the same Google account as the phone app and your lists stay in sync. "
        "The list is stored only in a hidden app folder on your Drive.\n\n"
        "Your changes upload within seconds; changes from other devices arrive "
        "when the app starts and every 5 minutes.",
    "브라우저에서 구글 로그인을 마쳐 주세요…": "Finish signing in to Google in your browser…",
    "구글 계정 연결을 끊을까요?\n이 컴퓨터의 목록은 그대로 남습니다.":
        "Disconnect your Google account?\nThe list on this computer stays as it is.",

    # 설정 값 이름 (store.py)
    "100% (불투명)": "100% (opaque)",
    "작게": "Small",
    "보통 (기본)": "Normal (default)",
    "크게": "Large",
    "아주 크게": "Extra large",
    "좁게": "Narrow",
    "넓게": "Wide",
    "아주 넓게": "Extra wide",
    "윈도우 설정 따르기": "Follow Windows",
    "밝게": "Light",
    "어둡게": "Dark",
    "날짜 (10/5)": "Date (10/5)",
    "남은 날 (D-3)": "Days left (D-3)",
    "둘 다 (10/5 D-3)": "Both (10/5 D-3)",
    "끔 (계속 보관)": "Off (keep all)",
    "30일 지나면 삭제": "Delete after 30 days",
    "90일 지나면 삭제": "Delete after 90 days",

    # 동기화 메시지 (drive_sync.py)
    "이 창은 닫아도 됩니다.": "You can close this window.",
    "이 버전에는 구글 연결 정보가 들어 있지 않습니다": "This build has no Google sign-in info",
    "연결됐습니다": "Connected",
    "연결하지 못했습니다": "Couldn’t connect",
    "시간 안에 로그인을 마치지 않았습니다": "Sign-in timed out",
    "로그인 응답이 올바르지 않습니다": "Invalid sign-in response",
    "취소됨": "cancelled",
    "승인하지 않았거나, 이 계정이 테스트 사용자 목록에 없습니다":
        "Access was denied, or this account isn’t on the test user list",
    "로그인 실패: ": "Sign-in failed: ",
    "토큰을 받지 못했습니다 (%s) %s": "Couldn’t get a token (%s) %s",
    "통신 오류: %s": "Network error: %s",
    "구글이 장기 토큰을 주지 않았습니다. 다시 시도해 주세요":
        "Google didn’t return a refresh token. Please try again",
    "구글 연결이 만료됐습니다. 설정에서 다시 연결해 주세요":
        "Google sign-in expired. Reconnect it in Settings",
    "인증 실패 (%s) %s": "Authentication failed (%s) %s",
    "드라이브 조회 실패 (%s) %s": "Drive lookup failed (%s) %s",
    "내려받기 실패 (%s) %s": "Download failed (%s) %s",
    "올리기 실패 (%s) %s": "Upload failed (%s) %s",
    "구글 계정이 연결되지 않았습니다": "No Google account connected",
    "동기화 완료 · ": "Synced · ",
    "동기화 완료 · 받아옴": "Synced · received",
    "변경 없음": "no changes",
    "올림": "uploaded",
    "확인": "checked",
}
