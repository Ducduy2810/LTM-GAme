@echo off
setlocal
cd /d "%~dp0"
chcp 65001 > nul
echo ===============================================================
echo   Dang bien dich va khoi chay Java Game Server...
echo ===============================================================

:: Dung java va javac tu cung mot JDK de tranh lech phien ban.
set "JDK_BIN="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK_BIN=%JAVA_HOME%\bin"
if not defined JDK_BIN for /f "delims=" %%J in ('where javac.exe 2^>nul') do if not defined JDK_BIN set "JDK_BIN=%%~dpJ"
if not defined JDK_BIN (
    echo [LOI] Khong tim thay JDK. Hay cai JDK 17 tro len va cau hinh JAVA_HOME.
    pause
    exit /b 1
)
if not exist "%JDK_BIN%\java.exe" (
    echo [LOI] Khong tim thay java.exe trong "%JDK_BIN%".
    pause
    exit /b 1
)
echo JDK dang su dung: "%JDK_BIN%"
"%JDK_BIN%\java.exe" -version

if not exist bin mkdir bin
if not exist lib mkdir lib
if not exist data mkdir data

:: Kiem tra SQLite JDBC JAR
set SQLITE_JAR=lib\sqlite-jdbc-3.45.3.0.jar
if not exist %SQLITE_JAR% (
    echo [LOI] Khong tim thay %SQLITE_JAR%
    echo Vui long chay lenh sau de tai ve:
    echo   curl -L -o %SQLITE_JAR% https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.3.0/sqlite-jdbc-3.45.3.0.jar
    pause
    exit /b 1
)

:: Classpath: bin + sqlite JAR + slf4j (required by sqlite-jdbc)
set CP=bin;%SQLITE_JAR%;lib\slf4j-api-2.0.13.jar;lib\slf4j-nop-2.0.13.jar

:: Bien dich voi classpath co SQLite
"%JDK_BIN%\javac.exe" --release 17 -encoding UTF-8 -cp "%SQLITE_JAR%;lib\slf4j-api-2.0.13.jar" -d bin ^
    src/vn/game/config/*.java ^
    src/vn/game/util/*.java ^
    src/vn/game/model/*.java ^
    src/vn/game/db/*.java ^
    src/vn/game/server/*.java ^
    src/vn/game/games/*.java ^
    src/vn/game/*.java

if %errorlevel% neq 0 (
    echo [LOI] Khong the bien dich source code Java!
    pause
    exit /b %errorlevel%
)

echo [THANH CONG] Bien dich hoan tat.

:: Tu dong mo Firewall neu co quyen Admin
net session >nul 2>&1
if %errorlevel% equ 0 (
    echo Dang tu dong mo Firewall cho cong 3000...
    netsh advfirewall firewall delete rule name="Game Server TCP 3000 In" >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server TCP 3000 In" dir=in action=allow protocol=TCP localport=3000 >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server UDP 3000 In" dir=in action=allow protocol=UDP localport=3000 >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server TCP 3000 Out" dir=out action=allow protocol=TCP localport=3000 >nul 2>&1
    echo [OK] Da mo Firewall tu dong!
) else (
    echo [CANH BAO] Khong co quyen Admin - Khong the tu dong mo Firewall
    echo Neu ban be khong vao duoc, hay chay open_firewall.bat ^(Run as Admin^)
)

echo.
echo   Dang khoi dong Server (SQLite DB: data\game.db)...
echo ===============================================================
"%JDK_BIN%\java.exe" -cp "%CP%" vn.game.Main
pause
