package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.secpPubKeyTweakMul
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads desktop Sparrow(-LTC) wallet files, so a wallet exported on the PC can be
 * opened on the phone. Format (sparrow JsonPersistence):
 *
 *   base64("SPRW1" + salt16)                          — 28-char header
 *   base64(ECIES-BIE1 blob)                           — rest of file
 *
 * where the ECIES private key = Argon2id(password, salt) (32 bytes), the blob is
 * "BIE1" || ephemeralPub33 || AES128-CBC ciphertext || HMAC-SHA256, and the plaintext
 * is deflate-compressed wallet JSON. Inside the JSON, the seed mnemonic is separately
 * AES256-CBC encrypted with the same Argon2 output. Unencrypted exports are plain JSON.
 *
 * NOTE: files saved by the real desktop app use [SPRW1_PARAMS] (256 MB memory-hard);
 * the desktop test fixtures use [TEST_PARAMS]. The file does not record which.
 */
object SparrowImport {
    data class ArgonParams(val iterations: Int, val memoryKb: Int, val parallelism: Int)

    val SPRW1_PARAMS = ArgonParams(10, 256 * 1024, 4)
    val TEST_PARAMS = ArgonParams(1, 1024, 1)

    class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class ImportedWallet(
        val name: String?,
        val network: String,       // lowercase drongo network id, e.g. "mainnet"
        val scriptType: String?,   // e.g. "P2WPKH"
        val derivationPath: String?,
        val masterFingerprint: String?,
        val xpub: String?,
        val seedType: String,      // "BIP39" or "ELECTRUM"
        val mnemonic: String,
        val needsPassphrase: Boolean,
        /** Desktop-recorded MWEB history, when the file carries it (Sparrow Link pushes). */
        val mwebHistory: List<MwebHistoryEntry> = emptyList(),
        /** Desktop-recorded transaction labels (txid → label), when the file carries them. */
        val txLabels: Map<String, String> = emptyMap()
    )

    private const val HEADER_LENGTH = 28
    private const val MAGIC = "SPRW1"

    /**
     * Checks whether an imported seed is usable on mobile; returns a user-facing rejection
     * reason, or null when it's fine. A matching master fingerprint is cryptographic proof
     * the seed derives the same wallet as desktop, and overrides format heuristics — desktop
     * stores Electrum-imported seeds without prefix validation, so a seed can be perfectly
     * valid (Electrum-LTC accepted it) while failing our recognizer.
     */
    fun seedProblem(imported: ImportedWallet): String? {
        if(imported.seedType != "BIP39" && imported.seedType != "ELECTRUM") {
            return "${imported.seedType} seeds aren't supported on mobile yet"
        }
        if(imported.needsPassphrase) {
            return "This seed uses a BIP39 passphrase — passphrase entry at import isn't supported yet"
        }
        val fingerprint = imported.masterFingerprint
        if(fingerprint != null) {
            val seed = if(imported.seedType == "ELECTRUM") ElectrumSeed.toSeed(imported.mnemonic, "")
                       else Bip39.seed(imported.mnemonic, "")
            val derived = HDKey.fromSeed(seed).fingerprint.toHex()
            return if(derived.equals(fingerprint, ignoreCase = true)) null
                   else "Decrypted seed doesn't match the wallet's fingerprint ($fingerprint) — the file may be corrupt"
        }
        val recognized = if(imported.seedType == "ELECTRUM") ElectrumSeed.isValid(imported.mnemonic)
                         else Bip39.isValid(imported.mnemonic)
        if(!recognized) {
            val words = imported.mnemonic.trim().split(Regex("\\s+")).size
            val prefix = ElectrumSeed.versionPrefix(imported.mnemonic) ?: "n/a"
            return "The decrypted seed isn't a recognized ${imported.seedType} mnemonic " +
                "($words words, Electrum version prefix $prefix) and the file carries no fingerprint to verify against"
        }
        return null
    }

    fun isEncryptedSparrowFile(bytes: ByteArray): Boolean = runCatching {
        val header = Base64.getDecoder().decode(bytes.copyOfRange(0, HEADER_LENGTH))
        header.size == 21 && header.copyOfRange(0, 5).decodeToString() == MAGIC
    }.getOrDefault(false)

    fun importFile(bytes: ByteArray, password: String, params: ArgonParams = SPRW1_PARAMS): ImportedWallet {
        val text = bytes.decodeToString()
        if(text.trimStart().startsWith("{")) {
            return parseWallet(text, password, params, null, null) // unencrypted (no-password) export
        }
        if(!isEncryptedSparrowFile(bytes)) {
            throw ImportException("Not a Sparrow wallet file (missing SPRW1 header)")
        }
        val salt = Base64.getDecoder().decode(bytes.copyOfRange(0, HEADER_LENGTH)).copyOfRange(5, 21)
        val key = argon2id(password.encodeToByteArray(), salt, params)
        val payload = try {
            Base64.getDecoder().decode(String(bytes, HEADER_LENGTH, bytes.size - HEADER_LENGTH, Charsets.US_ASCII).trim())
        } catch(e: IllegalArgumentException) {
            throw ImportException("Corrupt wallet file (bad base64 body)", e)
        }
        val json = inflate(eciesDecrypt(payload, key)).decodeToString()
        return parseWallet(json, password, params, key, salt)
    }

