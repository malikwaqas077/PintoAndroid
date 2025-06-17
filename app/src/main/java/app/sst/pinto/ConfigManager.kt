package app.sst.pinto.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class ConfigManager private constructor(context: Context) {
    private val TAG = "ConfigManager"
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "pinto_config"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val DEFAULT_PORT = "5001"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Check if this is the first launch (no server URL configured)
     */
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)
    }

    /**
     * Get the complete WebSocket URL
     */
    fun getServerUrl(): String {
        val ip = prefs.getString(KEY_SERVER_URL, "") ?: ""
        val port = prefs.getString(KEY_SERVER_PORT, DEFAULT_PORT) ?: DEFAULT_PORT

        return if (ip.isNotEmpty()) {
            "ws://$ip:$port"
        } else {
            ""
        }
    }

    /**
     * Get just the IP address
     */
    fun getServerIp(): String {
        return prefs.getString(KEY_SERVER_URL, "") ?: ""
    }

    /**
     * Get the port
     */
    fun getServerPort(): String {
        return prefs.getString(KEY_SERVER_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
    }

    /**
     * Save server configuration
     */
    fun saveServerConfig(ip: String, port: String = DEFAULT_PORT): Boolean {
        return try {
            // Validate server address format (IP or domain)
            if (!isValidServerAddress(ip)) {
                Log.e(TAG, "Invalid server address format: $ip")
                return false
            }

            // Validate port
            val portInt = port.toIntOrNull()
            if (portInt == null || portInt !in 1..65535) {
                Log.e(TAG, "Invalid port number: $port")
                return false
            }

            prefs.edit()
                .putString(KEY_SERVER_URL, ip.trim())
                .putString(KEY_SERVER_PORT, port.trim())
                .putBoolean(KEY_IS_FIRST_LAUNCH, false)
                .apply()

            Log.d(TAG, "Server config saved: ws://$ip:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving server config", e)
            false
        }
    }

    /**
     * Clear server configuration (for testing or reset)
     */
    fun clearServerConfig() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_SERVER_PORT)
            .putBoolean(KEY_IS_FIRST_LAUNCH, true)
            .apply()
        Log.d(TAG, "Server config cleared")
    }

    /**
     * Check if server URL is configured
     */
    fun hasServerUrl(): Boolean {
        return getServerIp().isNotEmpty()
    }

    /**
     * Validate server address format (supports both IP addresses and domain names)
     */
    private fun isValidServerAddress(address: String): Boolean {
        if (address.isEmpty()) return false

        // Allow localhost for development
        if (address.lowercase() == "localhost") return true

        // Check if it's a valid domain name
        if (isValidDomainName(address)) return true

        // Check if it's a valid IPv4 address
        return isValidIPv4(address)
    }

    /**
     * Validate domain name format
     */
    private fun isValidDomainName(domain: String): Boolean {
        if (domain.length > 253) return false

        // Basic domain validation regex
        val domainRegex = Regex(
            "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+" +
                    "[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"
        )

        // Allow simple hostnames (no dots) for local networks
        val hostnameRegex = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

        return domainRegex.matches(domain) || hostnameRegex.matches(domain)
    }

    /**
     * Validate IPv4 address format
     */
    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }
}