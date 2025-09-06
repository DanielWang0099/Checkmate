@echo off
REM Simple batch wrapper for the PowerShell script
REM Can be run from anywhere in the Checkmate project

pushd "%~dp0"
powershell -ExecutionPolicy Bypass -File "ultimate-install-log.ps1" %*
popd
