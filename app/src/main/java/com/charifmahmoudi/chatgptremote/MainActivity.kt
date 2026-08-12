package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val stored = SecureConfig(this).load()
        val tunnel = EditText(this).apply { hint = "tunnel_…"; setText(stored.tunnelId) }
        val key = EditText(this).apply { hint = "Runtime API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(stored.apiKey) }
        val host = EditText(this).apply { hint = "ADB host (127.0.0.1)"; setText(stored.adbHost) }
        val pairPort = EditText(this).apply { hint = "Pairing port from Wireless debugging"; inputType = InputType.TYPE_CLASS_NUMBER }
        val pin = EditText(this).apply { hint = "6-digit ADB pairing PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val adbPort = EditText(this).apply { hint = "ADB connection port"; inputType = InputType.TYPE_CLASS_NUMBER; if (stored.adbPort != 0) setText(stored.adbPort.toString()) }
        val pair = Button(this).apply { text = "Pair ADB" }
        val start = Button(this).apply { text = "Save and start service" }
        val stop = Button(this).apply { text = "Stop service" }
        val status = TextView(this).apply { text = "Enter tunnel credentials. For first use, open Developer options → Wireless debugging → Pair device with pairing code, then enter its temporary port and PIN here." }
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40); addView(tunnel); addView(key); addView(host); addView(pairPort); addView(pin); addView(adbPort); addView(pair); addView(start); addView(stop); addView(status) }) })
        pair.setOnClickListener {
            status.text = "Pairing… keep the Android pairing dialog open."
            lifecycleScope.launch {
                runCatching { AdbMcpTransport.pair(host.text.toString().trim(), pairPort.text.toString().toInt(), pin.text.toString()) }
                    .onSuccess { pin.text.clear(); status.text = "Paired. Now copy the separate IP address & port shown on the main Wireless debugging screen into ADB connection port." }
                    .onFailure { status.text = "Pairing failed: ${it.javaClass.simpleName}" }
            }
        }
        start.setOnClickListener {
            runCatching {
                AppConfig(tunnel.text.toString().trim(), key.text.toString(), host.text.toString().trim(), adbPort.text.toString().toInt()).also {
                    require(Regex("^tunnel_[0-9a-f]{32}$").matches(it.tunnelId)); require(it.apiKey.isNotBlank())
                    require(it.adbHost == "127.0.0.1" || it.adbHost == "localhost") { "Same-device ADB host must be loopback" }
                    require(it.adbPort in 1..65535)
                }.also { SecureConfig(this).save(it) }
            }.onSuccess { requestNotification(); ContextCompat.startForegroundService(this, Intent(this, TunnelService::class.java)); status.text = "Starting… check the notification." }
             .onFailure { status.text = "Invalid configuration: ${it.message}" }
        }
        stop.setOnClickListener { stopService(Intent(this, TunnelService::class.java)); status.text = "Stopped" }
    }
    private fun requestNotification() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
}
