package app.sst.pinto

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sst.pinto.ui.components.Screensaver
import app.sst.pinto.ui.components.TimeoutWarning
import app.sst.pinto.ui.screens.PaymentScreen
import app.sst.pinto.ui.theme.PintoTheme
import app.sst.pinto.utils.TimeoutManager
import app.sst.pinto.viewmodels.PaymentViewModel
import app.sst.pinto.config.ConfigManager


class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var timeoutManager: TimeoutManager
    lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        enableEdgeToEdge()

        // Initialize timeout manager
        timeoutManager = TimeoutManager.getInstance()
        configManager = ConfigManager.getInstance(applicationContext)

        // You can set this in your app configuration or make it user-configurable
//        val serverUrl = "ws://192.168.2.115:8080"
        val serverUrl = configManager.getServerUrl()

        // Set video resource for screensaver - assumes you have a video file in res/raw/screensaver.mp4
        val screensaverVideoResId = R.raw.screensaver

        setContent {
            Log.d(TAG, "Setting content")
            PintoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(serverUrl, screensaverVideoResId)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // Record user interaction for any touch event
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "Touch event detected, recording user interaction")
            timeoutManager.recordUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        // Reset timeout timer when app comes back to foreground
        timeoutManager.recordUserInteraction()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
    }
}

// Update in MainActivity.kt

@Composable
fun MainScreen(serverUrl: String, screensaverVideoResId: Int) {
    val TAG = "MainScreen"
    Log.d(TAG, "MainScreen composition started")

    val viewModel: PaymentViewModel = viewModel()
    val screenState by viewModel.screenState.collectAsState()
    val isScreensaverVisible by viewModel.isScreensaverVisible.collectAsState()
    val isOnAmountScreen by viewModel.isOnAmountScreen.collectAsState()
    val timeoutManager = TimeoutManager.getInstance()

    // Connect to backend when the app starts
    viewModel.connectToBackend(serverUrl)

    // Setup custom timeout behavior based on current screen
    LaunchedEffect(isOnAmountScreen) {
        if (isOnAmountScreen) {
            // On amount screen, we want to disable the countdown warning
            // but still track inactivity for screensaver
            timeoutManager.setSkipWarning(true)
            Log.d(TAG, "Amount screen detected, skipping timeout warning")
        } else {
            // On other screens, show the normal countdown warning
            timeoutManager.setSkipWarning(false)
            Log.d(TAG, "Non-amount screen detected, enabling timeout warning")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Main payment screen that handles all states
        PaymentScreen(
            screenState = screenState,
            onAmountSelected = { amount ->
                Log.d(TAG, "Amount selected: $amount")
                viewModel.recordUserInteraction()
                viewModel.selectAmount(amount)
            },
            onPaymentMethodSelected = { method ->
                Log.d(TAG, "Payment method selected: $method")
                viewModel.recordUserInteraction()
                viewModel.selectPaymentMethod(method)
            },
            onCancelPayment = {
                Log.d(TAG, "Payment canceled by user")
                viewModel.recordUserInteraction()
                viewModel.cancelPayment()
            }
        )

        // Only show timeout warning if we're not on the amount selection screen
        // The actual visibility control is now handled by TimeoutManager based on skipWarning flag
        TimeoutWarning(
            timeoutManager = timeoutManager,
            onContinue = {
                Log.d(TAG, "User chose to continue session")
                viewModel.recordUserInteraction()
            },
            onTimeout = {
                Log.d(TAG, "User chose to end session immediately")
                viewModel.cancelPayment(isTimeout = true)
                // Show screensaver immediately
                viewModel.forceShowScreensaver()
            }
        )

        // Screensaver - shows when timeout has occurred
        Screensaver(
            isVisible = isScreensaverVisible,
            videoResId = screensaverVideoResId,
            onTap = {
                Log.d(TAG, "Screensaver tapped")
                viewModel.dismissScreensaver()
            }
        )
    }
}