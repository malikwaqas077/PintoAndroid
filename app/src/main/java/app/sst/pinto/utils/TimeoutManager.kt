package app.sst.pinto.utils

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages timeout functionality for the application.
 * Handles inactivity detection and automatic timeout.
 */
class TimeoutManager private constructor() {
    private val TAG = "TimeoutManager"

    // Timeout duration before triggering screensaver (30 seconds)
    private val timeoutDuration = 30 * 1000L

    // State flows for tracking timeout state
    private val _timeoutOccurred = MutableStateFlow(false)
    val timeoutOccurred: StateFlow<Boolean> = _timeoutOccurred

    // Job for tracking timeout
    private var timeoutJob: Job? = null

    // Callback for timeout events
    private var onTimeoutCallback: (() -> Unit)? = null

    /**
     * Set up the timeout manager with a callback for timeout events.
     */
    fun setup(onTimeout: () -> Unit) {
        Log.d(TAG, "Setting up TimeoutManager with callback")
        this.onTimeoutCallback = onTimeout
        resetTimer()
    }

    /**
     * Records user interaction to reset the timeout timer.
     */
    fun recordUserInteraction() {
        resetTimer()

        // If timeout has occurred, reset the flag
        if (_timeoutOccurred.value) {
            _timeoutOccurred.value = false
        }
    }

    /**
     * Resets the timeout timer.
     */
    private fun resetTimer() {
        // Cancel any existing timeout job
        timeoutJob?.cancel()

        // Start a new timeout job.
        //
        // NOTE: We deliberately don't wrap `delay(...)` in a catch-all.
        // Coroutine cancellation is a cooperative `CancellationException`,
        // so every time `recordUserInteraction()` cancels the previous
        // job the old code logged a misleading "Error in timeout timer"
        // entry. We now only log truly unexpected failures and always
        // rethrow cancellation to preserve structured concurrency.
        timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                delay(timeoutDuration)
                if (isActive) {
                    Log.d(TAG, "Timeout occurred after $timeoutDuration ms")
                    _timeoutOccurred.value = true
                    onTimeoutCallback?.invoke()
                }
            } catch (e: CancellationException) {
                // Expected: the timer was reset or paused. Rethrow so the
                // coroutine machinery treats it as a normal cancellation.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in timeout timer", e)
            }
        }
    }

    /**
     * Pause timers when showing screensaver.
     */
    fun pauseTimersForScreensaver() {
        Log.d(TAG, "Pausing timers for screensaver")
        timeoutJob?.cancel()
        timeoutJob = null
    }

    /**
     * Resume timers after screensaver is dismissed.
     */
    fun resumeTimersAfterScreensaver() {
        Log.d(TAG, "Resuming timers after screensaver")
        resetTimer()
    }

    /**
     * Cancels all timers.
     */
    fun cancelTimers() {
        Log.d(TAG, "Canceling all timers")
        timeoutJob?.cancel()
        timeoutJob = null
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