package app.sst.pinto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sst.pinto.ui.screens.PaymentScreen
import app.sst.pinto.ui.theme.PintoTheme
import app.sst.pinto.viewmodels.PaymentViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // You can set this in your app configuration or make it user-configurable
        val serverUrl = "ws://192.168.2.115:8080"

        setContent {
            PintoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: PaymentViewModel = viewModel()
                    val screenState by viewModel.screenState.collectAsState()

                    // Connect to backend when the app starts
                    viewModel.connectToBackend(serverUrl)

                    // Main payment screen that handles all states
                    PaymentScreen(
                        screenState = screenState,
                        onAmountSelected = { amount -> viewModel.selectAmount(amount) },
                        onPaymentMethodSelected = { method -> viewModel.selectPaymentMethod(method) },
                        onCancelPayment = { viewModel.cancelPayment() }
                    )
                }
            }
        }
    }
}