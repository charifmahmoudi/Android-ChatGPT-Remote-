package com.charifmahmoudi.chatgptremote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class AppConfig(val tunnelId: String, val apiKey: String, val mcpUrl: String)
class SecureConfig(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "secure_config", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    fun save(config: AppConfig) = prefs.edit().putString("tunnel", config.tunnelId).putString("key", config.apiKey).putString("mcp", config.mcpUrl).apply()
    fun load() = AppConfig(prefs.getString("tunnel", "")!!, prefs.getString("key", "")!!, prefs.getString("mcp", "http://127.0.0.1:8765/mcp")!!)
}
