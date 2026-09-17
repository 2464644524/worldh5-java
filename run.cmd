@echo off
chcp 65001 >nul
cd /d %~dp0
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;C:\Program Files\nodejs;%PATH%"
set "MAVEN_OPTS=-Xms128m -Xmx1024m"
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
set "PLAYWRIGHT_SKIP_BROWSER_GC=1"
"%~dp0tools\apache-maven-3.9.11\bin\mvn.cmd" -o -s "%USERPROFILE%\.m2\settings.xml" -q compile exec:java
if errorlevel 1 (
  echo.
  echo 离线启动失败。请把本窗口上方红色报错截图发给开发者。
  pause
)