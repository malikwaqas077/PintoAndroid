package app.sst.pinto

import android.os.Bundle
import android.os.Build
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.sst.pinto.data.AppDatabase
import app.sst.pinto.network.SocketManager
import app.sst.pinto.payment.NNSmartPaymentManager
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.ImmersiveModeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var timeoutManager: TimeoutManager
    private lateinit var configManager: ConfigManager
    private lateinit var fileLogger: FileLogger

    private var isReady = false

    private fun isLikelyNewlandDevice(): Boolean {
        val fingerprint = buildString {
            append(Build.MANUFACTURER ?: "")
            append(' ')
            append(Build.BRAND ?: "")
            append(' ')
            append(Build.MODEL ?: "")
            append(' ')
            append(Build.DEVICE ?: "")
            append(' ')
            append(Build.PRODUCT ?: "")
        }.lowercase()

        return fingerprint.contains("u2000") ||
            fingerprint.contains("newland") ||
            fingerprint.contains("bengal")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !isReady }
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // Initialize managers first
        timeoutManager = TimeoutManager.getInstance()
        configManager = ConfigManager.getInstance(applicationContext)
        fileLogger = FileLogger.getInstance(applicationContext)

        // Route critical subsystem logs to retained local files.
        SocketManager.getInstance().configureLogging(fileLogger)
        NNSmartPaymentManager.configureLogging(fileLogger)
        app.sst.pinto.payment.PlanetPaymentManager.configureLogging(fileLogger)

        // Persist uncaught crashes before process exits.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            fileLogger.e(
                TAG,
                "Uncaught exception on thread=${thread.name}: ${throwable.message}",
                throwable
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
        
        // Initialize the Planet/Integra SDK ONLY when the configured payment
        // provider is Integra. Loading the Integra native .so on a non-Planet
        // terminal (e.g. Newland U2000 running NNSmart) has been observed to
        // crash with SIGSEGV inside the JNI bridge, so we skip it entirely
        // on other providers.
        CoroutineScope(Dispatchers.IO).launch {
            val provider = try {
                AppDatabase.getDatabase(applicationContext)
                    .deviceInfoDao()
                    .getDeviceInfo()
                    .first()
                    ?.paymentProvider
                    ?.lowercase()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read payment provider from DB, defaulting to nnsmart", e)
                null
            } ?: "nnsmart"

            if (provider == "integra" && !isLikelyNewlandDevice()) {
                Log.d(TAG, "Payment provider = integra, initializing Planet SDK")
                app.sst.pinto.payment.PlanetPaymentManager.initializeLoggerAsync { isAvailable ->
                    if (isAvailable) {
                        Log.d(TAG, "Planet SDK is available on this device")
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
                            }
                        }.start()
                    } else {
                        Log.w(TAG, "Planet SDK is not available on this device - payment features will be disabled")
                    }
                }
            } else if (provider == "integra") {
                Log.w(
                    TAG,
                    "Payment provider is integra but device looks like Newland/U2000; skipping Planet SDK preload to avoid native crash"
                )
            } else {
                Log.d(TAG, "Payment provider = $provider, skipping Planet SDK initialization")
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
                    LaunchedEffect(Unit) {
                        isReady = true
                    }
                }
            }
        }
    }

    private fun setupFullscreen() {
        ImmersiveModeHelper.setupFullscreen(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ImmersiveModeHelper.onWindowFocus(this, hasFocus)
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        timeoutManager.recordUserInteraction()
        ImmersiveModeHelper.onResume(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk mode: swallow the back button so tapping it on the transient
        // system nav bar can't exit the activity.
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Record any user interaction
        timeoutManager.recordUserInteraction()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            timeoutManager.recordUserInteraction()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        ImmersiveModeHelper.cleanup(this)
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
                        {
                            showServerConfig = false
                            showSettings = true
                        }
                    } else null
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