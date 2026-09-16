package app.sst.pinto.payment

import android.app.Activity
import android.content.Intent
import app.sst.pinto.utils.AppLog
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bridges Switchio Pay's Activity-based ECR API into suspend functions.
 *
 * Switchio requires [startActivityForResult] (modern: ActivityResultLauncher).
 * ViewModels cannot own that launcher, so [MainActivity] binds it here once
 * in [onCreate] before the activity reaches STARTED.
 */
object SwitchioEcrBridge {

    private const val TAG = "SwitchioEcrBridge"

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null
    private val mutex = Mutex()

    /**
     * Must be called from [ComponentActivity.onCreate] before STARTED.
     */
    fun bind(activity: ComponentActivity) {
        if (launcher != null) {
            AppLog.d(TAG, "Switchio ECR launcher already bound")
            return
        }
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val deferred = pending
            pending = null
            if (deferred == null) {
                AppLog.w(TAG, "Received Switchio activity result with no pending waiter")
            } else {
                deferred.complete(result)
            }
        }
        AppLog.d(TAG, "Switchio ECR launcher bound")
    }

    fun unbind() {
        launcher = null
        pending?.cancel()
        pending = null
    }

    /**
     * Launch a Switchio Intent and await its activity result.
     *
     * @return the activity result, or null on timeout / missing launcher.
     */
    suspend fun startForResult(intent: Intent, timeoutMs: Long): ActivityResult? =
        mutex.withLock {
            val activeLauncher = launcher
            if (activeLauncher == null) {
                AppLog.e(TAG, "Cannot launch Switchio Intent — bridge not bound to an Activity")
                return@withLock null
            }

            val deferred = CompletableDeferred<ActivityResult>()
            pending = deferred

            try {
                withContext(Dispatchers.Main) {
                    activeLauncher.launch(intent)
                }
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: Exception) {
                AppLog.e(TAG, "Switchio activity launch failed", e)
                null
            } finally {
                if (pending === deferred) {
                    pending = null
                }
            }
        }

    fun isSuccess(result: ActivityResult?): Boolean {
        return result != null && result.resultCode == Activity.RESULT_OK
    }
}
