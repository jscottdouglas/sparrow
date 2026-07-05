package com.sparrowwallet.mobile.crypto

/** A P2WPKH utxo the wallet can spend, paired with the key that owns it. */
class SpendableInput(
    val txid: String, // display (big-endian) hex
    val vout: Long,
    val value: Long,  // litoshis
    val key: HDKey
)

class BuiltTx(val tx: Tx, val amount: Long, val fee: Long, val change: Long) {
    val vsize: Int get() {
        val base = tx.serialize(false).size
        val total = tx.serialize(true).size
        return (base * 3 + total + 3) / 4
    }
    fun hex(): String = tx.serialize().toHex()
}

/** The same selection as [BuiltTx] but left unsigned — for the PSBT / external-signer flow. */
class UnsignedBuild(
    val tx: Tx,
    val inputs: List<SpendableInput>,
    val amount: Long,
    val fee: Long,
    val change: Long
)

/**
 * Builds and signs a P2WPKH spend: largest-first coin selection, fee from a
 * litoshi/vbyte rate against the estimated vsize, change back to the wallet
 * (dropped into the fee when below the dust limit). Signing goes through the
 * same BIP143 + RFC6979 low-R path verified byte-for-byte against desktop
 * drongo in TxParityTest.
 */
object TxBuilder {
    const val DUST_LIMIT = 546L

    fun build(
        candidates: List<SpendableInput>,
        destScript: ByteArray,
        amount: Long,
        feeRatePerVb: Long,
        changeScript: ByteArray,
        sendMax: Boolean = false
    ): BuiltTx {
        val unsigned = buildUnsigned(candidates, destScript, amount, feeRatePerVb, changeScript, sendMax)
        return sign(unsigned)
    }

    /** Selection and transaction assembly without signing — the PSBT source. */
    fun buildUnsigned(
        candidates: List<SpendableInput>,
        destScript: ByteArray,
        amount: Long,
        feeRatePerVb: Long,
        changeScript: ByteArray,
        sendMax: Boolean = false
    ): UnsignedBuild {
        require(feeRatePerVb >= 1) { "Fee rate must be at least 1 lit/vB" }
        val sorted = candidates.filter { it.value > 0 }.sortedByDescending { it.value }
        require(sorted.isNotEmpty()) { "No spendable coins" }

        if(sendMax) {
            val total = sorted.sumOf { it.value }
            val fee = estimateVsize(sorted.size, listOf(destScript.size)) * feeRatePerVb
            val sendValue = total - fee
            require(sendValue >= DUST_LIMIT) { "Balance is too small to cover the network fee" }
            return assemble(sorted, listOf(TxOutput(sendValue, destScript)), sendValue, fee, 0)
        }

        require(amount >= DUST_LIMIT) { "Amount is below the ${DUST_LIMIT} litoshi dust limit" }
        val selected = ArrayList<SpendableInput>()
        var total = 0L
        var fee = 0L
        for(input in sorted) {
            selected.add(input)
            total += input.value
            fee = estimateVsize(selected.size, listOf(destScript.size, changeScript.size)) * feeRatePerVb
            if(total >= amount + fee) {
                break
            }
        }
        require(total >= amount + fee) { "Insufficient funds: need ${amount + fee} litoshis, have $total spendable" }

        var change = total - amount - fee
        val outputs = ArrayList<TxOutput>()
        outputs.add(TxOutput(amount, destScript))
        if(change >= DUST_LIMIT) {
            outputs.add(TxOutput(change, changeScript))
        } else {
            fee += change
            change = 0
        }
        return assemble(selected, outputs, amount, fee, change)
    }

    private fun assemble(
        inputs: List<SpendableInput>,
        outputs: List<TxOutput>,
        amount: Long,
        fee: Long,
        change: Long
    ): UnsignedBuild {
        val tx = Tx(2, inputs.map { TxInput(it.txid, it.vout) }, outputs)
        return UnsignedBuild(tx, inputs, amount, fee, change)
    }

    /** Estimated vsize for a tx spending only P2WPKH inputs (72-byte signatures assumed). */
    fun estimateVsize(inputCount: Int, outputScriptSizes: List<Int>): Long {
        val base = 4 + varIntSize(inputCount) + inputCount * 41 +
            varIntSize(outputScriptSizes.size) + outputScriptSizes.sumOf { 8 + varIntSize(it) + it } + 4
        val witness = 2 + inputCount * 108 // marker+flag, then per input: count(1) + sig(1+72) + pubkey(1+33)
        return ((base * 4 + witness + 3) / 4).toLong()
    }

    private fun varIntSize(n: Int): Int = when {
        n < 0xFD -> 1
        n <= 0xFFFF -> 3
        else -> 5
    }

    private fun sign(unsigned: UnsignedBuild): BuiltTx {
        val tx = unsigned.tx
        for((i, spend) in unsigned.inputs.withIndex()) {
            val pub = spend.key.pubKey
            val sighash = tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(pub), spend.value)
            tx.inputs[i].witness = listOf(Ecdsa.signForInput(sighash, spend.key.privKey), pub)
        }
        return BuiltTx(tx, unsigned.amount, unsigned.fee, unsigned.change)
    }
}
