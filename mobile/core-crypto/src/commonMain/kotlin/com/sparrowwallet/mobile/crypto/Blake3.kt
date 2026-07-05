package com.sparrowwallet.mobile.crypto

/**
 * BLAKE3 hash, single-chunk (inputs up to 1024 bytes), 32-byte output.
 *
 * MWEB stealth-address derivation hashes 37-byte messages ('A' || le32(index) || scan
 * secret), so tree hashing is not needed; extend to multi-chunk if a larger use appears.
 * Verified against the official empty-input vector and 24 MWEB golden addresses.
 */
object Blake3 {
    private val IV = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19.toInt())

    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val ROOT = 8

    fun hash(input: ByteArray): ByteArray {
        require(input.size <= 1024) { "Single-chunk BLAKE3 only — extend for inputs > 1024 bytes" }
        var cv = IV.copyOf()
        val blockCount = if(input.isEmpty()) 1 else (input.size + 63) / 64
        var v = IntArray(16)
        for(block in 0 until blockCount) {
            val offset = block * 64
            val blockLen = minOf(64, input.size - offset)
            val bytes = ByteArray(64)
            input.copyInto(bytes, 0, offset, offset + blockLen)
            var flags = 0
            if(block == 0) flags = flags or CHUNK_START
            if(block == blockCount - 1) flags = flags or (CHUNK_END or ROOT)
            v = compress(cv, toWords(bytes), 0L, blockLen, flags)
            cv = v.copyOfRange(0, 8)
        }
        val out = ByteArray(32)
        for(i in 0 until 8) {
            out[i * 4] = (cv[i] and 0xFF).toByte()
            out[i * 4 + 1] = ((cv[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((cv[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((cv[i] ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun compress(cv: IntArray, block: IntArray, counter: Long, blockLen: Int, flags: Int): IntArray {
        val v = IntArray(16)
        cv.copyInto(v, 0)
        IV.copyInto(v, 8, 0, 4)
        v[12] = counter.toInt()
        v[13] = (counter ushr 32).toInt()
        v[14] = blockLen
        v[15] = flags

        var m = block
        for(round in 0 until 7) {
            g(v, 0, 4, 8, 12, m[0], m[1])
            g(v, 1, 5, 9, 13, m[2], m[3])
            g(v, 2, 6, 10, 14, m[4], m[5])
            g(v, 3, 7, 11, 15, m[6], m[7])
            g(v, 0, 5, 10, 15, m[8], m[9])
            g(v, 1, 6, 11, 12, m[10], m[11])
            g(v, 2, 7, 8, 13, m[12], m[13])
            g(v, 3, 4, 9, 14, m[14], m[15])
            if(round < 6) {
                m = IntArray(16) { m[MSG_PERMUTATION[it]] }
            }
        }

        for(i in 0 until 8) {
            v[i] = v[i] xor v[i + 8]
            v[i + 8] = v[i + 8] xor cv[i]
        }
        return v
    }

    private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        v[a] = v[a] + v[b] + mx
        v[d] = (v[d] xor v[a]).rotateRight(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(12)
        v[a] = v[a] + v[b] + my
        v[d] = (v[d] xor v[a]).rotateRight(8)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight(7)
    }

    private fun toWords(bytes: ByteArray): IntArray {
        return IntArray(16) { w ->
            (bytes[w * 4].toInt() and 0xFF) or
                    ((bytes[w * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[w * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[w * 4 + 3].toInt() and 0xFF) shl 24)
        }
    }
}
