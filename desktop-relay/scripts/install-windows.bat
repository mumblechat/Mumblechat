@echo off
REM MumbleChat Relay Node - Windows Installation Script
REM
REM Features:
REM - Auto-detects CPU, RAM, Disk to calculate max nodes
REM - Creates isolated storage directories per node
REM - Locks/reserves storage space using fsutil
REM - Enforces resource limits per machine
REM
REM Run as Administrator

setlocal EnableDelayedExpansion

echo.
echo ================================================================
echo         MumbleChat Desktop Relay Node - Windows Installer
echo ================================================================
echo.

REM Check admin rights
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo ERROR: Please run as Administrator
    pause
    exit /b 1
)

REM Configuration paths
set "MUMBLECHAT_BASE=%ProgramData%\MumbleChat"
set "NODES_DIR=%MUMBLECHAT_BASE%\nodes"
set "CONFIG_DIR=%MUMBLECHAT_BASE%\config"
set "LOCK_FILE=%MUMBLECHAT_BASE%\.storage.lock"
set "RESOURCE_FILE=%CONFIG_DIR%\resources.json"

REM Create base directories
if not exist "%MUMBLECHAT_BASE%" mkdir "%MUMBLECHAT_BASE%"
if not exist "%MUMBLECHAT_BASE%\data" mkdir "%MUMBLECHAT_BASE%\data"
if not exist "%MUMBLECHAT_BASE%\logs" mkdir "%MUMBLECHAT_BASE%\logs"
if not exist "%CONFIG_DIR%" mkdir "%CONFIG_DIR%"
if not exist "%NODES_DIR%" mkdir "%NODES_DIR%"

REM Handle command line arguments
if "%1"=="--info" goto :show_info
if "%1"=="info" goto :show_info
if "%1"=="--list" goto :list_nodes
if "%1"=="list" goto :list_nodes
if "%1"=="--lock" goto :lock_storage
if "%1"=="lock" goto :lock_storage
if "%1"=="--unlock" goto :unlock_storage
if "%1"=="unlock" goto :unlock_storage
if "%1"=="--help" goto :show_help
if "%1"=="help" goto :show_help
if "%1"=="-h" goto :show_help

REM =============================================================================
REM RESOURCE DETECTION
REM =============================================================================

:detect_resources
echo Detecting system resources...
echo.

REM Get CPU cores
for /f "tokens=2 delims==" %%a in ('wmic cpu get NumberOfLogicalProcessors /value ^| find "="') do set CPU_CORES=%%a
set CPU_CORES=%CPU_CORES: =%

REM Get total RAM in MB
for /f "tokens=2 delims==" %%a in ('wmic OS get TotalVisibleMemorySize /value ^| find "="') do set RAM_KB=%%a
set RAM_KB=%RAM_KB: =%
set /a RAM_MB=%RAM_KB% / 1024

REM Get free RAM in MB
for /f "tokens=2 delims==" %%a in ('wmic OS get FreePhysicalMemory /value ^| find "="') do set FREE_RAM_KB=%%a
set FREE_RAM_KB=%FREE_RAM_KB: =%
set /a FREE_RAM_MB=%FREE_RAM_KB% / 1024

REM Get disk free space in MB (C: drive)
for /f "tokens=3" %%a in ('dir /-c %MUMBLECHAT_BASE% ^| find "bytes free"') do set FREE_BYTES=%%a
set FREE_BYTES=%FREE_BYTES:,=%
set /a DISK_FREE_MB=%FREE_BYTES:~0,-6%
if "%DISK_FREE_MB%"=="" set DISK_FREE_MB=50000

REM Get machine ID (use motherboard serial)
for /f "tokens=2 delims==" %%a in ('wmic baseboard get serialnumber /value ^| find "="') do set MACHINE_ID=%%a
if "%MACHINE_ID%"=="" set MACHINE_ID=%COMPUTERNAME%

REM Calculate locked storage
set LOCKED_MB=0
if exist "%LOCK_FILE%" (
    for /f "tokens=2 delims=:" %%a in ('findstr "total_locked_mb" "%LOCK_FILE%"') do (
        set LOCKED_MB=%%a
        set LOCKED_MB=!LOCKED_MB: =!
        set LOCKED_MB=!LOCKED_MB:,=!
    )
)

