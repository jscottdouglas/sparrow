package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The external-signer path must be indistinguishable from direct signing: an unsigned
 * PSBT signed via [Psbt.sign] and finalized has to produce the byte-identical network
 * transaction that [TxBuilder.build] signs directly (RFC6979 makes both deterministic),
 * and the txid must be the same before and after signing.
 */
class PsbtTest {
    private val hardened = 0x80000000L
    private val master = HDKey.fromSeed(Bip39.seed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
    private val account = master.derivePath("m/84'/2'/0'")
    private val key0 = account.deriveChild(0).deriveChild(0)
    private val key1 = account.deriveChild(0).deriveChild(1)
    private val changeKey = account.deriveChild(1).deriveChild(0)
    private val destKey = account.deriveChild(0).deriveChild(9)

    private val inputs = listOf(
        SpendableInput("aa".repeat(32), 0, 50_000_000, key0),
        SpendableInput("bb".repeat(32), 1, 30_000_000, key1)
    )
    private val destScript = Scripts.p2wpkhOutput(destKey.pubKey)
    private val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)

    private fun accountPath(chain: Long, index: Long) =
        listOf(84L + hardened, 2L + hardened, 0L + hardened, chain, index)

    private fun createPsbt(unsigned: UnsignedBuild): ByteArray = Psbt.create(
        unsigned,
        masterFingerprint = master.fingerprint,
        pathFor = { input ->
            when(input.key) {
                key0 -> accountPath(0, 0)
                key1 -> accountPath(0, 1)
                else -> error("unexpected input key")
            }
        },
        changeIndex = 1,
        changePubKey = changeKey.pubKey,
        changePath = accountPath(1, 0)
    )

    @Test
    fun psbtPathMatchesDirectSigningExactly() {
        val direct = TxBuilder.build(inputs, destScript, 60_000_000, 2, changeScript)
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val psbt = createPsbt(unsigned)

        val parsed = Psbt.parse(psbt)
        assertContentEquals(unsigned.tx.serialize(false), parsed.unsignedTxBytes)
        assertEquals(2, parsed.inputs.size)
        assertEquals(50_000_000, parsed.inputs[0].witnessUtxoValue)

        // "external signer": a fresh parse signs with keys resolved only by pubkey
        val keysByPub = mapOf(key0.pubKey.toHex() to key0, key1.pubKey.toHex() to key1)
        val signed = Psbt.sign(parsed, keyFor = { keysByPub[it] })
        val finalTx = Psbt.finalize(Psbt.parse(signed))

        assertContentEquals(direct.tx.serialize(), finalTx.serialize())
        assertEquals(direct.tx.txid(), finalTx.txid())
        // P2WPKH txid excludes witnesses: the unsigned preview txid already matches
        assertEquals(unsigned.tx.txid(), finalTx.txid())
    }

    @Test
    fun finalizeRejectsUnsignedPsbt() {
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val parsed = Psbt.parse(createPsbt(unsigned))
        assertFailsWith<IllegalArgumentException> { Psbt.finalize(parsed) }
    }

    @Test
    fun base64RoundTrip() {
        val unsigned = TxBuilder.buildUnsigned(inputs, destScript, 60_000_000, 2, changeScript)
        val psbt = createPsbt(unsigned)
        assertContentEquals(psbt, Psbt.fromBase64(Psbt.toBase64(psbt)))
        assertFailsWith<IllegalArgumentException> { Psbt.fromBase64("not base64 !!!") }
    }

    @Test
    fun parseRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { Psbt.parse(ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { Psbt.parse("70736274aa".hexToBytes()) }
    }
}
