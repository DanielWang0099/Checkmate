# Checkmate Install & Log Script

This directory contains the ultimate PowerShell script to automate APK installation and log capture for the Checkmate app.

## Quick Start

The simplest way to install and capture logs from **any directory** within the project:

```powershell
# From the logcat-scripts directory
.\run.ps1

# From anywhere in the project (using full path)
.\logcat-scripts\run.ps1

# Or directly run the main script
.\logcat-scripts\ultimate-install-log.ps1
```

**The script automatically finds your Checkmate project** no matter where you run it from!

## What it does

1. **Auto-detects** the Checkmate project directory
2. **Clears** logcat buffer for clean logs
3. **Installs** the debug APK using `gradlew installDebug`
4. **Captures** logs in real-time to your terminal
5. **Saves** everything to `checkmate-logcat.txt` in the `logcat-scripts` directory
6. **Stops** cleanly when you press Ctrl+C
7. **Shows** a summary with file size, line count, and any crashes found

## Script Options

### `ultimate-install-log.ps1`
The main script with filtering options:

```powershell
# Default: App + important system logs (recommended)
.\ultimate-install-log.ps1

# Capture only our app logs  
.\ultimate-install-log.ps1 -AppOnly

# Capture all logs (unfiltered - use for deep debugging)
.\ultimate-install-log.ps1 -AllLogs

# Custom log filename
.\ultimate-install-log.ps1 -LogFile "my-test-run.txt"
```

### `run.ps1` 
Simple alias to the ultimate script with default settings.

## Works From Anywhere

You can run these scripts from:
- ✅ Project root (`C:\...\Checkmate\`)  
- ✅ Frontend directory (`C:\...\Checkmate\frontend\`)
- ✅ Logcat-scripts directory (`C:\...\Checkmate\logcat-scripts\`)
- ✅ Any subdirectory within the project

The script will automatically find the Checkmate project and navigate to the correct directories.

## Usage Tips

1. **For crash debugging**: Use default settings (captures app + system errors)
2. **For performance analysis**: Use `-AppOnly` (just your app logs)
3. **For deep debugging**: Use `-AllLogs` (everything)
4. **Always check the summary** at the end - it highlights crashes and errors

## Log File Location

Logs are saved in the same directory as the scripts: `logcat-scripts\checkmate-logcat.txt`

This keeps everything organized and makes it easy to find your logs alongside the scripts.

## Requirements

- Windows PowerShell
- `adb` in your PATH (Android SDK)
- Android device/emulator connected and authorized
