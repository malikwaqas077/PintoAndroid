package app.sst.pinto.utils

/**
 * The log lines that matter when following a transaction: every message exchanged with the
 * controller and the Ask portal, every payment terminal request and response, and the route
 * taken as a result. One line per event, so the log file reads as a transcript.
 *
 * ">>>" is sent by this terminal, "<<<" is received by it.
 */
object WireLog {
    private const val CONTROLLER = "WS-CTRL"
    private const val PORTAL = "WS-PORTAL"
    private const val FLOW_TAG = "FLOW"

    /** Message received from the controller (payment server). */
    fun controllerIn(message: String) = AppLog.i(CONTROLLER, "<<< $message")

    /** Message sent to the controller (payment server). */
    fun controllerOut(message: String) = AppLog.i(CONTROLLER, ">>> $message")

    /** Message received from the Ask portal deviceHub. */
    fun portalIn(message: String) = AppLog.i(PORTAL, "<<< $message")

    /** Message sent to the Ask portal deviceHub. */
    fun portalOut(message: String) = AppLog.i(PORTAL, ">>> $message")

    /** Request sent to the payment terminal app (Switchio, NNSmart, CCV, ...). */
    fun payRequest(provider: String, detail: String) = AppLog.i("PAY-$provider", ">>> $detail")

    /** Response received from the payment terminal app. */
    fun payResponse(provider: String, detail: String) = AppLog.i("PAY-$provider", "<<< $detail")

    /** The route taken as a result: screen change, limit decision, reversal, timeout, cancel. */
    fun flow(detail: String) = AppLog.i(FLOW_TAG, detail)
}
