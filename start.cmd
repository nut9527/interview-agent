@echo off
node "%~dp0scripts\dev.mjs" %*
if errorlevel 1 (
  echo.
  echo Startup failed. See the message above.
  pause
  exit /b 1
)
