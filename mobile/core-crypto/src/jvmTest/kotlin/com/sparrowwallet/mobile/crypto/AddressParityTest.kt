package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** All 96 golden-vector addresses (3 seeds × 4 script types × 5 receive + 3 change). */
class AddressParityTest {
    private val vectors = Json.parseToJsonElement(
        File("../vectors/golden-vectors.json").readText()
    ).jsonObject

    @Test
    fun allAddressesMatchDesktop() {
        for(seedElement in vectors["seeds"]!!.jsonArray) {
            val seedObj = seedElement.jsonObject
            val seedName = seedObj["name"]!!.jsonPrimitive.content
            val master = HDKey.fromSeed(Bip39.seed(
                seedObj["mnemonic"]!!.jsonPrimitive.content,
                seedObj["passphrase"]!!.jsonPrimitive.content))

            for(entry in seedObj["scriptTypes"]!!.jsonArray) {
                val obj = entry.jsonObject
                val scriptType = obj["scriptType"]!!.jsonPrimitive.content
                val account = master.derivePath(obj["derivationPath"]!!.jsonPrimitive.content)
                val receive = obj["receiveAddresses"]!!.jsonArray.map { it.jsonPrimitive.content }
                val change = obj["changeAddresses"]!!.jsonArray.map { it.jsonPrimitive.content }

                for((i, expected) in receive.withIndex()) {
                    assertEquals(expected, deriveAddress(scriptType, account, false, i),
                        "seed=$seedName scriptType=$scriptType receive[$i]")
                }
                for((i, expected) in change.withIndex()) {
                    assertEquals(expected, deriveAddress(scriptType, account, true, i),
                        "seed=$seedName scriptType=$scriptType change[$i]")
                }
            }
        }
    }

    private fun deriveAddress(scriptType: String, account: HDKey, change: Boolean, index: Int): String {
        if(scriptType == "MWEB") {
            val scanSecret = account.deriveChild(HDKey.HARDENED_BIT).privKey
            val spendPubKey = account.deriveChild(HDKey.HARDENED_BIT + 1).pubKey
            return Addresses.mwebStealth(scanSecret, spendPubKey, Addresses.mwebAddressIndex(change, index))
        }
        val pubKey = account.deriveChild(if(change) 1L else 0L).deriveChild(index.toLong()).pubKey
        return when(scriptType) {
            "P2PKH" -> Addresses.p2pkh(pubKey)
            "P2SH_P2WPKH" -> Addresses.p2shP2wpkh(pubKey)
            "P2WPKH" -> Addresses.p2wpkh(pubKey)
            else -> throw IllegalArgumentException("Unknown script type: $scriptType")
        }
    }
}
