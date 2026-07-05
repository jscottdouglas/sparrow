package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Byte-for-byte parity with desktop drongo, via golden vectors.
 * Every derivation the mobile wallet performs must produce exactly what desktop produces.
 */
class DerivationParityTest {
    private val vectors = Json.parseToJsonElement(
        File("../vectors/golden-vectors.json").readText()
    ).jsonObject

    @Test
    fun masterKeysMatchDesktop() {
        forEachSeed { name, seed, obj ->
            val master = HDKey.fromSeed(seed)
            assertEquals(obj["extendedMasterPrivateKey"]!!.jsonPrimitive.content, master.xprv(), "seed=$name")
            assertEquals(obj["masterFingerprint"]!!.jsonPrimitive.content, master.fingerprint.toHex(), "seed=$name")
        }
    }

    @Test
    fun accountXpubsMatchDesktop() {
        forEachScriptType { name, scriptType, master, entry ->
            val account = master.derivePath(entry["derivationPath"]!!.jsonPrimitive.content)
            assertEquals(entry["accountXpub"]!!.jsonPrimitive.content, account.xpub(), "seed=$name scriptType=$scriptType")
        }
    }

    @Test
    fun mwebViewKeysMatchDesktop() {
        forEachScriptType { name, scriptType, master, entry ->
            val account = master.derivePath(entry["derivationPath"]!!.jsonPrimitive.content)
            // drongo: scan secret at <account>/0', spend pubkey at <account>/1'
            val scan = account.deriveChild(HDKey.HARDENED_BIT)
            val spend = account.deriveChild(HDKey.HARDENED_BIT + 1)
            assertEquals(entry["mwebScanSecret"]!!.jsonPrimitive.content, scan.privKey.toHex(), "seed=$name scriptType=$scriptType")
            assertEquals(entry["mwebSpendPubKey"]!!.jsonPrimitive.content, spend.pubKey.toHex(), "seed=$name scriptType=$scriptType")
        }
    }

    @Test
    fun receiveKeysMatchDesktop() {
        forEachScriptType { name, scriptType, master, entry ->
            val account = master.derivePath(entry["derivationPath"]!!.jsonPrimitive.content)
            val receive0 = account.deriveChild(0).deriveChild(0)
            assertEquals(entry["receive0PubKey"]!!.jsonPrimitive.content, receive0.pubKey.toHex(), "seed=$name scriptType=$scriptType")
            // drongo emits WIF with the Bitcoin version byte 0x80 (not LTC's 0xB0) — match it exactly
            assertEquals(entry["receive0Wif"]!!.jsonPrimitive.content, receive0.wif(0x80), "seed=$name scriptType=$scriptType")
        }
    }

    private fun forEachSeed(action: (name: String, seed: ByteArray, obj: JsonObject) -> Unit) {
        for(element in vectors["seeds"]!!.jsonArray) {
            val obj = element.jsonObject
            val seed = Bip39.seed(
                obj["mnemonic"]!!.jsonPrimitive.content,
                obj["passphrase"]!!.jsonPrimitive.content
            )
            action(obj["name"]!!.jsonPrimitive.content, seed, obj)
        }
    }

    private fun forEachScriptType(action: (seedName: String, scriptType: String, master: HDKey, entry: JsonObject) -> Unit) {
        forEachSeed { name, seed, obj ->
            val master = HDKey.fromSeed(seed)
            for(entry in obj["scriptTypes"]!!.jsonArray) {
                val entryObj = entry.jsonObject
                action(name, entryObj["scriptType"]!!.jsonPrimitive.content, master, entryObj)
            }
        }
    }
}
