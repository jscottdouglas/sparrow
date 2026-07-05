package com.sparrowwallet.mobile.electrum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Decimal-LTC → litoshi parsing used for verbose-transaction history deltas. */
class LtcAmountTest {
    @Test
    fun parsesVerboseTxValues() {
        assertEquals(5_000_000L, ElectrumProtocol.ltcToLitoshis("0.05"))
        assertEquals(123_456_789L, ElectrumProtocol.ltcToLitoshis("1.23456789"))
        assertEquals(100_000_000L, ElectrumProtocol.ltcToLitoshis("1"))
        assertEquals(1L, ElectrumProtocol.ltcToLitoshis("0.00000001"))
        assertEquals(0L, ElectrumProtocol.ltcToLitoshis("0.00000000"))
        assertEquals(8_400_000_000_000_000L, ElectrumProtocol.ltcToLitoshis("84000000"))
        assertEquals(-5_000_000L, ElectrumProtocol.ltcToLitoshis("-0.05"))
        assertEquals(50_000_000L, ElectrumProtocol.ltcToLitoshis(".5"))
    }

    @Test
    fun rejectsMalformedValues() {
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1e-05") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("0.123456789") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("") }
        assertFailsWith<IllegalArgumentException> { ElectrumProtocol.ltcToLitoshis("1.2.3") }
    }
}
