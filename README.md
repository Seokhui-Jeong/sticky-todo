# 할 일 메모

폰 홈 화면 위젯과 PC 바탕화면 메모가 같은 할 일 목록을 구글 드라이브로 주고받습니다.

## ⬇ 최신 버전 받기

| | 받기 | |
|---|---|---|
| 📱 **폰 앱 · 위젯** | [todo-widget.apk](https://github.com/Seokhui-Jeong/sticky-todo/releases/latest/download/todo-widget.apk) | 폰에서 눌러 바로 설치 |
| 🖥 **할 일 메모 Desktop** | [sticky-todo.exe](https://github.com/Seokhui-Jeong/sticky-todo/releases/download/desktop/sticky-todo.exe) | 설치 없이 바로 실행 |

두 주소는 늘 가장 최근에 빌드한 파일을 가리킵니다.
빌드 기록은 [Actions](https://github.com/Seokhui-Jeong/sticky-todo/actions) 왼쪽에서
**Build APK** / **Build Desktop** 을 골라 보면 각각의 최신 빌드가 맨 위에 나옵니다.

```
┌─────────────────────────────┐
│ 할 일  3                 ＋ │   ← 제목을 누르면 앱, ＋ 를 누르면 추가창
├─────────────────────────────┤
│ ☐ ★ 보고서 초안 쓰기   9/19 │   ← 즐겨찾기는 맨 위
│ ☐  회의 자료 정리 9/20 ↻7   │   ← 반복 일정
│ ☑  메일 회신           9/25 │   ← 완료한 것은 맨 아래로
└─────────────────────────────┘
```

## 기능

- 왼쪽 체크 · 가운데 할 일 · 오른쪽 날짜 3칸 구조
- 체크한 할 일은 목록 맨 아래로 내려감
- **완료 + 기한 지남** → 보관함으로 자동 이동 (삭제되지 않음)
- **미완료 + 기한 지남** → 빨간 날짜로 목록에 계속 남음
- 자정이 지나거나 재부팅해도 알아서 다시 정리
- 위젯은 크기 조절 핸들로 늘리면 더 많은 줄이 보이고, 넘치면 스크롤됨
- 위젯 배경은 반투명이며 폰의 다크모드에 맞춰 흰색/검정으로 자동 전환

### 날짜 입력

`9/18` · `9-18` · `9.18` · `9월18일` · `26.9.18` · `2026-09-18` · `26년 9월 18일` ·
`오늘` · `내일` · `모레` · `+7`(7일 뒤)

연도를 안 적으면 올해로 보고, 이미 6개월 넘게 지난 날짜면 내년으로 해석합니다.

---

## APK 만드는 법

### 방법 1. GitHub에 올리면 자동으로 만들어짐 (PC에 설치할 것 없음)

1. [github.com](https://github.com)에 로그인하고 **New repository** → 이름 아무거나 → **Create**
2. 이 폴더의 파일 전부를 그 저장소에 올립니다
   (웹에서 **uploading an existing file** → 폴더째 끌어다 놓기 → Commit)
3. 저장소 상단 **Actions** 탭 → `Build APK` → 초록색 체크가 뜰 때까지 3~5분 기다립니다
4. 그 실행 화면 맨 아래 **Artifacts → todo-widget-apk** 를 눌러 zip을 내려받고, 압축을 풀면
   `app-debug.apk` 가 나옵니다
5. 이 파일을 폰으로 옮겨 실행 → "출처를 알 수 없는 앱" 허용 → 설치

> `.github` 폴더가 같이 올라가야 자동 빌드가 돕니다. 웹에서 끌어다 놓을 때
> 숨김 폴더가 빠지면, 저장소에서 **Add file → Create new file** 로
> `.github/workflows/build-apk.yml` 경로를 직접 입력해 내용을 붙여넣으세요.

### 방법 2. Android Studio

1. Android Studio에서 **Open** → 이 폴더 선택
2. Gradle 동기화가 끝나면 **Build → Build Bundle(s)/APK(s) → Build APK(s)**
3. `app/build/outputs/apk/debug/app-debug.apk` 생성

> `gradlew` 는 들어있지만 보통의 것과 다릅니다. 래퍼 바이너리(`gradle-wrapper.jar`)를
> 함께 넣을 수 없어서, 같은 일을 하는 셸 스크립트로 대신했습니다.
> gradle 이 깔려 있으면 그걸 쓰고, 없으면 Gradle 배포판을 받아 씁니다.
> Android Studio 가 진짜 래퍼를 새로 만들겠다고 물어보면 그렇게 해도 됩니다.

---

## 위젯 올리기

홈 화면 빈 곳을 **길게 누르기** → **위젯** → **할 일** → 끌어다 놓기.
올린 뒤 위젯을 길게 눌러 나오는 **크기 조절 핸들**로 원하는 크기를 맞추면 됩니다.
가로 1칸 × 세로 1칸부터 화면 전체까지 자유롭게 늘어납니다.

## 동작 바꾸기

`app/src/main/java/com/sticky/todo/Model.kt` 아래쪽 `Rules`:

```kotlin
const val ARCHIVE_UNDONE_OVERDUE = false  // true 로 바꾸면 미완료도 기한 지나면 보관함행
```

색은 `app/src/main/res/values/colors.xml`(라이트)과
`values-night/colors.xml`(다크)에서 바꿉니다.
위젯 투명도는 `widget_bg` 색의 맨 앞 두 자리(투명도)를 조절하면 됩니다.
`#F2FFFFFF`에서 `F2`를 낮출수록 더 투명해집니다.

## 데이터

각 기기에 저장되고, 구글 계정을 연결하면 드라이브의 앱 전용 숨김 공간(이 앱만 볼 수 있음)에
목록 하나를 두고 기기끼리 맞춥니다. PC 앱 자료는 `%APPDATA%\StickyTodo` 에 있습니다.

---

## 빌드가 실패하면

Actions 화면에서 빨간 X 가 난 단계를 펼치면 원인이 나옵니다.
실패했을 때는 `build-logs` 라는 Artifact 도 같이 올라오니 받아서 보내주세요.

자주 나오는 것:

| 메시지 | 원인 / 해결 |
|---|---|
| `Task 'assembleDebug' not found in root project` | 파일을 폴더째 올려서 `settings.gradle.kts` 가 저장소 맨 위에 없는 경우. 저장소 첫 화면에 `app`, `settings.gradle.kts`, `build.gradle.kts` 가 바로 보여야 합니다 |
| `No such file or directory: ./gradlew` | 워크플로가 `gradle` 대신 `./gradlew` 를 부르고 있는 경우. 이 저장소의 `build-apk.yml` 을 그대로 쓰면 됩니다 |
| `Failed to find package 'tools'` | `android-actions/setup-android` 를 쓸 때 나는 오류. 지금 워크플로에서는 그 액션을 뺐습니다 |
| `SDK location not found` | `local.properties` 를 실수로 올린 경우. 저장소에서 지우세요 |
