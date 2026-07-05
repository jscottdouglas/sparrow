package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.sha256
import com.sparrowwallet.mobile.crypto.toHex

/**
 * Electrum protocol primitives that are pure (no I/O), so they can be unit-tested
 * without a server. The scripthash mapping is the address→server-query key and must
 * match desktop drongo exactly (ElectrumServer.getScriptHash):
 *   scripthash = hex(reverse(SHA256(scriptPubKey)))
 */
object ElectrumProtocol {
    fun scriptHash(scriptPubKey: ByteArray): String = sha256(scriptPubKey).reversedArray().toHex()

    /**
     * Parses a decimal LTC amount string (as found in verbose transaction JSON, e.g.
     * "0.05", "1.23456789") into litoshis without any floating-point rounding.
     */
    fun ltcToLitoshis(decimal: String): Long {
        val negative = decimal.startsWith("-")
        val s = decimal.removePrefix("-")
        require(s.isNotEmpty() && s.all { it.isDigit() || it == '.' }) { "Not a decimal amount: $decimal" }
        val parts = s.split(".")
        require(parts.size <= 2 && (parts.getOrNull(1)?.length ?: 0) <= 8) { "Not an LTC amount: $decimal" }
        val whole = if(parts[0].isEmpty()) 0L else parts[0].toLong()
        val frac = if(parts.size > 1) parts[1].padEnd(8, '0').toLong() else 0L
        val value = whole * 100_000_000L + frac
        return if(negative) -value else value
    }
}
