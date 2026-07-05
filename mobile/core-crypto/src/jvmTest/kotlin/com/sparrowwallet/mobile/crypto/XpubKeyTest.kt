package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Watch-only derivation must produce byte-identical results to seed-based derivation:
 * every golden-vector P2WPKH address re-derived from the account xpub alone, plus the
 * official BIP32 test-vector CKDpub chain.
 */
class XpubKeyTest {
    private val vectors = Json.parseToJsonElement(
        File("../vectors/golden-vectors.json").readText()
    ).jsonObject

    @Test
    fun goldenAddressesFromXpubAlone() {
        for(seed in vectors["seeds"]!!.jsonArray) {
            val s = seed.jsonObject
            for(st in s["scriptTypes"]!!.jsonArray) {
                val t = st.jsonObject
                if(t["scriptType"]!!.jsonPrimitive.content != "P2WPKH") continue
                val account = XpubKey.fromXpub(t["accountXpub"]!!.jsonPrimitive.content)
                val receive = account.deriveChild(0)
                val change = account.deriveChild(1)
                t["receiveAddresses"]!!.jsonArray.forEachIndexed { i, addr ->
                    assertEquals(addr.jsonPrimitive.content, Addresses.p2wpkh(receive.deriveChild(i.toLong()).pubKey))
                }
                t["changeAddresses"]!!.jsonArray.forEachIndexed { i, addr ->
                    assertEquals(addr.jsonPrimitive.content, Addresses.p2wpkh(change.deriveChild(i.toLong()).pubKey))
                }
            }
        }
    }

    @Test
    fun publicDerivationMatchesPrivateDerivation() {
        val master = HDKey.fromSeed(Bip39.seed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", ""))
        val account = master.derivePath("m/84'/2'/0'")
        val watchOnly = XpubKey.fromXpub(account.xpub())
        for(chain in 0L..1L) {
            for(i in 0L..5L) {
                assertContentEquals(
                    account.deriveChild(chain).deriveChild(i).pubKey,
                    watchOnly.deriveChild(chain).deriveChild(i).pubKey,
                    "chain $chain index $i"
                )
            }
        }
    }

    @Test
    fun bip32TestVector1CkdPub() {
        // BIP32 test vector 1: from the m/0' xpub, CKDpub(1) must give the published m/0'/1 xpub
        val m0h = XpubKey.fromXpub(
            "xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw")
        assertEquals(
            "xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ",
            m0h.deriveChild(1).xpub()
        )
    }

    @Test
    fun hardenedDerivationRejected() {
        val seed = vectors["seeds"]!!.jsonArray[0].jsonObject
        val xpub = seed["scriptTypes"]!!.jsonArray.first {
            it.jsonObject["scriptType"]!!.jsonPrimitive.content == "P2WPKH"
        }.jsonObject["accountXpub"]!!.jsonPrimitive.content
        assertFailsWith<IllegalArgumentException> {
            XpubKey.fromXpub(xpub).deriveChild(HDKey.HARDENED_BIT)
        }
    }

    @Test
    fun xprvRejected() {
        val seed = vectors["seeds"]!!.jsonArray[0].jsonObject
        assertFailsWith<IllegalArgumentException> {
            XpubKey.fromXpub(seed["extendedMasterPrivateKey"]!!.jsonPrimitive.content)
        }
    }
}
