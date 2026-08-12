package com.charifmahmoudi.chatgptremote

import android.content.Context
import android.content.Intent

enum class ServicePhase { STARTING, NEED_TUNNEL, NEED_PAIRING, NEED_ADB_PORT, PAIRING, CONNECTING, RUNNING, ERROR, STOPPED }
data class ServiceStatus(val phase: ServicePhase, val message: String)
object ServiceState {
    const val ACTION_STATUS = "com.charifmahmoudi.chatgptremote.STATUS"
    @Volatile var current = ServiceStatus(ServicePhase.STOPPED, "Service is stopped"); private set
    fun publish(context: Context, phase: ServicePhase, message: String) {
        current = ServiceStatus(phase, message)
        context.sendBroadcast(Intent(ACTION_STATUS).setPackage(context.packageName).putExtra("phase", phase.name).putExtra("message", message))
    }
}
