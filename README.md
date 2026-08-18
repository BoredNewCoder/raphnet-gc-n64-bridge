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
- USB reads are wrapped so a single bad transfer can't crash the app
  (Android's default exception handling is process-wide, not per-thread —
  a naive reader loop can take the whole app down over one bad transfer)

## What works (continued)

- **N64/GC mode toggle** — tap the button under the log in-app to switch.
  Persisted across restarts, picks a distinct uinput device name per mode
  ("Raphnet N64 Bridge Gamepad" / "Raphnet GC Bridge Gamepad") so
  RetroArch keeps fully independent, non-colliding autoconfig profiles
  for each. Replaces an earlier auto-detect attempt via the adapter's
  `RQ_RNT_GET_CONTROLLER_TYPE` vendor command, which was investigated
  and ruled out — this adapter's firmware doesn't respond on that
  command channel at all in normal mode (confirmed live via a sanity
  check with a simpler command too, not just one failed attempt).
- **GameCube main stick Y-axis fix** — GC mode applies a confirmed
  correction for the raw stick reading coming out inverted; N64's
  formula is untouched (already validated correct across a full real
  play session before this fix existed).
- Main stick and C-stick both confirmed live at the raw USB level for
  GameCube mode too — full range, clean values, no dead zone on either
  axis. Binding them (and the rest of GC's RetroPad set) in RetroArch is
  a one-time per-device setup step, same as any brand-new controller
  identity, not an app bug.

## Dolphin support

Also works with the [Dolphin](https://dolphin-emu.org/) Android app for
GameCube emulation — device shows up the same way as in RetroArch
("Raphnet GC Bridge Gamepad" in Dolphin's Device picker under
GameCube Input → Controller 1 → Configure, once the port's type is set
to **Standard Controller**, not the GameCube Adapter preset).

D-Pad is emitted as a real `ABS_HAT0X`/`ABS_HAT0Y` hat axis (in addition
to, but no longer conflicting with, the discrete per-direction bits used
elsewhere) — Dolphin's own Android controller listener doesn't treat
`BUTTON_SELECT`/`BUTTON_1` (the discrete codes bit10/bit12 go out on) as
bindable GameCube buttons the way RetroArch's does, even though both
codes are real, live-confirmed to fire and translate to genuine Android
keycodes. The hat axis is the conventional D-Pad representation nearly
every emulator's Android backend expects, so it binds cleanly instead.
L/R triggers: bind the **L**/**R** rows to the real digital click bits
(`BUTTON_Y`/`BUTTON_Z` in Dolphin's capture, matching bit4/bit5 — an
Android keycode naming coincidence, unrelated to GameCube's own Z
button), and **L-Analog**/**R-Analog** to the analog axis, matching real
GameCube hardware's separate click-vs-pressure signals.

**Rumble works — real fix, live-confirmed 2026-08-17.** The earlier
"dead end" (below, kept for history) was diagnosed via raphnet's own
vendor command `RQ_RNT_SET_VIBRATION`, sent as a Feature Report on
report ID 0 — that whole command channel turned out to be unresponsive
on this adapter for *any* request, not just vibration (even a plain
GET_VERSION probe got no answer). The real fix, found by reading
raphnet's firmware source (`github.com/raphnet/gc_n64_usb-v3`,
`usbpad.c`'s `usbpad_hid_set_report`) instead of guessing: this adapter
implements a genuine USB HID PID force-feedback **Output-Report** state
machine on report IDs 1/5/0x0A — a different, simpler channel (plain
`SET_REPORT`, no response needed) than the broken vendor-command one.
`sendVibration()` now drives that state machine directly (set effect
duration → set constant-force magnitude → start/stop). Confirmed live
with the real N64 controller + Rumble Pak — the controller genuinely
buzzes.

<details>
<summary>Old dead-end writeup (superseded, kept for history)</summary>

The uinput device advertises `FF_RUMBLE` and forwarded play/stop events
to the adapter via raphnet's documented `RQ_RNT_SET_VIBRATION` vendor
command — the USB transfer succeeded cleanly every time, but the real
controller never buzzed. Root cause turned out to be a dead command
channel (see above), not an unfixable hardware limitation as first
assumed.

</details>

## Known limitations

- **Only one controller at a time on this adapter** — with both an N64
  and a GameCube controller plugged into the adapter's two physical
  ports simultaneously, button reads stop working entirely (axes still
  update). This is a real firmware behavior (the adapter only actively
  polls one channel at a time despite having two ports), not something
  fixable from this app's side. Unplug one if buttons stop responding.

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
6. In-app, tap the mode button to match whichever controller you have
   connected (defaults to N64).
7. In RetroArch, the device will show up as **"Raphnet N64 Bridge
   Gamepad"** or **"Raphnet GC Bridge Gamepad"** depending on the mode
   you picked — bind normally, D-Pad Left now works.

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
