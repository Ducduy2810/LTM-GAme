@echo off
chcp 65001 > nul
echo ===============================================================
echo   MO WINDOWS FIREWALL CHO GAME SERVER (Port 3000)
echo ===============================================================
echo.

:: Kiem tra quyen Administrator
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [CAN QUYEN ADMIN] Vui long click chuot phai vao file nay
    echo                       va chon "Run as Administrator"
    echo.
    pause
    exit /b 1
)

echo Dang mo Firewall cho cong 3000 (TCP + UDP)...

:: Xoa quy tac cu neu co
netsh advfirewall firewall delete rule name="Game Server TCP 3000 In" >nul 2>&1
netsh advfirewall firewall delete rule name="Game Server UDP 3000 In" >nul 2>&1
netsh advfirewall firewall delete rule name="Game Server TCP 3000 Out" >nul 2>&1
netsh advfirewall firewall delete rule name="Game Server UDP 3000 Out" >nul 2>&1
netsh advfirewall firewall delete rule name="Java Game Server In" >nul 2>&1
netsh advfirewall firewall delete rule name="Java Game Server Out" >nul 2>&1

:: Mo Firewall Inbound cho cong 3000
netsh advfirewall firewall add rule name="Game Server TCP 3000 In" dir=in action=allow protocol=TCP localport=3000 >nul 2>&1
netsh advfirewall firewall add rule name="Game Server UDP 3000 In" dir=in action=allow protocol=UDP localport=3000 >nul 2>&1

:: Mo Firewall Outbound cho cong 3000
netsh advfirewall firewall add rule name="Game Server TCP 3000 Out" dir=out action=allow protocol=TCP localport=3000 >nul 2>&1
netsh advfirewall firewall add rule name="Game Server UDP 3000 Out" dir=out action=allow protocol=UDP localport=3000 >nul 2>&1

:: Mo Firewall cho chuong trinh Java (tat ca cong)
for %%f in (java.exe javaw.exe) do (
    for /f "delims=" %%p in ('where %%f 2^>nul') do (
        netsh advfirewall firewall add rule name="Java Game Server In" dir=in action=allow program="%%p" >nul 2>&1
        netsh advfirewall firewall add rule name="Java Game Server Out" dir=out action=allow program="%%p" >nul 2>&1
    )
)

echo.
echo [THANH CONG] Da mo Firewall cho cong 3000 va chuong trinh Java!
echo.
echo Cac quy tac da tao:
echo   - Game Server TCP 3000 In/Out
echo   - Game Server UDP 3000 In/Out
echo   - Java Game Server In/Out (cho tat ca cong)
echo.
echo Ban be cung mang LAN gio co the vao game qua IP cua may ban.
echo.
pause