REM Calculate max nodes
set /a MAX_BY_CPU=%CPU_CORES% * 2
set /a MAX_BY_RAM=%RAM_MB% / 256
set /a MAX_BY_DISK=%DISK_FREE_MB% / 1024

set MAX_NODES=%MAX_BY_CPU%
if %MAX_BY_RAM% lss %MAX_NODES% set MAX_NODES=%MAX_BY_RAM%
if %MAX_BY_DISK% lss %MAX_NODES% set MAX_NODES=%MAX_BY_DISK%
if %MAX_NODES% gtr 10 set MAX_NODES=10
if %MAX_NODES% lss 1 set MAX_NODES=1

REM Count deployed nodes
set DEPLOYED_NODES=0
for /d %%d in ("%NODES_DIR%\*") do set /a DEPLOYED_NODES+=1

set /a AVAILABLE_SLOTS=%MAX_NODES% - %DEPLOYED_NODES%
set /a AVAILABLE_DISK_MB=%DISK_FREE_MB% - %LOCKED_MB% - 1024

echo ================================================================
echo                     SYSTEM RESOURCES
echo ================================================================
echo   Machine ID: %MACHINE_ID%
echo ----------------------------------------------------------------
echo   CPU Cores:        %CPU_CORES% (Max %MAX_BY_CPU% nodes by CPU)
echo   RAM Total:        %RAM_MB% MB (Max %MAX_BY_RAM% nodes by RAM)
echo   Disk Free:        %DISK_FREE_MB% MB (Max %MAX_BY_DISK% nodes by Disk)
echo ----------------------------------------------------------------
echo   Storage Locked:   %LOCKED_MB% MB (by %DEPLOYED_NODES% nodes)
echo   Storage Available: %AVAILABLE_DISK_MB% MB
echo ----------------------------------------------------------------
echo   MAX NODES ALLOWED: %MAX_NODES%
echo   NODES DEPLOYED:    %DEPLOYED_NODES%
echo   SLOTS AVAILABLE:   %AVAILABLE_SLOTS%
echo ================================================================
echo.

REM Save resource info
(
echo {
echo   "machine_id": "%MACHINE_ID%",
echo   "cpu_cores": %CPU_CORES%,
echo   "ram_total_mb": %RAM_MB%,
echo   "disk_free_mb": %DISK_FREE_MB%,
echo   "disk_locked_mb": %LOCKED_MB%,
echo   "disk_available_mb": %AVAILABLE_DISK_MB%,
echo   "max_nodes": %MAX_NODES%,
echo   "deployed_nodes": %DEPLOYED_NODES%,
echo   "available_slots": %AVAILABLE_SLOTS%
echo }
) > "%RESOURCE_FILE%"

goto :install

REM =============================================================================
REM SHOW INFO
REM =============================================================================

:show_info
call :detect_resources
goto :eof

REM =============================================================================
REM LIST NODES
REM =============================================================================

:list_nodes
echo.
echo ================================================================
echo                     DEPLOYED NODES
echo ================================================================

set NODE_COUNT=0
for /d %%d in ("%NODES_DIR%\*") do (
    set /a NODE_COUNT+=1
    set "NODE_ID=%%~nxd"
    
    if exist "%%d\node.json" (
        for /f "tokens=2 delims=:" %%s in ('findstr "storage_mb" "%%d\node.json"') do (
            set STORAGE=%%s
            set STORAGE=!STORAGE: =!
            set STORAGE=!STORAGE:,=!
        )
    ) else (
        set STORAGE=?
    )
    
    echo   !NODE_COUNT!. !NODE_ID:~0,20!... - !STORAGE! MB - %%d
)

if %NODE_COUNT%==0 echo   No nodes deployed yet.
echo ================================================================
echo.
goto :eof

REM =============================================================================
REM LOCK STORAGE
REM =============================================================================

:lock_storage
if "%2"=="" (
    echo Usage: %0 --lock ^<node_id^> ^<storage_mb^>
    goto :eof
)
if "%3"=="" (
    echo Usage: %0 --lock ^<node_id^> ^<storage_mb^>
    goto :eof
)

