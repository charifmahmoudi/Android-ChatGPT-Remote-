package com.charifmahmoudi.chatgptremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class TunnelService : LifecycleService() {
    private var client: TunnelClient? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("tunnel", "Secure MCP Tunnel", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, NotificationCompat.Builder(this, "tunnel").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("MCP tunnel active").setContentText("Private Android MCP bridge is running").setOngoing(true).build())
        val config = SecureConfig(this).load()
        if (config.tunnelId.isBlank() || config.apiKey.isBlank()) { stopSelf(); return }
        val http = OkHttpClient()
        client = TunnelClient("https://api.openai.com", config.tunnelId, config.apiKey, HttpMcpTransport(config.mcpUrl, http), http)
        lifecycleScope.launch { runCatching { client?.run() }.onFailure { stopSelf() } }
    }
    override fun onDestroy() { client?.stop(); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }
}
