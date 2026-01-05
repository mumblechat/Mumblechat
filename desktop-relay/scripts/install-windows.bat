@echo off
REM MumbleChat Relay Node - Windows Installation Script
REM
REM This script installs the MumbleChat Desktop Relay Node on Windows.
REM Run as Administrator

echo.
echo ================================================================
echo         MumbleChat Desktop Relay Node - Windows Installer
echo ================================================================
echo.

REM Check for Node.js
where node >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo Node.js not found. Please install Node.js 18+ from:
    echo https://nodejs.org/
    echo.
    pause
    exit /b 1
)

REM Check Node version
for /f "tokens=1 delims=v" %%i in ('node -v') do set NODE_VERSION=%%i
echo Node.js version: %NODE_VERSION%

REM Create directories
echo Creating directories...
if not exist "%ProgramData%\MumbleChat" mkdir "%ProgramData%\MumbleChat"
if not exist "%ProgramData%\MumbleChat\data" mkdir "%ProgramData%\MumbleChat\data"
if not exist "%ProgramData%\MumbleChat\logs" mkdir "%ProgramData%\MumbleChat\logs"
if not exist "%ProgramData%\MumbleChat\config" mkdir "%ProgramData%\MumbleChat\config"

REM Get script directory
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%.."

REM Install dependencies and build
echo Installing dependencies...
call npm install

echo Building...
call npm run build

REM Install globally
echo Installing globally...
call npm link

REM Copy default config
if not exist "%ProgramData%\MumbleChat\config\config.json" (
    echo Copying default configuration...
    copy config.example.json "%ProgramData%\MumbleChat\config\config.json"
)

REM Create start script
echo Creating start script...
(
echo @echo off
echo cd /d "%ProgramData%\MumbleChat"
echo mumblechat-relay start --config "%ProgramData%\MumbleChat\config\config.json"
) > "%ProgramData%\MumbleChat\start-relay.bat"

REM Create Windows Task Scheduler task for auto-start
echo.
echo Would you like to configure auto-start on Windows boot? (Y/N)
set /p AUTOSTART=

if /i "%AUTOSTART%"=="Y" (
    echo Creating scheduled task...
    schtasks /create /tn "MumbleChat Relay" /tr "\"%ProgramData%\MumbleChat\start-relay.bat\"" /sc onlogon /rl highest /f
    echo Scheduled task created.
)

echo.
echo ================================================================
echo                     Installation Complete!
echo ================================================================
echo.
echo Next steps:
echo.
echo 1. Run the setup wizard:
echo    mumblechat-relay setup
echo.
echo 2. Register on blockchain:
echo    mumblechat-relay register
echo.
echo 3. Start the relay:
echo    mumblechat-relay start
echo.
echo    Or use the start script:
echo    "%ProgramData%\MumbleChat\start-relay.bat"
echo.
echo Configuration: %ProgramData%\MumbleChat\config\config.json
echo Logs: %ProgramData%\MumbleChat\logs\
echo.
pause
