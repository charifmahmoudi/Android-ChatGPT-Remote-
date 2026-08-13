package com.charifmahmoudi.chatgptremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns the complete background lifecycle: configuration, pairing, tunnel polling, and MCP. */
class TunnelService : LifecycleService() {
    private val secureConfig by lazy { SecureConfig(this) }
    private var tunnelClient: TunnelClient? = null
    private var worker: Job? = null
    @Volatile private var tunnelConnected = false
    @Volatile private var adbHealthy = false

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.initialize(this)
        DiagnosticLog.record("service", "onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting background service…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        DiagnosticLog.record("service", "onStart action=${actionName(intent?.action)} startId=$startId")
        when (intent?.action) {
            ACTION_SET_TUNNEL -> acceptTunnelCredentials(intent)
            ACTION_PAIR -> pairAdb(intent)
            ACTION_SET_ADB -> acceptAdbEndpoint(intent)
            ACTION_RETRY -> evaluateConfiguration()
            ACTION_STOP -> stopService()
            else -> evaluateConfiguration()
        }
        return START_STICKY
    }

    private fun acceptTunnelCredentials(intent: Intent) {
        val tunnelId = intent.getStringExtra(EXTRA_TUNNEL).orEmpty().trim()
        val runtimeKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        if (!TUNNEL_ID.matches(tunnelId) || runtimeKey.isBlank()) {
            publish(ServicePhase.NEED_TUNNEL, "Invalid tunnel ID or runtime key")
            return
        }
        secureConfig.saveTunnel(tunnelId, runtimeKey)
        evaluateConfiguration()
    }

