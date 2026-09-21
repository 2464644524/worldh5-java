@echo off
chcp 65001 >nul
cd /d %~dp0
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Program Files\nodejs;%PATH%"
set "MAVEN_OPTS=-Xms128m -Xmx1024m"
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set "PLAYWRIGHT_SKIP_BROWSER_GC=1"
set "WORLD_AUTO_PILOT=1"
set "RESTART_COUNT=0"

:start_app
"%~dp0tools\apache-maven-3.9.11\bin\mvn.cmd" -o -s "%USERPROFILE%\.m2\settings.xml" -q compile exec:java
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" exit /b 0

set /a RESTART_COUNT+=1
if %RESTART_COUNT% geq 10 (
  echo %date% %time%  Auxiliary crashed 10 times. Last exit code: %EXIT_CODE% >> "%~dp0data\??????.txt"
  exit /b %EXIT_CODE%
)
echo %date% %time%  Auxiliary exited with code %EXIT_CODE%; restarting %RESTART_COUNT%/10 in 10 seconds... >> "%~dp0data\??????.txt"
timeout /t 10 /nobreak >nul
goto start_app