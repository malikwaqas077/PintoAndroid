package app.sst.pinto

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sst.pinto.ui.components.Screensaver
import app.sst.pinto.ui.screens.PaymentScreen
import app.sst.pinto.ui.theme.PintoTheme
import app.sst.pinto.utils.TimeoutManager
import app.sst.pinto.viewmodels.PaymentViewModel
import app.sst.pinto.config.ConfigManager
import app.sst.pinto.ui.components.PressAndHoldDetector
import app.sst.pinto.ui.screens.PinAuthScreen
import app.sst.pinto.ui.screens.ServerConfigScreen
import app.sst.pinto.ui.screens.SettingsScreen
import android.content.Intent
import android.net.Uri

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var timeoutManager: TimeoutManager
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // Initialize managers first
        timeoutManager = TimeoutManager.getInstance()
        configManager = ConfigManager.getInstance(applicationContext)
        
        // Initialize Planet SDK logger early (must be done before any SDK calls)
        // This prevents native crashes that can occur if SDK is used before logger is initialized
        // Run on background thread to avoid blocking UI thread, especially on non-Planet devices
        app.sst.pinto.payment.PlanetPaymentManager.initializeLoggerAsync { isAvailable ->
            if (isAvailable) {
                Log.d(TAG, "Planet SDK is available on this device")
                
                // Initialize Integra instance early at app start for faster transactions
                // This avoids initialization delay during payment processing
                Thread {
                    try {
                        val success = app.sst.pinto.payment.PlanetPaymentManager.initializeIntegra()
                        if (success) {
                            Log.d(TAG, "Planet Integra initialized successfully at app start")
                        } else {
                            Log.w(TAG, "Planet Integra initialization deferred (will initialize on first transaction)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initializing Planet Integra at app start", e)
                        // Non-fatal: will initialize lazily on first transaction
                    }
                }.start()
            } else {
                Log.w(TAG, "Planet SDK is not available on this device - payment features will be disabled")
            }
        }

        // Make the app fullscreen BEFORE setting content
        setupFullscreen()

        setContent {
            Log.d(TAG, "Setting content")
            PintoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(configManager, R.raw.screensaver)
                }
            }
        }
    }

    private fun setupFullscreen() {
        Log.d(TAG, "Setting up fullscreen mode")

        // Method 1: Using WindowCompat (modern approach)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.apply {
            // Hide both status bar and navigation bar
            hide(WindowInsetsCompat.Type.systemBars())
            // Set sticky immersive behavior
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Method 2: Using window flags (additional safety)
        window.apply {
            // Keep screen on
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Full screen flags
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            // Hide navigation bar
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        // Method 3: System UI visibility flags (legacy but effective)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LOW_PROFILE
                )

        Log.d(TAG, "Fullscreen setup complete")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "Window focus changed: $hasFocus")
        if (hasFocus) {
            // Re-apply fullscreen when window regains focus
            setupFullscreen()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        // Reset timeout timer when app comes back to foreground
        timeoutManager.recordUserInteraction()
        // Re-apply fullscreen mode
        setupFullscreen()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Record any user interaction
        timeoutManager.recordUserInteraction()
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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy called")
        // Clean up Planet SDK resources
        try {
            app.sst.pinto.payment.PlanetPaymentManager.cleanup()
            Log.d(TAG, "Planet SDK resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Planet SDK resources", e)
        }
    }
}

@Composable
fun MainScreen(configManager: ConfigManager, screensaverVideoResId: Int) {
    val TAG = "MainScreen"
    val viewModel: PaymentViewModel = viewModel()
    val screenState by viewModel.screenState.collectAsState()
    val isScreensaverVisible by viewModel.isScreensaverVisible.collectAsState()
    val isOnAmountScreen by viewModel.isOnAmountScreen.collectAsState()
    val timeoutManager = TimeoutManager.getInstance()

    // Server configuration state
    var showServerConfig by remember { mutableStateOf(configManager.isFirstLaunch()) }
    var showPinAuth by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(configManager.getServerUrl()) }

    // Connect to backend only if we have a server URL
    LaunchedEffect(serverUrl) {
        if (serverUrl.isNotEmpty() && !showServerConfig) {
            viewModel.connectToBackend(serverUrl)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showPinAuth -> {
                // Show PIN authentication screen
                PinAuthScreen(
                    onPinCorrect = {
                        showPinAuth = false
                        showSettings = true
                    },
                    onCancel = {
                        showPinAuth = false
                    }
                )
            }
            showSettings -> {
                // Show settings screen
                SettingsScreen(
                    context = LocalContext.current,
                    onOpenServerConfig = {
                        showSettings = false
                        showServerConfig = true
                    },
                    onDownloadVideo = {
                        // Implement video download if needed
                        // For now, just log
                        android.util.Log.d("MainActivity", "Download video requested")
                    },
                    onClose = {
                        showSettings = false
                    },
                    onCloseApp = {
                        // Close the app
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                )
            }
            showServerConfig -> {
                // Show server configuration screen
                ServerConfigScreen(
                    currentIp = configManager.getServerIp(),
                    currentPort = configManager.getServerPort(),
                    isFirstTime = configManager.isFirstLaunch(),
                    onSave = { ip, port ->
                        val success = configManager.saveServerConfig(ip, port)
                        if (success) {
                            serverUrl = configManager.getServerUrl()
                            showServerConfig = false
                        }
                    },
                    onCancel = if (!configManager.isFirstLaunch()) {
                        { showServerConfig = false }
                    } else null,
                    onTest = { ip, port ->
                        // Implement connection test if needed
                        val testUrl = "ws://$ip:$port"
                        // You could add a simple connection test here
                    }
                )
            }
            else -> {
                // Main app content
                PaymentScreen(
                    screenState = screenState,
                    onAmountSelected = { amount ->
                        viewModel.recordUserInteraction()
                        viewModel.selectAmount(amount)
                    },
                    onPaymentMethodSelected = { method ->
                        viewModel.recordUserInteraction()
                        viewModel.selectPaymentMethod(method)
                    },
                    onReceiptResponse = { wantsReceipt ->
                        viewModel.recordUserInteraction()
                        viewModel.respondToReceiptQuestion(wantsReceipt)
                    },
                    onCancelPayment = {
                        viewModel.recordUserInteraction()
                        viewModel.cancelPayment()
                    }
                )
                // Screensaver
                Screensaver(
                    isVisible = isScreensaverVisible,
                    videoResId = screensaverVideoResId,
                    onTap = {
                        viewModel.dismissScreensaver()
                    }
                )

                // Press and hold detector (bottom-right corner)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    PressAndHoldDetector(
                        holdDurationMs = 5000L,
                        onHoldComplete = {
                            // Show PIN authentication instead of directly showing server config
                            showPinAuth = true
                        }
                    )
                }
            }
        }
    }
}