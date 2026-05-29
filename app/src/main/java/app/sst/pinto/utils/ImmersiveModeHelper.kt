package app.sst.pinto.utils

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Kiosk fullscreen helper — hides system bars and installs edge overlays plus a
 * periodic re-hide loop so swipes that briefly reveal the status / navigation
 * bars get re-hidden quickly. Mirrors the PayBridge implementation so both apps
 * behave the same on U2000 without any per-device adb provisioning.
 *
 * Note: this is a "hide quickly" approach, not a hard block. Bars still appear
 * transiently on edge swipe. Hard blocking requires Device Owner + Lock Task,
 * which needs one-time `dpm set-device-owner` per device.
 */
object ImmersiveModeHelper {

    private const val TAG = "ImmersiveMode"
    private const val PERIODIC_HIDE_MS = 5_000L
    private const val RESUME_HIDE_DELAY_MS = 500L

    private const val TOP_INTERCEPTOR_HEIGHT_PX = 50
    private const val BOTTOM_INTERCEPTOR_HEIGHT_PX = 120

    private data class Session(
        var topTouchInterceptor: View? = null,
        var bottomTouchInterceptor: View? = null,
        var periodicHideRunnable: Runnable? = null,
        var setupComplete: Boolean = false,
    )

    private val sessions = mutableMapOf<Activity, Session>()

    fun setupFullscreen(activity: Activity) {
        val session = sessions.getOrPut(activity) { Session() }
        if (session.setupComplete) {
            hideSystemBars(activity)
            return
        }
        session.setupComplete = true

        val window = activity.window
        val decor = window.decorView

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val insetsController = WindowCompat.getInsetsController(window, decor)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            hide(WindowInsetsCompat.Type.navigationBars())
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        decor.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LOW_PROFILE
            )

