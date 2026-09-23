@rem 최소 Gradle 래퍼 (Windows)
@rem gradle 명령이 있으면 그대로 넘기고, 없으면 안내만 한다.
@if "%DEBUG%"=="" @echo off
where gradle >nul 2>nul
if %errorlevel%==0 (
    gradle %*
    exit /b %errorlevel%
)
echo.
echo Gradle 이 설치되어 있지 않습니다.
echo Android Studio 에서 프로젝트를 열면 알아서 처리해 줍니다.
echo 또는 https://gradle.org/install/ 에서 설치하세요.
echo.
exit /b 1
