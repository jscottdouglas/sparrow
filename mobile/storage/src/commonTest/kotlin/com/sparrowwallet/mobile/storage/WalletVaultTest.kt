package com.sparrowwallet.mobile.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WalletVaultTest {
    private val data = VaultData(
        mnemonic = "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor",
        passphrase = "",
        seedType = "BIP39",
        derivationPath = "m/84'/2'/0'"
    )

    // fewer iterations keep the test fast; production uses the default
    private val iters = 10_000

    @Test
    fun sealOpenRoundTrip() {
        val blob = WalletVault.seal(data, "correct horse battery staple", iters)
        assertEquals(data, WalletVault.open(blob, "correct horse battery staple"))
    }

    @Test
    fun wrongPasswordFails() {
        val blob = WalletVault.seal(data, "correct horse battery staple", iters)
        assertFailsWith<VaultException> { WalletVault.open(blob, "wrong password") }
    }

    @Test
    fun tamperedCiphertextFails() {
        val blob = WalletVault.seal(data, "pw", iters)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte() // flip a tag bit
        assertFailsWith<VaultException> { WalletVault.open(blob, "pw") }
    }

    @Test
    fun tamperedHeaderFails() {
        val blob = WalletVault.seal(data, "pw", iters)
        blob[5] = (blob[5].toInt() xor 0x01).toByte() // mutate iterations field (bound as AAD)
        assertFailsWith<VaultException> { WalletVault.open(blob, "pw") }
    }

    @Test
    fun ciphertextDiffersEachSeal() {
        // random salt+iv => no two blobs identical, so the vault isn't leaking a static key
        val a = WalletVault.seal(data, "pw", iters)
        val b = WalletVault.seal(data, "pw", iters)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun electrumSeedTypeRoundTrips() {
        val electrum = data.copy(seedType = "ELECTRUM", derivationPath = "m/0'")
        val blob = WalletVault.seal(electrum, "pw", iters)
        assertEquals(electrum, WalletVault.open(blob, "pw"))
    }
}
