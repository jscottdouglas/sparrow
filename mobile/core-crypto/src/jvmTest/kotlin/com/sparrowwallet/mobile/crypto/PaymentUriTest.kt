package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * BIP21 litecoin: URI parsing for scanned payment QR codes — bare addresses,
 * full URIs with amounts/labels, uppercase QR alphanumeric payloads, and the
 * req-* rejection rule.
 */
class PaymentUriTest {
    private val bech32Addr = "ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9"
    private val p2pkhAddr = "LM2WMpR1Rp6j3Sa59cMXMs1SPzj9eXpGc1"
    private val mwebAddr = "ltcmweb1qq20e4x8u9wtjmvmxvlrt2gjmwqfr6959w73l5r738hyz0k9dq0y5tq4c34jvxsxupp09zwtc9pcgqk8lvq0z0nnwsm2vulvhncw3pkjxsldd7cu"

    @Test
    fun bareAddressPassesThrough() {
        val p = PaymentUri.parse(" $bech32Addr ")
        assertEquals(bech32Addr, p.address)
        assertNull(p.amount)
        assertNull(p.label)
        assertNull(p.message)
    }

    @Test
    fun bareBase58AddressKeepsCase() {
        assertEquals(p2pkhAddr, PaymentUri.parse(p2pkhAddr).address)
    }

    @Test
    fun uriWithAmountAndLabel() {
        val p = PaymentUri.parse("litecoin:$bech32Addr?amount=0.015&label=Coffee%20fund&message=thanks%21")
        assertEquals(bech32Addr, p.address)
        assertEquals("0.015", p.amount)
        assertEquals("Coffee fund", p.label)
        assertEquals("thanks!", p.message)
    }

    @Test
    fun uppercaseQrPayloadIsNormalized() {
        val p = PaymentUri.parse("LITECOIN:${bech32Addr.uppercase()}?AMOUNT=1")
        assertEquals(bech32Addr, p.address)
        assertEquals("1", p.amount)
    }

    @Test
    fun uppercaseBareBech32IsLowercased() {
        assertEquals(bech32Addr, PaymentUri.parse(bech32Addr.uppercase()).address)
        assertEquals(mwebAddr, PaymentUri.parse(mwebAddr.uppercase()).address)
    }

    @Test
    fun uppercaseTestnetHrpIsLowercased() {
        val testnetAddr = "tltc1qw508d6qejxtdg4y5r3zarvary0c5xw7klfsuq0"
        assertEquals(testnetAddr, PaymentUri.parse(testnetAddr.uppercase()).address)
    }

    @Test
    fun mwebUriParses() {
        val p = PaymentUri.parse("litecoin:$mwebAddr?amount=2.5")
        assertEquals(mwebAddr, p.address)
        assertEquals("2.5", p.amount)
    }

    @Test
    fun schemeCaseInsensitiveAndSlashesTolerated() {
        assertEquals(bech32Addr, PaymentUri.parse("Litecoin://$bech32Addr").address)
    }

    @Test
    fun unknownParamIgnoredButReqParamRejected() {
        val p = PaymentUri.parse("litecoin:$bech32Addr?somethingyoudontunderstand=42")
        assertEquals(bech32Addr, p.address)
        assertFailsWith<IllegalArgumentException> {
            PaymentUri.parse("litecoin:$bech32Addr?req-fancyfeature=1")
        }
    }

    @Test
    fun malformedAmountRejected() {
        assertFailsWith<IllegalArgumentException> { PaymentUri.parse("litecoin:$bech32Addr?amount=abc") }
        assertFailsWith<IllegalArgumentException> { PaymentUri.parse("litecoin:$bech32Addr?amount=1,5") }
        assertFailsWith<IllegalArgumentException> { PaymentUri.parse("litecoin:$bech32Addr?amount=0.123456789") }
    }

    @Test
    fun emptyInputRejected() {
        assertFailsWith<IllegalArgumentException> { PaymentUri.parse("litecoin:?amount=1") }
        assertFailsWith<IllegalArgumentException> { PaymentUri.parse("   ") }
    }

    @Test
    fun multiByteUtf8LabelDecodes() {
        val p = PaymentUri.parse("litecoin:$bech32Addr?label=caf%C3%A9")
        assertEquals("café", p.label)
    }
}
