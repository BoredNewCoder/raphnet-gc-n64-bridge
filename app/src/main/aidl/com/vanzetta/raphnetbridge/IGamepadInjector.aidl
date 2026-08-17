package com.vanzetta.raphnetbridge;

// Runs in a separate process spawned by Shizuku under the ADB shell UID, which is the only
// UID confirmed able to open /dev/uinput on this device (same constraint discovered and
// documented in the sibling 8bitdo-xbox-bridge project). Binding to this from the main app
// process gets us a real uinput-backed virtual gamepad, bypassing Android's InputManager
// (and its BTN_MODE/D-Pad-Left system-level interception) entirely.
interface IGamepadInjector {
    void sendReport(int buttons, int x, int y, int cx, int cy, int lt, int rt);
    void destroy();
}