set "NODE_ID=%2"
set "STORAGE_MB=%3"
set "NODE_DIR=%NODES_DIR%\%NODE_ID%"

REM Check if exists
if exist "%NODE_DIR%" (
    echo ERROR: Node %NODE_ID% already exists!
    goto :eof
)

REM Detect resources first
call :detect_resources >nul

REM Check max nodes
if %DEPLOYED_NODES% geq %MAX_NODES% (
    echo ERROR: Max nodes ^(%MAX_NODES%^) reached! Cannot deploy more nodes.
    goto :eof
)

REM Check storage
if %STORAGE_MB% gtr %AVAILABLE_DISK_MB% (
    echo ERROR: Not enough storage available!
    echo Requested: %STORAGE_MB% MB, Available: %AVAILABLE_DISK_MB% MB
    goto :eof
)

echo Locking %STORAGE_MB% MB storage for node %NODE_ID%...

REM Create directories
mkdir "%NODE_DIR%"
mkdir "%NODE_DIR%\storage"
mkdir "%NODE_DIR%\logs"
mkdir "%NODE_DIR%\keys"
mkdir "%NODE_DIR%\cache"

REM Create reserved space file using fsutil (Windows)
set "RESERVE_FILE=%NODE_DIR%\storage\.reserved_space"
set /a STORAGE_BYTES=%STORAGE_MB% * 1048576

echo Allocating %STORAGE_MB% MB disk space...
fsutil file createnew "%RESERVE_FILE%" %STORAGE_BYTES% >nul 2>&1
if %errorLevel% neq 0 (
    REM Fallback: create file with certutil
    echo Creating sparse file...
    fsutil sparse setflag "%RESERVE_FILE%" >nul 2>&1
    fsutil sparse setrange "%RESERVE_FILE%" 0 %STORAGE_BYTES% >nul 2>&1
)

REM Set file as hidden and system (protection)
attrib +h +s "%RESERVE_FILE%" >nul 2>&1

REM Create node info
(
echo {
echo   "node_id": "%NODE_ID%",
echo   "storage_mb": %STORAGE_MB%,
echo   "locked_at": "%date% %time%",
echo   "storage_path": "%NODE_DIR%\storage",
echo   "reserve_file": "%RESERVE_FILE%",
echo   "status": "locked"
echo }
) > "%NODE_DIR%\node.json"

REM Update lock file
set /a NEW_LOCKED=%LOCKED_MB% + %STORAGE_MB%
set /a NEW_DEPLOYED=%DEPLOYED_NODES% + 1

(
echo {
echo   "total_locked_mb": %NEW_LOCKED%,
echo   "total_nodes": %NEW_DEPLOYED%,
echo   "updated_at": "%date% %time%"
echo }
) > "%LOCK_FILE%"

echo.
echo SUCCESS: Storage locked - %STORAGE_MB% MB for node %NODE_ID%
echo Node directory: %NODE_DIR%
echo.
goto :eof

REM =============================================================================
REM UNLOCK STORAGE
REM =============================================================================

:unlock_storage
if "%2"=="" (
    echo Usage: %0 --unlock ^<node_id^>
    goto :eof
)

set "NODE_ID=%2"
set "NODE_DIR=%NODES_DIR%\%NODE_ID%"

if not exist "%NODE_DIR%" (
    echo ERROR: Node %NODE_ID% not found!
    goto :eof
)

echo Unlocking storage for node %NODE_ID%...

REM Get storage amount
set STORAGE_MB=0
if exist "%NODE_DIR%\node.json" (
    for /f "tokens=2 delims=:" %%s in ('findstr "storage_mb" "%NODE_DIR%\node.json"') do (
        set STORAGE_MB=%%s
        set STORAGE_MB=!STORAGE_MB: =!
        set STORAGE_MB=!STORAGE_MB:,=!
    )
)

REM Remove attributes
attrib -h -s "%NODE_DIR%\storage\.reserved_space" >nul 2>&1

REM Remove directory
rmdir /s /q "%NODE_DIR%" >nul 2>&1

REM Update lock file
call :detect_resources >nul

