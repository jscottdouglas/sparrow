package com.sparrowwallet.mobile.crypto

import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(key, "HmacSHA512"))
    return mac.doFinal(data)
}

actual fun pbkdf2HmacSha512(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        .generateSecret(PBEKeySpec(password, salt, iterations, keyLengthBytes * 8))
        .encoded
}

actual fun nfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)

actual fun secureRandomBytes(length: Int): ByteArray =
    ByteArray(length).also { java.security.SecureRandom().nextBytes(it) }

actual fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
        javax.crypto.spec.GCMParameterSpec(128, iv))
    cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}

actual fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
        javax.crypto.spec.GCMParameterSpec(128, iv))
    cipher.updateAAD(aad)
    return cipher.doFinal(ciphertext)
}

actual fun pbkdf2HmacSha256(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
    return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password, salt, iterations, keyLengthBytes * 8))
        .encoded
}
