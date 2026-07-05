package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.secpPublicKeyCreate
import com.sparrowwallet.mobile.crypto.secpPubKeyTweakMul
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Writes a desktop-Sparrow-compatible encrypted wallet file (the exact inverse of
 * [SparrowImport]) so a mobile wallet can be pushed to the PC and opened there with
 * the same password. Layout and crypto mirror sparrow's JsonPersistence/drongo:
 * SPRW1 header, Argon2id file key, ECIES-BIE1 over deflated JSON, and the seed
 * separately AES-256-CBC encrypted under its own Argon2 salt.
 */
object SparrowExport {
    private val random = SecureRandom()

    fun exportFile(
        name: String,
        network: String,          // "mainnet" or "testnet"
        seedType: String,         // "BIP39" or "ELECTRUM"
        mnemonic: String,
        derivationPath: String,
        masterFingerprint: String,
        xpub: String,
        password: String,
        params: SparrowImport.ArgonParams = SparrowImport.SPRW1_PARAMS
    ): ByteArray {
        require(password.isNotEmpty()) { "Export requires the wallet password" }

        // one Argon2 key for both layers, as desktop does: the seed is encrypted with the
        // file key (keySalt = header salt), because Wallet.decrypt uses the file key directly
        val headerSalt = ByteArray(16).also { random.nextBytes(it) }
        val fileKey = argon2id(password.encodeToByteArray(), headerSalt, params)
        val seedIv = ByteArray(16).also { random.nextBytes(it) }
        val encryptedMnemonic = aesCbcEncrypt(mnemonic.encodeToByteArray(), fileKey, seedIv)

        val label = "Sparrow Mobile"
        val json = buildJsonObject {
            put("name", name)
            put("network", network.uppercase())
            put("policyType", "SINGLE")
            put("scriptType", "P2WPKH")
            putJsonObject("defaultPolicy") {
                put("name", "Default")
                putJsonObject("miniscript") { put("script", "wpkh(${label.replace(" ", "")})") }
            }
            putJsonArray("keystores") {
                add(buildJsonObject {
                    put("label", label)
                    put("source", "SW_SEED")
                    put("walletModel", "SPARROW")
                    putJsonObject("keyDerivation") {
                        put("masterFingerprint", masterFingerprint)
                        put("derivationPath", derivationPath)
                    }
                    put("extendedPublicKey", xpub)
                    putJsonObject("seed") {
                        put("type", seedType)
                        putJsonObject("encryptedMnemonicCode") {
                            put("initialisationVector", seedIv.toHex())
                            put("encryptedBytes", encryptedMnemonic.toHex())
                            put("keySalt", headerSalt.toHex())
                            putJsonObject("encryptionType") {
                                put("deriver", "ARGON2")
                                put("crypter", "AES_CBC_PKCS7")
                            }
                        }
                        put("needsPassphrase", false)
                        put("creationTimeSeconds", 0L)
                    }
                })
            }
        }.toString()

        // ECIES to the file key's public point over the deflated JSON
        val receiverPub = secpPublicKeyCreate(fileKey)
        val blob = eciesEncrypt(deflate(json.encodeToByteArray()), receiverPub)

        val header = Base64.getEncoder().encode("SPRW1".encodeToByteArray() + headerSalt)
        check(header.size == 28) { "Header must encode to 28 bytes" }
        return header + blob
    }

    private fun argon2id(password: ByteArray, salt: ByteArray, p: SparrowImport.ArgonParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withIterations(p.iterations)
                .withMemoryAsKB(p.memoryKb)
                .withParallelism(p.parallelism)
                .build()
        )
        val out = ByteArray(32)
        generator.generateBytes(password, out)
        return out
    }

    /** Electrum-style ECIES (BIE1) encrypt to the given compressed public key. */
    private fun eciesEncrypt(plain: ByteArray, receiverPub: ByteArray): ByteArray {
        val ephemeralPriv = ByteArray(32).also { random.nextBytes(it) }
        val ephemeralPub = secpPublicKeyCreate(ephemeralPriv)
        val ecdh = secpPubKeyTweakMul(receiverPub, ephemeralPriv)
        val hash = MessageDigest.getInstance("SHA-512").digest(ecdh)
        val iv = hash.copyOfRange(0, 16)
        val keyE = hash.copyOfRange(16, 32)
        val keyM = hash.copyOfRange(32, 64)

        val ciphertext = aesCbcEncrypt(plain, keyE, iv)
        val payload = "BIE1".encodeToByteArray() + ephemeralPub + ciphertext
        val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyM, "HmacSHA256")) }
        return Base64.getEncoder().encode(payload + hmac.doFinal(payload))
    }

    private fun aesCbcEncrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plain)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
