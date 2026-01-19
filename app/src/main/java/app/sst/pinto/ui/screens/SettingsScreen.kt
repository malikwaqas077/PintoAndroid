package app.sst.pinto.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sst.pinto.data.AppDatabase
import app.sst.pinto.utils.getDeviceIpAddress
import app.sst.pinto.utils.getDeviceSerialNumber
import android.content.Context

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    context: Context,
    onOpenServerConfig: () -> Unit,
    onDownloadVideo: () -> Unit,
    onClose: () -> Unit,
    onCloseApp: () -> Unit
) {
    val database = AppDatabase.getDatabase(context)
    val deviceInfo by database.deviceInfoDao().getDeviceInfo().collectAsState(initial = null)
    
    // Device information
    var deviceIpAddress by remember { mutableStateOf<String?>(null) }
    var deviceSerialNumber by remember { mutableStateOf<String?>(null) }
    
    // Load device information when screen is displayed
    LaunchedEffect(Unit) {
        deviceIpAddress = getDeviceIpAddress()
        deviceSerialNumber = getDeviceSerialNumber()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Device Info Section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Device Info",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        // Device Serial Number
                        DeviceInfoRow(
                            label = "Device Serial Number",
                            value = deviceSerialNumber ?: "Loading..."
                        )
                        
                        // Device IP Address
                        DeviceInfoRow(
                            label = "Device IP Address",
                            value = deviceIpAddress ?: "Loading..."
                        )
                        
                        // Transaction Configuration (only show if device info exists)
                        deviceInfo?.let { info ->
                            DeviceInfoRow(
                                label = "Currency",
                                value = info.currency
                            )
                            
                            DeviceInfoRow(
                                label = "Min Transaction Limit",
                                value = formatCurrency(info.currency, info.minTransactionLimit)
                            )
                            
                            DeviceInfoRow(
                                label = "Max Transaction Limit",
                                value = formatCurrency(info.currency, info.maxTransactionLimit)
                            )
                            
                            DeviceInfoRow(
                                label = "Transaction Fee",
                                value = when (info.transactionFeeType) {
                                    "FIXED" -> formatCurrency(info.currency, info.transactionFeeValue)
                                    "PERCENTAGE" -> "${info.transactionFeeValue}%"
                                    else -> "Not set"
                                }
                            )
                            
                            DeviceInfoRow(
                                label = "YASPA Enabled",
                                value = if (info.yaspaEnabled) "Payment method selection enabled" else "Direct payment (timeout screen)"
                            )
                            
                            DeviceInfoRow(
                                label = "Payment Provider",
                                value = when (info.paymentProvider.uppercase()) {
                                    "MOCK" -> "Mock (Simulated)"
                                    "INTEGRA" -> "Integra (Real)"
                                    else -> info.paymentProvider
                                }
                            )
                            
                            DeviceInfoRow(
                                label = "Require Card Receipt",
                                value = if (info.requireCardReceipt) "Enabled" else "Disabled"
                            )
                        } ?: run {
                            Text(
                                text = "No device configuration found. Connect to server to receive configuration.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Settings Options
            item {
                SettingItemCard(
                    title = "Server Configuration",
                    description = "WebSocket server configuration",
                    onClick = onOpenServerConfig
                )
            }
            
            item {
                SettingItemCard(
                    title = "Download Video",
                    description = "Download screensaver video",
                    onClick = onDownloadVideo
                )
            }
            
            item {
                SettingItemCard(
                    title = "Close App",
                    description = "Exit the application",
                    onClick = onCloseApp
                )
            }
            
            // Add bottom padding
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SettingItemCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ArrowForward,
                contentDescription = "Open $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatCurrency(currency: String, amount: Double): String {
    val symbol = when (currency.uppercase()) {
        "GBP" -> "£"
        "USD" -> "$"
        "EUR" -> "€"
        else -> currency
    }
    return "$symbol${String.format("%.2f", amount)}"
}

