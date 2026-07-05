package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Address → scriptPubKey decoding for spend outputs: every address type the wallet
 * can encode must decode back to exactly the script the builders produce, and
 * corrupted/foreign inputs must be rejected.
 */
class AddressDecodeTest {
    private val account = HDKey.fromSeed(
        Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", "")
    ).derivePath("m/84'/2'/0'")
    private val pubKey = account.deriveChild(0).deriveChild(0).pubKey

    @Test
    fun p2wpkhRoundTrip() {
        val address = Addresses.p2wpkh(pubKey)
        assertTrue(address.startsWith("ltc1q"))
        assertContentEquals(Scripts.p2wpkhOutput(pubKey), Addresses.toScriptPubKey(address))
    }

    @Test
    fun p2pkhRoundTrip() {
        val address = Addresses.p2pkh(pubKey)
        assertTrue(address.startsWith("L"))
        assertContentEquals(Scripts.p2pkhOutput(pubKey), Addresses.toScriptPubKey(address))
    }

    @Test
    fun p2shP2wpkhRoundTrip() {
        val address = Addresses.p2shP2wpkh(pubKey)
        assertTrue(address.startsWith("M"))
        assertContentEquals(Scripts.p2shP2wpkhOutput(pubKey), Addresses.toScriptPubKey(address))
    }

    @Test
    fun uppercaseBech32Accepted() {
        val address = Addresses.p2wpkh(pubKey).uppercase()
        assertContentEquals(Scripts.p2wpkhOutput(pubKey), Addresses.toScriptPubKey(address))
    }

    @Test
    fun bip173ReferenceVectorDecodes() {
        // The BIP173 P2WPKH example (Bitcoin HRP — decodeWitness itself is network-agnostic)
        val decoded = Bech32.decodeWitness("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")
        assertEquals("bc", decoded.hrp)
        assertEquals(0, decoded.version)
        assertEquals("751e76e8199196d454941c45d1b3a323f1433bd6", decoded.program.toHex())
    }

    @Test
    fun witnessV1DecodesToBip350Script() {
        val program = ByteArray(32) { it.toByte() }
        val address = Bech32.encodeWitness(Litecoin.BECH32_HRP, 1, program)
        val script = Addresses.toScriptPubKey(address)
        assertEquals(0x51, script[0].toInt() and 0xFF) // OP_1
        assertEquals(32, script[1].toInt())
        assertContentEquals(program, script.copyOfRange(2, 34))
    }

    @Test
    fun corruptedBech32ChecksumRejected() {
        val address = Addresses.p2wpkh(pubKey)
        val corrupted = address.dropLast(1) + (if(address.last() == 'q') 'p' else 'q')
        assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey(corrupted) }
    }

    @Test
    fun corruptedBase58ChecksumRejected() {
        val address = Addresses.p2pkh(pubKey)
        val mid = address.length / 2
        val corrupted = address.substring(0, mid) + (if(address[mid] == 'x') 'y' else 'x') + address.substring(mid + 1)
        assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey(corrupted) }
    }

    @Test
    fun mwebDestinationRejectedWithClearMessage() {
        val scan = account.deriveChild(0).deriveChild(1).privKey
        val mweb = Addresses.mwebStealth(scan, pubKey, 1)
        val error = assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey(mweb) }
        assertTrue(error.message!!.contains("MWEB"))
    }

    @Test
    fun garbageRejected() {
        assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey("not an address") }
        assertFailsWith<IllegalArgumentException> { Addresses.toScriptPubKey("") }
        // valid base58 checksum but a Bitcoin P2PKH version byte
        assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(Base58.encodeChecked(byteArrayOf(0) + ByteArray(20)))
        }
    }

    @Test
    fun base58DecodeRoundTrip() {
        val payload = byteArrayOf(Litecoin.P2PKH_VERSION.toByte()) + hash160(pubKey)
        assertContentEquals(payload, Base58.decodeChecked(Base58.encodeChecked(payload)))
    }

    @Test
    fun testnetAddressesRoundTrip() {
        val net = Network.TESTNET
        val bech = Addresses.p2wpkh(pubKey, net.bech32Hrp)
        assertTrue(bech.startsWith("tltc1"))
        assertContentEquals(Scripts.p2wpkhOutput(pubKey), Addresses.toScriptPubKey(bech, net))

        val p2pkh = Addresses.p2pkh(pubKey, net.p2pkhVersion)
        assertTrue(p2pkh.startsWith("m") || p2pkh.startsWith("n"))
        assertContentEquals(Scripts.p2pkhOutput(pubKey), Addresses.toScriptPubKey(p2pkh, net))

        val p2sh = Addresses.p2shP2wpkh(pubKey, net.p2shVersion)
        assertTrue(p2sh.startsWith("Q"))
        assertContentEquals(Scripts.p2shP2wpkhOutput(pubKey), Addresses.toScriptPubKey(p2sh, net))
    }

    @Test
    fun wrongNetworkBech32RejectedWithClearMessage() {
        val mainnetAddr = Addresses.p2wpkh(pubKey)
        val error = assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(mainnetAddr, Network.TESTNET)
        }
        assertTrue(error.message!!.contains("Mainnet"))

        val testnetAddr = Addresses.p2wpkh(pubKey, Network.TESTNET.bech32Hrp)
        val error2 = assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(testnetAddr, Network.MAINNET)
        }
        assertTrue(error2.message!!.contains("Testnet"))
    }

    @Test
    fun wrongNetworkBase58Rejected() {
        val testnetP2pkh = Addresses.p2pkh(pubKey, Network.TESTNET.p2pkhVersion)
        assertFailsWith<IllegalArgumentException> {
            Addresses.toScriptPubKey(testnetP2pkh, Network.MAINNET)
        }
    }
}
