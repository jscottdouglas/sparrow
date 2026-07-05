package com.sparrowwallet.mobile.crypto

/**
 * Bech32/bech32m (BIP173/BIP350) encoding.
 * Matches drongo's Bech32: witness version 0 uses classic bech32, others bech32m.
 * MWEB addresses are version 0 over a 66-byte (A||B) program with HRP "ltcmweb".
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATORS = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    private const val BECH32M_CONST = 0x2bc830a3

    /** Encodes a witness program with its version prepended, per BIP173/BIP350. */
    fun encodeWitness(hrp: String, witnessVersion: Int, program: ByteArray): String {
        require(witnessVersion in 0..16) { "Invalid witness version: $witnessVersion" }
        val data = ByteArray(1) { witnessVersion.toByte() } + convertBits(program, 8, 5, true)
        val constant = if(witnessVersion == 0) 1 else BECH32M_CONST
        return encode(hrp, data, constant)
    }

    class DecodedWitness(val hrp: String, val version: Int, val program: ByteArray)

    /**
     * Decodes a segwit address, verifying the checksum (bech32 for version 0, bech32m
     * otherwise, per BIP350). Program length is validated by the caller against its
     * context (BIP141 allows 2..40; MWEB uses a 66-byte program under its own HRP).
     */
    fun decodeWitness(address: String): DecodedWitness {
        require(address == address.lowercase() || address == address.uppercase()) { "Mixed-case bech32 string" }
        val bech = address.lowercase()
        val pos = bech.lastIndexOf('1')
        require(pos >= 1 && pos + 1 + 6 < bech.length) { "Malformed bech32 string" }
        val hrp = bech.substring(0, pos)
        val data5 = ByteArray(bech.length - pos - 1) {
            val digit = CHARSET.indexOf(bech[pos + 1 + it])
            require(digit >= 0) { "Invalid bech32 character: ${bech[pos + 1 + it]}" }
            digit.toByte()
        }
        val version = data5[0].toInt()
        require(version in 0..16) { "Invalid witness version: $version" }
        val constant = if(version == 0) 1 else BECH32M_CONST
        val values = hrpExpand(hrp) + IntArray(data5.size) { data5[it].toInt() }
        require(polymod(values) == constant) { "Bad bech32 checksum" }
        val program = convertBits(data5.copyOfRange(1, data5.size - 6), 5, 8, false)
        return DecodedWitness(hrp, version, program)
    }

    private fun encode(hrp: String, data5: ByteArray, constant: Int): String {
        val checksum = createChecksum(hrp, data5, constant)
        val out = StringBuilder(hrp).append('1')
        for(b in data5 + checksum) {
            out.append(CHARSET[b.toInt()])
        }
        return out.toString()
    }

    fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        val maxV = (1 shl toBits) - 1
        for(value in data) {
            acc = (acc shl fromBits) or (value.toInt() and 0xFF)
            bits += fromBits
            while(bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxV).toByte())
            }
        }
        if(pad) {
            if(bits > 0) {
                out.add(((acc shl (toBits - bits)) and maxV).toByte())
            }
        } else {
            require(bits < fromBits && ((acc shl (toBits - bits)) and maxV) == 0) { "Invalid padding" }
        }
        return out.toByteArray()
    }

    private fun createChecksum(hrp: String, data5: ByteArray, constant: Int): ByteArray {
        val values = hrpExpand(hrp) + IntArray(data5.size) { data5[it].toInt() } + IntArray(6)
        val polymod = polymod(values) xor constant
        return ByteArray(6) { ((polymod ushr (5 * (5 - it))) and 31).toByte() }
    }

    private fun hrpExpand(hrp: String): IntArray {
        val out = IntArray(hrp.length * 2 + 1)
        for((i, c) in hrp.withIndex()) {
            out[i] = c.code ushr 5
            out[hrp.length + 1 + i] = c.code and 31
        }
        return out
    }

    private fun polymod(values: IntArray): Int {
        var chk = 1
        for(value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1FFFFFF) shl 5) xor value
            for(i in 0 until 5) {
                if((top ushr i) and 1 == 1) {
                    chk = chk xor GENERATORS[i]
                }
            }
        }
        return chk
    }
}
