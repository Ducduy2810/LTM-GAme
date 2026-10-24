@echo off
chcp 65001 > nul

if not exist bin mkdir bin
if not exist lib mkdir lib
if not exist data mkdir data

if not exist .env (
    echo [LOI] Khong tim thay file .env - Vui long tao tu file .env.example
    pause
    exit /b 1
)

set SQLITE_JAR=lib\sqlite-jdbc-3.45.3.0.jar
if not exist %SQLITE_JAR% (
    echo [LOI] Khong tim thay %SQLITE_JAR%
    pause
    exit /b 1
)

set CP=bin;%SQLITE_JAR%;lib\slf4j-api-2.0.13.jar;lib\slf4j-nop-2.0.13.jar

javac -encoding UTF-8 -cp "%SQLITE_JAR%;lib\slf4j-api-2.0.13.jar" -d bin ^
    src/vn/game/config/*.java ^
    src/vn/game/util/*.java ^
    src/vn/game/model/*.java ^
    src/vn/game/db/*.java ^
    src/vn/game/server/*.java ^
    src/vn/game/games/*.java ^
    src/vn/game/*.java

if %errorlevel% neq 0 (
    echo [LOI] Bien dich that bai!
    pause
    exit /b %errorlevel%
)

net session >nul 2>&1
if %errorlevel% equ 0 (
    netsh advfirewall firewall delete rule name="Game Server TCP 3000 In" >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server TCP 3000 In" dir=in action=allow protocol=TCP localport=3000 >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server UDP 3000 In" dir=in action=allow protocol=UDP localport=3000 >nul 2>&1
    netsh advfirewall firewall add rule name="Game Server TCP 3000 Out" dir=out action=allow protocol=TCP localport=3000 >nul 2>&1
)

java -cp %CP% vn.game.Main
pause
