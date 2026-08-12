package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Status and credential-entry surface only.
 *
 * All pairing, tunnel, ADB, and MCP work belongs to [TunnelService]. Closing this activity does
 * not interrupt the service.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var tunnelGroup: LinearLayout
    private lateinit var pairingGroup: LinearLayout
    private lateinit var adbPortGroup: LinearLayout

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            render(ServiceState.current)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val stored = SecureConfig(this).load()
        val tunnelIdInput = editText("tunnel_…").apply { setText(stored.tunnelId) }
        val runtimeKeyInput = editText(
            "Runtime API key",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        ).apply { setText(stored.apiKey) }
        val pairingHostInput = editText("ADB host").apply { setText(LOOPBACK_HOST) }
        val pairingPortInput = editText("Pairing port", InputType.TYPE_CLASS_NUMBER)
        val pairingPinInput = editText(
            "6-digit pairing PIN",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        val adbPortInput = editText("ADB connection port", InputType.TYPE_CLASS_NUMBER).apply {
            if (stored.adbPort > 0) setText(stored.adbPort.toString())
        }

        statusView = TextView(this)
        tunnelGroup = verticalGroup(
            tunnelIdInput,
            runtimeKeyInput,
            button("Submit tunnel credentials") {
                sendServiceAction(TunnelService.ACTION_SET_TUNNEL) {
                    putExtra(TunnelService.EXTRA_TUNNEL, tunnelIdInput.text.toString())
                    putExtra(TunnelService.EXTRA_KEY, runtimeKeyInput.text.toString())
                }
                runtimeKeyInput.text.clear()
            },
        )
        pairingGroup = verticalGroup(
            pairingHostInput,
            pairingPortInput,
            pairingPinInput,
            button("Pair ADB") {
                sendServiceAction(TunnelService.ACTION_PAIR) {
                    putExtra(TunnelService.EXTRA_HOST, pairingHostInput.text.toString())
                    putExtra(TunnelService.EXTRA_PORT, pairingPortInput.integerValue())
                    putExtra(TunnelService.EXTRA_PIN, pairingPinInput.text.toString())
                }
                pairingPinInput.text.clear()
            },
        )
        adbPortGroup = verticalGroup(
            adbPortInput,
            button("Submit ADB connection port") {
                sendServiceAction(TunnelService.ACTION_SET_ADB) {
                    putExtra(TunnelService.EXTRA_HOST, LOOPBACK_HOST)
                    putExtra(TunnelService.EXTRA_PORT, adbPortInput.integerValue())
                }
            },
        )

        val content = verticalGroup(
            statusView,
            tunnelGroup,
            pairingGroup,
            adbPortGroup,
            button("Retry background service") { sendServiceAction(TunnelService.ACTION_RETRY) },
            button("Stop service") { sendServiceAction(TunnelService.ACTION_STOP) },
        ).apply { setPadding(40, 60, 40, 40) }
        setContentView(ScrollView(this).apply { addView(content) })

        requestNotificationPermission()
        ContextCompat.startForegroundService(this, Intent(this, TunnelService::class.java))
        render(ServiceState.current)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(ServiceState.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun sendServiceAction(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, TunnelService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun render(status: ServiceStatus) {
        statusView.text = "Status: ${status.phase}\n${status.message}"
        tunnelGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_TUNNEL)
        pairingGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_PAIRING)
        adbPortGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_ADB_PORT)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun editText(hintText: String, type: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { hint = hintText; inputType = type }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

    private fun verticalGroup(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it) }
    }

    private fun EditText.integerValue(): Int = text.toString().toIntOrNull() ?: 0
    private fun View.visibleOnlyWhen(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val NOTIFICATION_REQUEST = 1
    }
}
