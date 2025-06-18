package app.sst.pinto.utils

import android.os.CountDownTimer
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages timeout functionality for the app.
 * Tracks user inactivity and triggers timeout events when necessary.
 */
class TimeoutManager private constructor() {
    private val TAG = "TimeoutManager"

    // Time in milliseconds before showing the timeout warning
    private val INACTIVITY_THRESHOLD = 30000L // 30 seconds - adjust as needed

    // Time in milliseconds for the timeout warning countdown
    private val COUNTDOWN_DURATION = 30000L // 30 seconds countdown

    // Total seconds for countdown (used for display)
    private val TOTAL_SECONDS = (COUNTDOWN_DURATION / 1000).toInt()

    // Countdown timer interval - use exactly 1 second
    private val COUNTDOWN_INTERVAL = 1000L // 1 second intervals

    private var inactivityTimer: CountDownTimer? = null
    private var countdownTimer: CountDownTimer? = null

    // State properties
    private val _isTimeoutWarningVisible = mutableStateOf(false)
    val isTimeoutWarningVisible: State<Boolean> = _isTimeoutWarningVisible

    private val _countdownProgress = mutableStateOf(1f)
    val countdownProgress: State<Float> = _countdownProgress

    // Direct seconds remaining value for accurate display
    private val _secondsRemaining = mutableIntStateOf(TOTAL_SECONDS)
    val secondsRemaining: State<Int> = _secondsRemaining

    private val _timeoutOccurred = MutableStateFlow(false)
    val timeoutOccurred: StateFlow<Boolean> = _timeoutOccurred

    private val _timedOut = mutableStateOf(false)
    val timedOut: State<Boolean> = _timedOut

    // Callback for when a timeout occurs
    var onTimeout: (() -> Unit)? = null

    init {
//        Log.d(TAG, "TimeoutManager initialized, inactivity threshold: ${INACTIVITY_THRESHOLD/1000} seconds, countdown: ${COUNTDOWN_DURATION/1000} seconds")
        startInactivityTimer()
    }

    /**
     * Records a user interaction to reset the inactivity timer.
     */
    fun recordUserInteraction() {
        Log.d(TAG, "User interaction recorded, resetting timers")
        resetTimers()
        _timedOut.value = false
    }
    // Add to TimeoutManager class

    /**
     * Pauses all timers while the screensaver is active.
     * Call this when the screensaver becomes visible.
     */
    fun pauseTimersForScreensaver() {
        Log.d(TAG, "Pausing all timers while screensaver is active")
        inactivityTimer?.cancel()
        countdownTimer?.cancel()
        // Don't reset states here as we don't want to restart timers
    }

    /**
     * Restarts timers when the screensaver is dismissed.
     * This should be called when the screensaver is hidden.
     */
    fun resumeTimersAfterScreensaver() {
        Log.d(TAG, "Resuming timers after screensaver is dismissed")
        // Reset all states and start a fresh inactivity timer
        resetTimers()
    }

    /**
     * Triggers a timeout event.
     */
    private fun triggerTimeout() {
        Log.d(TAG, "Timeout triggered")
        // Stop all timers
        inactivityTimer?.cancel()
        countdownTimer?.cancel()

        // Update UI states
        _isTimeoutWarningVisible.value = false
        _countdownProgress.value = 0f
        _secondsRemaining.value = 0

        // First set to false to ensure state change is detected
        _timeoutOccurred.value = false
        // Then set to true to ensure Flow collectors detect the change
        _timeoutOccurred.value = true
        _timedOut.value = true

        // Call timeout callback
        onTimeout?.invoke()

        // Note: Not calling resetTimers() here anymore because we don't want
        // to start a new inactivity timer while the screensaver is active
    }
    /**
     * Resets all timers and clears warning state.
     */
    private fun resetTimers() {
        Log.d(TAG, "Resetting all timers")
        inactivityTimer?.cancel()
        countdownTimer?.cancel()
        _isTimeoutWarningVisible.value = false
        _countdownProgress.value = 1f
        _secondsRemaining.value = TOTAL_SECONDS
        startInactivityTimer()
    }

