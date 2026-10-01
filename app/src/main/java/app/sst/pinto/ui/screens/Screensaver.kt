package app.sst.pinto.ui.components

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import app.sst.pinto.utils.AppLog
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
import androidx.media3.common.C
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
private const val PLAYER_ERROR_RETRY_DELAY_MS = 3_000L

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

    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    // Handle visibility changes
    LaunchedEffect(isVisible) {
        AppLog.d(TAG, "Screensaver visibility changed to $isVisible")
        if (isVisible) {
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
                    "android.resource://${context.packageName}/$videoResId"
                }

                setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))

                // The screensaver is silent. Skip the audio track so no AAC decoder is
                // created: on the PAX IM30 (Android 7.1) OMX.google.aac.decoder
                // intermittently fails to initialise, and ExoPlayer then fails the whole
                // playback, leaving a black screen.
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()

                // Configure player
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = true

                // Add listener to handle playback state changes
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_ENDED -> {
                                seekTo(0)
                                play()
                            }
                            Player.STATE_READY -> {
                            }
                            Player.STATE_BUFFERING -> {
                            }
                            Player.STATE_IDLE -> {
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        AppLog.e(TAG, "Player error: ${error.message}")
                        // Try to recover by recreating the media item, after a pause so a
                        // persistent failure doesn't spin in a tight retry loop.
                        val failedPlayer = this@apply
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (player === failedPlayer) {
                                setMediaItem(MediaItem.fromUri(Uri.parse(videoUriString)))
                                prepare()
                                play()
                            }
                        }, PLAYER_ERROR_RETRY_DELAY_MS)
                    }
                })

                // Don't call prepare() here — wait until the PlayerView
                // surface is attached (in the AndroidView update block)
                // to avoid a surface swap that corrupts the decoder.
            }
        } else {
            // Release player when screensaver is hidden
            player?.release()
            player = null
        }
    }

    // Ensure player is released when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            player = null
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable {
                    AppLog.d(TAG, "Screensaver tapped, invoking onTap")
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
                                    exoPlayer.prepare()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } ?: run {
                // Fallback for when player isn't initialized yet
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