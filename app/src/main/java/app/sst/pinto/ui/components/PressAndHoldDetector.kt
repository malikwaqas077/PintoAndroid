package app.sst.pinto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.launch

@Composable
fun PressAndHoldDetector(
    holdDurationMs: Long = 5000L, // 5 seconds
    onHoldComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val TAG = "PressAndHoldDetector"
    var isPressed by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableStateOf(0f) }
    var progressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Reset function
    fun resetState() {
        Log.d(TAG, "Resetting press and hold state")
        isPressed = false
        holdProgress = 0f
        progressJob?.cancel()
        progressJob = null
    }

    Box(
        modifier = modifier
            .size(60.dp) // Slightly larger for easier pressing
            .alpha(0f) // More visible when pressed
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        Log.d(TAG, "Press detected, starting hold timer")
                        isPressed = true
                        holdProgress = 0f

                        // Start progress animation
                        progressJob = coroutineScope.launch {
                            try {
                                val stepDuration = 50L
                                val totalSteps = (holdDurationMs / stepDuration).toInt()

                                Log.d(TAG, "Starting progress animation: $totalSteps steps")

                                for (step in 1..totalSteps) {
                                    if (!isPressed) {
                                        Log.d(TAG, "Press released early at step $step")
                                        break
                                    }

                                    delay(stepDuration)
                                    holdProgress = step.toFloat() / totalSteps

                                    Log.d(TAG, "Progress: ${(holdProgress * 100).toInt()}%")

                                    if (step >= totalSteps) {
                                        Log.d(TAG, "Hold complete! Triggering callback")
                                        onHoldComplete()
                                        resetState()
                                        return@launch
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in progress animation", e)
                            }
                        }

                        // Wait for pointer release
                        val released = tryAwaitRelease()
                        Log.d(TAG, "Pointer released: $released")

                        // Reset state when released early
                        if (isPressed) {
                            resetState()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Background circle with better visibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = when {
                        isPressed && holdProgress > 0.8f -> Color.Green.copy(alpha = 0.4f)
                        isPressed -> Color.Blue.copy(alpha = 0.3f)
                        else -> Color.Gray.copy(alpha = 0.2f)
                    },
                    shape = CircleShape
                )
        )

        // Progress indicator - more prominent
        if (isPressed && holdProgress > 0f) {
            CircularProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier.fillMaxSize(0.9f),
                strokeWidth = 4.dp,
                color = if (holdProgress > 0.8f) Color.Green else Color.Blue
            )

            // Show percentage text for debugging
            Text(
                text = "${(holdProgress * 100).toInt()}%",
                fontSize = 10.sp,
                color = Color.Blue
            )
        } else {
            // Settings icon - more visible
            Text(
                text = "⚙",
                fontSize = 20.sp,
                color = if (isPressed) Color.Blue else Color.Gray.copy(alpha = 0.7f)
            )
        }
    }
}