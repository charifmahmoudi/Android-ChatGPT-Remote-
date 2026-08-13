package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Dashboard and parameter entry only; all operational work is owned by [TunnelService]. */
class MainActivity : AppCompatActivity() {
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusIndicator: View
    private lateinit var activityIndicator: ProgressBar
    private lateinit var tunnelSummary: TextView
    private lateinit var adbSummary: TextView
    private lateinit var setupCard: View
    private lateinit var setupTitle: TextView
    private lateinit var setupHelp: TextView
    private lateinit var tunnelGroup: LinearLayout
    private lateinit var pairingGroup: LinearLayout
    private lateinit var adbPortGroup: LinearLayout
    private lateinit var tunnelIdInput: EditText
    private lateinit var runtimeKeyInput: EditText
    private lateinit var pairingPortInput: EditText
    private lateinit var pairingPinInput: EditText
    private lateinit var adbPortInput: EditText
    private lateinit var retryButton: MaterialButton
    private lateinit var stopButton: MaterialButton

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render(ServiceState.current)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)
        bindViews()
        populateStoredConfiguration()
        bindActions()
        requestNotificationPermission()

        // Starting the service is idempotent. It survives after this activity is closed.
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
        render(ServiceState.current)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun bindViews() {
        statusTitle = findViewById(R.id.statusTitle)
        statusMessage = findViewById(R.id.statusMessage)
        statusIndicator = findViewById(R.id.statusIndicator)
        activityIndicator = findViewById(R.id.activityIndicator)
        tunnelSummary = findViewById(R.id.tunnelSummary)
        adbSummary = findViewById(R.id.adbSummary)
        setupCard = findViewById(R.id.setupCard)
        setupTitle = findViewById(R.id.setupTitle)
        setupHelp = findViewById(R.id.setupHelp)
        tunnelGroup = findViewById(R.id.tunnelGroup)
        pairingGroup = findViewById(R.id.pairingGroup)
        adbPortGroup = findViewById(R.id.adbPortGroup)
        tunnelIdInput = findViewById(R.id.tunnelIdInput)
        runtimeKeyInput = findViewById(R.id.runtimeKeyInput)
        pairingPortInput = findViewById(R.id.pairingPortInput)
        pairingPinInput = findViewById(R.id.pairingPinInput)
        adbPortInput = findViewById(R.id.adbPortInput)
        retryButton = findViewById(R.id.retryButton)
        stopButton = findViewById(R.id.stopButton)
        findViewById<TextView>(R.id.versionText).text = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
    }

    private fun populateStoredConfiguration() {
        val config = SecureConfig(this).load()
        tunnelIdInput.setText(config.tunnelId)
        runtimeKeyInput.setText(config.apiKey)
        if (config.adbPort > 0) adbPortInput.setText(config.adbPort.toString())
        updateConfigurationSummary(config)
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.saveTunnelButton).setOnClickListener {
            sendServiceAction(TunnelService.ACTION_SET_TUNNEL) {
                putExtra(TunnelService.EXTRA_TUNNEL, tunnelIdInput.text.toString())
                putExtra(TunnelService.EXTRA_KEY, runtimeKeyInput.text.toString())
            }
            runtimeKeyInput.text.clear()
        }
        findViewById<MaterialButton>(R.id.pairButton).setOnClickListener {
            sendServiceAction(TunnelService.ACTION_PAIR) {
                putExtra(TunnelService.EXTRA_HOST, LOOPBACK_HOST)
                putExtra(TunnelService.EXTRA_PORT, pairingPortInput.integerValue())
                putExtra(TunnelService.EXTRA_PIN, pairingPinInput.text.toString())
            }
            pairingPinInput.text.clear()
        }
        findViewById<MaterialButton>(R.id.saveAdbPortButton).setOnClickListener {
            sendServiceAction(TunnelService.ACTION_SET_ADB) {
                putExtra(TunnelService.EXTRA_HOST, LOOPBACK_HOST)
                putExtra(TunnelService.EXTRA_PORT, adbPortInput.integerValue())
            }
        }
        retryButton.setOnClickListener { sendServiceAction(TunnelService.ACTION_RETRY) }
        stopButton.setOnClickListener { sendServiceAction(TunnelService.ACTION_STOP) }
        findViewById<MaterialButton>(R.id.copyLogsButton).setOnClickListener { copyLogs() }
    }

    private fun render(status: ServiceStatus) {
        val presentation = presentationFor(status.phase)
        statusTitle.text = getString(presentation.title)
        statusMessage.text = status.message
        statusIndicator.setBackgroundResource(presentation.indicator)
        activityIndicator.visibility = if (presentation.busy) View.VISIBLE else View.GONE

        val requiresInput = status.phase in INPUT_PHASES
        setupCard.visibility = if (requiresInput) View.VISIBLE else View.GONE
        tunnelGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_TUNNEL)
        pairingGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_PAIRING)
        adbPortGroup.visibleOnlyWhen(status.phase == ServicePhase.NEED_ADB_PORT)
        retryButton.visibility = if (status.phase == ServicePhase.ERROR) View.VISIBLE else View.GONE
        stopButton.visibility = if (status.phase == ServicePhase.STOPPED) View.GONE else View.VISIBLE

        when (status.phase) {
            ServicePhase.NEED_TUNNEL -> showSetup(R.string.setup_tunnel_title, R.string.setup_tunnel_help)
            ServicePhase.NEED_PAIRING -> showSetup(R.string.setup_pair_title, R.string.setup_pair_help)
            ServicePhase.NEED_ADB_PORT -> showSetup(R.string.setup_port_title, R.string.setup_port_help)
            else -> Unit
        }
        updateConfigurationSummary(SecureConfig(this).load())
    }

    private fun showSetup(title: Int, help: Int) {
        setupTitle.setText(title)
        setupHelp.setText(help)
    }

    private fun updateConfigurationSummary(config: AppConfig) {
        tunnelSummary.text = if (config.tunnelId.isBlank()) {
            getString(R.string.not_configured)
        } else {
            getString(R.string.configured_tunnel, config.tunnelId.takeLast(6))
        }
        adbSummary.text = if (config.adbPort == 0) {
            getString(R.string.not_configured)
        } else {
            getString(R.string.configured_adb, config.adbHost, config.adbPort)
        }
    }

    private fun presentationFor(phase: ServicePhase) = when (phase) {
        ServicePhase.RUNNING -> StatusPresentation(R.string.status_running, R.drawable.status_dot_success, false)
        ServicePhase.CONNECTING, ServicePhase.PAIRING, ServicePhase.STARTING ->
            StatusPresentation(R.string.status_working, R.drawable.status_dot_working, true)
        ServicePhase.NEED_TUNNEL, ServicePhase.NEED_PAIRING, ServicePhase.NEED_ADB_PORT ->
            StatusPresentation(R.string.status_action_required, R.drawable.status_dot_warning, false)
        ServicePhase.ERROR -> StatusPresentation(R.string.status_error, R.drawable.status_dot_error, false)
        ServicePhase.STOPPED -> StatusPresentation(R.string.status_stopped, R.drawable.status_dot_neutral, false)
    }

    private fun sendServiceAction(action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, TunnelService::class.java).setAction(action).apply(extras),
        )
    }

    private fun copyLogs() {
        val report = DiagnosticLog.export(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.diagnostic_logs), report),
        )
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun EditText.integerValue() = text.toString().toIntOrNull() ?: 0
    private fun View.visibleOnlyWhen(visible: Boolean) { visibility = if (visible) View.VISIBLE else View.GONE }

    private data class StatusPresentation(val title: Int, val indicator: Int, val busy: Boolean)

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val NOTIFICATION_REQUEST = 1
        val INPUT_PHASES = setOf(
            ServicePhase.NEED_TUNNEL,
            ServicePhase.NEED_PAIRING,
            ServicePhase.NEED_ADB_PORT,
        )
    }
}
