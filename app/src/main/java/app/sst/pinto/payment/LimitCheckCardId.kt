package app.sst.pinto.payment

/**
 * Chooses the card identifier sent to the controller for its per-card daily limit check.
 */
object LimitCheckCardId {
    /** Shared test identifier; only ever used on debuggable (bench) builds. */
    const val DEV_MOCK_PAR = "V0010013021140394841643193699"

    /**
     * @param realId the PAR / token / panHash returned by the payment app, if any
     * @param debuggable whether this is a debuggable (bench) build
     * @return the real identifier when present; [DEV_MOCK_PAR] on debuggable builds; null on
     * release builds, where the payment must be rejected rather than let every card share one limit
     */
    fun resolve(realId: String?, debuggable: Boolean): String? =
        realId?.trim()?.takeIf { it.isNotEmpty() } ?: if (debuggable) DEV_MOCK_PAR else null

    /** Fewest digits a masked card number must keep (6 leading + 4 trailing) to be usable. */
    const val MIN_MASKED_PAN_DIGITS = 8

    /**
     * Stop-gap card reference for payment apps that return no PAR or token: the digits of a masked
     * card number, e.g. "541333******0036" -> "5413330036". Weaker than a real PAR, because two
     * cards in the same bank range ending in the same four digits produce the same reference.
     */
    fun fromMaskedPan(maskedPan: String?): String? =
        maskedPan?.filter { it.isDigit() }?.takeIf { it.length >= MIN_MASKED_PAN_DIGITS }
}
