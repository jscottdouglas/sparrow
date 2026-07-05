package com.sparrowwallet.mobile.crypto

/**
 * Read-only BIP32 node: enough to derive receive/change public keys for scanning
 * without any private material. [HDKey] implements it too, so wallet scanning code
 * accepts either a full key or a watch-only xpub.
 */
interface Bip32Pub {
    val pubKey: ByteArray
    fun deriveChild(childNumber: Long): Bip32Pub
}

/**
 * BIP32 public-only (CKDpub) derivation from a serialized account xpub — used by
 * background sync so balances can be checked without unlocking the vault.
 */
class XpubKey private constructor(
    override val pubKey: ByteArray,
    val chainCode: ByteArray,
    val depth: Int,
    val parentFingerprint: ByteArray,
    val childNumber: Long,
    val version: Int
) : Bip32Pub {
    val fingerprint: ByteArray by lazy { hash160(pubKey).copyOfRange(0, 4) }

    override fun deriveChild(childNumber: Long): XpubKey {
        require(childNumber in 0 until HDKey.HARDENED_BIT) { "Cannot derive a hardened child from an xpub" }
        val data = ByteArray(37)
        pubKey.copyInto(data, 0)
        data[33] = ((childNumber ushr 24) and 0xFF).toByte()
        data[34] = ((childNumber ushr 16) and 0xFF).toByte()
        data[35] = ((childNumber ushr 8) and 0xFF).toByte()
        data[36] = (childNumber and 0xFF).toByte()
        val i = hmacSha512(chainCode, data)
        val childPub = secpPubKeyTweakAdd(pubKey, i.copyOfRange(0, 32))
        return XpubKey(childPub, i.copyOfRange(32, 64), depth + 1, fingerprint, childNumber, version)
    }

    /** Re-serializes with the version header it was parsed with. */
    fun xpub(): String {
        val out = ByteArray(78)
        out[0] = ((version ushr 24) and 0xFF).toByte()
        out[1] = ((version ushr 16) and 0xFF).toByte()
        out[2] = ((version ushr 8) and 0xFF).toByte()
        out[3] = (version and 0xFF).toByte()
        out[4] = depth.toByte()
        parentFingerprint.copyInto(out, 5)
        out[9] = ((childNumber ushr 24) and 0xFF).toByte()
        out[10] = ((childNumber ushr 16) and 0xFF).toByte()
        out[11] = ((childNumber ushr 8) and 0xFF).toByte()
        out[12] = (childNumber and 0xFF).toByte()
        chainCode.copyInto(out, 13)
        pubKey.copyInto(out, 45)
        return Base58.encodeChecked(out)
    }

    companion object {
        fun fromXpub(xpub: String): XpubKey {
            val payload = Base58.decodeChecked(xpub.trim())
            require(payload.size == 78) { "Not a valid extended key" }
            val keyData = payload.copyOfRange(45, 78)
            require(keyData[0].toInt() == 2 || keyData[0].toInt() == 3) {
                "Not a public extended key (xprv given?)"
            }
            val version = ((payload[0].toInt() and 0xFF) shl 24) or
                          ((payload[1].toInt() and 0xFF) shl 16) or
                          ((payload[2].toInt() and 0xFF) shl 8) or
                          (payload[3].toInt() and 0xFF)
            val childNumber = ((payload[9].toLong() and 0xFF) shl 24) or
                              ((payload[10].toLong() and 0xFF) shl 16) or
                              ((payload[11].toLong() and 0xFF) shl 8) or
                              (payload[12].toLong() and 0xFF)
            return XpubKey(
                keyData,
                payload.copyOfRange(13, 45),
                payload[4].toInt() and 0xFF,
                payload.copyOfRange(5, 9),
                childNumber,
                version
            )
        }
    }
}