    // Add to TimeoutManager class

    // Flag to determine if warning should be skipped
    private var skipWarning = false

    /**
     * Sets whether to skip the warning countdown and go straight to timeout
     * Used to disable countdown on amount selection screen
     */
    fun setSkipWarning(skip: Boolean) {
        Log.d(TAG, "Setting skipWarning = $skip")
        skipWarning = skip

        // If currently showing warning and we want to skip, hide it
        if (skip && _isTimeoutWarningVisible.value) {
            Log.d(TAG, "Warning is currently visible but skipWarning was set - hiding warning")
            _isTimeoutWarningVisible.value = false
        }
    }

    /**
     * Shows the timeout warning and starts the countdown timer.
     * Modified to check skipWarning flag.
     */
    private fun showTimeoutWarning() {
        if (skipWarning) {
            Log.d(TAG, "Skipping timeout warning as requested, going straight to timeout")
            triggerTimeout()
            return
        }

        Log.d(TAG, "Showing timeout warning and starting countdown timer")
        _isTimeoutWarningVisible.value = true
        _countdownProgress.value = 1f
        _secondsRemaining.value = TOTAL_SECONDS

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(COUNTDOWN_DURATION, COUNTDOWN_INTERVAL) {
            override fun onTick(millisUntilFinished: Long) {
                // If skip flag gets set during countdown, cancel and trigger timeout
                if (skipWarning) {
                    Log.d(TAG, "Skip flag set during countdown - canceling and triggering timeout")
                    cancel()
                    _isTimeoutWarningVisible.value = false
                    triggerTimeout()
                    return
                }

                // Calculate progress from 1.0 to 0.0
                val progress = millisUntilFinished.toFloat() / COUNTDOWN_DURATION.toFloat()
                _countdownProgress.value = progress

                // Set exact seconds remaining for display
                val exactSeconds = (millisUntilFinished / 1000).toInt()
                _secondsRemaining.value = exactSeconds

//                Log.d(TAG, "Countdown: $exactSeconds seconds remaining, progress: $progress")
            }

            override fun onFinish() {
                Log.d(TAG, "Countdown timer finished, triggering timeout")
                _countdownProgress.value = 0f
                _secondsRemaining.value = 0
                triggerTimeout()
            }
        }.start()
    }
    /**
     * Starts the timer that tracks user inactivity.
     */
    private fun startInactivityTimer() {
        Log.d(TAG, "Starting inactivity timer for ${INACTIVITY_THRESHOLD/1000} seconds")
        inactivityTimer?.cancel()

        inactivityTimer = object : CountDownTimer(INACTIVITY_THRESHOLD, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                // Log every 5 seconds for debugging
                if (secondsLeft % 5 == 0L) {
//                    Log.d(TAG, "Inactivity timer: $secondsLeft seconds remaining")
                }
            }

            override fun onFinish() {
//                Log.d(TAG, "Inactivity timer finished, showing timeout warning")
                showTimeoutWarning()
            }
        }.start()
    }

    /**
     * Shows the timeout warning and starts the countdown timer.
     */


    /**
     * Triggers a timeout event.
     */


    /**
     * Confirms the user wants to continue, canceling the timeout.
     */
    fun continueSession() {
        Log.d(TAG, "User confirmed continuation, resetting timers")
        resetTimers()
    }

    /**
     * Cancels the active timers and clears state.
     */
    fun cancelTimers() {
        Log.d(TAG, "Canceling all timers")
        inactivityTimer?.cancel()
        countdownTimer?.cancel()
        _isTimeoutWarningVisible.value = false
        _countdownProgress.value = 1f
        _secondsRemaining.value = TOTAL_SECONDS
    }

    /**
     * Sets up the timeout manager with a callback.
     */
    fun setup(timeoutCallback: () -> Unit) {
        Log.d(TAG, "Setting up timeout manager with callback")
        onTimeout = timeoutCallback
    }

    companion object {
        @Volatile
        private var instance: TimeoutManager? = null

        fun getInstance(): TimeoutManager {
            return instance ?: synchronized(this) {
                instance ?: TimeoutManager().also { instance = it }
            }
        }
    }
}