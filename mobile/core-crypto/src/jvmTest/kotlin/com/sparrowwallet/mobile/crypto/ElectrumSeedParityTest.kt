package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Byte-for-byte parity with desktop drongo for Electrum-seed import — the mechanism that
 * lets a wallet created in Electrum-LTC be imported on mobile and match desktop exactly.
 */
class ElectrumSeedParityTest {
    private val vector = Json.parseToJsonElement(
        File("../vectors/electrum-seed-vector.json").readText()
    ).jsonObject

    private fun field(name: String): String = vector[name]!!.jsonPrimitive.content

    @Test
    fun seedBytesMatchDesktop() {
        assertEquals(field("seedBytes"), ElectrumSeed.toSeed(field("mnemonic"), field("passphrase")).toHex())
    }

    @Test
    fun masterAndAccountKeysMatchDesktop() {
        val master = HDKey.fromSeed(ElectrumSeed.toSeed(field("mnemonic"), field("passphrase")))
        assertEquals(field("extendedMasterPrivateKey"), master.xprv())
        val account = master.derivePath(field("derivationPath"))
        assertEquals(field("accountXpub"), account.xpub())
    }

    @Test
    fun mwebViewKeysReDerivedFromSeed() {
        val account = HDKey.fromSeed(ElectrumSeed.toSeed(field("mnemonic"), field("passphrase")))
            .derivePath(field("derivationPath"))
        assertEquals(field("mwebScanSecret"), account.deriveChild(HDKey.HARDENED_BIT).privKey.toHex())
        assertEquals(field("mwebSpendPubKey"), account.deriveChild(HDKey.HARDENED_BIT + 1).pubKey.toHex())
    }

    @Test
    fun receiveAddressesMatchDesktop() {
        val account = HDKey.fromSeed(ElectrumSeed.toSeed(field("mnemonic"), field("passphrase")))
            .derivePath(field("derivationPath"))
        val expected = vector["receiveAddresses"]!!.jsonArray.map { it.jsonPrimitive.content }
        for((i, addr) in expected.withIndex()) {
            assertEquals(addr, Addresses.p2wpkh(account.deriveChild(0).deriveChild(i.toLong()).pubKey), "receive[$i]")
        }
    }

    @Test
    fun versionPrefixAndValidity() {
        assertEquals("100", ElectrumSeed.versionPrefix(field("mnemonic")))
        assertTrue(ElectrumSeed.isValid(field("mnemonic")))
        assertEquals("m/0'", ElectrumSeed.defaultDerivation(field("mnemonic")))
    }

    @Test
    fun bip39MnemonicIsNotAValidElectrumSeed() {
        // the two formats are distinct — a BIP39 phrase must not pass the Electrum check
        assertFalse(ElectrumSeed.isValid("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
    }
}
