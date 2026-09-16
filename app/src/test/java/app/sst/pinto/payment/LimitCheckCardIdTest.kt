package app.sst.pinto.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitCheckCardIdTest {

    @Test
    fun realIdentifierIsUsedOnAnyBuild() {
        assertEquals("PAR123", LimitCheckCardId.resolve("PAR123", debuggable = false))
        assertEquals("PAR123", LimitCheckCardId.resolve("  PAR123 ", debuggable = true))
    }

    @Test
    fun missingIdentifierUsesMockOnlyOnDebuggableBuilds() {
        assertEquals(LimitCheckCardId.DEV_MOCK_PAR, LimitCheckCardId.resolve(null, debuggable = true))
        assertEquals(LimitCheckCardId.DEV_MOCK_PAR, LimitCheckCardId.resolve("   ", debuggable = true))
    }

    @Test
    fun maskedPanBecomesDigitsOnlyReference() {
        assertEquals("5413330036", LimitCheckCardId.fromMaskedPan("541333******0036"))
        assertEquals("5413330036", LimitCheckCardId.fromMaskedPan(" 541333 ****** 0036 "))
        assertEquals("5413330036", LimitCheckCardId.fromMaskedPan("5413330036"))
    }

    @Test
    fun unusableMaskedPanIsRejected() {
        assertNull(LimitCheckCardId.fromMaskedPan(null))
        assertNull(LimitCheckCardId.fromMaskedPan(""))
        assertNull(LimitCheckCardId.fromMaskedPan("******"))
        assertNull(LimitCheckCardId.fromMaskedPan("1234***"))
    }

    @Test
    fun missingIdentifierIsRejectedOnReleaseBuilds() {
        assertNull(LimitCheckCardId.resolve(null, debuggable = false))
        assertNull(LimitCheckCardId.resolve("", debuggable = false))
        assertNull(LimitCheckCardId.resolve("  ", debuggable = false))
    }
}
