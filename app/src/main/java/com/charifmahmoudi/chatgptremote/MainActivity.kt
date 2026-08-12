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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val stored = SecureConfig(this).load()
        val tunnel = EditText(this).apply { hint = "tunnel_…"; setText(stored.tunnelId) }
        val key = EditText(this).apply { hint = "Runtime API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(stored.apiKey) }
        val mcp = EditText(this).apply { hint = "http://127.0.0.1:8765/mcp"; setText(stored.mcpUrl) }
        val start = Button(this).apply { text = "Save and start service" }
        val stop = Button(this).apply { text = "Stop service" }
        val status = TextView(this).apply { text = "Raw ADB is not enabled by this app. Pair and implement an ADB transport separately." }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40); addView(tunnel); addView(key); addView(mcp); addView(start); addView(stop); addView(status) })
        start.setOnClickListener {
            runCatching {
                AppConfig(tunnel.text.toString().trim(), key.text.toString(), mcp.text.toString().trim()).also {
                    require(Regex("^tunnel_[0-9a-f]{32}$").matches(it.tunnelId)); require(it.apiKey.isNotBlank())
                    require(it.mcpUrl.startsWith("http://127.0.0.1") || it.mcpUrl.startsWith("https://"))
                }.also { SecureConfig(this).save(it) }
            }.onSuccess { requestNotification(); ContextCompat.startForegroundService(this, Intent(this, TunnelService::class.java)); status.text = "Starting… check the notification." }
             .onFailure { status.text = "Invalid configuration: ${it.message}" }
        }
        stop.setOnClickListener { stopService(Intent(this, TunnelService::class.java)); status.text = "Stopped" }
    }
    private fun requestNotification() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
}