(
echo {
echo   "total_locked_mb": %LOCKED_MB%,
echo   "total_nodes": %DEPLOYED_NODES%,
echo   "updated_at": "%date% %time%"
echo }
) > "%LOCK_FILE%"

echo.
echo SUCCESS: Storage unlocked - %STORAGE_MB% MB freed from node %NODE_ID%
echo.
goto :eof

REM =============================================================================
REM SHOW HELP
REM =============================================================================

:show_help
echo MumbleChat Relay Node - Windows Installer
echo.
echo Usage: %0 [command]
echo.
echo Commands:
echo   (none)              Full installation
echo   --info              Show system resources and limits
echo   --list              List deployed nodes
echo   --lock ^<id^> ^<mb^>    Lock storage for new node
echo   --unlock ^<id^>       Unlock storage and remove node
echo   --help              Show this help
echo.
goto :eof

REM =============================================================================
REM INSTALLATION
REM =============================================================================

:install

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
if not exist "%CONFIG_DIR%\config.json" (
    echo Copying default configuration...
    copy config.example.json "%CONFIG_DIR%\config.json"
)

REM Create env file with resource limits
(
echo # MumbleChat Relay Node Environment
echo # RELAY_PRIVATE_KEY=0x...
echo.
echo MAX_NODES=%MAX_NODES%
echo MACHINE_ID=%MACHINE_ID%
) > "%CONFIG_DIR%\relay.env"

REM Create start script
echo Creating start script...
(
echo @echo off
echo cd /d "%MUMBLECHAT_BASE%"
echo mumblechat-relay start --config "%CONFIG_DIR%\config.json"
) > "%MUMBLECHAT_BASE%\start-relay.bat"

REM Create storage management script
(
echo @echo off
echo setlocal
echo.
echo if "%%1"=="info" goto info
echo if "%%1"=="list" goto list
echo if "%%1"=="lock" goto lock
echo if "%%1"=="unlock" goto unlock
echo.
echo echo MumbleChat Storage Management
echo echo.
echo echo Usage: mumblechat-storage ^<command^>
echo echo.
echo echo Commands:
echo echo   info           Show system resources
echo echo   list           List deployed nodes
echo echo   lock ^<id^> ^<mb^> Lock storage for node
echo echo   unlock ^<id^>    Unlock storage
echo goto :eof
echo.
echo :info
echo "%SCRIPT_DIR%install-windows.bat" --info
echo goto :eof
echo.
echo :list
echo "%SCRIPT_DIR%install-windows.bat" --list
echo goto :eof
echo.
echo :lock
echo "%SCRIPT_DIR%install-windows.bat" --lock %%2 %%3
echo goto :eof
echo.
echo :unlock
echo "%SCRIPT_DIR%install-windows.bat" --unlock %%2
echo goto :eof
) > "%MUMBLECHAT_BASE%\mumblechat-storage.bat"

REM Add to PATH
setx PATH "%PATH%;%MUMBLECHAT_BASE%" /M >nul 2>&1

REM Auto-start option
echo.
echo Would you like to configure auto-start on Windows boot? (Y/N)
set /p AUTOSTART=

if /i "%AUTOSTART%"=="Y" (
    echo Creating scheduled task...
    schtasks /create /tn "MumbleChat Relay" /tr "\"%MUMBLECHAT_BASE%\start-relay.bat\"" /sc onlogon /rl highest /f >nul 2>&1
    echo Scheduled task created.
)

echo.
echo ================================================================
echo                     Installation Complete!
echo ================================================================
echo.
echo   Max Nodes: %MAX_NODES%   Available Storage: %AVAILABLE_DISK_MB% MB
echo.
echo   Storage Commands:
echo     mumblechat-storage info
echo     mumblechat-storage list
echo     mumblechat-storage lock ^<node_id^> ^<storage_mb^>
echo     mumblechat-storage unlock ^<node_id^>
echo.
echo   Next steps:
echo   1. Edit %CONFIG_DIR%\relay.env with your private key
echo   2. Run: mumblechat-relay setup
echo   3. Run: mumblechat-relay register
echo   4. Run: mumblechat-relay start
echo.
echo   Or use: "%MUMBLECHAT_BASE%\start-relay.bat"
echo.
pause
