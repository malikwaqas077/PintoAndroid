package app.sst.pinto.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DccDetailsTest {

    // Values taken from Planet's "DCC in Unattended Solutions" sample response.
    private val dccResponse = mapOf(
        "Result" to "A",
        "Amount" to "10.0",
        "AmountUsed" to "1.39",
        "Currency" to "DKK",
        "CurrencyUsed" to "EUR",
        "DCCFlag" to "Y",
        "LocalAmount" to "10.00",
        "BinAmount" to "1.39",
        "LocalCurrency" to "",
        "BinCurrency" to "EUR",
        "DCCMarkup" to "3",
        "DCCSponsor" to "MarkUp ECB",
        "BinRate" to "0.1388109",
        "PrintData2" to "CARDHOLDER RECEIPT"
    )

    @Test
    fun `parses DCC fields and falls back to Currency when LocalCurrency is empty`() {
        val dcc = DccDetails.fromOptions(dccResponse)!!
        assertEquals("10.00", dcc.localAmount)
        assertEquals("DKK", dcc.localCurrency)
        assertEquals("1.39", dcc.dccAmount)
        assertEquals("EUR", dcc.dccCurrency)
        assertEquals("0.1388109", dcc.exchangeRate)
        assertEquals("3", dcc.markupPercent)
        assertEquals("MarkUp ECB", dcc.sponsor)
    }

    @Test
    fun `returns null when DCC was not used`() {
        assertNull(DccDetails.fromOptions(dccResponse + ("DCCFlag" to "N")))
        assertNull(DccDetails.fromOptions(dccResponse - "DCCFlag"))
    }

    @Test
    fun `sale result exposes dcc and receipts from raw options`() {
        val result = PlanetPaymentResult(success = true, rawOptions = dccResponse)
        assertEquals("EUR", result.dcc?.dccCurrency)
        assertEquals("CARDHOLDER RECEIPT", result.cardholderReceipt)
        assertNull(result.merchantReceipt)
    }
}
