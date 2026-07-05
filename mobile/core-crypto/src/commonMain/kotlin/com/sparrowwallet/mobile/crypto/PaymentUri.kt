package com.sparrowwallet.mobile.crypto

/**
 * BIP21 payment URI parsing for scanned QR codes:
 *   litecoin:<address>?amount=<LTC decimal>&label=...&message=...
 * Also accepts a bare address, and whole-payload-uppercase QRs (encoders uppercase
 * the entire string for QR alphanumeric mode, which has no lowercase letters).
 * Address validity is NOT checked here — callers validate against their network.
 */
object PaymentUri {
    data class Payment(
        val address: String,
        /** Amount as the URI's decimal LTC string (e.g. "0.015"), null if absent. */
        val amount: String? = null,
        val label: String? = null,
        val message: String? = null
    )

    private val AMOUNT_REGEX = Regex("""\d+(\.\d{1,8})?""")

    fun parse(text: String): Payment {
        var body = text.trim()
        if(body.length >= 9 && body.substring(0, 9).equals("litecoin:", ignoreCase = true)) {
            body = body.substring(9).removePrefix("//")
        }
        val queryStart = body.indexOf('?')
        val address = normalizeAddress(if(queryStart < 0) body else body.substring(0, queryStart))
        require(address.isNotEmpty()) { "No address in the scanned code" }

        var amount: String? = null
        var label: String? = null
        var message: String? = null
        if(queryStart >= 0) {
            for(param in body.substring(queryStart + 1).split('&')) {
                if(param.isEmpty()) continue
                val eq = param.indexOf('=')
                val key = (if(eq < 0) param else param.substring(0, eq)).lowercase()
                val value = if(eq < 0) "" else percentDecode(param.substring(eq + 1))
                when(key) {
                    "amount" -> {
                        require(AMOUNT_REGEX.matches(value)) { "Invalid amount in payment code: $value" }
                        amount = value
                    }
                    "label" -> label = value
                    "message" -> message = value
                    else -> require(!key.startsWith("req-")) {
                        "Payment code requires an unsupported feature: $key"
                    }
                }
            }
        }
        return Payment(address, amount, label, message)
    }

    /**
     * Lowercases an all-uppercase bech32 address (base58 is case-sensitive, so only
     * addresses whose lowercased form starts with a known HRP are touched).
     */
    private fun normalizeAddress(raw: String): String {
        val addr = raw.trim()
        if(addr.any { it in 'a'..'z' }) return addr
        val lower = addr.lowercase()
        for(net in Network.entries) {
            if(lower.startsWith(net.bech32Hrp + "1") || lower.startsWith(net.mwebHrp + "1")) {
                return lower
            }
        }
        return addr
    }

    private fun percentDecode(text: String): String {
        if('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while(i < text.length) {
            val c = text[i]
            if(c == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if(hex != null) {
                    bytes.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            for(b in c.toString().encodeToByteArray()) bytes.add(b)
            i++
        }
        return bytes.toByteArray().decodeToString()
    }
}
