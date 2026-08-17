package com.vanzetta.raphnetbridge

import android.util.Log

private const val TAG = "RaphnetInjector"

/**
 * Instantiated by Shizuku via reflection in a process it spawns under the ADB shell UID
 * (no-arg constructor required — Shizuku's contract, not ours). That UID is the only one
 * confirmed able to open /dev/uinput on this device (same constraint as the sibling
 * 8bitdo-xbox-bridge project).
 */
class GamepadInjectorService : IGamepadInjector.Stub() {

    private external fun nativeOpenUinput(name: String): Int
    private external fun nativeCloseUinput(fd: Int)
    private external fun nativeSendReport(
        fd: Int, buttonBits: Int, x: Int, y: Int, cx: Int, cy: Int, lt: Int, rt: Int,
    )
    private external fun nativePollFFEvent(fd: Int, timeoutMs: Int): Int

    companion object {
        init {
            System.loadLibrary("raphnetuinput")
        }
    }

    private var uinputFd: Int = -1

    // Same orphan-process risk as GipBridge's injector: Shizuku spawns a fresh process on
    // the next bind rather than reaping an old one left behind by an abnormal host exit
    // (force-stop, OOM kill). Kill any same-UID siblings before creating a new uinput
    // device, so a stale device never lingers alongside the fresh one.
    private fun killStaleSiblings() {
        val myPid = android.os.Process.myPid()
        runCatching {
            val proc = ProcessBuilder(
                "sh", "-c",
                "for p in \$(pidof com.vanzetta.raphnetbridge:injector); do " +
                    "[ \"\$p\" != \"$myPid\" ] && kill -9 \"\$p\"; done",
            ).redirectErrorStream(true).start()
            proc.waitFor()
        }.onFailure { Log.e(TAG, "killStaleSiblings failed: ${it.message}") }
    }

    init {
        killStaleSiblings()
        // Device is opened lazily via openDevice(), not here — the caller decides the name
        // (N64 vs GC mode, see RaphnetBridgeService), and Linux uinput needs the real name up
        // front at creation time, unlike a no-arg constructor Shizuku's contract requires here.
    }

    override fun openDevice(name: String) {
        // uinput fixes a device's name for its fd's lifetime — a mode switch means close (if
        // one's already open) then recreate under the new name, not rename in place.
        if (uinputFd >= 0) {
            nativeCloseUinput(uinputFd)
            uinputFd = -1
        }
        // Name must NOT contain the substring "Virtual" -- RetroArch's Android input driver
        // (input/drivers/android_input.c) hardcodes a special case that relabels any device
        // whose name contains "Virtual" as "SHIELD Virtual Controller" (meant for the
        // Shield remote's NVIDIA-button/CEC virtual device) — confirmed as a real bug hit
        // and fixed once already in the sibling 8bitdo-xbox-bridge project.
        uinputFd = runCatching { nativeOpenUinput(name) }.getOrElse { -1 }
        Log.d(TAG, "uinput gamepad '$name' fd=$uinputFd")
    }

    override fun sendReport(buttons: Int, x: Int, y: Int, cx: Int, cy: Int, lt: Int, rt: Int) {
        if (uinputFd < 0) return
        nativeSendReport(uinputFd, buttons, x, y, cx, cy, lt, rt)
    }

    override fun pollForceFeedback(timeoutMs: Int): Int {
        if (uinputFd < 0) return -1
        return nativePollFFEvent(uinputFd, timeoutMs)
    }

    override fun destroy() {
        if (uinputFd >= 0) {
            nativeCloseUinput(uinputFd)
            uinputFd = -1
        }
    }
}
