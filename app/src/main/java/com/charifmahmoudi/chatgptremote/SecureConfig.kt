package com.charifmahmoudi.chatgptremote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppConfig(val tunnelId: String, val apiKey: String, val adbHost: String, val adbPort: Int)
class SecureConfig(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_config", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun save(config: AppConfig) = prefs.edit().putString("tunnel", config.tunnelId).putString("key", config.apiKey).putString("adb_host", config.adbHost).putInt("adb_port", config.adbPort).apply()
    fun load() = AppConfig(prefs.getString("tunnel", "")!!, prefs.getString("key", "")!!, prefs.getString("adb_host", "127.0.0.1")!!, prefs.getInt("adb_port", 0))
    fun saveTunnel(id: String, key: String) = prefs.edit().putString("tunnel", id).putString("key", key).apply()
    fun saveAdbEndpoint(host: String, port: Int) = prefs.edit().putString("adb_host", host).putInt("adb_port", port).apply()
    fun markPaired() = prefs.edit().putBoolean("adb_paired", true).apply()
    fun isPaired() = prefs.getBoolean("adb_paired", false)
}
