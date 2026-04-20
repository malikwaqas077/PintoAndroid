package app.sst.pinto.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.OptIn
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.sst.pinto.R
import app.sst.pinto.utils.FileLogger
import app.sst.pinto.utils.VideoDownloadManager
import java.io.File

private const val TAG = "Screensaver"

/**
 * A screensaver component that plays a video in a loop.
 * Displays when user has been inactive for a certain period.
 */
@OptIn(UnstableApi::class)
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
            // Release any existing player first
            player?.release()

            // Initialize new player when screensaver becomes visible
            player = ExoPlayer.Builder(context).build().apply {
                // Decide whether to use cached video or default resource
                val prefs = context.getSharedPreferences(
                    VideoDownloadManager.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val cachedPath = prefs.getString(
                    VideoDownloadManager.KEY_CURRENT_VIDEO_PATH,
                    null
                )

                val videoUriString = if (!cachedPath.isNullOrBlank()) {
                    FileLogger.getInstance(context).i(
                        TAG,
                        "Using cached screensaver video: $cachedPath"
                    )
                    Uri.fromFile(File(cachedPath)).toString()
                } else {
                    Log.d(TAG, "Using default screensaver video from resources")
                    "android.resource://${context.packageName}/$videoResId"
                }

                Log.d(TAG, "Setting media item: $videoUriString")
                setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))

                // Configure player
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true

                // Add listener to handle playback state changes
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        Log.d(TAG, "Playback state changed: $playbackState")
                        when (playbackState) {
                            Player.STATE_ENDED -> {
                                Log.d(TAG, "Video ended, seeking to start")
                                seekTo(0)
                                play()
                            }
                            Player.STATE_READY -> {
                                Log.d(TAG, "Video ready to play")
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Video buffering")
                            }
                            Player.STATE_IDLE -> {
                                Log.d(TAG, "Video idle")
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player error: ${error.message}")
                        // Try to recover by recreating the media item
                        setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))
                        prepare()
                        play()
                    }
                })

                // Don't call prepare() here — wait until the PlayerView
                // surface is attached (in the AndroidView update block)
                // to avoid a surface swap that corrupts the decoder.
                Log.d(TAG, "ExoPlayer created, waiting for surface before prepare")
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
                // SurfaceView (PlayerView default) does not draw correctly when a parent
                // applies alpha (e.g. AnimatedVisibility fadeIn), which often shows a black
                // rectangle until something forces a redraw (like a tap). TextureView
                // composites like a normal View and avoids that failure mode.
                key(exoPlayer) {
                    AndroidView(
                        factory = { ctx ->
                            Log.d(TAG, "Inflating texture PlayerView for Compose")
                            val inflater = LayoutInflater.from(ctx)
                            (inflater.inflate(
                                R.layout.screensaver_player_view,
                                null,
                                false
                            ) as PlayerView).apply {
                                layoutParams =
                                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                                setKeepContentOnPlayerReset(true)
                            }
                        },
                        update = { playerView ->
                            if (playerView.player != exoPlayer) {
                                playerView.player = exoPlayer
                                // Prepare now that the surface is attached
                                if (exoPlayer.playbackState == Player.STATE_IDLE) {
                                    Log.d(TAG, "Surface attached, preparing ExoPlayer")
                                    exoPlayer.prepare()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
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