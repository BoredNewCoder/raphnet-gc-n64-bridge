# Raphnet GC/N64 Controller Bridge (Unofficial)

Android/Android TV app that reads raw USB HID reports directly from a
**raphnet GC/N64-to-USB v3.6 adapter** and re-emits them as a virtual
gamepad, fixing D-Pad Left (which normally gets eaten by Android as a
reserved system button) and letting you remap C-buttons/Z to modern
RetroPad-style binds.

**This is an unofficial, third-party project. Not affiliated with,
endorsed by, or supported by [raphnet.net](https://raphnet.net).**

## Why this exists

raphnet's GC/N64-to-USB adapter reports D-Pad Left as raw `BTN_MODE` —
the same evdev code Android system-wide reserves for a controller's
Guide/Home button. Android intercepts it before any app, RetroArch
included, ever sees it, so that one input is permanently unbindable
out of the box.

This app claims the adapter's USB interface directly (bypassing
Android's own input pipeline entirely), parses the raw HID reports
itself, and re-emits everything through a real Linux `uinput` virtual
gamepad — identical evdev codes for every other button/axis, except
D-Pad Left, which goes out on `BTN_0` instead (confirmed present in
this device's real keylayout file, unlike other candidate codes that
got silently dropped).

## What works

- D-Pad Left binds normally in RetroArch (the actual point of this app)
- Full button + analog stick + C-stick + trigger support
- Custom remap: C-buttons → right stick, Z → L2 (default build)
- Runs as a foreground service, survives being backgrounded
- Change-detection on outgoing reports (only sends on real movement) —
  keeps emulator input latency low instead of flooding uinput at the
  adapter's native ~170Hz poll rate

## What's in progress

- GameCube mode currently shares N64's device identity/binds — GC gets
  its own auto-detected profile in a future update
- GameCube main stick Y-axis is inverted — known issue, not yet fixed

## Requirements

- Android device with USB Host support (tested on NVIDIA Shield TV Pro)
- [Shizuku](https://shizuku.rikka.app/) installed and running (needed for
  `/dev/uinput` access — see below)
- A raphnet GC/N64-to-USB v3.6 adapter, plugged in via USB-OTG/hub
- "Install unknown apps" allowed for whatever file manager/browser you
  sideload with

## One-time device setup (fresh/reset Shield TV Pro)

None of this is optional — a freshly reset Shield has no developer
options, no Shizuku, no unknown-sources permission. Do this once per
device (Shizuku's service does need restarting after every reboot,
see step 5).

1. **Enable Developer options**: Settings → Device Preferences → About
   → scroll to Build → click **Build** repeatedly (~7x) until it says
   "You are now a developer."
2. **Turn on debugging**: Settings → Device Preferences → Developer
   options → enable **USB debugging** AND **Wireless debugging** (both).
3. **Install Shizuku**: sideload it (Play Store isn't on most Shields —
   grab the APK from shizuku.rikka.app or F-Droid) same way as this app,
   see step 6 below for the unknown-sources toggle.
4. **Pair Shizuku over Wireless debugging**:
   - In Developer options, open **Wireless debugging** → **Pair device
     with pairing code**. It shows an IP:port and a 6-digit code.
   - Open the Shizuku app, choose the wireless-debugging pairing option,
     enter that code using the Shield remote (arrow-nav + select on the
     on-screen keyboard — no physical keyboard needed, just slow).
   - Once paired, go back to Shizuku's start screen and tap **Start**.
     It should report "Running."
5. **Every reboot**: Shizuku's service does not survive a reboot on a
   non-rooted device — reopen the Shizuku app and tap **Start** again
   after any Shield restart, before opening this bridge app.
6. **Allow unknown sources**: whichever app you use to open the APK
   file (file manager, browser download) will prompt "Install unknown
   apps" the first time — allow it for that app specifically.

## Install

1. Grab the APK from this repo's [Releases](../../releases) page (or
   build it yourself, see below), and copy it to the device (USB drive,
   Syncthing, `adb push`, browser download — your choice).
2. Open it on-device and allow install from unknown sources if prompted
   (see step 6 above).
3. Make sure Shizuku is running (step 4/5 above) — this app will not
   work without it.
4. Open this app, grant it Shizuku permission when prompted.
5. Plug in the raphnet adapter with a GC or N64 controller attached.
6. In RetroArch, the device will show up as **"Raphnet GC-N64 Bridge
   Gamepad"** — bind normally, D-Pad Left now works.

### Why Shizuku is required

`/dev/uinput` (used to create the virtual gamepad) is only writable by
the `shell` UID on stock/non-rooted Android. Shizuku is what grants this
app a shell-UID child process to own that device. No root needed.

## Building from source

Standard Android Studio / Gradle project, Kotlin + JNI/C uinput backend.

```
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`.

## Support

If this saved you time or you just want to say thanks:

**Cash App:** [$CVanZetta](https://cash.app/$CVanZetta)
