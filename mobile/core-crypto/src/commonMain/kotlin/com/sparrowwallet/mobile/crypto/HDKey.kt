package com.sparrowwallet.mobile.crypto

/**
 * BIP32 hierarchical deterministic private key.
 *
 * Port of drongo's HDKeyDerivation/DeterministicKey behavior, verified byte-for-byte
 * against desktop-generated golden vectors and the official BIP32 test vectors.
 */
class HDKey private constructor(
    val privKey: ByteArray,           // 32 bytes
    val chainCode: ByteArray,         // 32 bytes
    val depth: Int,
    val parentFingerprint: ByteArray, // 4 bytes
    val childNumber: Long             // 0..2^32-1; >= 0x80000000 means hardened
) : Bip32Pub {
    override val pubKey: ByteArray by lazy { secpPublicKeyCreate(privKey) }
    val fingerprint: ByteArray by lazy { hash160(pubKey).copyOfRange(0, 4) }

    override fun deriveChild(childNumber: Long): HDKey {
        require(childNumber in 0 until 0x1_0000_0000L) { "Child number out of range: $childNumber" }
        val data = ByteArray(37)
        if(childNumber >= HARDENED_BIT) {
            privKey.copyInto(data, 1) // 0x00 || priv
        } else {
            pubKey.copyInto(data, 0)  // serP(pub)
        }
        writeUint32BE(data, 33, childNumber)
        val i = hmacSha512(chainCode, data)
        val childKey = secpPrivKeyTweakAdd(privKey, i.copyOfRange(0, 32))
        return HDKey(childKey, i.copyOfRange(32, 64), depth + 1, fingerprint, childNumber)
    }

    /** Derives along an absolute path like "m/44'/2'/0'" (', h and H mark hardened). */
    fun derivePath(path: String): HDKey {
        val parts = path.trim().split("/")
        require(parts.isNotEmpty() && (parts[0] == "m" || parts[0] == "M")) { "Path must start with m: $path" }
        var key = this
        for(part in parts.drop(1)) {
            if(part.isEmpty()) {
                continue
            }
            val hardened = part.endsWith("'") || part.endsWith("h") || part.endsWith("H")
            val index = (if(hardened) part.dropLast(1) else part).toLong()
            key = key.deriveChild(if(hardened) index + HARDENED_BIT else index)
        }
        return key
    }

    fun xprv(version: Int = Litecoin.XPRV_HEADER): String = serialize(version, byteArrayOf(0) + privKey)

    fun xpub(version: Int = Litecoin.XPUB_HEADER): String = serialize(version, pubKey)

    /**
     * WIF encoding. Note: LTC's standard WIF version is [Litecoin.WIF_VERSION] (0xB0),
     * but desktop drongo currently emits the Bitcoin version 0x80 — parity tests pass
     * 0x80 explicitly to match it.
     */
    fun wif(version: Int, compressed: Boolean = true): String {
        val payload = byteArrayOf(version.toByte()) + privKey + if(compressed) byteArrayOf(1) else byteArrayOf()
        return Base58.encodeChecked(payload)
    }

    private fun serialize(version: Int, keyData: ByteArray): String {
        val out = ByteArray(78)
        writeUint32BE(out, 0, version.toLong() and 0xFFFFFFFFL)
        out[4] = depth.toByte()
        parentFingerprint.copyInto(out, 5)
        writeUint32BE(out, 9, childNumber)
        chainCode.copyInto(out, 13)
        keyData.copyInto(out, 45)
        return Base58.encodeChecked(out)
    }

    companion object {
        const val HARDENED_BIT = 0x80000000L

        fun fromSeed(seed: ByteArray): HDKey {
            val i = hmacSha512("Bitcoin seed".encodeToByteArray(), seed)
            return HDKey(i.copyOfRange(0, 32), i.copyOfRange(32, 64), 0, ByteArray(4), 0L)
        }

        private fun writeUint32BE(target: ByteArray, offset: Int, value: Long) {
            target[offset] = ((value ushr 24) and 0xFF).toByte()
            target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            target[offset + 3] = (value and 0xFF).toByte()
        }
    }
}
