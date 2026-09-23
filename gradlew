#!/usr/bin/env sh
#
# 최소 Gradle 래퍼.
#
# 보통의 gradlew 는 gradle/wrapper/gradle-wrapper.jar 라는 바이너리 파일이 있어야
# 동작한다. 이 프로젝트는 그 jar 를 함께 넣을 수 없어서, 같은 일을 하는 셸
# 스크립트로 대신한다. 하는 일은 두 가지뿐이다.
#
#   1) gradle 명령이 이미 있으면 그대로 넘긴다
#   2) 없으면 Gradle 배포판을 내려받아 .gradle-dist/ 에 풀고 그것으로 실행한다
#
# 덕분에 ./gradlew 를 부르는 어떤 CI 설정에서도 그대로 동작한다.

set -e

GRADLE_VERSION=8.7

APP_HOME=$(cd "$(dirname "$0")" >/dev/null 2>&1 && pwd)
DIST_DIR="$APP_HOME/.gradle-dist"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"

# 1) 시스템에 gradle 이 있으면 그걸 쓴다 (GitHub Actions 의 setup-gradle 등)
if [ -z "$STICKY_FORCE_DOWNLOAD" ] && command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

# 2) 없으면 내려받는다
if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_DIR"
    ZIP="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

    echo "Gradle $GRADLE_VERSION 을 내려받습니다..."
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$ZIP" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$ZIP" "$URL"
    else
        echo "curl 또는 wget 이 필요합니다." >&2
        exit 1
    fi

    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$ZIP" -d "$DIST_DIR"
    else
        echo "unzip 이 필요합니다." >&2
        exit 1
    fi
    rm -f "$ZIP"
    chmod +x "$GRADLE_BIN"
fi

exec "$GRADLE_BIN" "$@"
