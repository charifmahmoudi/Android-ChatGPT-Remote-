package com.charifmahmoudi.chatgptremote

import android.app.*
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TunnelService : LifecycleService() {
    private var client: TunnelClient? = null
    private var worker: Job? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "ADB MCP background service", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Starting background service…")); evaluate()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SET_TUNNEL -> {
                val id = intent.getStringExtra(EXTRA_TUNNEL).orEmpty().trim(); val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
                if (!Regex("^tunnel_[0-9a-f]{32}$").matches(id) || key.isBlank()) publish(ServicePhase.ERROR, "Invalid tunnel ID or runtime key")
                else { SecureConfig(this).saveTunnel(id, key); evaluate() }
            }
            ACTION_PAIR -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim(); val port = intent.getIntExtra(EXTRA_PORT, 0); val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                worker?.cancel(); worker = lifecycleScope.launch {
                    publish(ServicePhase.PAIRING, "Pairing… keep the Wireless Debugging PIN dialog open")
                    runCatching { AdbMcpTransport.pair(host, port, pin) }
                        .onSuccess { SecureConfig(this@TunnelService).markPaired(); publish(ServicePhase.NEED_ADB_PORT, "Paired. Enter the connection port from the main Wireless debugging screen") }
                        .onFailure { publish(ServicePhase.NEED_PAIRING, "Pairing failed. Open a fresh pairing-code dialog and retry") }
                }
            }
            ACTION_SET_ADB -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim(); val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (host !in setOf("127.0.0.1", "localhost") || port !in 1..65535) publish(ServicePhase.ERROR, "Invalid same-device ADB endpoint")
                else { SecureConfig(this).saveAdbEndpoint(host, port); evaluate() }
            }
            ACTION_RETRY -> evaluate()
            ACTION_STOP -> { publish(ServicePhase.STOPPED, "Service stopped"); stopSelf() }
            else -> evaluate()
        }
        return START_STICKY
    }
    private fun evaluate() {
        worker?.cancel(); client?.stop(); client = null
        val config = SecureConfig(this).load()
        when {
            config.tunnelId.isBlank() || config.apiKey.isBlank() -> publish(ServicePhase.NEED_TUNNEL, "Tunnel credentials required")
            config.adbPort == 0 && !SecureConfig(this).isPaired() -> publish(ServicePhase.NEED_PAIRING, "Wireless ADB pairing required")
            config.adbPort == 0 -> publish(ServicePhase.NEED_ADB_PORT, "Enter the connection port from the main Wireless debugging screen")
            else -> worker = lifecycleScope.launch {
                publish(ServicePhase.CONNECTING, "Connecting Secure MCP Tunnel and local ADB…")
                val next = TunnelClient("https://api.openai.com", config.tunnelId, config.apiKey, AdbMcpTransport(config.adbHost, config.adbPort)); client = next
                publish(ServicePhase.RUNNING, "ADB is exposed privately through MCP")
                runCatching { next.run() }.onFailure { publish(ServicePhase.ERROR, "Background connection stopped; tap Retry") }
            }
        }
    }
    private fun publish(phase: ServicePhase, message: String) { ServiceState.publish(this, phase, message); getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message)) }
    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ChatGPT ADB MCP").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    override fun onDestroy() { worker?.cancel(); client?.stop(); ServiceState.publish(this, ServicePhase.STOPPED, "Service stopped"); super.onDestroy() }
    companion object {
        const val CHANNEL="adb_mcp_service"; const val NOTIFICATION_ID=1
        const val ACTION_SET_TUNNEL="set_tunnel"; const val ACTION_PAIR="pair"; const val ACTION_SET_ADB="set_adb"; const val ACTION_RETRY="retry"; const val ACTION_STOP="stop"
        const val EXTRA_TUNNEL="tunnel"; const val EXTRA_KEY="key"; const val EXTRA_HOST="host"; const val EXTRA_PORT="port"; const val EXTRA_PIN="pin"
    }
}
