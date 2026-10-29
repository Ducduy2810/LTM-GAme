@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
set "JDK_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK_BIN=%JAVA_HOME%\bin"
if not defined JDK_BIN for /f "delims=" %%J in ('where javac.exe 2^>nul') do if not defined JDK_BIN set "JDK_BIN=%%~dpJ"
if not defined JDK_BIN (
    echo JDK 17 or newer is required.
    pause
    exit /b 1
)
if not exist bin mkdir bin
"%JDK_BIN%\javac.exe" --release 17 -encoding UTF-8 -d bin src/vn/game/config/*.java src/vn/game/util/*.java src/vn/game/model/*.java src/vn/game/db/*.java src/vn/game/server/*.java src/vn/game/games/*.java src/vn/game/*.java
if errorlevel 1 (
    pause
    exit /b 1
)
echo Create a separate administrator account. No default password is used.
"%JDK_BIN%\java.exe" -cp "bin;lib/*" vn.game.db.AdminAccount --create
pause
