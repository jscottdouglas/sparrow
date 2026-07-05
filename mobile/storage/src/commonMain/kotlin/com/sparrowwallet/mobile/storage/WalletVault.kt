package com.sparrowwallet.mobile.storage

import com.sparrowwallet.mobile.crypto.aesGcmDecrypt
import com.sparrowwallet.mobile.crypto.aesGcmEncrypt
import com.sparrowwallet.mobile.crypto.pbkdf2HmacSha256
import com.sparrowwallet.mobile.crypto.secureRandomBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One historical MWEB transaction as recorded by desktop (net wallet delta). MWEB prunes
 * spent outputs from the chain, so past activity can't be rescanned — it travels with the
 * wallet and is stored inside the vault (amounts/labels are sensitive).
 */
@Serializable
data class MwebHistoryEntry(
    val txid: String,
    val height: Int = 0,
    /** Unix seconds; 0 if unknown. */
    val time: Long = 0,
    /** Net litoshis in(+)/out(-) of this wallet. */
    val value: Long,
    val label: String = ""
)

/** The secret material a wallet needs to fully reconstruct itself. */
@Serializable
data class VaultData(
    val mnemonic: String,
    val passphrase: String = "",
    /** "BIP39" or "ELECTRUM" — which seed algorithm produced this wallet. */
    val seedType: String = "BIP39",
    val derivationPath: String = "m/84'/2'/0'",
    /** Network id ("mainnet"/"testnet"); defaults keep vaults sealed before this field readable. */
    val network: String = "mainnet",
    /** "P2WPKH" (spendable on mobile) or "MWEB" (keys-only until the mwebd scanner lands). */
    val scriptType: String = "P2WPKH",
    /** Desktop-recorded MWEB transaction history (see [MwebHistoryEntry]). */
    val mwebHistory: List<MwebHistoryEntry> = emptyList(),
    /** Desktop-recorded transaction labels (txid → label), shown on the history list. */
    val txLabels: Map<String, String> = emptyMap()
)

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Password-encrypted wallet vault (AES-256-GCM). The blob is safe to persist to disk; the
 * GCM auth tag makes a wrong password or any tampering fail loudly on [open]. On Android the
 * blob lives in app-private storage and the password step can be gated by biometrics later.
 *
 * Blob layout (all big-endian):
 *   "SLTC" (4) | version=1 (1) | iterations (4) | salt (16) | iv (12) | ciphertext+tag
 */
object WalletVault {
    private const val MAGIC = "SLTC"
    private const val VERSION = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN = 32
    private const val DEFAULT_ITERATIONS = 200_000

    private val json = Json { ignoreUnknownKeys = true }

    fun seal(data: VaultData, password: String, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val salt = secureRandomBytes(SALT_LEN)
        val iv = secureRandomBytes(IV_LEN)
        val key = pbkdf2HmacSha256(password.toCharArray(), salt, iterations, KEY_LEN)
        val header = header(iterations, salt, iv)
        val plaintext = json.encodeToString(VaultData.serializer(), data).encodeToByteArray()
        // bind the header as AAD so version/params can't be swapped without detection
        val ciphertext = aesGcmEncrypt(key, iv, plaintext, header)
        return header + ciphertext
    }

    fun open(blob: ByteArray, password: String): VaultData {
        require(blob.size > MAGIC.length + 1 + 4 + SALT_LEN + IV_LEN) { "Vault blob too short" }
        val magic = blob.copyOfRange(0, 4).decodeToString()
        if(magic != MAGIC) throw VaultException("Not a Sparrow-LTC vault")
        val version = blob[4].toInt()
        if(version != VERSION) throw VaultException("Unsupported vault version $version")
        val iterations = readIntBE(blob, 5)
        val salt = blob.copyOfRange(9, 9 + SALT_LEN)
        val iv = blob.copyOfRange(9 + SALT_LEN, 9 + SALT_LEN + IV_LEN)
        val ciphertext = blob.copyOfRange(9 + SALT_LEN + IV_LEN, blob.size)
        val header = blob.copyOfRange(0, 9 + SALT_LEN + IV_LEN)

        val key = pbkdf2HmacSha256(password.toCharArray(), salt, iterations, KEY_LEN)
        val plaintext = try {
            aesGcmDecrypt(key, iv, ciphertext, header)
        } catch(t: Throwable) {
            throw VaultException("Wrong password or corrupted vault", t)
        }
        return json.decodeFromString(VaultData.serializer(), plaintext.decodeToString())
    }

    private fun header(iterations: Int, salt: ByteArray, iv: ByteArray): ByteArray =
        MAGIC.encodeToByteArray() + byteArrayOf(VERSION.toByte()) + intBE(iterations) + salt + iv

    private fun intBE(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
}
