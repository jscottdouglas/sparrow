package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Bip39Test {
    @Test
    fun standardVectorRoundTrip() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals("00000000000000000000000000000000", Bip39.mnemonicToEntropy(mnemonic).toHex())
        assertEquals(mnemonic, Bip39.entropyToMnemonic(ByteArray(16)))
    }

    @Test
    fun goldenMnemonicsValidate() {
        assertTrue(Bip39.isValid("absent essay fox snake vast pumpkin height crouch silent bulb excuse razor"))
    }

    @Test
    fun checksumMismatchRejected() {
        // last word altered: checksum no longer matches
        assertFalse(Bip39.isValid("absent essay fox snake vast pumpkin height crouch silent bulb excuse abandon"))
    }

    @Test
    fun unknownWordRejected() {
        assertFalse(Bip39.isValid("absent essay fox snake vast pumpkin height crouch silent bulb excuse litecoin"))
    }

    @Test
    fun wordlistIsSortedFor2048Words() {
        // binarySearch in mnemonicToEntropy relies on sorted order
        assertEquals(2048, BIP39_ENGLISH.size)
        assertEquals(BIP39_ENGLISH.sorted(), BIP39_ENGLISH)
    }

    @Test
    fun twentyFourWordRoundTrip() {
        val entropy = ByteArray(32) { it.toByte() }
        val mnemonic = Bip39.entropyToMnemonic(entropy)
        assertEquals(24, mnemonic.split(" ").size)
        assertEquals(entropy.toHex(), Bip39.mnemonicToEntropy(mnemonic).toHex())
        assertTrue(Bip39.isValid(mnemonic))
    }
}
