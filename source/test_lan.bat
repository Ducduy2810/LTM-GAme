@echo off
chcp 65001 > nul
echo ===============================================================
echo   KIEM TRA KET NOI MANG LAN
echo ===============================================================
echo.

:: Hien thi IP cua may nay
echo [1] Dia chi IP cua may ban:
ipconfig | findstr /i "IPv4"
echo.

:: Kiem tra server co dang chay khong
echo [2] Kiem tra Server co dang chay o cong 3000 khong:
netstat -an | findstr ":3000 " | findstr "LISTENING"
if %errorlevel% neq 0 (
    echo   [CANH BAO] Khong tim thay Server o cong 3000!
    echo   Vui long chay run.bat truoc de khoi dong Server.
    goto :end
)
echo   [OK] Server dang lang nghe o cong 3000
echo.

:: Kiem tra server co bind 0.0.0.0 (chap nhan LAN) hay chi localhost
echo [3] Kiem tra Server co chap nhan ket noi LAN khong:
netstat -an | findstr ":3000 " | findstr "LISTENING" | findstr "0.0.0.0" >nul 2>&1
if %errorlevel% equ 0 (
    echo   [OK] Server bind 0.0.0.0 - Chap nhan ket noi tu LAN
) else (
    netstat -an | findstr ":3000 " | findstr "LISTENING" | findstr "127.0.0.1" >nul 2>&1
    if %errorlevel% equ 0 (
        echo   [LOI] Server chi bind 127.0.0.1 - KHONG chap nhan LAN!
        echo   Vui long bien dich lai: javac -encoding UTF-8 -d bin src/vn/game/server/*.java
        goto :end
    ) else (
        echo   [OK] Server dang lang nghe, co the chap nhan LAN
    )
)
echo.

:: Kiem tra firewall
echo [4] Kiem tra Windows Firewall cho cong 3000:
netsh advfirewall firewall show rule name="Game Server TCP 3000 In" 2>nul | findstr "Enabled" >nul
if %errorlevel% equ 0 (
    echo   [OK] Quy tac Firewall da duoc mo
) else (
    echo   [CANH BAO] Chua mo Firewall! Chay open_firewall.bat (Run as Admin)
)
echo.

:: Kiem tra profile mang
echo [5] Kiem tra Profile mang hien tai:
powershell -Command "Get-NetConnectionProfile | Select-Object Name, NetworkCategory" 2>nul
echo   Neu mang la "Public", thu doi thanh "Private":
echo   Settings ^> Network ^> Properties ^> Network profile = Private
echo.

:: Hien thi huong dan
echo [6] Huong dan cho ban be:
echo   - Nhap IP hien thi o tren (vi du: http://192.168.1.100:3000)
echo   - Mo trong trinh duyet bat ky
echo   - Neu khong vao duoc, thu ping IP may ban tu may ban be
echo   - Neu ping duoc nhung khong vao duoc web -> chay open_firewall.bat
echo.

:end
pause
