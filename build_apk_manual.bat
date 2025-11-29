@echo off
echo Building Ham-Chat APK...
echo.

REM Set JAVA_HOME
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot

REM Set ANDROID_HOME (adjust path as needed)
set ANDROID_HOME=C:\Users\Admin\AppData\Local\Android\Sdk

REM Add tools to PATH
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\tools;%ANDROID_HOME%\platform-tools;%PATH%

echo Java Version:
java -version
echo.

echo Android SDK Tools:
adb version
echo.

echo Building APK...
call gradlew.bat assembleRelease

if %ERRORLEVEL% EQU 0 (
    echo.
    echo BUILD SUCCESSFUL!
    echo APK Location: app\build\outputs\apk\release\app-release.apk
    echo.
    echo Install with: adb install app\build\outputs\apk\release\app-release.apk
) else (
    echo.
    echo BUILD FAILED!
    echo Check error messages above.
)

pause
