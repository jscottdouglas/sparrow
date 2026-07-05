package com.sparrowwallet.mobile.crypto

/**
 * Platform crypto primitives (expect/actual). The JVM actuals use JDK providers;
 * Android/iOS actuals will use platform or native providers as targets come online.
 */
expect fun sha256(data: ByteArray): ByteArray

expect fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray

expect fun pbkdf2HmacSha512(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray

/** Unicode NFKD normalization (required by BIP39). */
expect fun nfkd(text: String): String

/** Cryptographically secure random bytes (platform CSPRNG). */
expect fun secureRandomBytes(length: Int): ByteArray

/** AES-256-GCM encrypt; returns ciphertext with the 16-byte auth tag appended. */
expect fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

/** AES-256-GCM decrypt; throws if the auth tag fails (wrong key or tampered data). */
expect fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

/** PBKDF2-HMAC-SHA256 (for password→key derivation at rest). */
expect fun pbkdf2HmacSha256(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray

/** RIPEMD160(SHA256(data)) — Bitcoin/Litecoin HASH160. */
fun hash160(data: ByteArray): ByteArray = Ripemd160.digest(sha256(data))

/** SHA256(SHA256(data)) — used for base58check checksums. */
fun sha256d(data: ByteArray): ByteArray = sha256(sha256(data))
