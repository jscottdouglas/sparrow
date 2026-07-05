package com.sparrowwallet.mobile.crypto

private const val HEX_CHARS = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for(b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return out.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(length / 2) { i ->
        ((digit(this[i * 2]) shl 4) or digit(this[i * 2 + 1])).toByte()
    }
}

private fun digit(c: Char): Int {
    val d = when(c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Invalid hex char: $c")
    }
    return d
}