    /** Argon2id per drongo's Argon2KeyDeriver (version 1.3, 32-byte output). */
    private fun argon2id(password: ByteArray, salt: ByteArray, p: ArgonParams): ByteArray {
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

    /** Electrum-style ECIES (BIE1), the decrypt side of drongo's ECIESKeyCrypter. */
    private fun eciesDecrypt(decoded: ByteArray, privKey: ByteArray): ByteArray {
        if(decoded.size < 85) throw ImportException("Wallet file body too short")
        if(!decoded.copyOfRange(0, 4).contentEquals("BIE1".encodeToByteArray())) {
            throw ImportException("Wallet file body has invalid magic bytes")
        }
        val ephemeralPub = decoded.copyOfRange(4, 37)
        val ciphertext = decoded.copyOfRange(37, decoded.size - 32)
        val mac = decoded.copyOfRange(decoded.size - 32, decoded.size)

        val ecdh = secpPubKeyTweakMul(ephemeralPub, privKey) // compressed shared point
        val hash = MessageDigest.getInstance("SHA-512").digest(ecdh)
        val iv = hash.copyOfRange(0, 16)
        val keyE = hash.copyOfRange(16, 32)
        val keyM = hash.copyOfRange(32, 64)

        val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keyM, "HmacSHA256")) }
        if(!hmac.doFinal(decoded.copyOfRange(0, decoded.size - 32)).contentEquals(mac)) {
            throw ImportException("Wrong password for this wallet file")
        }
        return aesCbcDecrypt(ciphertext, keyE, iv)
    }

    private fun aesCbcDecrypt(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun inflate(deflated: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(deflated)).use { it.readBytes() }

    private fun parseWallet(
        json: String,
        password: String,
        params: ArgonParams,
        fileKey: ByteArray?,
        fileSalt: ByteArray?
    ): ImportedWallet {
        val wallet = try {
            Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(json).jsonObject
        } catch(e: Exception) {
            throw ImportException("Could not parse the wallet JSON", e)
        }
        val keystores = wallet["keystores"]?.jsonArray
            ?: throw ImportException("No keystores in the wallet file")
        if(keystores.size != 1) {
            throw ImportException("Only single-keystore wallets can be imported (this one has ${keystores.size})")
        }
        val keystore = keystores[0].jsonObject
        val seed = keystore["seed"]?.jsonObject
            ?: throw ImportException("This wallet has no seed (watch-only or hardware) — import it on mobile once PSBT support lands")

        val mnemonic = seed["mnemonicCode"]?.jsonArray?.joinToString(" ") { it.jsonPrimitive.content }
            ?: run {
                val enc = seed["encryptedMnemonicCode"]?.jsonObject
                    ?: throw ImportException("The seed is missing from the wallet file")
                val iv = enc["initialisationVector"]?.jsonPrimitive?.content?.hexToBytes()
                    ?: throw ImportException("Encrypted seed is missing its IV")
                val ct = enc["encryptedBytes"]?.jsonPrimitive?.content?.hexToBytes()
                    ?: throw ImportException("Encrypted seed is missing its data")
                // The seed keeps the Argon2 salt it was originally encrypted under, which can
                // differ from the file header's salt (files re-salt on save; seeds don't).
                val keySalt = enc["keySalt"]?.jsonPrimitive?.contentOrNull?.hexToBytes()
                val seedKey = when {
                    keySalt == null || (fileSalt != null && keySalt.contentEquals(fileSalt)) ->
                        fileKey ?: throw ImportException("The seed is encrypted but the file wasn't — unexpected format")
                    else -> argon2id(password.encodeToByteArray(), keySalt, params)
                }
                try {
                    aesCbcDecrypt(ct, seedKey, iv).decodeToString()
                } catch(e: Exception) {
                    throw ImportException("Could not decrypt the seed with this password", e)
                }
            }

        val mwebHistory = wallet["mwebHistory"]?.jsonArray?.mapNotNull { element ->
            val o = element.jsonObject
            val txid = o["txid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MwebHistoryEntry(
                txid = txid,
                height = o["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                time = o["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                value = o["value"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                label = o["label"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        } ?: emptyList()

        val txLabels = wallet["txLabels"]?.jsonObject?.mapNotNull { (txid, value) ->
            value.jsonPrimitive.contentOrNull?.takeIf { it.isNotEmpty() }?.let { txid to it }
        }?.toMap() ?: emptyMap()

        val keyDerivation = keystore["keyDerivation"]?.jsonObject
        val seedTypeRaw = seed["type"]?.jsonPrimitive?.contentOrNull ?: "BIP39"
        return ImportedWallet(
            name = wallet["name"]?.jsonPrimitive?.contentOrNull,
            network = (wallet["network"]?.jsonPrimitive?.contentOrNull ?: "mainnet").lowercase(),
            scriptType = wallet["scriptType"]?.jsonPrimitive?.contentOrNull,
            derivationPath = keyDerivation?.get("derivationPath")?.jsonPrimitive?.contentOrNull,
            masterFingerprint = keyDerivation?.get("masterFingerprint")?.jsonPrimitive?.contentOrNull,
            xpub = keystore["extendedPublicKey"]?.jsonPrimitive?.contentOrNull,
            seedType = if(seedTypeRaw.uppercase().contains("ELECTRUM")) "ELECTRUM" else "BIP39",
            mnemonic = mnemonic.trim(),
            needsPassphrase = seed["needsPassphrase"]?.jsonPrimitive?.booleanOrNull ?: false,
            mwebHistory = mwebHistory,
            txLabels = txLabels
        )
    }
}
