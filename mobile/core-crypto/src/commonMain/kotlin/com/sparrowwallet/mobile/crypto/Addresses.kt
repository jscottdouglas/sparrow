package com.sparrowwallet.mobile.crypto

/**
 * Litecoin address encoding, matching desktop drongo byte-for-byte
 * (see AddressParityTest against golden vectors).
 */
object Addresses {
    fun p2pkh(pubKey: ByteArray, version: Int = Litecoin.P2PKH_VERSION): String {
        return Base58.encodeChecked(byteArrayOf(version.toByte()) + hash160(pubKey))
    }

    fun p2shP2wpkh(pubKey: ByteArray, version: Int = Litecoin.P2SH_VERSION): String {
        val redeemScript = byteArrayOf(0x00, 0x14) + hash160(pubKey) // OP_0 PUSH20 <pkh>
        return Base58.encodeChecked(byteArrayOf(version.toByte()) + hash160(redeemScript))
    }

    fun p2wpkh(pubKey: ByteArray, hrp: String = Litecoin.BECH32_HRP): String {
        return Bech32.encodeWitness(hrp, 0, hash160(pubKey))
    }

    /**
     * MWEB stealth address for the given index (drongo MwebAddressDeriver):
     *   m  = BLAKE3('A' || le32(index) || scanSecret)
     *   Bi = spendPubKey + G*m
     *   Ai = scanSecret * Bi
     * encoded as bech32 version 0 over Ai || Bi with HRP "ltcmweb".
     */
    fun mwebStealth(scanSecret: ByteArray, spendPubKey: ByteArray, index: Int, hrp: String = Litecoin.MWEB_HRP): String {
        val message = ByteArray(37)
        message[0] = 'A'.code.toByte()
        message[1] = (index and 0xFF).toByte()
        message[2] = ((index ushr 8) and 0xFF).toByte()
        message[3] = ((index ushr 16) and 0xFF).toByte()
        message[4] = ((index ushr 24) and 0xFF).toByte()
        scanSecret.copyInto(message, 5)
        val m = Blake3.hash(message)
        val b = secpPubKeyTweakAdd(spendPubKey, m)
        val a = secpPubKeyTweakMul(b, scanSecret)
        return Bech32.encodeWitness(hrp, 0, a + b)
    }

    /**
     * MWEB wallet-node → stealth address index (drongo MwebUtils.getAddressIndex):
     * all change nodes share index 0; receive node i uses index i+1.
     */
    fun mwebAddressIndex(change: Boolean, nodeIndex: Int): Int {
        return if(change) 0 else nodeIndex + 1
    }

    /**
     * Decodes a Litecoin address to its scriptPubKey, for building transaction outputs.
     * Accepts bech32 segwit (v0 P2WPKH/P2WSH and v1+ per BIP350), base58 P2PKH, and
     * base58 P2SH (current plus deprecated Bitcoin-shared version) for the given network.
     * Throws IllegalArgumentException with a user-facing message on anything else.
     */
    fun toScriptPubKey(address: String, network: Network = Network.MAINNET): ByteArray {
        val addr = address.trim()
        val lower = addr.lowercase()
        for(net in Network.entries) {
            if(lower.startsWith(net.mwebHrp + "1")) {
                throw IllegalArgumentException("MWEB destinations aren't supported on mobile yet")
            }
        }
        val wrongNet = Network.entries.firstOrNull { it != network && lower.startsWith(it.bech32Hrp + "1") }
        if(wrongNet != null && !lower.startsWith(network.bech32Hrp + "1")) {
            throw IllegalArgumentException("That's a ${wrongNet.displayName} address — this wallet is on ${network.displayName}")
        }
        if(lower.startsWith(network.bech32Hrp + "1")) {
            val witness = Bech32.decodeWitness(addr)
            require(witness.hrp == network.bech32Hrp) { "Wrong address network: ${witness.hrp}" }
            return when {
                witness.version == 0 && (witness.program.size == 20 || witness.program.size == 32) ->
                    byteArrayOf(0x00, witness.program.size.toByte()) + witness.program
                witness.version in 1..16 && witness.program.size in 2..40 ->
                    byteArrayOf((0x50 + witness.version).toByte(), witness.program.size.toByte()) + witness.program
                else -> throw IllegalArgumentException("Invalid witness program length: ${witness.program.size}")
            }
        }
        val payload = Base58.decodeChecked(addr)
        require(payload.size == 21) { "Invalid address payload length: ${payload.size}" }
        val hash = payload.copyOfRange(1, 21)
        return when(payload[0].toInt() and 0xFF) {
            network.p2pkhVersion ->
                byteArrayOf(0x76.toByte(), 0xA9.toByte(), 0x14) + hash + byteArrayOf(0x88.toByte(), 0xAC.toByte())
            network.p2shVersion, network.p2shLegacyVersion ->
                byteArrayOf(0xA9.toByte(), 0x14) + hash + byteArrayOf(0x87.toByte())
            else -> throw IllegalArgumentException("Not a ${network.displayName} Litecoin address (version byte ${payload[0].toInt() and 0xFF})")
        }
    }
}
