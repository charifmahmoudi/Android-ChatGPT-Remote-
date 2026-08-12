package com.charifmahmoudi.chatgptremote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppConfig(
    val tunnelId: String,
    val apiKey: String,
    val adbHost: String,
    val adbPort: Int,
)

/** Stores credentials with keys protected by Android Keystore. */
class SecureConfig(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load() = AppConfig(
        tunnelId = preferences.getString(KEY_TUNNEL_ID, "").orEmpty(),
        apiKey = preferences.getString(KEY_API_KEY, "").orEmpty(),
        adbHost = preferences.getString(KEY_ADB_HOST, LOOPBACK_HOST).orEmpty(),
        adbPort = preferences.getInt(KEY_ADB_PORT, 0),
    )

    fun saveTunnel(id: String, key: String) {
        preferences.edit().putString(KEY_TUNNEL_ID, id).putString(KEY_API_KEY, key).apply()
    }

    fun saveAdbEndpoint(host: String, port: Int) {
        preferences.edit().putString(KEY_ADB_HOST, host).putInt(KEY_ADB_PORT, port).apply()
    }

    fun markPaired() {
        preferences.edit().putBoolean(KEY_ADB_PAIRED, true).apply()
    }

    fun isPaired(): Boolean = preferences.getBoolean(KEY_ADB_PAIRED, false)

    private companion object {
        const val FILE_NAME = "secure_config"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val KEY_TUNNEL_ID = "tunnel"
        const val KEY_API_KEY = "key"
        const val KEY_ADB_HOST = "adb_host"
        const val KEY_ADB_PORT = "adb_port"
        const val KEY_ADB_PAIRED = "adb_paired"
    }
}
