package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/** Known-answer tests for the primitives, independent of the golden vectors. */
class CryptoSelfTest {
    @Test
    fun sha256KnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex())
    }

    @Test
    fun ripemd160KnownVectors() {
        assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", Ripemd160.digest(ByteArray(0)).toHex())
        assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", Ripemd160.digest("abc".encodeToByteArray()).toHex())
        assertEquals("12a053384a9c0c88e405a06c27dcf49ada62eb2b",
            Ripemd160.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHex())
    }

    @Test
    fun secpGeneratorSanity() {
        // pubkey of privkey 1 is the generator point G, compressed
        val one = ByteArray(32).also { it[31] = 1 }
        assertEquals("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            secpPublicKeyCreate(one).toHex())
    }

    @Test
    fun blake3EmptyInputVector() {
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            Blake3.hash(ByteArray(0)).toHex())
    }

    /** BIP173 reference: P2WPKH of the generator-point pubkey on Bitcoin mainnet. */
    @Test
    fun bech32Bip173Vector() {
        val pubKey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".hexToBytes()
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", Addresses.p2wpkh(pubKey, "bc"))
        assertEquals("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH", Addresses.p2pkh(pubKey, 0))
    }

    /** Official BIP32 test vector 1 — exercises HMAC, tweak-add, serialization, base58check. */
    @Test
    fun bip32ReferenceVector1() {
        val master = HDKey.fromSeed("000102030405060708090a0b0c0d0e0f".hexToBytes())
        assertEquals("xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi", master.xprv())
        assertEquals("xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8", master.xpub())

        val m0h = master.derivePath("m/0'")
        assertEquals("xpub68Gmy5EdvgibQVfPdqkBBCHxA5htiqg55crXYuXoQRKfDBFA1WEjWgP6LHhwBZeNK1VTsfTFUHCdrfp1bgwQ9xv5ski8PX9rL2dZXvgGDnw", m0h.xpub())

        // non-hardened step exercises parent-pubkey derivation
        val m0h1 = master.derivePath("m/0'/1")
        assertEquals("xpub6ASuArnXKPbfEwhqN6e3mwBcDTgzisQN1wXN9BJcM47sSikHjJf3UFHKkNAWbWMiGj7Wf5uMash7SyYq527Hqck2AxYysAA7xmALppuCkwQ", m0h1.xpub())
    }
}
