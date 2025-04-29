package app.sst.pinto.ui.components

import android.net.Uri
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

private const val TAG = "Screensaver"

/**
 * A screensaver component that plays a video in a loop.
 * Displays when user has been inactive for a certain period.
 */
@Composable
fun Screensaver(
    isVisible: Boolean,
    videoResId: Int, // Resource ID for the video file
    onTap: () -> Unit
) {
    Log.d(TAG, "Screensaver component called with isVisible=$isVisible")

    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    // Handle visibility changes
    LaunchedEffect(isVisible) {
        Log.d(TAG, "Screensaver visibility changed to $isVisible")
        if (isVisible) {
            Log.d(TAG, "Initializing ExoPlayer")
            // Initialize player when screensaver becomes visible
            player = ExoPlayer.Builder(context).build().apply {
                // Set up video from raw resource
                val videoUri = "android.resource://${context.packageName}/$videoResId"
                Log.d(TAG, "Setting media item: $videoUri")
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUri)))

                // Configure player
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true
                prepare()

                Log.d(TAG, "ExoPlayer prepared and set to play")
            }
        } else {
            // Release player when screensaver is hidden
            Log.d(TAG, "Releasing ExoPlayer")
            player?.release()
            player = null
        }
    }

    // Ensure player is released when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Screensaver component disposed, releasing player")
            player?.release()
            player = null
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Log.d(TAG, "Rendering screensaver video player")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable {
                    Log.d(TAG, "Screensaver tapped, invoking onTap")
                    onTap()
                },
            contentAlignment = Alignment.Center
        ) {
            // Render the video player
            player?.let { exoPlayer ->
                AndroidView(
                    factory = { ctx ->
                        Log.d(TAG, "Creating PlayerView")
                        PlayerView(ctx).apply {
                            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                            this.player = exoPlayer
                            useController = false // Hide playback controls

//                            // Set resize mode
//                            // Use standard zoom mode
//                            setResizeMode(PlayerView.RESIZE_MODE_ZOOM)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } ?: run {
                // Fallback for when player isn't initialized yet
                Log.d(TAG, "Showing fallback text (player not ready)")
                Text(
                    text = "Loading screensaver...",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}