    private fun pairAdb(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
        if (!isLoopback(host) || port !in VALID_PORTS || !PIN.matches(pin)) {
            publish(ServicePhase.NEED_PAIRING, "Enter a loopback host, valid pairing port, and 6-digit PIN")
            return
        }

        cancelWork()
        worker = lifecycleScope.launch {
            publish(ServicePhase.PAIRING, "Pairing… keep the Wireless Debugging PIN dialog open")
            try {
                AdbMcpTransport.pair(host, port, pin)
                secureConfig.markPaired()
                publish(
                    ServicePhase.NEED_ADB_PORT,
                    "Paired. Enter the connection port from the main Wireless debugging screen",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DiagnosticLog.record("adb", "pair failed chain=${error.safeClassChain()}")
                publish(
                    ServicePhase.NEED_PAIRING,
                    "Pairing failed. Open a fresh pairing-code dialog and retry",
                )
            }
        }
    }

    private fun acceptAdbEndpoint(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (!isLoopback(host) || port !in VALID_PORTS) {
            publish(ServicePhase.NEED_ADB_PORT, "Invalid same-device ADB endpoint")
            return
        }
        secureConfig.saveAdbEndpoint(host, port)
        evaluateConfiguration()
    }

    private fun evaluateConfiguration() {
        cancelWork()
        val config = secureConfig.load()
        when {
            config.tunnelId.isBlank() || config.apiKey.isBlank() ->
                publish(ServicePhase.NEED_TUNNEL, "Tunnel credentials required")
            config.adbPort == 0 && !secureConfig.isPaired() ->
                publish(ServicePhase.NEED_PAIRING, "Wireless ADB pairing required")
            config.adbPort == 0 ->
                publish(ServicePhase.NEED_ADB_PORT, "Enter the ADB connection port")
            else -> startTunnel(config)
        }
    }

    private fun startTunnel(config: AppConfig) {
        worker = lifecycleScope.launch {
            tunnelConnected = false
            adbHealthy = false
            publish(ServicePhase.CONNECTING, "Checking local ADB before connecting the secure tunnel…")
            val transport = AdbMcpTransport(
                config.adbHost,
                config.adbPort,
                onDiagnostic = { event -> DiagnosticLog.record("adb", event) },
                onHealthChanged = { healthy, category -> onAdbHealthChanged(healthy, category) },
            )
            try {
                transport.probe()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The probe already recorded a privacy-safe class chain and health category.
                publish(ServicePhase.NEED_ADB_PORT, adbFailureMessage("preflight"))
                return@launch
            }

            publish(ServicePhase.CONNECTING, "Local ADB verified · connecting secure tunnel…")
            val client = TunnelClient(
                baseUrl = OPENAI_API,
                tunnelId = config.tunnelId,
                apiKey = config.apiKey,
                transport = transport,
                onConnected = {
                    tunnelConnected = true
                    publishReadiness()
                },
                onConnectionLost = {
                    tunnelConnected = false
                    publish(ServicePhase.CONNECTING, "Tunnel interrupted · reconnecting automatically")
                },
                onDiagnostic = { event -> DiagnosticLog.record("tunnel", event) },
            )
            tunnelClient = client
            try {
                client.run()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SecurityException) {
                DiagnosticLog.record("tunnel", "stopped category=authorization")
                publish(ServicePhase.NEED_TUNNEL, "Tunnel authorization failed; enter valid credentials")
            } catch (error: Exception) {
                DiagnosticLog.record("tunnel", "stopped category=${error.javaClass.simpleName}")
                publish(ServicePhase.ERROR, "Background connection stopped; tap Retry")
            }
        }
    }

    private fun stopService() {
        cancelWork()
        publish(ServicePhase.STOPPED, "Service stopped")
        stopSelf()
    }

    private fun cancelWork() {
        worker?.cancel()
        worker = null
        tunnelClient?.stop()
        tunnelClient = null
        tunnelConnected = false
        adbHealthy = false
    }

    private fun onAdbHealthChanged(healthy: Boolean, category: String) {
        val changed = adbHealthy != healthy
        adbHealthy = healthy
        if (changed) DiagnosticLog.record("health", "adb=${if (healthy) "healthy" else "failed"} category=$category")
        if (healthy) {
            publishReadiness()
        } else if (tunnelConnected) {
            publish(ServicePhase.NEED_ADB_PORT, adbFailureMessage("runtime"))
        }
    }

    /** RUNNING is a composite state: both the remote tunnel and the local ADB bridge must be live. */
    private fun publishReadiness() {
        if (tunnelConnected && adbHealthy) {
            publish(ServicePhase.RUNNING, "Secure tunnel connected · local ADB verified · MCP ready")
        }
    }

    private fun adbFailureMessage(stage: String): String {
        DiagnosticLog.record("health", "readiness failed component=adb stage=$stage tunnel=${if (tunnelConnected) "connected" else "pending"}")
        return "Local ADB is unreachable. Keep Wireless debugging enabled, verify the connection port, then save it again."
    }

    private fun publish(phase: ServicePhase, message: String) {
        ServiceState.publish(this, phase, message)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB MCP background service", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("ChatGPT ADB MCP")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    override fun onDestroy() {
        DiagnosticLog.record("service", "onDestroy")
        cancelWork()
        ServiceState.publish(this, ServicePhase.STOPPED, "Service stopped")
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_TUNNEL = "set_tunnel"
        const val ACTION_PAIR = "pair"
        const val ACTION_SET_ADB = "set_adb"
        const val ACTION_RETRY = "retry"
        const val ACTION_STOP = "stop"
        const val EXTRA_TUNNEL = "tunnel"
        const val EXTRA_KEY = "key"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"

        private const val CHANNEL_ID = "adb_mcp_service"
        private const val NOTIFICATION_ID = 1
        private const val OPENAI_API = "https://api.openai.com"
        private val VALID_PORTS = 1..65_535
        private val PIN = Regex("^\\d{6}$")
        private val TUNNEL_ID = Regex("^tunnel_[0-9a-f]{32}$")

        private fun isLoopback(host: String) = host == "127.0.0.1" || host == "localhost"

        private fun actionName(action: String?) = when (action) {
            ACTION_SET_TUNNEL -> "set_tunnel"
            ACTION_PAIR -> "pair"
            ACTION_SET_ADB -> "set_adb"
            ACTION_RETRY -> "retry"
            ACTION_STOP -> "stop"
            null -> "restore"
            else -> "unknown"
        }

        private fun Throwable.safeClassChain(): String = generateSequence(this) { it.cause }
            .take(4)
            .joinToString(">") { it.javaClass.simpleName.ifBlank { "Throwable" } }
    }
}
