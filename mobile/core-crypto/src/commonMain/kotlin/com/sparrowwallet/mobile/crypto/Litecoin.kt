package com.sparrowwallet.mobile.crypto

/**
 * Litecoin networks, in lockstep with drongo's Network enum in the Sparrow-LTC fork
 * (MAINNET and TESTNET rows; regtest/signet omitted until needed). Mainnet values are
 * exercised by the golden-vector parity tests.
 */
enum class Network(
    val id: String,
    val displayName: String,
    /** BIP44 coin type: m/purpose'/coinType'/... */
    val coinType: Int,
    val p2pkhVersion: Int,
    val p2shVersion: Int,
    /** Deprecated P2SH version also accepted when decoding (Bitcoin-shared prefix). */
    val p2shLegacyVersion: Int,
    val wifVersion: Int,
    val bech32Hrp: String,
    val mwebHrp: String,
    val xpubHeader: Int,
    val xprvHeader: Int
) {
    MAINNET("mainnet", "Mainnet", 2, 48, 50, 5, 128, "ltc", "ltcmweb", 0x0488B21E, 0x0488ADE4),
    TESTNET("testnet", "Testnet", 1, 111, 58, 196, 191, "tltc", "tmweb", 0x043587CF, 0x04358394);

    val defaultDerivationPath: String get() = "m/84'/$coinType'/0'"

    companion object {
        fun fromId(id: String): Network = entries.firstOrNull { it.id == id.lowercase() } ?: MAINNET
    }
}

/**
 * Litecoin mainnet network constants (aliases into [Network.MAINNET], kept for the
 * existing call sites and parity tests).
 */
object Litecoin {
    /** BIP44 coin type: m/purpose'/2'/... */
    const val COIN_TYPE: Int = 2

    /** Bech32 HRP for segwit addresses (ltc1...). */
    const val BECH32_HRP: String = "ltc"

    /** Bech32 HRP for MWEB addresses (ltcmweb1...). */
    const val MWEB_HRP: String = "ltcmweb"

    /** Base58 version byte for P2PKH addresses (L...). */
    const val P2PKH_VERSION: Int = 48

    /** Base58 version byte for P2SH addresses (M...). */
    const val P2SH_VERSION: Int = 50

    /** Base58 version byte for WIF private keys. */
    const val WIF_VERSION: Int = 176

    /** BIP32 extended key headers (Sparrow-LTC emits standard xprv/xpub headers). */
    const val XPUB_HEADER: Int = 0x0488B21E
    const val XPRV_HEADER: Int = 0x0488ADE4

    /** MWEB keystore derivation: account path for the MWEB script type. */
    const val MWEB_DERIVATION_PATH: String = "m/1000'"

    /** MWEB view key components, relative to the account path (per drongo Keystore). */
    const val MWEB_SCAN_KEY_CHILD: String = "0'"  // scan secret: <account>/0'
    const val MWEB_SPEND_KEY_CHILD: String = "1'" // spend key:   <account>/1'
}
