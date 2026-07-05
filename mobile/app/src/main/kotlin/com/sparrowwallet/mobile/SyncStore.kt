package com.sparrowwallet.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials for checking a wallet's balance WITHOUT its password: the account xpub
 * (public side) or the MWEB scan key (private side) — view-only material, never a seed
 * or spend key. Background sync can see incoming funds but can never spend.
 */
@Serializable
data class SyncCredentials(
    val name: String,
    val network: String,
    val scriptType: String,               // "P2WPKH" or "MWEB"
    val xpub: String? = null,             // public wallets
    val scanSecretHex: String? = null,    // MWEB wallets
    val birthHeight: Int = 0,
    /** Baseline for "what's new" detection; empty = first run, record only. */
    val knownTxids: List<String> = emptyList(),
    val knownOutputIds: List<String> = emptyList()
)

/**
 * Stores [SyncCredentials] encrypted under a hardware-backed Android Keystore key
 * (non-exportable, this-device-only), plus the background-refresh settings.
 * Populated on wallet unlock while background refresh is enabled.
 */
class SyncStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Minutes between background checks; 0 = off. Android's floor is 15. */
    var intervalMinutes: Int
        get() = prefs.getInt("interval", 0)
        set(value) { prefs.edit().putInt("interval", value).apply() }

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(value) { prefs.edit().putBoolean("notify", value).apply() }

    private fun dir() = File(context.filesDir, "synccache").apply { mkdirs() }
    private fun fileFor(name: String) = File(dir(), safe(name) + ".bin")
    private fun safe(name: String) = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "_")

    fun save(creds: SyncCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(json.encodeToString(SyncCredentials.serializer(), creds).encodeToByteArray())
        fileFor(creds.name).writeBytes(cipher.iv + sealed)
    }

    fun load(name: String): SyncCredentials? = loadFile(fileFor(name))

    fun loadAll(): List<SyncCredentials> =
        dir().listFiles { f -> f.extension == "bin" }.orEmpty().mapNotNull { loadFile(it) }

    private fun loadFile(file: File): SyncCredentials? {
        if(!file.exists()) return null
        return try {
            val blob = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            json.decodeFromString(SyncCredentials.serializer(),
                cipher.doFinal(blob.copyOfRange(12, blob.size)).decodeToString())
        } catch(e: Exception) {
            null // key rotated or file damaged — treated as absent, re-cached on next unlock
        }
    }

    fun delete(name: String) { fileFor(name).delete() }

    fun clearAll() { dir().listFiles()?.forEach { it.delete() } }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "sparrow-sync-cache"
    }
}
