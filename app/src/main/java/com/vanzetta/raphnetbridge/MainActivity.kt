package com.vanzetta.raphnetbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Thin UI shell — the actual USB/uinput engine lives in RaphnetBridgeService (a foreground
 * service) so it keeps running when this Activity is backgrounded. This class just starts
 * that service, binds to it for the live log stream, and displays it.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var modeButton: Button
    private var service: RaphnetBridgeService? = null

    private fun refreshModeButton() {
        val m = service?.getControllerMode() ?: ControllerMode.N64
        modeButton.text = "Mode: $m (tap to switch to ${if (m == ControllerMode.N64) "GC" else "N64"})"
    }

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread {
            logView.append(line)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RaphnetBridgeService.LocalBinder).getService()
            service = svc
            runOnUiThread {
                logView.text = svc.getLogHistory()
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                refreshModeButton()
            }
            svc.setLogListener(logListener)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val disclaimer = TextView(this).apply {
            text = "Unofficial third-party bridge — not affiliated with, endorsed by, or " +
                "supported by raphnet.net."
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(24, 24, 24, 0)
        }
        modeButton = Button(this).apply {
            text = "Mode: N64 (tap to switch to GC)"
            setOnClickListener {
                val current = service?.getControllerMode() ?: ControllerMode.N64
                val next = if (current == ControllerMode.N64) ControllerMode.GC else ControllerMode.N64
                service?.setControllerMode(next)
                refreshModeButton()
            }
        }
        logView = TextView(this).apply { textSize = 12f; setPadding(24, 12, 24, 24) }
        // Real fix: the log auto-scrolls to bottom on every new line (every ~500ms while a
        // controller's connected), and the mode button used to live inside that same
        // scrolling container — every log update yanked it off-screen. Only the log itself
        // scrolls now; the disclaimer + mode button are pinned above it, always reachable.
        // A focusable ScrollView silently steals D-pad focus from the mode button below it
        // (same real gotcha already documented in the sibling 8bitdo-xbox-bridge project) —
        // this view has nothing worth focusing itself, only scrolling.
        scroll = ScrollView(this).apply { addView(logView); isFocusable = false }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(disclaimer)
            addView(modeButton)
            addView(
                scroll,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ),
            )
        }
        setContentView(root)

        val svcIntent = Intent(this, RaphnetBridgeService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)
        bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        service?.setLogListener(null)
        runCatching { unbindService(serviceConnection) }
        super.onDestroy()
    }
}
