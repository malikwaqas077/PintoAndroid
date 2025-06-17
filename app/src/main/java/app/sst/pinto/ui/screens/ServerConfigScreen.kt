package app.sst.pinto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    currentIp: String = "",
    currentPort: String = "5001",
    isFirstTime: Boolean = true,
    onSave: (String, String) -> Unit,
    onCancel: (() -> Unit)? = null,
    onTest: ((String, String) -> Unit)? = null
) {
    var ipAddress by remember { mutableStateOf(currentIp) }
    var port by remember { mutableStateOf(currentPort) }
    var ipError by remember { mutableStateOf<String?>(null) }
    var portError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    fun validateInputs(): Boolean {
        var isValid = true

        // Validate IP/Domain
        if (ipAddress.isBlank()) {
            ipError = "Server address is required"
            isValid = false
        } else if (!isValidAddressFormat(ipAddress)) {
            ipError = "Invalid server address format"
            isValid = false
        } else {
            ipError = null
        }

        // Validate Port
        val portInt = port.toIntOrNull()
        if (port.isBlank()) {
            portError = "Port is required"
            isValid = false
        } else if (portInt == null || portInt !in 1..65535) {
            portError = "Port must be between 1-65535"
            isValid = false
        } else {
            portError = null
        }

        return isValid
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = if (isFirstTime) "Welcome to Pinto Terminal" else "Server Configuration",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isFirstTime)
                "Please enter your server details to get started"
            else
                "Update your server connection details",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Configuration Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Server Address Field (renamed from IP Address)
                Column {
                    Text(
                        text = "Server Address",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = {
                            ipAddress = it
                            ipError = null
                        },
                        placeholder = { Text("192.168.1.100 or domain.com") },
                        isError = ipError != null,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    ipError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }

                // Port Field
                Column {
                    Text(
                        text = "Port",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            port = it
                            portError = null
                        },
                        placeholder = { Text("5001") },
                        isError = portError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    portError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                        )
                    }
                }

                // Preview URL
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "Connection URL:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (ipAddress.isNotEmpty()) "ws://$ipAddress:$port" else "ws://[ip]:[port]",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Test Connection Button (if provided)
            onTest?.let { testCallback ->
                OutlinedButton(
                    onClick = {
                        if (validateInputs()) {
                            isLoading = true
                            testCallback(ipAddress.trim(), port.trim())
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Test")
                    }
                }
            }

            // Cancel Button (if not first time)
            if (!isFirstTime && onCancel != null) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text("Cancel")
                }
            }

            // Save Button
            Button(
                onClick = {
                    if (validateInputs()) {
                        onSave(ipAddress.trim(), port.trim())
                    }
                },
                modifier = Modifier.weight(if (isFirstTime) 2f else 1f),
                enabled = !isLoading
            ) {
                Text(if (isFirstTime) "Connect" else "Save")
            }
        }

        if (isFirstTime) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "💡 Tip: Contact your administrator for the correct server details",
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}
private fun isValidAddressFormat(address: String): Boolean {
    if (address.lowercase() == "localhost") return true

    // Check if it's a valid domain name
    if (isValidDomainName(address)) return true

    // Check if it's a valid IPv4 address
    return isValidIPv4(address)
}

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
private fun isValidIpFormat(ip: String): Boolean {
    if (ip.lowercase() == "localhost") return true

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

