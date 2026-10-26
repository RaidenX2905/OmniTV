# OmniTV Setup & Deployment Guide

## 1. Quick Build

Run from project root:

```bash
./gradlew assembleDebug
```

Output APK:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Deploy to Android TV / Fire TV

### Method A: ADB Over Wi-Fi
1. Enable Developer Options & USB/Network Debugging on Android TV (Settings -> Device Preferences -> About -> Tap Build 7 times -> Developer Options).
2. Note TV IP Address (Settings -> Network).
3. Connect and install:
```bash
adb connect <TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Method B: USB Drive
1. Copy `app-debug.apk` to a USB flash drive.
2. Plug into the TV USB port.
3. Use file manager on the TV to install.

---

## 3. Remote Control Shortcuts
- **OK / D-pad Center**: Show/hide channel guide.
- **D-pad LEFT / RIGHT**: Switch previous / next adjacent channels.
- **D-pad UP / DOWN**: Scroll channels / quick step.
- **Green / Yellow / 0 / Audio Key**: Cycle Aspect Ratio (Fit, Fill, Stretch).
- **BACK**: Close overlay or exit player.
- **MENU**: Change playlist URL.
