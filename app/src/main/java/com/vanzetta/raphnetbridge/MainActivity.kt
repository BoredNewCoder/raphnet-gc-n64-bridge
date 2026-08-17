package com.vanzetta.raphnetbridge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
    private var service: RaphnetBridgeService? = null

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
        logView = TextView(this).apply { textSize = 12f; setPadding(24, 12, 24, 24) }
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(disclaimer)
            addView(logView)
        }
        scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

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
