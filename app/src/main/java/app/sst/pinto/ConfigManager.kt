package app.sst.pinto.config

import android.content.Context
import android.content.SharedPreferences
import app.sst.pinto.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Configuration manager for handling app settings.
 * Currently uses SharedPreferences but designed to be replaced with remote config later.
 */
class ConfigManager private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREF_NAME, Context.MODE_PRIVATE
    )

    // StateFlows for reactive settings
    private val _serverUrl = MutableStateFlow(getServerUrl())
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _retryIntervalSeconds = MutableStateFlow(getRetryIntervalSeconds())
    val retryIntervalSeconds: StateFlow<Int> = _retryIntervalSeconds.asStateFlow()

    private val _maxRetryAttempts = MutableStateFlow(getMaxRetryAttempts())
    val maxRetryAttempts: StateFlow<Int> = _maxRetryAttempts.asStateFlow()

    private val _inactivityTimeoutSeconds = MutableStateFlow(getInactivityTimeoutSeconds())
    val inactivityTimeoutSeconds: StateFlow<Int> = _inactivityTimeoutSeconds.asStateFlow()

    // Server URL
    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
        _serverUrl.value = url
    }

    // Retry interval
    fun getRetryIntervalSeconds(): Int {
        return prefs.getInt(KEY_RETRY_INTERVAL, DEFAULT_RETRY_INTERVAL_SECONDS)
    }

    fun setRetryIntervalSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_RETRY_INTERVAL, seconds).apply()
        _retryIntervalSeconds.value = seconds
    }

    // Max retry attempts
    fun getMaxRetryAttempts(): Int {
        return prefs.getInt(KEY_MAX_RETRY_ATTEMPTS, DEFAULT_MAX_RETRY_ATTEMPTS)
    }

    fun setMaxRetryAttempts(attempts: Int) {
        prefs.edit().putInt(KEY_MAX_RETRY_ATTEMPTS, attempts).apply()
        _maxRetryAttempts.value = attempts
    }

    // Inactivity timeout
    fun getInactivityTimeoutSeconds(): Int {
        return prefs.getInt(KEY_INACTIVITY_TIMEOUT, DEFAULT_INACTIVITY_TIMEOUT_SECONDS)
    }

    fun setInactivityTimeoutSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_INACTIVITY_TIMEOUT, seconds).apply()
        _inactivityTimeoutSeconds.value = seconds
    }

    // For better organization, you can add a method to update multiple settings at once
    fun updateConnectionConfig(
        serverUrl: String? = null,
        retryInterval: Int? = null,
        maxRetries: Int? = null
    ) {
        val editor = prefs.edit()

        serverUrl?.let {
            editor.putString(KEY_SERVER_URL, it)
            _serverUrl.value = it
        }

        retryInterval?.let {
            editor.putInt(KEY_RETRY_INTERVAL, it)
            _retryIntervalSeconds.value = it
        }

        maxRetries?.let {
            editor.putInt(KEY_MAX_RETRY_ATTEMPTS, it)
            _maxRetryAttempts.value = it
        }

        editor.apply()
    }

    companion object {
        private const val PREF_NAME = "app_config"

        // Keys
        private const val KEY_SERVER_URL = "ws://192.168.2.112:5001"
        private const val KEY_RETRY_INTERVAL = "retry_interval_seconds"
        private const val KEY_MAX_RETRY_ATTEMPTS = "max_retry_attempts"
        private const val KEY_INACTIVITY_TIMEOUT = "inactivity_timeout_seconds"

        // Default values
//        const val DEFAULT_SERVER_URL = "ws://192.168.2.115:8080"
        const val DEFAULT_SERVER_URL = "ws://192.168.2.112:5001"
        const val DEFAULT_RETRY_INTERVAL_SECONDS = 5
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 0 // 0 means infinite
        const val DEFAULT_INACTIVITY_TIMEOUT_SECONDS = 120

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}