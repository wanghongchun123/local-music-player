@echo off
chcp 65001 >nul
setlocal

REM ============================================================
REM  本机一键编译 APK 脚本（无需 Android Studio）
REM  双击运行，或在本目录命令行执行 build.bat
REM ============================================================

set "JAVA_HOME=C:\Users\Administrator\dev\java\jdk-17.0.14+7"
set "ANDROID_HOME=C:\Users\Administrator\dev\android"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_HOME=C:\Users\Administrator\dev\gradle\gradle-8.2"

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ============================================
echo  本地音乐播放器 - 开始编译
echo ============================================

REM 检查 java
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [错误] 找不到 JDK，路径：%JAVA_HOME%
    exit /b 1
)

REM 检查 Gradle
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    echo [错误] 找不到 Gradle，路径：%GRADLE_HOME%
    exit /b 1
)

echo [1/2] 编译中（首次会下载依赖，请耐心等待）...
"%GRADLE_HOME%\bin\gradle.bat" assembleDebug --no-daemon
if errorlevel 1 (
    echo [错误] 编译失败，请查看上方日志
    exit /b 1
)

echo [2/2] 编译成功！
echo.
echo APK 位置：
echo   app\build\outputs\apk\debug\app-debug.apk
echo.
echo 把该 APK 传到手机即可安装。
pause
endlocal
