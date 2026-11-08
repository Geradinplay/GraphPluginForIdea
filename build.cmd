@echo off
setlocal
cd /d "%~dp0"
echo === Readable Call Graph / Maven / Java 17 ===
echo.
where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven was not found in PATH.
  echo Open pom.xml in IntelliJ IDEA, then use Maven tool window - Lifecycle - package.
  pause
  exit /b 1
)
mvn -B -DskipTests=false clean package
if errorlevel 1 (
  echo.
  echo BUILD FAILED. See error above.
  pause
  exit /b 1
)
echo.
echo Plugin: target\ReadableCallGraph-0.2.0.zip
pause
