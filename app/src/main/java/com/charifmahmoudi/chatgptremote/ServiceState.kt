package com.charifmahmoudi.chatgptremote

import android.content.Context
import android.content.Intent

enum class ServicePhase {
    STARTING, NEED_TUNNEL, NEED_PAIRING, NEED_ADB_PORT, PAIRING,
    CONNECTING, RUNNING, ERROR, STOPPED,
}

data class ServiceStatus(val phase: ServicePhase, val message: String)

/** In-process state plus a package-scoped signal for the optional status activity. */
object ServiceState {
    const val ACTION_STATUS = "com.charifmahmoudi.chatgptremote.STATUS"

    @Volatile
    var current = ServiceStatus(ServicePhase.STOPPED, "Service is stopped")
        private set

    fun publish(context: Context, phase: ServicePhase, message: String) {
        current = ServiceStatus(phase, message)
        context.sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_PHASE, phase.name)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    private const val EXTRA_PHASE = "phase"
    private const val EXTRA_MESSAGE = "message"
}
