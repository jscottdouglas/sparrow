package com.sparrowwallet.mobile.crypto

/**
 * secp256k1 operations (expect/actual).
 *
 * Signatures deliberately mirror the secp256k1-kmp (ACINQ) API subset we use, so
 * production targets can delegate straight to libsecp256k1 while the JVM parity
 * target uses a pure-BigInteger implementation.
 */

/** Compressed (33-byte) public key for a 32-byte private key. */
expect fun secpPublicKeyCreate(privKey: ByteArray): ByteArray

/** (privKey + tweak) mod n, as 32 bytes. Throws if the result is zero or inputs invalid. */
expect fun secpPrivKeyTweakAdd(privKey: ByteArray, tweak: ByteArray): ByteArray

/** pubKey + G*tweak, compressed. Used by BIP32 CKDpub and MWEB stealth derivation. */
expect fun secpPubKeyTweakAdd(pubKey: ByteArray, tweak: ByteArray): ByteArray

/** pubKey * scalar (EC point multiplication), compressed. */
expect fun secpPubKeyTweakMul(pubKey: ByteArray, scalar: ByteArray): ByteArray

/**
 * ECDSA signature over a 32-byte message hash: RFC6979 deterministic nonce with
 * low-R grinding (§3.6 counter as 32-byte LE additional data) and low-S
 * normalization — byte-identical to drongo/Bitcoin Core. Returns compact 64-byte
 * r||s. Native actuals must grind via libsecp256k1's ndata parameter.
 */
expect fun secpSign(messageHash: ByteArray, privKey: ByteArray): ByteArray
