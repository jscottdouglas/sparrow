package com.sparrowwallet.mobile.crypto

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if(input.isEmpty()) {
            return ""
        }
        var zeros = 0
        while(zeros < input.size && input[zeros].toInt() == 0) {
            zeros++
        }
        val digits = input.copyOf()
        val encoded = StringBuilder()
        var start = zeros
        while(start < digits.size) {
            // long division of the (base-256) digits by 58, collecting remainders
            var remainder = 0
            for(i in start until digits.size) {
                val value = (digits[i].toInt() and 0xFF) + remainder * 256
                digits[i] = (value / 58).toByte()
                remainder = value % 58
            }
            if(digits[start].toInt() == 0) {
                start++
            }
            encoded.append(ALPHABET[remainder])
        }
        repeat(zeros) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    /** Encodes payload with a 4-byte double-SHA256 checksum appended (base58check). */
    fun encodeChecked(payload: ByteArray): String {
        return encode(payload + sha256d(payload).copyOfRange(0, 4))
    }

    fun decode(input: String): ByteArray {
        if(input.isEmpty()) {
            return ByteArray(0)
        }
        val digits = IntArray(input.length) {
            val digit = ALPHABET.indexOf(input[it])
            require(digit >= 0) { "Invalid base58 character: ${input[it]}" }
            digit
        }
        var zeros = 0
        while(zeros < digits.size && digits[zeros] == 0) {
            zeros++
        }
        val decoded = ByteArray(input.length)
        var outStart = decoded.size
        var start = zeros
        while(start < digits.size) {
            // long division of the (base-58) digits by 256, collecting remainders
            var remainder = 0
            for(i in start until digits.size) {
                val value = digits[i] + remainder * 58
                digits[i] = value / 256
                remainder = value % 256
            }
            if(digits[start] == 0) {
                start++
            }
            decoded[--outStart] = remainder.toByte()
        }
        // the long division can emit extra most-significant zero bytes — drop them
        while(outStart < decoded.size && decoded[outStart].toInt() == 0) {
            outStart++
        }
        return ByteArray(zeros) + decoded.copyOfRange(outStart, decoded.size)
    }

    /** Decodes a base58check string, verifying and stripping the 4-byte checksum. */
    fun decodeChecked(input: String): ByteArray {
        val decoded = decode(input)
        require(decoded.size >= 4) { "Base58 input too short" }
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        require(sha256d(payload).copyOfRange(0, 4).contentEquals(checksum)) { "Bad base58 checksum" }
        return payload
    }
}
