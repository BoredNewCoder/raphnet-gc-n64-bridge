package com.vanzetta.raphnetbridge;

// Runs in a separate process spawned by Shizuku under the ADB shell UID, which is the only
// UID confirmed able to open /dev/uinput on this device (same constraint discovered and
// documented in the sibling 8bitdo-xbox-bridge project). Binding to this from the main app
// process gets us a real uinput-backed virtual gamepad, bypassing Android's InputManager
// (and its BTN_MODE/D-Pad-Left system-level interception) entirely.
interface IGamepadInjector {
    // Creates (or recreates, if already open) the uinput device under the given name. Called
    // once on first connect, and again whenever the user switches N64/GC mode in-app -- Linux
    // uinput fixes a device's name for the lifetime of its fd, so a mode switch means
    // close-then-reopen under the new name rather than renaming in place.
    void openDevice(String name);
    void sendReport(int buttons, int x, int y, int cx, int cy, int lt, int rt);
    // Blocks up to timeoutMs waiting for a force-feedback event on the uinput device (Android's
    // vibrator subsystem is the real client here, not Dolphin/RetroArch directly -- it uploads
    // one rumble effect then plays/stops it by id). Upload/erase handshakes are handled
    // internally (always accepted, single-effect device); returns 1 on play, 0 on stop, -2 on
    // timeout/no event/handled-internally (caller should just call again), -1 if the device
    // isn't open.
    int pollForceFeedback(int timeoutMs);
    void destroy();
}
