# ShakeWake

Android app that runs as an accessibility service to provide shake-to-wake and volume key shortcuts.

## Features

| Action | Result |
|--------|--------|
| 🔴 Shake the phone | Wake the screen |
| 📸 Double-press Volume Down | Take a screenshot |
| 🔒 Double-press Volume Up | Lock / turn off the screen |
| 🔊 Single press Volume Up/Down | Normal volume change (350 ms delay) |

## How It Works

### Shake Detection (battery-efficient)
Uses continuous accelerometer listening for reliability. If the device exposes a wake-up accelerometer, the app uses it directly; otherwise it keeps a partial wake lock active while shake detection is enabled so shake-to-wake still works with the screen off.

### Volume Key Interception
The accessibility service intercepts all volume key events. On the first press, it waits 350 ms — if a second press of the same key arrives within that window, the special action (screenshot or lock) fires. Otherwise, the normal volume adjustment is performed via `AudioManager`.

### Screenshot
Uses `AccessibilityService.takeScreenshot()` on Android 11 / API 30+ for direct capture and saving to **Pictures/ShakeWake/** via the MediaStore API. On Android 10 (API 29), the app falls back to the platform screenshot action so the feature still works.

### Lock Screen
Uses `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` (available on Android 9 / API 28+).

### Wake Screen
Uses `PowerManager.SCREEN_BRIGHT_WAKE_LOCK` with `ACQUIRE_CAUSES_WAKEUP` (deprecated but still functional — the only way to wake the screen from a service without system-level permissions).

## Setup

1. Open the project in **Android Studio**
2. Build and install on your device
3. Open the app and tap **"Open Accessibility Settings"**
4. Find **ShakeWake** in the accessibility services list
5. Enable it and accept the permission dialog

## Permissions

| Permission | Purpose |
|-----------|---------|
| `WAKE_LOCK` | Wake the screen on shake |
| `VIBRATE` | Haptic feedback on double-press |
| Accessibility Service | Intercept volume key events, take screenshots, lock screen |

## Requirements

- Android 10 (API 29) or later
- Screenshot capture works on Android 10+; Android 11+ uses the direct accessibility screenshot API

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
├── java/com/shakewake/
│   ├── MainActivity.kt              # Setup screen, permissions, status
│   ├── ShakeWakeAccessibilityService.kt  # Core service
│   └── ShakeDetector.kt            # Always-on accelerometer shake detection
└── res/
    ├── xml/accessibility_service_config.xml
    ├── layout/activity_main.xml
    ├── values/{strings,colors,themes}.xml
    └── drawable/
```
