package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coin selection, fee, and change math for the send engine. Signing correctness
 * itself is covered byte-for-byte by TxParityTest; here we assert the built
 * transaction's structure and value conservation.
 */
class TxBuilderTest {
    private val account = HDKey.fromSeed(
        Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
    ).derivePath("m/84'/2'/0'")

    private fun fakeTxid(fill: Int): String = fill.toString(16).padStart(2, '0').repeat(32)

    private val candidates = listOf(
        SpendableInput(fakeTxid(1), 0, 50_000, account.deriveChild(0).deriveChild(0)),
        SpendableInput(fakeTxid(2), 1, 30_000, account.deriveChild(0).deriveChild(1)),
        SpendableInput(fakeTxid(3), 0, 20_000, account.deriveChild(1).deriveChild(0))
    )
    private val destScript = Scripts.p2wpkhOutput(account.deriveChild(0).deriveChild(5).pubKey)
    private val changeScript = Scripts.p2wpkhOutput(account.deriveChild(1).deriveChild(1).pubKey)

    private fun assertConservation(built: BuiltTx, selectedValue: Long) {
        assertEquals(selectedValue, built.tx.outputs.sumOf { it.value } + built.fee, "inputs = outputs + fee")
    }

    @Test
    fun selectsLargestFirstAndReturnsChange() {
        val built = TxBuilder.build(candidates, destScript, 60_000, 2, changeScript)
        assertEquals(2, built.tx.inputs.size) // 50k + 30k cover it, 20k untouched
        assertEquals(2, built.tx.outputs.size)
        assertEquals(60_000L, built.tx.outputs[0].value)
        assertContentEquals(destScript, built.tx.outputs[0].script)
        assertContentEquals(changeScript, built.tx.outputs[1].script)
        assertEquals(TxBuilder.estimateVsize(2, listOf(22, 22)) * 2, built.fee)
        assertEquals(built.change, built.tx.outputs[1].value)
        assertConservation(built, 80_000)
    }

    @Test
    fun everyInputCarriesAWitness() {
        val built = TxBuilder.build(candidates, destScript, 60_000, 2, changeScript)
        for(input in built.tx.inputs) {
            assertEquals(2, input.witness.size)
            assertEquals(33, input.witness[1].size) // compressed pubkey
            assertTrue(input.witness[0].size in 68..73) // DER sig + sighash byte
        }
        assertEquals(64, built.tx.txid().length)
        // low-R signing keeps actual size at or under the 72-byte-sig estimate
        assertTrue(built.vsize <= TxBuilder.estimateVsize(2, listOf(22, 22)))
    }

    @Test
    fun sendMaxSweepsEverythingWithSingleOutput() {
        val built = TxBuilder.build(candidates, destScript, 0, 1, changeScript, sendMax = true)
        assertEquals(3, built.tx.inputs.size)
        assertEquals(1, built.tx.outputs.size)
        assertEquals(TxBuilder.estimateVsize(3, listOf(22)), built.fee)
        assertEquals(100_000L - built.fee, built.amount)
        assertEquals(0L, built.change)
        assertConservation(built, 100_000)
    }

    @Test
    fun dustChangeIsFoldedIntoFee() {
        // 50k+30k selected; change = 80_000 - 79_500 - 209 = 291 < 546 dust
        val built = TxBuilder.build(candidates, destScript, 79_500, 1, changeScript)
        assertEquals(1, built.tx.outputs.size)
        assertEquals(0L, built.change)
        assertEquals(80_000L - 79_500L, built.fee)
        assertConservation(built, 80_000)
    }

    @Test
    fun insufficientFundsThrows() {
        val error = assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(candidates, destScript, 99_900, 2, changeScript)
        }
        assertTrue(error.message!!.contains("Insufficient"))
    }

    @Test
    fun dustAmountThrows() {
        assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(candidates, destScript, 500, 1, changeScript)
        }
    }

    @Test
    fun feeRateFloorEnforced() {
        assertFailsWith<IllegalArgumentException> {
            TxBuilder.build(candidates, destScript, 60_000, 0, changeScript)
        }
    }
}
