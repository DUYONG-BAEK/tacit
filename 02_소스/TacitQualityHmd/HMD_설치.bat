@echo off
chcp 65001 >nul
setlocal
rem ============================================================================
rem  TacitQ HMD 앱 설치 + 권한 + 대기 시작  (HMD 를 USB 로 PC 에 연결한 뒤 실행)
rem
rem    HMD_설치.bat            디버그 APK 설치  (app\build\outputs\apk\debug)
rem    HMD_설치.bat release    릴리스 APK 설치  (app\build\outputs\apk\release)
rem    HMD_설치.bat grant      설치 없이 권한만 (Android Studio 로 디버그 앱을 깔았을 때)
rem
rem  - 권한: 카메라(설치 -g), 다른 앱 위에 표시(appops). 업데이트로 덮어쓰면 권한은 그대로 남는다.
rem  - 끝나면 HMD 앱을 한 번 띄워 대기 서비스(BLE)를 켠다. 그 뒤로는 HMD 전원만 켜 두면
rem    폰 '검사 시작'으로 HMD 화면이 자동으로 뜬다 (부팅·업데이트 때도 대기 서비스가 저절로 켜짐).
rem  - PC 에 폰도 USB 로 꽂혀 있으면 adb 가 기기를 못 고른다. HMD 만 꽂거나 ANDROID_SERIAL 을 정한다.
rem ============================================================================
cd /d "%~dp0"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"
set "PKG=kr.co.snail.tacitq.hmd.debug"
set "OTHER=kr.co.snail.tacitq.hmd"
set "DIR=app\build\outputs\apk\debug"
if /i "%MODE%"=="release" set "PKG=kr.co.snail.tacitq.hmd"
if /i "%MODE%"=="release" set "OTHER=kr.co.snail.tacitq.hmd.debug"
if /i "%MODE%"=="release" set "DIR=app\build\outputs\apk\release"

rem adb — PATH 에 없으면 Android Studio 기본 SDK 위치
set "ADB=adb"
where adb >nul 2>&1 && goto have_adb
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" goto no_adb
:have_adb
"%ADB%" get-state >nul 2>&1 || goto no_device

if /i "%MODE%"=="grant" goto grant
set "APK="
for %%f in ("%DIR%\*.apk") do set "APK=%%f"
if not defined APK goto no_apk
echo [1/4] 설치: %APK%
"%ADB%" install -r -g "%APK%" || goto install_failed

:grant
echo [2/4] 카메라 권한
"%ADB%" shell pm grant %PKG% android.permission.CAMERA || goto not_installed
rem Android 12 이상 HMD 만 필요한 BLE 권한 — Navigator 520(Android 11)은 설치만 하면 허용이라 실패해도 괜찮다
"%ADB%" shell pm grant %PKG% android.permission.BLUETOOTH_ADVERTISE >nul 2>&1
"%ADB%" shell pm grant %PKG% android.permission.BLUETOOTH_CONNECT >nul 2>&1
echo [3/4] 다른 앱 위에 표시 허용 — 폰이 연결하면 HMD 화면을 앞으로 띄우는 데 필요
"%ADB%" shell appops set %PKG% SYSTEM_ALERT_WINDOW allow || goto failed
echo [4/4] HMD 앱 실행 — 대기 서비스(BLE) 시작
"%ADB%" shell am start -n %PKG%/kr.co.snail.tacitq.hmd.MainActivity >nul || goto failed

"%ADB%" shell pm list packages %OTHER% | findstr /x "package:%OTHER%" >nul && echo [주의] %OTHER% 도 깔려 있습니다. BLE 광고·카메라를 서로 다투니 지우세요: adb uninstall %OTHER%
echo.
echo 완료 — 이제 HMD 전원만 켜 두면 폰 '검사 시작'으로 HMD 화면이 자동으로 뜹니다.
exit /b 0

:no_adb
echo [실패] adb 를 찾지 못했습니다. Android SDK platform-tools 폴더를 PATH 에 넣거나 Android Studio SDK 를 기본 위치에 두세요.
exit /b 1
:no_device
echo [실패] HMD 가 adb 에 안 보입니다. USB 연결, HMD 의 USB 디버깅 허용, 다른 기기가 함께 꽂혀 있지 않은지 확인하세요.
exit /b 1
:no_apk
echo [실패] %DIR% 에 APK 가 없습니다. Android Studio 에서 먼저 빌드하세요 - Build, Build APK.
exit /b 1
:install_failed
echo [실패] 설치 실패. 서명이 다른 같은 앱이 깔려 있으면 지우고 다시: adb uninstall %PKG%
exit /b 1
:not_installed
echo [실패] %PKG% 가 HMD 에 없습니다. 설치부터 하세요 - grant 없이 실행.
exit /b 1
:failed
echo [실패] 위 adb 오류를 확인하세요.
exit /b 1
