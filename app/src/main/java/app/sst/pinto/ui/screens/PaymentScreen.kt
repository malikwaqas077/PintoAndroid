package app.sst.pinto.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sst.pinto.R
import app.sst.pinto.data.models.PaymentScreenState
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun CollectTicketScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Please collect your ticket")

        Spacer(modifier = Modifier.height(24.dp))

        // Changed to payment_terminal.gif
        GlideImage(
            model = R.raw.payment_terminal,
            contentDescription = "Collect Ticket",
            modifier = Modifier.size(240.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Attention-grabbing pulsating arrow or text
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse"
        )

        Text(
            text = "↓ ↓ ↓",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.scale(scale)
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ThankYouScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Logo at top
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Company Logo",
            modifier = Modifier
                .padding(top = 48.dp)
                .size(160.dp)
        )

        // Thank you animation
        GlideImage(
            model = R.raw.thankyou,
            contentDescription = "Thank You Animation",
            modifier = Modifier.size(240.dp)
        )

        // Thank you text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            Text(
                text = "Thank you",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Have a nice day",
                fontSize = 24.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DeviceErrorScreen(errorMessage: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Warning icon
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(Color.Transparent, RoundedCornerShape(percent = 50))
                .border(4.dp, Color.Red, RoundedCornerShape(percent = 50)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "!",
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Device out of use",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = errorMessage,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Please contact a member of staff for assistance.",
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ReceiptQuestionScreen(
    onResponseSelected: (Boolean) -> Unit
) {
    // Add a loading state to track when buttons should be disabled
    var isButtonsEnabled by remember { mutableStateOf(true) }

    // Function to handle button clicks and disable buttons
    fun handleResponse(wantsReceipt: Boolean) {
        if (isButtonsEnabled) {
            isButtonsEnabled = false // Disable buttons immediately
            onResponseSelected(wantsReceipt)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Do you require",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "a card receipt?",
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Card image or animated GIF
        GlideImage(
            model = R.raw.receipt,
            contentDescription = "Card Receipt",
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Yes/No buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // YES button
            Button(
                onClick = { handleResponse(true) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1A8CFF), // Blue color as shown in the image
                    disabledContainerColor = Color(0xFF1A8CFF).copy(alpha = 0.5f) // Dimmed when disabled
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = isButtonsEnabled
            ) {
                if (!isButtonsEnabled) {
                    // Show loading indicator if this button was clicked
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "YES",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // NO button
            Button(
                onClick = { handleResponse(false) },
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5C44), // Orange-red color as shown in the image
                    disabledContainerColor = Color(0xFFFF5C44).copy(alpha = 0.5f) // Dimmed when disabled
                ),
                shape = RoundedCornerShape(8.dp),
                enabled = isButtonsEnabled
            ) {
                if (!isButtonsEnabled) {
                    // Show loading indicator if this button was clicked
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "NO",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun TransactionFailedScreen(errorMessage: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "TRANSACTION FAILED")

        Spacer(modifier = Modifier.height(16.dp))

        // Failed animation
        GlideImage(
            model = R.raw.failed,
            contentDescription = "Transaction Failed",
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        errorMessage?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = it,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun LimitErrorScreen(errorMessage: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color(0xFFFFF9C4)), // Light yellow background
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_warning),
            contentDescription = "Warning",
            modifier = Modifier.size(80.dp),
            tint = Color(0xFFF57C00) // Orange warning color
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Limit Error",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Auto-navigating back in 5 seconds...
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse"
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Returning to amount selection...",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = Color.Black,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PrintingTicketScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Printing")

        Text(
            text = "Ticket",
            fontSize = 22.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Printing animation
        GlideImage(
            model = R.raw.ticket,
            contentDescription = "Printing Ticket",
            modifier = Modifier.size(240.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Pulsating text for processing status
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse"
        )

        Text(
            text = "Please wait...",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PaymentScreen(
    screenState: PaymentScreenState,
    onAmountSelected: (Int) -> Unit,
    onPaymentMethodSelected: (String) -> Unit,
    onReceiptResponse: (Boolean) -> Unit,
    onCancelPayment: () -> Unit = {}
) {
    // Track previous state for animations
    var previousState by remember { mutableStateOf<PaymentScreenState?>(null) }
    var shouldAnimate by remember { mutableStateOf(previousState != screenState) }

    LaunchedEffect(screenState) {
        if (previousState != screenState) {
            previousState = screenState
            shouldAnimate = true
        }
    }

    // Auto-navigate back from limit error screen
    if (screenState is PaymentScreenState.LimitError) {
        LaunchedEffect(Unit) {
            delay(5000) // Wait 5 seconds
            onAmountSelected(-2) // Custom code to return to amount selection
        }
    }

    AnimatedContent(
        targetState = screenState,
        transitionSpec = {
            fadeIn(animationSpec = tween(700)) with
                    fadeOut(animationSpec = tween(300))
        }
    ) { targetState ->
        // Handle different screen states
        when (targetState) {

            is PaymentScreenState.ReceiptQuestion -> ReceiptQuestionScreen(
                onResponseSelected = { wantsReceipt ->
                    if (wantsReceipt) {
                        onReceiptResponse(true)
                    } else {
                        onReceiptResponse(false)
                    }
                }
            )
            // Add this case to the when statement in the AnimatedContent block
            is PaymentScreenState.RefundProcessing -> RefundProcessingScreen(
                errorMessage = targetState.errorMessage
            )
            is PaymentScreenState.Timeout -> TimeoutScreen()
            is PaymentScreenState.Loading -> LoadingScreen()
            is PaymentScreenState.ConnectionError -> ConnectionErrorScreen()
            is PaymentScreenState.AmountSelect -> AmountSelectionScreen(
                amounts = targetState.amounts,
                currency = targetState.currency,
                showOtherOption = targetState.showOtherOption,
                onAmountSelected = onAmountSelected
            )
            is PaymentScreenState.KeypadEntry -> KeypadEntryScreen(
                currency = targetState.currency,
                onAmountEntered = onAmountSelected
            )
            is PaymentScreenState.PaymentMethodSelect -> PaymentMethodScreen(
                methods = targetState.methods,
                amount = targetState.amount,
                currency = targetState.currency,
                onMethodSelected = onPaymentMethodSelected,
                onCancel = onCancelPayment
            )
            is PaymentScreenState.QrCodeDisplay -> QrCodeScreen(
                onCancel = onCancelPayment
            )
            is PaymentScreenState.Processing -> ProcessingScreen()
            is PaymentScreenState.TransactionSuccess -> TransactionSuccessScreen()
            is PaymentScreenState.TransactionFailed -> TransactionFailedScreen(
                errorMessage = targetState.errorMessage
            )
            is PaymentScreenState.LimitError -> LimitErrorScreen(
                errorMessage = targetState.errorMessage
            )
            is PaymentScreenState.PrintingTicket -> PrintingTicketScreen()
            is PaymentScreenState.CollectTicket -> CollectTicketScreen()
            is PaymentScreenState.ThankYou -> ThankYouScreen()
            is PaymentScreenState.DeviceError -> DeviceErrorScreen(
                errorMessage = targetState.errorMessage
            )


        }
    }
}

@Composable
fun AnimatedHeader(text: String) {
    var animationPlayed by remember { mutableStateOf(false) }
    val alphaAnim = animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = tween(durationMillis = 1000)
    )
    val slideAnim = animateFloatAsState(
        targetValue = if (animationPlayed) 0f else -50f,
        animationSpec = tween(durationMillis = 800, easing = EaseOutQuad)
    )

    LaunchedEffect(key1 = true) {
        delay(100)
        animationPlayed = true
    }

    Text(
        text = text,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .alpha(alphaAnim.value)
            .offset(y = slideAnim.value.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            strokeWidth = 8.dp
        )
    }
}


@Composable
fun ConnectionErrorScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Error icon
        Icon(
            painter = painterResource(id = R.drawable.ic_warning),
            contentDescription = "Connection Error",
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Connection Error",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Unable to connect to the payment server. Please check your connection.",
            textAlign = TextAlign.Center,
            fontSize = 18.sp
        )
    }
}
@Composable
fun TimeoutScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black), // Optional black background
        contentAlignment = Alignment.Center
    ) {
        // Display the timeout image at full screen size
        Image(
            painter = painterResource(id = R.drawable.timeout),
            contentDescription = "Session Timeout",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds // This will make the image fill the available space
        )
    }
}
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun AmountSelectionScreen(
    amounts: List<Int>,
    currency: String,
    showOtherOption: Boolean,
    onAmountSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center, // Changed to center alignment
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Please Select an Amount")

        // Amounts grid - made larger to fill more space
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f) // Use more of the height
        ) {
            items(amounts) { amount ->
                AmountButton(
                    amount = amount,
                    currency = currency,
                    onClick = { onAmountSelected(amount) }
                )
            }

            if (showOtherOption) {
                item {
                    AmountButton(
                        amount = null,
                        currency = currency,
                        onClick = { onAmountSelected(-1) } // -1 indicates "Other"
                    )
                }
            }
        }
    }
}

@Composable
fun AmountButton(
    amount: Int?,
    currency: String,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp) // Increased height
            .shadow(
                elevation = animateDpAsState(
                    targetValue = if (isPressed) 2.dp else 8.dp,
                    animationSpec = tween(150)
                ).value,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                isPressed = true
                onClick()
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (amount != null) "$currency$amount" else "Other",
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp, // Increased font size
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RefundProcessingScreen(errorMessage: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Processing Refund")

        Spacer(modifier = Modifier.height(32.dp))

        // Processing animation - reuse the pending animation
        GlideImage(
            model = R.raw.pending,
            contentDescription = "Processing Refund",
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Message about the refund
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Payment successful but there was an issue with the ticket printer.",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Processing refund automatically.",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = it,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Pulsating text for processing status
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse"
        )

        Text(
            text = "Please wait while we process your refund...",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(alpha)
        )
    }
}
@Composable
fun PinAuthScreen(
    onPinCorrect: () -> Unit,
    onCancel: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val correctPin = "01482"

    fun addDigit(digit: String) {
        if (enteredPin.length < 5) {
            enteredPin += digit
            showError = false

            // Auto-check when 5 digits are entered
            if (enteredPin.length == 5) {
                if (enteredPin == correctPin) {
                    onPinCorrect()
                } else {
                    showError = true
                    // Clear PIN after short delay
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(1000)
                        enteredPin = ""
                        showError = false
                    }
                }
            }
        }
    }

    fun clearPin() {
        enteredPin = ""
        showError = false
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
            text = "Admin Access",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Enter PIN to access settings",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // PIN Display
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(5) { index ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = if (showError)
                                    MaterialTheme.colorScheme.errorContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                shape = CircleShape
                            )
                            .border(
                                width = 2.dp,
                                color = if (showError)
                                    MaterialTheme.colorScheme.error
                                else if (index < enteredPin.length)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (index < enteredPin.length) {
                            Text(
                                text = "●",
                                fontSize = 24.sp,
                                color = if (showError)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (showError) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Incorrect PIN",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // PIN Keypad
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Row 1: 1, 2, 3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 1..3) {
                        PinKeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = { addDigit(number.toString()) }
                        )
                    }
                }

                // Row 2: 4, 5, 6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 4..6) {
                        PinKeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = { addDigit(number.toString()) }
                        )
                    }
                }

                // Row 3: 7, 8, 9
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 7..9) {
                        PinKeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = { addDigit(number.toString()) }
                        )
                    }
                }

                // Row 4: Clear, 0, Cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Clear button
                    PinKeypadButton(
                        text = "Clear",
                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { clearPin() }
                    )

                    // 0 button
                    PinKeypadButton(
                        text = "0",
                        modifier = Modifier.weight(1f),
                        onClick = { addDigit("0") }
                    )

                    // Cancel button
                    PinKeypadButton(
                        text = "Cancel",
                        backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                        onClick = { onCancel() }
                    )
                }
            }
        }
    }
}

