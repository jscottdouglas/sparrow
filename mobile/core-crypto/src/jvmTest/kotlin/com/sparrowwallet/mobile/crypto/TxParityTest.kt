package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-for-byte signed-transaction parity with desktop drongo.
 * Every intermediate is asserted so a failure localizes to one stage:
 * key → scriptCode → sighash → signature → witness serialization → txid.
 */
class TxParityTest {
    private val vector = Json.parseToJsonElement(
        File("../vectors/signed-tx-vector.json").readText()
    ).jsonObject

    private fun field(name: String): String = vector[name]!!.jsonPrimitive.content

    @Test
    fun signedTransactionMatchesDesktop() {
        val master = HDKey.fromSeed(Bip39.seed(field("mnemonic"), field("passphrase")))
        val key = master.derivePath(field("keyPath"))
        assertEquals(field("signingPubKey"), key.pubKey.toHex(), "signing pubkey")

        val input = vector["input"]!!.jsonObject
        val tx = Tx(
            version = 2,
            inputs = listOf(TxInput(
                prevTxid = input["prevTxid"]!!.jsonPrimitive.content,
                vout = input["vout"]!!.jsonPrimitive.content.toLong(),
                sequence = input["sequence"]!!.jsonPrimitive.content.toLong())),
            outputs = vector["outputs"]!!.jsonArray.map {
                val o = it.jsonObject
                TxOutput(o["value"]!!.jsonPrimitive.content.toLong(), o["script"]!!.jsonPrimitive.content.hexToBytes())
            })

        val scriptCode = Scripts.p2pkhScriptCode(key.pubKey)
        assertEquals(field("scriptCode"), scriptCode.toHex(), "scriptCode")

        val inputValue = input["value"]!!.jsonPrimitive.content.toLong()
        val sighash = tx.bip143SighashAll(0, scriptCode, inputValue)
        assertEquals(field("sighash"), sighash.toHex(), "BIP143 sighash")

        val signature = Ecdsa.signForInput(sighash, key.privKey)
        assertEquals(field("signatureDer"), signature.toHex(), "DER signature (RFC6979)")

        tx.inputs[0].witness = listOf(signature, key.pubKey)
        assertEquals(field("rawTx"), tx.serialize().toHex(), "raw witness serialization")
        assertEquals(field("txid"), tx.txid(), "txid")
    }

    @Test
    fun outputScriptsMatchDerivedKeys() {
        val master = HDKey.fromSeed(Bip39.seed(field("mnemonic"), field("passphrase")))
        val account = master.derivePath("m/84'/2'/0'")
        val outputs = vector["outputs"]!!.jsonArray.map { it.jsonObject["script"]!!.jsonPrimitive.content }
        // output 0 pays receive[1], output 1 pays change[0] — cross-checks script construction
        assertEquals(outputs[0], Scripts.p2wpkhOutput(account.deriveChild(0).deriveChild(1).pubKey).toHex())
        assertEquals(outputs[1], Scripts.p2wpkhOutput(account.deriveChild(1).deriveChild(0).pubKey).toHex())
    }
}
