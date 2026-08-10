@echo off
where java >nul 2>nul
if errorlevel 1 (
    echo Java 21 was not found on your PATH.
    echo Install it from https://adoptium.net/temurin/releases/?version=21 then try again.
    pause
    exit /b 1
)

java -Xmx768m --add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED -jar "%~dp0client.jar"

if errorlevel 1 (
    echo.
    echo Zelus exited with an error. Press any key to close.
    pause >nul
)
