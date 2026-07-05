package com.sparrowwallet.mobile.crypto

/**
 * BIP39: mnemonic ↔ entropy and mnemonic → seed.
 * Matches drongo's DeterministicSeed output (see DerivationParityTest against golden vectors).
 */
object Bip39 {
    fun seed(mnemonic: String, passphrase: String = ""): ByteArray {
        return pbkdf2HmacSha512(
            nfkd(mnemonic).toCharArray(),
            ("mnemonic" + nfkd(passphrase)).encodeToByteArray(),
            2048,
            64
        )
    }

    /** Converts entropy (16/20/24/28/32 bytes) to a mnemonic sentence. */
    fun entropyToMnemonic(entropy: ByteArray): String {
        require(entropy.size % 4 == 0 && entropy.size in 16..32) { "Entropy must be 16-32 bytes in 4-byte steps" }
        val checksumBits = entropy.size / 4
        val data = entropy + sha256(entropy)[0]
        val wordCount = (entropy.size * 8 + checksumBits) / 11
        return (0 until wordCount).joinToString(" ") { w ->
            var index = 0
            for(bit in w * 11 until (w + 1) * 11) {
                index = (index shl 1) or bitAt(data, bit)
            }
            BIP39_ENGLISH[index]
        }
    }

    /**
     * Converts a mnemonic sentence back to entropy, throwing on unknown words,
     * invalid length, or checksum mismatch.
     */
    fun mnemonicToEntropy(mnemonic: String): ByteArray {
        val words = nfkd(mnemonic.trim().lowercase()).split(Regex("\\s+"))
        require(words.size % 3 == 0 && words.size in 12..24) { "Mnemonic must have 12, 15, 18, 21 or 24 words" }

        val indices = words.map { word ->
            BIP39_ENGLISH.binarySearch(word).also {
                require(it >= 0) { "Word not in BIP39 wordlist: $word" }
            }
        }

        val totalBits = words.size * 11
        val checksumBits = totalBits / 33
        val entropyBits = totalBits - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        for(bit in 0 until entropyBits) {
            if((indices[bit / 11] ushr (10 - bit % 11)) and 1 == 1) {
                entropy[bit / 8] = (entropy[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
            }
        }

        val checksum = sha256(entropy)
        for(bit in 0 until checksumBits) {
            val expected = bitAt(checksum, bit)
            val actual = (indices[(entropyBits + bit) / 11] ushr (10 - (entropyBits + bit) % 11)) and 1
            require(expected == actual) { "Mnemonic checksum mismatch" }
        }
        return entropy
    }

    fun isValid(mnemonic: String): Boolean {
        return runCatching { mnemonicToEntropy(mnemonic) }.isSuccess
    }

    private fun bitAt(data: ByteArray, bitIndex: Int): Int {
        return (data[bitIndex / 8].toInt() ushr (7 - bitIndex % 8)) and 1
    }
}
