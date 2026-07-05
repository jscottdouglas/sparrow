package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Imported-seed acceptance: a matching master fingerprint must override format heuristics,
 * because desktop stores Electrum-imported seeds without prefix validation — a seed can be
 * valid on desktop while failing our recognizer.
 */
class SeedProblemTest {
    private val bip39 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    // deliberately NOT a recognized Electrum seed (arbitrary words, unknown version prefix)
    private val oddElectrum = "sparrow flies over the silver litecoin river at midnight tonight"

    private fun imported(
        seedType: String,
        mnemonic: String,
        fingerprint: String?,
        needsPassphrase: Boolean = false
    ) = SparrowImport.ImportedWallet(
        name = "Test", network = "mainnet", scriptType = "MWEB", derivationPath = "m/1000'",
        masterFingerprint = fingerprint, xpub = null, seedType = seedType,
        mnemonic = mnemonic, needsPassphrase = needsPassphrase
    )

    private fun electrumFingerprint(mnemonic: String): String =
        HDKey.fromSeed(ElectrumSeed.toSeed(mnemonic, "")).fingerprint.toHex()

    @Test
    fun unrecognizedElectrumSeedAcceptedWhenFingerprintMatches() {
        assertNull(ElectrumSeed.versionPrefix(oddElectrum)?.takeIf { it in setOf("01", "100", "101") },
            "test premise: this must not be a recognized Electrum seed")
        assertNull(SparrowImport.seedProblem(imported("ELECTRUM", oddElectrum, electrumFingerprint(oddElectrum))))
    }

    @Test
    fun fingerprintMismatchRejected() {
        val problem = SparrowImport.seedProblem(imported("ELECTRUM", oddElectrum, "deadbeef"))
        assertNotNull(problem)
        assertTrue(problem.contains("fingerprint"))
    }

    @Test
    fun recognizedBip39WithoutFingerprintAccepted() {
        assertNull(SparrowImport.seedProblem(imported("BIP39", bip39, null)))
    }

    @Test
    fun bip39WithMatchingFingerprintAccepted() {
        val fingerprint = HDKey.fromSeed(Bip39.seed(bip39, "")).fingerprint.toHex()
        assertNull(SparrowImport.seedProblem(imported("BIP39", bip39, fingerprint)))
    }

    @Test
    fun unrecognizedSeedWithoutFingerprintRejectedWithDiagnostics() {
        val problem = SparrowImport.seedProblem(imported("ELECTRUM", oddElectrum, null))
        assertNotNull(problem)
        assertTrue(problem.contains("10 words"), "diagnostic should include word count: $problem")
    }

    @Test
    fun unsupportedSeedTypeRejected() {
        val problem = SparrowImport.seedProblem(imported("SLIP39", bip39, null))
        assertEquals("SLIP39 seeds aren't supported on mobile yet", problem)
    }

    @Test
    fun passphraseSeedRejected() {
        val problem = SparrowImport.seedProblem(imported("BIP39", bip39, null, needsPassphrase = true))
        assertNotNull(problem)
        assertTrue(problem.contains("passphrase"))
    }
}
