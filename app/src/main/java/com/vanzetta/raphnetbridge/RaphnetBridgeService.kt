package com.vanzetta.raphnetbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

private const val TAG = "RaphnetBridge"
private const val ACTION_USB_PERMISSION = "com.vanzetta.raphnetbridge.USB_PERMISSION"
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4243
private const val NOTIF_CHANNEL_ID = "raphnet_bridge_service"
private const val NOTIF_ID = 1

// Real raphnet GC/N64-to-USB v3.6, confirmed via live `dumpsys usb` earlier this session.
private const val RAPHNET_VID = 10395
private const val RAPHNET_PID = 96

// Real HID report layout, sourced directly from raphnet's own firmware
// (github.com/raphnet/gc_n64_usb-v3: reportdesc.c + usbpad.c's buildReportFromGC /
// buildReportFromN64), not guessed. Report ID 1, 15 bytes total including the ID byte:
//   byte0      = Report ID (0x01)
//   bytes1-2   = X       (main stick, LE uint16, firmware-centered on 16000, ±16000 range)
//   bytes3-4   = Y       (same, Y is firmware-inverted)
//   bytes5-6   = Rx/cx   (C-stick X, same centering)
//   bytes7-8   = Ry/cy   (C-stick Y, firmware-inverted)
//   bytes9-10  = L trigger (centered 16000, deflects positive only in default mode)
//   bytes11-12 = R trigger (same)
//   bytes13-14 = 16 button bits, LSB-first (byte13=bits0-7, byte14=bits8-15)
// Button bit order matches Linux's standard hid-generic gamepad table exactly (cross-checked
// against this session's own live evdev capture — every entry lines up). Real GameCube-mode
// bit mapping confirmed from mappings.c: bit12 (D-Pad Left) is BTN_MODE for BOTH N64 and GC
// modes — same adapter-wide bug, not console-specific.
private const val REPORT_ID_GAMEPAD = 1
private const val REPORT_SIZE = 15
private const val AXIS_CENTER = 16000
private const val PREF_MODE = "controller_mode"

enum class ControllerMode { N64, GC }

/**
 * Owns the USB session (reading the raphnet adapter's raw HID reports directly via
 * UsbManager/UsbDeviceConnection, bypassing Android's InputManager entirely — the whole
 * reason D-Pad Left is fixable at all) and the Shizuku injector binding (which owns the
 * replacement uinput virtual gamepad). Foreground service so Android TV's process
 * management doesn't kill/freeze the reader thread when backgrounded — same reasoning as
 * the sibling 8bitdo-xbox-bridge project's GipBridgeService.
 */
class RaphnetBridgeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): RaphnetBridgeService = this@RaphnetBridgeService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    private val logHistory = StringBuilder()
    @Volatile private var logListener: ((String) -> Unit)? = null
    fun setLogListener(listener: ((String) -> Unit)?) { logListener = listener }
    fun getLogHistory(): String = synchronized(logHistory) { logHistory.toString() }

    private lateinit var usbManager: UsbManager
    private var readerThread: Thread? = null
    private var injectorThread: Thread? = null
    @Volatile private var running = false
    private var connection: UsbDeviceConnection? = null
    // Single-slot mailbox between readLoop (producer) and injectLoop (consumer): decouples the
    // USB read from the Shizuku cross-process Binder call. Previously sendReport() ran inline
    // on the reader thread — if that IPC call ever stalled (GC pause, scheduler jitter), it
    // directly delayed the next bulkTransfer read, risking missed/uneven polls at the
    // adapter's ~170Hz native rate. Capacity 1 + poll-before-offer keeps only the latest
    // pending report, which is exactly what we want (no benefit to queuing stale input).
    private val reportQueue = java.util.concurrent.ArrayBlockingQueue<IntArray>(1)

    @Volatile private var injector: IGamepadInjector? = null

    private fun isController(device: UsbDevice) =
        device.vendorId == RAPHNET_VID && device.productId == RAPHNET_PID

    // Manual N64/GC mode toggle — replaces the auto-detect approach investigated and ruled
    // out 2026-08-17 (RQ_RNT_GET_CONTROLLER_TYPE: whole rntlib command channel unresponsive
    // on this adapter in normal mode, confirmed live via a working sanity check, not just one
    // failed guess). Mode picks the uinput device NAME, which is all RetroArch's autoconfig
    // keys off — giving N64 and GC distinct names means each gets its own independently
    // bound/saved RetroArch profile instead of silently sharing (and overwriting) one.
    private val prefs by lazy { getSharedPreferences("raphnet_bridge_prefs", Context.MODE_PRIVATE) }
    @Volatile private var mode: ControllerMode = ControllerMode.N64

    private fun loadMode(): ControllerMode =
        if (prefs.getString(PREF_MODE, "N64") == "GC") ControllerMode.GC else ControllerMode.N64

    private fun deviceNameFor(m: ControllerMode) = when (m) {
        ControllerMode.N64 -> "Raphnet N64 Bridge Gamepad"
        ControllerMode.GC -> "Raphnet GC Bridge Gamepad"
    }

    fun getControllerMode(): ControllerMode = mode

    // Called from MainActivity's toggle. Persists, then recreates the uinput device under the
    // new name if the injector is already connected (uinput can't rename a live fd — see
    // GamepadInjectorService.openDevice's comment).
    fun setControllerMode(m: ControllerMode) {
        mode = m
        prefs.edit().putString(PREF_MODE, m.name).apply()
        val name = deviceNameFor(m)
        log("Controller mode set to $m — uinput device: $name")
        runCatching { injector?.openDevice(name) }
            .onFailure { log("openDevice failed: ${it.message}") }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, GamepadInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("injector").debuggable(false).version(2)

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            injector = IGamepadInjector.Stub.asInterface(binder)
            val deviceName = deviceNameFor(mode)
            log("Shizuku injector service connected — opening uinput device: $deviceName")
            runCatching { injector?.openDevice(deviceName) }
                .onFailure { log("openDevice failed: ${it.message}") }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            injector = null
            log("Shizuku injector service disconnected.")
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku permission granted, binding injector service...")
            bindInjector()
        } else {
            log("Shizuku permission DENIED — cannot create the replacement uinput gamepad.")
        }
    }

    private fun bindInjector() {
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        Shizuku.bindUserService(userServiceArgs, userServiceConnection)
    }

    private fun setupShizuku() {
        if (Shizuku.isPreV11()) { log("Shizuku pre-v11, unsupported."); return }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bindInjector()
            Shizuku.shouldShowRequestPermissionRationale() ->
                log("Shizuku permission previously denied by user.")
            else -> Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            synchronized(this) {
                val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    log("USB permission granted for ${device.deviceName}")
                    startSession(device)
                } else {
                    log("USB permission DENIED")
                }
            }
        }
    }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device != null && isController(device)) {
                log("Attach event: ${device.deviceName} (vid=${device.vendorId} pid=${device.productId})")
                requestPermissionAndConnect(device)
            }
        }
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = intent.getParcelableExtraCompat(UsbManager.EXTRA_DEVICE)
            if (device != null && isController(device)) {
                log("Controller detached.")
                stopSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        mode = loadMode()
        startInForeground()

        val permFilter = IntentFilter(ACTION_USB_PERMISSION)
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val detachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbAttachReceiver, attachFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, permFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbAttachReceiver, attachFilter)
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbDetachReceiver, detachFilter)
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky { setupShizuku() }

        log("Raphnet Bridge service started. Looking for adapter (vid=$RAPHNET_VID pid=$RAPHNET_PID)...")
        findAndConnectExisting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Raphnet Bridge", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Raphnet GC/N64 Bridge active")
            .setContentText("D-Pad Left + C-stick fix running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun findAndConnectExisting() {
        val target = usbManager.deviceList.values.firstOrNull { isController(it) }
        if (target == null) {
            log("No adapter currently attached. Plug it in (attach receiver will catch it).")
            return
        }
        log("Found already-attached adapter: ${target.deviceName}")
        requestPermissionAndConnect(target)
    }

    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            startSession(device)
            return
        }
        // Same real gotcha as GipBridge: requesting permission with nothing in the
        // foreground (TV screensaver active) can leave the request stuck with no dialog
        // ever shown — bring the app forward first so the dialog has somewhere to attach.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, pi)
        log("Requested USB permission, waiting for user/system response...")
    }

    private fun startSession(device: UsbDevice) {
        if (running) { log("Session already running, ignoring duplicate start."); return }

        // Real raphnet adapter descriptor (Report descriptor confirmed via reportdesc.c):
        // standard single HID interface, one interrupt IN endpoint for input reports.
        val iface: UsbInterface? = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
        if (iface == null) { log("ERROR: HID interface not found"); return }

        var epIn: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
        }
        if (epIn == null) { log("ERROR: IN endpoint not found on HID interface"); return }

        val conn: UsbDeviceConnection? = usbManager.openDevice(device)
        if (conn == null) { log("ERROR: openDevice failed"); return }
        if (!conn.claimInterface(iface, true)) { log("ERROR: claimInterface failed"); return }

        log("Interface claimed. IN ep=0x${epIn.address.toString(16)}. Starting read + inject loops...")
        connection = conn
        running = true
        readerThread = thread(name = "raphnet-reader") { readLoop(conn, epIn) }
        injectorThread = thread(name = "raphnet-injector") { injectLoop() }
    }

    private fun stopSession() {
        running = false
        readerThread?.join(500)
        injectorThread?.join(500)
        runCatching { connection?.close() }
        connection = null
        readerThread = null
        injectorThread = null
    }

    private var lastLogAtMs = 0L
    private var packetsSeen = 0

    // Real perf fix: the raw USB reports arrive as fast as the adapter's own firmware polls
    // (confirmed live at ~170Hz, uncapped — no sleep in this loop when data is ready), and
    // every prior version forwarded ALL of them through Shizuku's cross-process Binder call
    // plus native uinput writes (7 EV_ABS + 15 EV_KEY + 1 EV_SYN each) regardless of whether
    // anything actually changed — real, measurable CPU competing with RetroArch's own N64
    // emulation loop (Zelda OoT reported laggy). Idle analog noise (~1-bit ADC jitter,
    // observed live oscillating x between -160/-320) would defeat an exact-equality dedup,
    // so axes use a small deadzone instead; buttons dedup exactly (no reason to ever miss a
    // real digital transition).
    private var lastSentButtons = -1
    private var lastSentX = 0; private var lastSentY = 0
    private var lastSentCx = 0; private var lastSentCy = 0
    private var lastSentLt = 0; private var lastSentRt = 0
    private val AXIS_DEADZONE = 200

    private fun readLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint) {
        val buf = ByteArray(64)
        while (running) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 2000)
            if (n <= 0) { Thread.sleep(20); continue }
            packetsSeen++

            if (buf[0].toInt() != REPORT_ID_GAMEPAD || n < REPORT_SIZE) {
                // Real device also emits Report ID 2 (PID/force-feedback state) per its own
                // descriptor — not the gamepad input report, ignore it here.
                continue
            }

            val x = le16(buf, 1) - AXIS_CENTER
            var y = -(le16(buf, 3) - AXIS_CENTER) // firmware inverts Y on the way out; un-invert
            val cx = le16(buf, 5) - AXIS_CENTER
            val cy = -(le16(buf, 7) - AXIS_CENTER) // same inversion as Y
            val lt = (le16(buf, 9) - AXIS_CENTER).coerceAtLeast(0)
            val rt = (le16(buf, 11) - AXIS_CENTER).coerceAtLeast(0)

            // Real, live-confirmed 2026-08-17: GameCube's main stick Y comes out backwards
            // relative to N64 even after the shared un-invert above (pushing GC stick up
            // measured y=+16000, which reads as down once bound in RetroArch — N64 has been
            // extensively live-tested correct with the un-inverted formula across a full real
            // play session, so only GC gets this extra flip, not the shared formula itself).
            if (mode == ControllerMode.GC) y = -y
            val buttons = (buf[13].toInt() and 0xFF) or ((buf[14].toInt() and 0xFF) shl 8)

            val now = SystemClock.elapsedRealtime()
            if (now - lastLogAtMs >= 500) {
                log(
                    "REPORT #$packetsSeen buttons=0x${buttons.toString(16)} " +
                        "x=$x y=$y cx=$cx cy=$cy lt=$lt rt=$rt"
                )
                lastLogAtMs = now
            }

            fun moved(a: Int, b: Int) = kotlin.math.abs(a - b) > AXIS_DEADZONE
            val changed = buttons != lastSentButtons ||
                moved(x, lastSentX) || moved(y, lastSentY) ||
                moved(cx, lastSentCx) || moved(cy, lastSentCy) ||
                moved(lt, lastSentLt) || moved(rt, lastSentRt)

            if (changed) {
                reportQueue.poll() // drop any stale unconsumed report, keep only latest
                reportQueue.offer(intArrayOf(buttons, x, y, cx, cy, lt, rt))
                lastSentButtons = buttons
                lastSentX = x; lastSentY = y
                lastSentCx = cx; lastSentCy = cy
                lastSentLt = lt; lastSentRt = rt
            }
        }
        log("Read loop stopped after $packetsSeen packets.")
    }

    private fun injectLoop() {
        while (running) {
            val r = runCatching {
                reportQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.getOrNull() ?: continue
            runCatching { injector?.sendReport(r[0], r[1], r[2], r[3], r[4], r[5], r[6]) }
                .onFailure { log("inject failed: ${it.message}") }
        }
    }

    private fun le16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val line = "$msg\n"
        synchronized(logHistory) {
            logHistory.append(line)
            if (logHistory.length > 20000) logHistory.delete(0, logHistory.length - 20000)
        }
        logListener?.invoke(line)
    }

    override fun onDestroy() {
        stopSession()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(usbAttachReceiver) }
        runCatching { unregisterReceiver(usbDetachReceiver) }
        runCatching { injector?.destroy() }
        runCatching { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) }
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        super.onDestroy()
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}
