package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.toHex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The export writer must round-trip through our own importer, and (via the vector file
 * this test regenerates) load in desktop Sparrow itself — see MobileExportTest in the
 * sparrow repo, which reads mobile/vectors/mobile-export-vector-wallet with password
 * "pass" under the desktop's own gson/crypto stack.
 */
class SparrowExportTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val master = HDKey.fromSeed(Bip39.seed(mnemonic, ""))
    private val account = master.derivePath("m/84'/2'/0'")

    private fun export(params: SparrowImport.ArgonParams) = SparrowExport.exportFile(
        name = "Mobile Export",
        network = "mainnet",
        seedType = "BIP39",
        mnemonic = mnemonic,
        derivationPath = "m/84'/2'/0'",
        masterFingerprint = master.fingerprint.toHex(),
        xpub = account.xpub(),
        password = "pass",
        params = params
    )

    @Test
    fun roundTripsThroughOwnImporter() {
        val file = export(SparrowImport.TEST_PARAMS)
        assertTrue(SparrowImport.isEncryptedSparrowFile(file))

        val imported = SparrowImport.importFile(file, "pass", SparrowImport.TEST_PARAMS)
        assertEquals("Mobile Export", imported.name)
        assertEquals("mainnet", imported.network)
        assertEquals("P2WPKH", imported.scriptType)
        assertEquals("m/84'/2'/0'", imported.derivationPath)
        assertEquals(master.fingerprint.toHex(), imported.masterFingerprint)
        assertEquals(account.xpub(), imported.xpub)
        assertEquals("BIP39", imported.seedType)
        assertEquals(mnemonic, imported.mnemonic)
    }

    @Test
    fun regeneratesDesktopTestVector() {
        // TEST_PARAMS deliberately: desktop unit tests always derive with TEST_PARAMS
        File("../vectors/mobile-export-vector-wallet").writeBytes(export(SparrowImport.TEST_PARAMS))
    }
}
