package app.sst.pinto.ui.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sst.pinto.utils.TimeoutManager

/**
 * A UI component that displays a timeout warning with a countdown.
 * Shows when the user has been inactive and will timeout soon.
 */
@Composable
fun TimeoutWarning(
    timeoutManager: TimeoutManager,
    onContinue: () -> Unit,
    onTimeout: () -> Unit
) {
    val TAG = "TimeoutWarning"
    val isVisible by timeoutManager.isTimeoutWarningVisible
    val progress by timeoutManager.countdownProgress
    val secondsRemaining by timeoutManager.secondsRemaining

    // Calculate appropriate color based on countdown progress
    val progressColor = when {
        progress > 0.6f -> Color.Green
        progress > 0.3f -> Color.Yellow
        else -> Color.Red
    }

    // Log when component is first composed
    LaunchedEffect(Unit) {
        Log.d(TAG, "TimeoutWarning component initialized")
    }

    // Log visibility changes
    LaunchedEffect(isVisible) {
        Log.d(TAG, "TimeoutWarning visibility changed: $isVisible")
    }

    // Log significant progress updates
    LaunchedEffect(secondsRemaining) {
//        Log.d(TAG, "TimeoutWarning seconds remaining: $secondsRemaining")
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
//        Log.d(TAG, "Rendering timeout warning dialog with progress: $progress")

        // Semi-transparent overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .padding(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Warning header
                    Text(
                        text = "Session Timeout Warning",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    // Warning message
                    Text(
                        text = "Your session will timeout due to inactivity",
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Circular progress indicator
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            color = progressColor,
                            strokeWidth = 8.dp
                        )

                        // Use the direct seconds value from TimeoutManager
                        Text(
                            text = secondsRemaining.toString(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action buttons with fixed layout
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Continue button - widened and with shorter text
                        Button(
                            onClick = {
                                Log.d(TAG, "Continue button clicked")
                                timeoutManager.continueSession()
                                onContinue()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp) // More rounded corners
                        ) {
                            Text(
                                text = "Stay",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // End session button - shorter text
                        Button(
                            onClick = {
                                Log.d(TAG, "End button clicked")
                                onTimeout()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp) // More rounded corners
                        ) {
                            Text(
                                text = "End",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}