package com.sparrowwallet.mobile.crypto

/**
 * Electrum's seed format — distinct from BIP39 (drongo ElectrumMnemonicCode):
 *  - seed  = PBKDF2-HMAC-SHA512(NFKD(mnemonic), salt = "electrum" + NFKD(passphrase), 2048)
 *  - valid = HMAC-SHA512("Seed version", NFKD(mnemonic)) begins with a known version prefix
 *
 * Electrum-LTC (the reference MWEB wallet) creates **segwit** seeds (prefix 100), which
 * derive at m/0'. Importing such a seed re-derives the MWEB scan/spend keys from it, so a
 * wallet imported on mobile matches the same wallet on desktop byte-for-byte
 * (see ElectrumSeedParityTest).
 */
object ElectrumSeed {
    private val VALID_PREFIXES = setOf("01", "100", "101")

    fun toSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val salt = "electrum" + nfkd(passphrase)
        return pbkdf2HmacSha512(nfkd(mnemonic).toCharArray(), salt.encodeToByteArray(), 2048, 64)
    }

    /**
     * Electrum's version prefix, replicating drongo's logic exactly (quirks included):
     * take the first hex nibble N of HMAC-SHA512("Seed version", mnemonic), then the first
     * N+2 hex chars, reparsed as an int and re-hex-encoded. Returns null if unparseable.
     */
    fun versionPrefix(mnemonic: String): String? {
        val hash = hmacSha512("Seed version".encodeToByteArray(), nfkd(mnemonic).encodeToByteArray())
        val hex = hash.toHex()
        val firstNibble = hex[0].digitToIntOrNull(10) ?: return null
        val prefixLength = firstNibble + 2
        if(prefixLength > hex.length) return null
        val prefix = hex.substring(0, prefixLength)
        return prefix.toLong(16).toString(16)
    }

    fun isValid(mnemonic: String): Boolean = versionPrefix(mnemonic) in VALID_PREFIXES

    /** Default derivation for an Electrum seed: segwit (100) uses m/0', standard (01) uses m. */
    fun defaultDerivation(mnemonic: String): String = when(versionPrefix(mnemonic)) {
        "100", "101" -> "m/0'"
        else -> "m"
    }
}