        hideSystemBars(activity)
        setupTouchInterceptors(activity)
        installDecorEdgeBlockers(activity)
        applyGestureExclusion(decor)
        startPeriodicHiding(activity)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            decor.setOnSystemUiVisibilityChangeListener { hideSystemBars(activity) }
        }

        decor.setOnApplyWindowInsetsListener { v, insets ->
            val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, v)
            if (compat.isVisible(WindowInsetsCompat.Type.statusBars()) ||
                compat.isVisible(WindowInsetsCompat.Type.navigationBars())
            ) {
                hideSystemBars(activity)
            }
            // Hide the bottom edge blocker while the IME is visible so the
            // keyboard's bottom row of keys remains tappable. The user can't
            // swipe up from the bottom edge while a soft keyboard occupies it,
            // so we lose nothing by suspending the interceptor here.
            setBottomEdgeBlockerVisible(activity, !compat.isVisible(WindowInsetsCompat.Type.ime()))
            insets
        }
    }

    private fun setBottomEdgeBlockerVisible(activity: Activity, visible: Boolean) {
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        sessions[activity]?.bottomTouchInterceptor?.let { v ->
            if (v.visibility != targetVisibility) v.visibility = targetVisibility
        }
        (activity.window.decorView as? ViewGroup)
            ?.findViewWithTag<View>("pinto_edge_bottom")
            ?.let { v ->
                if (v.visibility != targetVisibility) v.visibility = targetVisibility
            }
    }

    fun hideSystemBars(activity: Activity) {
        try {
            val window = activity.window
            val decor = window.decorView

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars())
                    controller.hide(android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
                }
            }

            val insetsController = WindowCompat.getInsetsController(window, decor)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT

            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )

            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

            applyGestureExclusion(decor)
            (decor as? ViewGroup)?.let { bringDecorEdgeBlockersToFront(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide system bars: ${e.message}", e)
        }
    }

    fun onResume(activity: Activity) {
        hideSystemBars(activity)
        activity.window.decorView.postDelayed(
            { hideSystemBars(activity) },
            RESUME_HIDE_DELAY_MS
        )
    }

    fun onWindowFocus(activity: Activity, hasFocus: Boolean) {
        if (hasFocus) hideSystemBars(activity)
    }

    fun cleanup(activity: Activity) {
        val session = sessions.remove(activity) ?: return
        session.periodicHideRunnable?.let { activity.window.decorView.removeCallbacks(it) }
        removeTouchInterceptor(activity, session.topTouchInterceptor)
        removeTouchInterceptor(activity, session.bottomTouchInterceptor)
        session.topTouchInterceptor = null
        session.bottomTouchInterceptor = null
        (activity.window.decorView as? ViewGroup)?.let { removeDecorEdgeBlockers(it) }
    }

    private fun setupTouchInterceptors(activity: Activity) {
        val session = sessions.getOrPut(activity) { Session() }
        removeTouchInterceptor(activity, session.topTouchInterceptor)
        removeTouchInterceptor(activity, session.bottomTouchInterceptor)

        session.topTouchInterceptor = createOverlayInterceptor(activity) { hideSystemBars(activity) }
        session.bottomTouchInterceptor = createOverlayInterceptor(activity) { hideSystemBars(activity) }

        addTouchInterceptor(activity, session.topTouchInterceptor, Gravity.TOP, TOP_INTERCEPTOR_HEIGHT_PX)
        addTouchInterceptor(activity, session.bottomTouchInterceptor, Gravity.BOTTOM, BOTTOM_INTERCEPTOR_HEIGHT_PX)
    }

    private fun createOverlayInterceptor(
        activity: Activity,
        onTouch: () -> Unit,
    ): View = object : View(activity) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                onTouch()
            }
            return true
        }
    }

    private fun addTouchInterceptor(
        activity: Activity,
        view: View?,
        gravity: Int,
        heightPx: Int,
    ) {
        if (view == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            y = 0
        }
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay touch interceptor failed ($gravity), using decor fallback: ${e.message}")
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun removeTouchInterceptor(activity: Activity, view: View?) {
        if (view == null) return
        try {
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (_: Exception) {
            // Already removed or never attached
        }
    }

    private fun installDecorEdgeBlockers(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        removeDecorEdgeBlockers(decor)

        val top = FrameLayout(activity).apply {
            tag = "pinto_edge_top"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TOP_INTERCEPTOR_HEIGHT_PX,
                Gravity.TOP
            )
            isClickable = true
            setOnTouchListener { _, _ ->
                hideSystemBars(activity)
                true
            }
        }
        val bottom = FrameLayout(activity).apply {
            tag = "pinto_edge_bottom"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                BOTTOM_INTERCEPTOR_HEIGHT_PX,
                Gravity.BOTTOM
            )
            isClickable = true
            setOnTouchListener { _, _ ->
                hideSystemBars(activity)
                true
            }
        }
        decor.addView(top)
        decor.addView(bottom)
        bringDecorEdgeBlockersToFront(decor)
    }

    private fun bringDecorEdgeBlockersToFront(decor: ViewGroup) {
        decor.findViewWithTag<View>("pinto_edge_top")?.bringToFront()
        decor.findViewWithTag<View>("pinto_edge_bottom")?.bringToFront()
    }

    private fun removeDecorEdgeBlockers(decor: ViewGroup) {
        decor.findViewWithTag<View>("pinto_edge_top")?.let { decor.removeView(it) }
        decor.findViewWithTag<View>("pinto_edge_bottom")?.let { decor.removeView(it) }
    }

    private fun startPeriodicHiding(activity: Activity) {
        val session = sessions.getOrPut(activity) { Session() }
        val decor = activity.window.decorView
        session.periodicHideRunnable?.let { decor.removeCallbacks(it) }

        session.periodicHideRunnable = Runnable {
            hideSystemBars(activity)
            decor.postDelayed(session.periodicHideRunnable!!, PERIODIC_HIDE_MS)
        }
        decor.postDelayed(session.periodicHideRunnable!!, PERIODIC_HIDE_MS)
    }

    private fun applyGestureExclusion(decor: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val band = (200 * decor.resources.displayMetrics.density).toInt()
        val w = decor.width
        val h = decor.height
        if (w == 0 || h == 0) return
        ViewCompat.setSystemGestureExclusionRects(
            decor,
            listOf(Rect(0, 0, w, band), Rect(0, h - band, w, h))
        )
    }
}