@Composable
fun PinKeypadButton(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .shadow(
                elevation = animateDpAsState(
                    targetValue = if (isPressed) 2.dp else 6.dp,
                    animationSpec = tween(150)
                ).value,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                isPressed = true
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = if (text.length > 1) 14.sp else 20.sp,
            color = textColor
        )
    }

    // Reset the pressed state
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}
@Composable
fun KeypadEntryScreen(
    currency: String,
    onAmountEntered: (Int) -> Unit
) {
    val enteredAmount = remember { mutableStateOf("0") }

    Box(modifier = Modifier.fillMaxSize()) {
        // Cancel button in top-left corner
        IconButton(
            onClick = {
                // Pass -2 to go back to amount selection screen
                onAmountEntered(-2)
            },
            modifier = Modifier
                .padding(16.dp)
                .size(48.dp)
                .align(Alignment.TopEnd)
                .background(
                    color = Color.Red.copy(alpha = 0.1f),
                    shape = CircleShape
                )
        ) {
            Text(
                text = "✕",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedHeader(text = "Enter Amount")

            // Amount display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "$currency${enteredAmount.value}",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Fixed Grid layout instead of LazyVerticalGrid to avoid scrolling
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Row 1: 1, 2, 3
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 1..3) {
                        KeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                enteredAmount.value = if (enteredAmount.value == "0") {
                                    number.toString()
                                } else {
                                    enteredAmount.value + number.toString()
                                }
                            }
                        )
                    }
                }

                // Row 2: 4, 5, 6
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 4..6) {
                        KeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                enteredAmount.value = if (enteredAmount.value == "0") {
                                    number.toString()
                                } else {
                                    enteredAmount.value + number.toString()
                                }
                            }
                        )
                    }
                }

                // Row 3: 7, 8, 9
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (number in 7..9) {
                        KeypadButton(
                            text = number.toString(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                enteredAmount.value = if (enteredAmount.value == "0") {
                                    number.toString()
                                } else {
                                    enteredAmount.value + number.toString()
                                }
                            }
                        )
                    }
                }

                // Row 4: Clear, 0, OK
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Clear button
                    KeypadButton(
                        text = "X",
                        backgroundColor = Color.Red.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            enteredAmount.value = "0"
                        }
                    )

                    // 0 button
                    KeypadButton(
                        text = "0",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (enteredAmount.value != "0") {
                                enteredAmount.value += "0"
                            }
                        }
                    )

                    // OK button
                    KeypadButton(
                        text = "OK",
                        backgroundColor = Color.Green.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            enteredAmount.value.toIntOrNull()?.let { amount ->
                                if (amount > 0) {
                                    onAmountEntered(amount)
                                }
                            }
                        }
                    )
                }

                // Add a spacer before the Cancel button
                Spacer(modifier = Modifier.height(20.dp))

                // Add a Cancel button at the bottom of the keypad
                Button(
                    onClick = {
                        // Pass -2 to go back to amount selection screen
                        onAmountEntered(-2)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        text = "CANCEL",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
@Composable
fun KeypadButton(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    onClick: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .shadow(
                elevation = animateDpAsState(
                    targetValue = if (isPressed) 1.dp else 4.dp,
                    animationSpec = tween(150)
                ).value,
                shape = RoundedCornerShape(12.dp)
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                isPressed = true
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    // Reset the pressed state
    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun PaymentMethodScreen(
    methods: List<String>,
    amount: Int,
    currency: String,
    onMethodSelected: (String) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center, // Changed to center alignment
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "How do you want to pay?")

        // Amount display
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Text(
                text = "Amount: $currency$amount",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Payment method buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 16.dp)
        ) {
            methods.forEach { method ->
                PaymentMethodButton(
                    method = method,
                    onClick = { onMethodSelected(method) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Cancel button
        Button(
            onClick = { onCancel() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red.copy(alpha = 0.7f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = "CANCEL",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun PaymentMethodButton(
    method: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp), // Increased height even more
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Text(
            text = when (method) {
                "DEBIT_CARD" -> "DEBIT CARD"
                "PAY_BY_BANK" -> "PAY BY BANK"
                else -> method
            },
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp // Increased font size
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun QrCodeScreen(
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Scan QR Code to Pay")

        Spacer(modifier = Modifier.height(24.dp))

        // Placeholder for QR code - in real app, generate dynamically
        Box(
            modifier = Modifier
                .size(280.dp)
                .border(width = 2.dp, color = Color.Black, shape = RoundedCornerShape(12.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            // QR placeholder - replace with actual QR code
            Image(
                painter = painterResource(id = R.drawable.qr_placeholder),
                contentDescription = "QR Code",
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Open your banking app and scan this QR code",
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.weight(1f))

        // Cancel button
        Button(
            onClick = { onCancel() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red.copy(alpha = 0.7f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = "CANCEL",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ProcessingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "Processing Payment")

        Spacer(modifier = Modifier.height(32.dp))

        // Processing animation
        GlideImage(
            model = R.raw.pending,
            contentDescription = "Processing Payment",
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Pulsating text for processing status
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ), label = "pulse"
        )

        Text(
            text = "Please wait while we process your payment...",
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun TransactionSuccessScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedHeader(text = "TRANSACTION SUCCESSFUL")

        Spacer(modifier = Modifier.height(24.dp))

        // Success animation
        GlideImage(
            model = R.raw.accepted,
            contentDescription = "Transaction Success",
            modifier = Modifier.size(200.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Please remove your card",
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}