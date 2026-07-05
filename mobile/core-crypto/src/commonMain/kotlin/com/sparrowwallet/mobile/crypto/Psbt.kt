package com.sparrowwallet.mobile.crypto

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * BIP174 partially-signed transactions for the external-signer flow, scoped to what
 * this wallet produces: P2WPKH inputs with witness utxos and BIP32 derivations.
 * The creator emits everything a hardware wallet or desktop Sparrow needs to sign;
 * the finalizer accepts the signed result and extracts the network transaction.
 * For P2WPKH the txid excludes witnesses, so it is identical before and after signing.
 */
object Psbt {
    private val MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()) // "psbt\xff"

    private const val GLOBAL_UNSIGNED_TX = 0x00
    private const val IN_WITNESS_UTXO = 0x01
    private const val IN_PARTIAL_SIG = 0x02
    private const val IN_BIP32_DERIVATION = 0x06
    private const val IN_FINAL_SCRIPTWITNESS = 0x08
    private const val OUT_BIP32_DERIVATION = 0x02

    class PsbtInput(
        val witnessUtxoValue: Long?,
        val witnessUtxoScript: ByteArray?,
        /** compressed pubkey hex → signature bytes (DER + sighash byte). */
        val partialSigs: Map<String, ByteArray>,
        /** pubkey hex → master fingerprint (4) + path child numbers. */
        val derivations: Map<String, Pair<ByteArray, List<Long>>>,
        val finalWitness: List<ByteArray>?
    )

    class Parsed(
        val unsignedTxBytes: ByteArray,
        val tx: Tx,
        val inputs: List<PsbtInput>
    )

    /**
     * Creates a PSBT from an unsigned build: the serialized unsigned transaction, plus
     * per input its witness utxo and BIP32 derivation ([masterFingerprint] and the full
     * path from the master key), and the change output's derivation when [changePath]
     * covers it.
     */
    fun create(
        unsigned: UnsignedBuild,
        masterFingerprint: ByteArray,
        pathFor: (SpendableInput) -> List<Long>,
        changeIndex: Int? = null,
        changePubKey: ByteArray? = null,
        changePath: List<Long>? = null
    ): ByteArray {
        require(masterFingerprint.size == 4) { "Master fingerprint must be 4 bytes" }
        val w = ByteWriter()
        w.bytes(MAGIC)

        // global map: the unsigned transaction (no witnesses, empty scriptSigs)
        writeEntry(w, byteArrayOf(GLOBAL_UNSIGNED_TX.toByte()), unsigned.tx.serialize(false))
        w.bytes(ByteArray(1)) // map separator

        for(input in unsigned.inputs) {
            val pub = input.key.pubKey
            writeEntry(w, byteArrayOf(IN_WITNESS_UTXO.toByte()),
                ByteWriter().u64le(input.value).varBytes(Scripts.p2wpkhOutput(pub)).toByteArray())
            writeEntry(w, byteArrayOf(IN_BIP32_DERIVATION.toByte()) + pub,
                derivationValue(masterFingerprint, pathFor(input)))
            w.bytes(ByteArray(1))
        }

        for((index, _) in unsigned.tx.outputs.withIndex()) {
            if(index == changeIndex && changePubKey != null && changePath != null) {
                writeEntry(w, byteArrayOf(OUT_BIP32_DERIVATION.toByte()) + changePubKey,
                    derivationValue(masterFingerprint, changePath))
            }
            w.bytes(ByteArray(1))
        }
        return w.toByteArray()
    }

    private fun derivationValue(fingerprint: ByteArray, path: List<Long>): ByteArray {
        val w = ByteWriter().bytes(fingerprint)
        for(child in path) w.u32le(child)
        return w.toByteArray()
    }

    private fun writeEntry(w: ByteWriter, key: ByteArray, value: ByteArray) {
        w.varBytes(key)
        w.varBytes(value)
    }

    fun parse(bytes: ByteArray): Parsed {
        val r = ByteReader(bytes)
        require(r.bytes(5).contentEquals(MAGIC)) { "Not a PSBT (bad magic)" }

        var unsignedTx: ByteArray? = null
        readMap(r) { key, value ->
            if(key[0].toInt() == GLOBAL_UNSIGNED_TX) unsignedTx = value
        }
        val txBytes = unsignedTx ?: throw IllegalArgumentException("PSBT has no unsigned transaction")
        val tx = parseUnsignedTx(txBytes)

        val inputs = ArrayList<PsbtInput>()
        for(i in tx.inputs.indices) {
            var utxoValue: Long? = null
            var utxoScript: ByteArray? = null
            val sigs = HashMap<String, ByteArray>()
            val derivations = HashMap<String, Pair<ByteArray, List<Long>>>()
            var finalWitness: List<ByteArray>? = null
            readMap(r) { key, value ->
                when(key[0].toInt()) {
                    IN_WITNESS_UTXO -> {
                        val vr = ByteReader(value)
                        utxoValue = vr.u64le()
                        utxoScript = vr.varBytes()
                    }
                    IN_PARTIAL_SIG -> sigs[key.copyOfRange(1, key.size).toHex()] = value
                    IN_BIP32_DERIVATION -> {
                        val vr = ByteReader(value)
                        val fp = vr.bytes(4)
                        val path = ArrayList<Long>()
                        while(vr.remaining() >= 4) path.add(vr.u32le())
                        derivations[key.copyOfRange(1, key.size).toHex()] = fp to path
                    }
                    IN_FINAL_SCRIPTWITNESS -> {
                        val vr = ByteReader(value)
                        val count = vr.varInt()
                        finalWitness = List(count.toInt()) { vr.varBytes() }
                    }
                }
            }
            inputs.add(PsbtInput(utxoValue, utxoScript, sigs, derivations, finalWitness))
        }
        // output maps (ignored content-wise, but must be consumed when present)
        for(i in tx.outputs.indices) {
            if(r.remaining() > 0) readMap(r) { _, _ -> }
        }
        return Parsed(txBytes, tx, inputs)
    }

    /**
     * Finalizes a signed P2WPKH PSBT into the broadcastable transaction: each input
     * needs either a final witness or exactly one partial signature.
     */
    fun finalize(parsed: Parsed): Tx {
        for((i, input) in parsed.inputs.withIndex()) {
            val witness = input.finalWitness ?: run {
                val (pubHex, sig) = input.partialSigs.entries.firstOrNull()?.let { it.key to it.value }
                    ?: throw IllegalArgumentException("Input ${i + 1} has no signature — sign the PSBT first")
                listOf(sig, pubHex.hexToBytes())
            }
            require(witness.size == 2) { "Input ${i + 1}: unexpected witness shape for P2WPKH" }
            parsed.tx.inputs[i].witness = witness
        }
        return parsed.tx
    }

    /** Signs every input this [keyFor] can serve (by pubkey from the derivation entries). */
    fun sign(parsed: Parsed, keyFor: (String) -> HDKey?): ByteArray {
        val sigsPerInput = parsed.inputs.mapIndexed { i, input ->
            val pubHex = input.derivations.keys.firstOrNull()
                ?: input.partialSigs.keys.firstOrNull()
                ?: return@mapIndexed emptyMap<String, ByteArray>()
            val key = keyFor(pubHex) ?: return@mapIndexed emptyMap<String, ByteArray>()
            val value = input.witnessUtxoValue
                ?: throw IllegalArgumentException("Input ${i + 1} has no witness utxo")
            val sighash = parsed.tx.bip143SighashAll(i, Scripts.p2pkhScriptCode(key.pubKey), value)
            mapOf(pubHex to Ecdsa.signForInput(sighash, key.privKey))
        }
        return reencode(parsed, sigsPerInput)
    }

    private fun reencode(parsed: Parsed, sigsPerInput: List<Map<String, ByteArray>>): ByteArray {
        val w = ByteWriter()
        w.bytes(MAGIC)
        writeEntry(w, byteArrayOf(GLOBAL_UNSIGNED_TX.toByte()), parsed.unsignedTxBytes)
        w.bytes(ByteArray(1))
        for((i, input) in parsed.inputs.withIndex()) {
            if(input.witnessUtxoValue != null && input.witnessUtxoScript != null) {
                writeEntry(w, byteArrayOf(IN_WITNESS_UTXO.toByte()),
                    ByteWriter().u64le(input.witnessUtxoValue).varBytes(input.witnessUtxoScript).toByteArray())
            }
            for((pubHex, sig) in input.partialSigs + sigsPerInput[i]) {
                writeEntry(w, byteArrayOf(IN_PARTIAL_SIG.toByte()) + pubHex.hexToBytes(), sig)
            }
            for((pubHex, deriv) in input.derivations) {
                writeEntry(w, byteArrayOf(IN_BIP32_DERIVATION.toByte()) + pubHex.hexToBytes(),
                    derivationValue(deriv.first, deriv.second))
            }
            w.bytes(ByteArray(1))
        }
        for(unused in parsed.tx.outputs) w.bytes(ByteArray(1))
        return w.toByteArray()
    }

    private fun readMap(r: ByteReader, onEntry: (ByteArray, ByteArray) -> Unit) {
        while(true) {
            val keyLen = r.varInt()
            if(keyLen == 0L) return
            val key = r.bytes(keyLen.toInt())
            val value = r.varBytes()
            onEntry(key, value)
        }
    }

    private fun parseUnsignedTx(bytes: ByteArray): Tx {
        val r = ByteReader(bytes)
        val version = r.u32le()
        val inputCount = r.varInt()
        require(inputCount in 1..10_000) { "Unreasonable input count in PSBT transaction" }
        val inputs = List(inputCount.toInt()) {
            val txid = r.bytes(32).reversedArray().toHex()
            val vout = r.u32le()
            val scriptSig = r.varBytes()
            require(scriptSig.isEmpty()) { "PSBT transaction must be unsigned" }
            val sequence = r.u32le()
            TxInput(txid, vout, sequence)
        }
        val outputCount = r.varInt()
        val outputs = List(outputCount.toInt()) {
            val value = r.u64le()
            TxOutput(value, r.varBytes())
        }
        return Tx(version, inputs, outputs, r.u32le())
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun toBase64(bytes: ByteArray): String = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun fromBase64(text: String): ByteArray = try {
        Base64.decode(text.trim())
    } catch(e: IllegalArgumentException) {
        throw IllegalArgumentException("Not a valid PSBT (bad base64)", e)
    }
}

/** Minimal little-endian byte reader for PSBT decoding. */
internal class ByteReader(private val data: ByteArray) {
    private var pos = 0

    fun remaining(): Int = data.size - pos

    fun bytes(count: Int): ByteArray {
        require(pos + count <= data.size) { "Truncated data" }
        return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun u32le(): Long {
        val b = bytes(4)
        return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
               ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
    }

    fun u64le(): Long {
        val b = bytes(8)
        var v = 0L
        for(i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun varInt(): Long {
        val first = bytes(1)[0].toInt() and 0xFF
        return when {
            first < 0xFD -> first.toLong()
            first == 0xFD -> (bytes(1)[0].toLong() and 0xFF) or ((bytes(1)[0].toLong() and 0xFF) shl 8)
            first == 0xFE -> u32le()
            else -> u64le()
        }
    }

    fun varBytes(): ByteArray = bytes(varInt().toInt())
}
