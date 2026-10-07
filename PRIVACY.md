# 개인정보처리방침 — 할 일 메모

최종 수정일: 2026년 10월 7일

"할 일 메모"(안드로이드 앱·위젯, 할 일 메모 Desktop)는 개인이 자기 할 일을 관리하려고 만든 앱입니다.
이 문서는 앱이 어떤 정보를 다루고 어디에 보관하는지 설명합니다.

## 다루는 정보

- **할 일 목록**: 사용자가 직접 입력한 할 일, 기한, 반복 설정, 완료 여부, 보관함 항목
- **구글 계정 이메일 주소**: 동기화를 켰을 때, 어느 계정에 연결됐는지 화면에 보여 주는 용도로만 사용

위치, 연락처, 사진, 기기 식별자 등 그 밖의 정보는 수집하지 않습니다.

## 보관 위치

- 할 일 목록은 **사용자의 기기 안**에 저장됩니다.
- 사용자가 구글 계정을 연결하면, 목록 사본 한 개가 **사용자 본인의 구글 드라이브 안 '앱 전용 숨김 공간'(appDataFolder)** 에 저장됩니다.
  이 공간은 이 앱만 읽고 쓸 수 있으며, 드라이브의 다른 파일에는 접근하지 않습니다.
- 개발자의 서버는 없습니다. 개발자를 포함한 누구에게도 정보가 전송되거나 공유되지 않습니다.
- 광고, 분석, 추적 도구를 쓰지 않습니다.

## 구글 사용자 데이터 사용

이 앱은 구글 API에서 받은 정보를 할 일 목록 동기화 외의 목적으로 사용하지 않으며,
[Google API 서비스 사용자 데이터 정책](https://developers.google.com/terms/api-services-user-data-policy)
(제한적 사용 요건 포함)을 따릅니다.

요청하는 권한:
- `drive.appdata` — 드라이브의 앱 전용 숨김 공간에 목록 파일 한 개를 읽고 쓰기
- `email`, `openid` — 연결된 계정의 이메일 주소 표시

## 삭제 방법

- **기기 자료**: 앱을 삭제하면 지워집니다. PC 앱은 `%APPDATA%\StickyTodo` 폴더를 지우면 됩니다.
- **드라이브 자료**: 구글 드라이브 → 설정 → 앱 관리 → "할 일 메모" → 옵션 → *숨겨진 앱 데이터 삭제*
- **연결 해제**: 앱의 설정에서 "연결 끊기"를 누르거나, [구글 계정 → 보안 → 타사 액세스](https://myaccount.google.com/connections)에서 권한을 삭제할 수 있습니다.

## 문의

[GitHub 이슈](https://github.com/Seokhui-Jeong/sticky-todo/issues)로 남겨 주세요.

---

### Privacy Policy (English summary)

"할 일 메모" (To-do Memo) stores the to-do items you enter on your own device. If you connect a Google account,
a copy is kept in your own Google Drive app data folder (`drive.appdata`), which only this app can access.
Your email address is used only to show which account is connected. No data is sent to the developer or any third party,
and no ads, analytics or tracking are used. Use of information received from Google APIs adheres to the
Google API Services User Data Policy, including the Limited Use requirements.
To delete data, uninstall the app and remove its hidden app data in Google Drive → Settings → Manage apps.
Contact: https://github.com/Seokhui-Jeong/sticky-todo/issues
