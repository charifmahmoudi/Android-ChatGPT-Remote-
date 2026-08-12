package com.charifmahmoudi.chatgptremote

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var tunnelGroup: LinearLayout
    private lateinit var pairingGroup: LinearLayout
    private lateinit var portGroup: LinearLayout
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { render(ServiceState.current) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val stored = SecureConfig(this).load()
        val tunnel = EditText(this).apply { hint = "tunnel_…"; setText(stored.tunnelId) }
        val key = EditText(this).apply { hint = "Runtime API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(stored.apiKey) }
        val host = EditText(this).apply { hint = "ADB host"; setText("127.0.0.1") }
        val pairPort = EditText(this).apply { hint = "Pairing port from Wireless debugging"; inputType = InputType.TYPE_CLASS_NUMBER }
        val pin = EditText(this).apply { hint = "6-digit ADB pairing PIN"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        val adbPort = EditText(this).apply { hint = "ADB connection port"; inputType = InputType.TYPE_CLASS_NUMBER; if (stored.adbPort > 0) setText(stored.adbPort.toString()) }
        val pair = Button(this).apply { text = "Pair ADB" }
        val saveTunnel = Button(this).apply { text = "Submit tunnel credentials" }
        val savePort = Button(this).apply { text = "Submit ADB connection port" }
        val retry = Button(this).apply { text = "Retry background service" }
        val stop = Button(this).apply { text = "Stop service" }
        status = TextView(this)
        tunnelGroup = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(tunnel); addView(key); addView(saveTunnel) }
        pairingGroup = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(host); addView(pairPort); addView(pin); addView(pair) }
        portGroup = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(adbPort); addView(savePort) }
        setContentView(ScrollView(this).apply { addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; setPadding(40,60,40,40); addView(status); addView(tunnelGroup); addView(pairingGroup); addView(portGroup); addView(retry); addView(stop) }) })
        pair.setOnClickListener {
            send(TunnelService.ACTION_PAIR) { putExtra(TunnelService.EXTRA_HOST,host.text.toString()); putExtra(TunnelService.EXTRA_PORT,pairPort.text.toString().toIntOrNull()?:0); putExtra(TunnelService.EXTRA_PIN,pin.text.toString()) }; pin.text.clear()
        }
        saveTunnel.setOnClickListener { send(TunnelService.ACTION_SET_TUNNEL) { putExtra(TunnelService.EXTRA_TUNNEL,tunnel.text.toString()); putExtra(TunnelService.EXTRA_KEY,key.text.toString()) }; key.text.clear() }
        savePort.setOnClickListener { send(TunnelService.ACTION_SET_ADB) { putExtra(TunnelService.EXTRA_HOST,"127.0.0.1"); putExtra(TunnelService.EXTRA_PORT,adbPort.text.toString().toIntOrNull()?:0) } }
        retry.setOnClickListener { send(TunnelService.ACTION_RETRY) {} }
        stop.setOnClickListener { send(TunnelService.ACTION_STOP) {} }
        requestNotification(); ContextCompat.startForegroundService(this, Intent(this, TunnelService::class.java)); render(ServiceState.current)
    }
    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, receiver, IntentFilter(ServiceState.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED) }
    override fun onStop() { unregisterReceiver(receiver); super.onStop() }
    private fun send(action:String, extras:Intent.()->Unit) { ContextCompat.startForegroundService(this, Intent(this,TunnelService::class.java).setAction(action).apply(extras)) }
    private fun render(value:ServiceStatus) { status.text="Status: ${value.phase}\n${value.message}"; tunnelGroup.visibility=if(value.phase==ServicePhase.NEED_TUNNEL) android.view.View.VISIBLE else android.view.View.GONE; pairingGroup.visibility=if(value.phase==ServicePhase.NEED_PAIRING) android.view.View.VISIBLE else android.view.View.GONE; portGroup.visibility=if(value.phase==ServicePhase.NEED_ADB_PORT) android.view.View.VISIBLE else android.view.View.GONE }
    private fun requestNotification() { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
}
