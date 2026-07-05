package com.sparrowwallet.mobile

import android.content.Context
import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.Bech32
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.Psbt
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.SpendableInput
import com.sparrowwallet.mobile.crypto.TxBuilder
import com.sparrowwallet.mobile.crypto.XpubKey
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.mobile.electrum.ElectrumClient
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.WalletSnapshot
import com.sparrowwallet.mobile.storage.MwebHistoryEntry
import com.sparrowwallet.mobile.storage.SparrowExport
import com.sparrowwallet.mobile.storage.SparrowImport
import com.sparrowwallet.mobile.storage.SparrowLink
import com.sparrowwallet.mobile.storage.VaultData
import com.sparrowwallet.mobile.storage.WalletVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.math.ceil

/**
 * Ties the tested modules together into wallet operations the UI can call.
 * Wallets are named vaults under filesDir/wallets/ — <name>.vault (encrypted) plus
 * <name>.meta (plaintext JSON with non-secret display info like the network).
 */
class WalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val walletsDir = File(context.filesDir, "wallets").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // migrate the single-wallet layout from v0.3
        val legacy = File(context.filesDir, "wallet.vault")
        if(legacy.exists() && listWallets().isEmpty()) {
            legacy.copyTo(vaultFile("My Wallet"), overwrite = true)
            writeMeta("My Wallet", Network.MAINNET)
            legacy.delete()
        }
    }

    data class WalletInfo(
        val name: String,
        val network: Network,
        val scriptType: String = "P2WPKH",
        /** Name of the paired public/private counterpart wallet on this phone, if linked. */
        val linkedWallet: String? = null
    )

    private fun safeName(name: String): String =
        name.trim().map { if(it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else '_' }
            .joinToString("").take(40)

    private fun vaultFile(name: String) = File(walletsDir, "${safeName(name)}.vault")
    private fun metaFile(name: String) = File(walletsDir, "${safeName(name)}.meta")

    private fun writeMeta(name: String, network: Network, scriptType: String = "P2WPKH", linkedWallet: String? = null) {
        val json = JSONObject().put("network", network.id).put("scriptType", scriptType)
        linkedWallet?.let { json.put("linkedWallet", it) }
        metaFile(name).writeText(json.toString())
    }

    fun listWallets(): List<WalletInfo> =
        walletsDir.listFiles { f -> f.name.endsWith(".vault") }.orEmpty().sortedBy { it.name }.map { f ->
            val name = f.name.removeSuffix(".vault")
            runCatching {
                val meta = JSONObject(metaFile(name).readText())
                WalletInfo(
                    name,
                    Network.fromId(meta.getString("network")),
                    meta.optString("scriptType", "P2WPKH"),
                    meta.optString("linkedWallet", "").takeIf { it.isNotEmpty() }
                )
            }.getOrDefault(WalletInfo(name, Network.MAINNET))
        }

    fun walletInfo(name: String): WalletInfo? = listWallets().firstOrNull { it.name == safeName(name) }

    /** Records the public↔private pairing in both wallets' metadata. */
    fun linkWallets(publicName: String, mwebName: String) {
        val publicInfo = walletInfo(publicName) ?: return
        val mwebInfo = walletInfo(mwebName) ?: return
        writeMeta(publicName, publicInfo.network, publicInfo.scriptType, safeName(mwebName))
        writeMeta(mwebName, mwebInfo.network, mwebInfo.scriptType, safeName(publicName))
    }

    fun hasWallets(): Boolean = listWallets().isNotEmpty()

    fun deleteWallet(name: String) {
        vaultFile(name).delete()
        metaFile(name).delete()
    }

    /** Detects BIP39 vs Electrum seed and returns the type + default derivation, or null if invalid. */
    fun classifySeed(mnemonic: String, network: Network): Pair<String, String>? = when {
        Bip39.isValid(mnemonic) -> "BIP39" to network.defaultDerivationPath
        ElectrumSeed.isValid(mnemonic) -> "ELECTRUM" to ElectrumSeed.defaultDerivation(mnemonic)
        else -> null
    }

    fun createNewMnemonic(): String =
        Bip39.entropyToMnemonic(ByteArray(16).also { SecureRandom().nextBytes(it) })

    /**
     * Seals a new named wallet; returns a user-facing error, or null on success.
     * [seedTypeOverride] skips seed re-classification — used by imports, where the desktop
     * file's declared type is authoritative (and may be valid without being recognizable,
     * e.g. Electrum seeds with uncommon version prefixes).
     */
    fun saveWallet(name: String, mnemonic: String, passphrase: String, password: String, network: Network,
                   derivationOverride: String? = null, scriptType: String = "P2WPKH",
                   seedTypeOverride: String? = null,
                   mwebHistory: List<MwebHistoryEntry> = emptyList(),
                   txLabels: Map<String, String> = emptyMap()): String? {
        val clean = safeName(name)
        if(clean.isEmpty()) return "Give the wallet a name"
        if(vaultFile(clean).exists()) return "A wallet named \"$clean\" already exists"
        val classified = classifySeed(mnemonic.trim(), network)
        val type = seedTypeOverride ?: classified?.first ?: return "Not a valid BIP39 or Electrum seed"
        val derivation = derivationOverride ?: classified?.second ?: return "Not a valid BIP39 or Electrum seed"
        val data = VaultData(mnemonic.trim(), passphrase, type, derivation, network.id, scriptType, mwebHistory, txLabels)
        vaultFile(clean).writeBytes(WalletVault.seal(data, password))
        writeMeta(clean, network, scriptType)
        return null
    }

    /**
     * Imports a desktop Sparrow wallet file (encrypted or plain JSON export), re-sealing
     * it as a mobile vault under [devicePassword]. Returns the created wallet's name;
     * throws with a user-facing message on any problem. Argon2 here is memory-hard
     * (256 MB) by design — run off the main thread and expect a few seconds.
     */
    suspend fun importSparrowWallet(
        fileBytes: ByteArray,
        filePassword: String,
        preferredName: String?,
        devicePassword: String
    ): String = withContext(Dispatchers.Default) {
        val imported = SparrowImport.importFile(fileBytes, filePassword)
        val network = validateImported(imported)
        saveImported(imported, network, preferredName, devicePassword)
    }

    /**
     * Imports a public wallet together with its linked private (MWEB) wallet, as pushed by
     * desktop's Link pair transfer. Both files are decrypted and validated before either is
     * saved, then the pairing is recorded in both wallets' metadata.
     */
    suspend fun importSparrowPair(
        payload: SparrowLink.ReceivedPayload,
        filePassword: String,
        linkedFilePassword: String,
        preferredName: String?,
        devicePassword: String
    ): String = withContext(Dispatchers.Default) {
        val linked = payload.linked
            ?: return@withContext importSparrowWallet(payload.wallet.file, filePassword, preferredName, devicePassword)

        val importedPublic = SparrowImport.importFile(payload.wallet.file, filePassword)
        val publicNetwork = try {
            validateImported(importedPublic)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("\"${payload.wallet.name}\": ${e.message}", e)
        }
        val importedMweb = try {
            SparrowImport.importFile(linked.file, linkedFilePassword)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("Linked private wallet \"${linked.name}\": ${e.message}", e)
        }
        val mwebNetwork = try {
            validateImported(importedMweb)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("Linked private wallet \"${linked.name}\": ${e.message}", e)
        }

        val publicName = saveImported(importedPublic, publicNetwork, preferredName, devicePassword)
        val mwebName = try {
            saveImported(importedMweb, mwebNetwork, linked.name, devicePassword)
        } catch(e: Exception) {
            throw SparrowImport.ImportException(
                "\"$publicName\" was imported, but the linked private wallet failed: ${e.message}", e)
        }
        linkWallets(publicName, mwebName)
        publicName
    }

    private fun validateImported(imported: SparrowImport.ImportedWallet): Network {
        val network = when(imported.network) {
            "mainnet" -> Network.MAINNET
            "testnet" -> Network.TESTNET
            else -> throw SparrowImport.ImportException("Unsupported network in wallet file: ${imported.network}")
        }
        if(imported.scriptType != null && imported.scriptType != "P2WPKH" && imported.scriptType != "MWEB") {
            throw SparrowImport.ImportException("Mobile currently supports Native Segwit (P2WPKH) and MWEB wallets — this one is ${imported.scriptType}")
        }
        SparrowImport.seedProblem(imported)?.let { throw SparrowImport.ImportException(it) }
        return network
    }

    private fun saveImported(imported: SparrowImport.ImportedWallet, network: Network, preferredName: String?, devicePassword: String): String {
        val name = preferredName?.takeIf { it.isNotBlank() } ?: imported.name ?: "Imported wallet"
        val derivation = imported.derivationPath
            ?: if(imported.seedType == "ELECTRUM") ElectrumSeed.defaultDerivation(imported.mnemonic)
               else network.defaultDerivationPath
        saveWallet(name, imported.mnemonic, "", devicePassword, network, derivation,
            imported.scriptType ?: "P2WPKH", imported.seedType, imported.mwebHistory, imported.txLabels)
            ?.let { throw SparrowImport.ImportException(it) }
        return safeName(name)
    }

    /** Opens a wallet's vault; throws VaultException on wrong password. */
    fun unlock(name: String, password: String): VaultData =
        WalletVault.open(vaultFile(name).readBytes(), password)
            .also { runCatching { cacheSyncCredentials(name, it) } }

    val syncStore by lazy { SyncStore(appContext) }

    /**
     * Caches view-only sync credentials (xpub / MWEB scan key — never seeds or spend
     * keys) under the Android Keystore, so background refresh can check for new funds
     * without the wallet password. Only while background refresh is enabled.
     */
    private fun cacheSyncCredentials(name: String, data: VaultData) {
        if(syncStore.intervalMinutes <= 0) return
        val prior = syncStore.load(name)
        val creds = if(data.scriptType == "MWEB") SyncCredentials(
            name = name, network = data.network, scriptType = "MWEB",
            scanSecretHex = mwebScanSecret(data).toHex(),
            birthHeight = mwebBirthHeight(data),
            knownOutputIds = prior?.knownOutputIds ?: emptyList()
        ) else SyncCredentials(
            name = name, network = data.network, scriptType = data.scriptType,
            xpub = accountKey(data).xpub(network(data).xpubHeader),
            knownTxids = prior?.knownTxids ?: emptyList()
        )
        syncStore.save(creds)
    }

    /** Balance/history check from a cached xpub alone — the background-sync read path. */
    suspend fun scanXpub(network: Network, xpub: String): WalletSnapshot =
        withServer(network) { WalletScanner(it).sync(XpubKey.fromXpub(xpub)) }

    /** MWEB coin check from a cached scan key alone — the background-sync read path. */
    suspend fun mwebSnapshotFromScanKey(network: Network, scanSecretHex: String, birthHeight: Int): MwebdService.MwebSnapshot {
        val s = serverSettings(network)
        val service = MwebdService.get(appContext, network, if(s.useProxy) "${s.proxyHost}:${s.proxyPort}" else null)
        return service.snapshot(scanSecretHex.hexToBytes(), birthHeight)
    }

    /**
     * Re-seals an unlocked wallet with an updated label for [txid] (blank deletes).
     * Labels are wallet data, so they live inside the encrypted vault — the session
     * password from unlock re-seals it.
     */
    fun updateLabel(name: String, data: VaultData, password: String, txid: String, label: String): VaultData {
        val clean = label.trim()
        val labels = data.txLabels.toMutableMap()
        if(clean.isEmpty()) labels.remove(txid) else labels[txid] = clean
        val history = data.mwebHistory.map { if(it.txid == txid) it.copy(label = clean) else it }
        val updated = data.copy(txLabels = labels, mwebHistory = history)
        vaultFile(name).writeBytes(WalletVault.seal(updated, password))
        return updated
    }

    /** Bulk label adopt + single re-seal — used by desktop label sync. */
    private fun updateLabels(name: String, data: VaultData, password: String, labels: Map<String, String>): VaultData {
        if(labels.isEmpty()) return data
        val merged = data.txLabels.toMutableMap()
        for((txid, label) in labels) if(label.isNotBlank()) merged[txid] = label.trim()
        val history = data.mwebHistory.map { e -> labels[e.txid]?.let { e.copy(label = it.trim()) } ?: e }
        val updated = data.copy(txLabels = merged, mwebHistory = history)
        vaultFile(name).writeBytes(WalletVault.seal(updated, password))
        return updated
    }

    /** Every label this wallet knows: the vault map plus imported MWEB entry labels. */
    private fun allLabels(data: VaultData): Map<String, String> = buildMap {
        for(e in data.mwebHistory) if(e.label.isNotEmpty()) put(e.txid, e.label)
        putAll(data.txLabels)
    }

    class LabelSyncOutcome(val summary: String, val updated: Map<String, VaultData>)

    /**
     * Phone side of a desktop label sync: matches wallets by master fingerprint, adopts
     * the labels the phone is missing (re-sealing each vault once), sends the desktop
     * what it's missing. Nothing is overwritten — differing labels are kept and counted.
     */
    suspend fun linkSyncLabels(
        pairing: SparrowLink.Pairing,
        wallets: List<Triple<String, VaultData, String>>
    ): LabelSyncOutcome = withContext(Dispatchers.IO) {
        val local = wallets.map { (name, data, _) ->
            SparrowLink.WalletLabels(masterKey(data).fingerprint.toHex(), name, allLabels(data))
        }
        val result = SparrowLink.syncLabels(pairing, local)
        val updated = HashMap<String, VaultData>()
        var applied = 0
        for((name, data, password) in wallets) {
            val fp = masterKey(data).fingerprint.toHex().lowercase()
            result.toPhone[fp]?.let { newLabels ->
                updated[name] = updateLabels(name, data, password, newLabels)
                applied += newLabels.size
            }
        }
        val summary = if(result.matchedWallets.isEmpty()) {
            "The desktop wallet doesn't match any wallet open on this phone (different seed?)"
        } else {
            "Synced ${result.matchedWallets.joinToString()} — $applied label(s) to phone, " +
            "${result.sentToDesktop} to desktop" +
            if(result.conflicts > 0) ". ${result.conflicts} differed and were left unchanged on both." else "."
        }
        LabelSyncOutcome(summary, updated)
    }

    fun network(data: VaultData): Network = Network.fromId(data.network)

    fun masterKey(data: VaultData): HDKey {
        val seed = if(data.seedType == "ELECTRUM") ElectrumSeed.toSeed(data.mnemonic, data.passphrase)
                   else Bip39.seed(data.mnemonic, data.passphrase)
        return HDKey.fromSeed(seed)
    }

    fun accountKey(data: VaultData): HDKey = masterKey(data).derivePath(data.derivationPath)

    fun receiveAddress(account: HDKey, index: Int, network: Network): String =
        Addresses.p2wpkh(account.deriveChild(0).deriveChild(index.toLong()).pubKey, network.bech32Hrp)

    fun accountXpub(data: VaultData): String = accountKey(data).xpub(network(data).xpubHeader)

    /** MWEB keys/address, derived exactly like desktop drongo's Keystore (m/1000', scan 0', spend 1'). */
    data class MwebInfo(val firstAddress: String, val scanSecretHex: String, val spendPubKeyHex: String)

    // dedicated MWEB wallets use their own account path; other script types pair with
    // the same-seed m/1000' MWEB account, matching desktop's child-wallet convention
    private fun mwebAccountPath(data: VaultData) = if(data.scriptType == "MWEB") data.derivationPath else "m/1000'"

    fun mwebInfo(data: VaultData): MwebInfo {
        val master = masterKey(data)
        val accountPath = mwebAccountPath(data)
        val scanSecret = master.derivePath("$accountPath/0'").privKey
        val spendPubKey = master.derivePath("$accountPath/1'").pubKey
        val first = Addresses.mwebStealth(
            scanSecret, spendPubKey, Addresses.mwebAddressIndex(change = false, nodeIndex = 0), network(data).mwebHrp
        )
        return MwebInfo(first, scanSecret.toHex(), spendPubKey.toHex())
    }

    // ---- Electrum server settings (per network) ----

    data class ServerSettings(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val allowSelfSigned: Boolean,
        val useProxy: Boolean,
        val proxyHost: String,
        val proxyPort: Int
    )

    private fun defaultServer(network: Network) = when(network) {
        Network.MAINNET -> ServerSettings("ltc.rentonisk.com", 50002, true, false, false, "127.0.0.1", 9050)
        Network.TESTNET -> ServerSettings("electrum.ltc.xurious.com", 51002, true, true, false, "127.0.0.1", 9050)
    }

    fun serverSettings(network: Network): ServerSettings {
        val d = defaultServer(network)
        val p = "server.${network.id}"
        return ServerSettings(
            prefs.getString("$p.host", d.host)!!,
            prefs.getInt("$p.port", d.port),
            prefs.getBoolean("$p.tls", d.useTls),
            prefs.getBoolean("$p.selfSigned", d.allowSelfSigned),
            prefs.getBoolean("$p.useProxy", d.useProxy),
            prefs.getString("$p.proxyHost", d.proxyHost)!!,
            prefs.getInt("$p.proxyPort", d.proxyPort)
        )
    }

    fun saveServerSettings(network: Network, s: ServerSettings) {
        val p = "server.${network.id}"
        prefs.edit()
            .putString("$p.host", s.host).putInt("$p.port", s.port)
            .putBoolean("$p.tls", s.useTls).putBoolean("$p.selfSigned", s.allowSelfSigned)
            .putBoolean("$p.useProxy", s.useProxy)
            .putString("$p.proxyHost", s.proxyHost).putInt("$p.proxyPort", s.proxyPort)
            .apply()
    }

    private fun newClient(s: ServerSettings) = ElectrumClient(
        s.host, s.port, s.useTls,
        if(s.useProxy) SocksProxy(s.proxyHost, s.proxyPort) else null,
        s.allowSelfSigned
    )

    /** Connects with the given settings and returns [serverSoftware, protocolVersion]. */
    suspend fun testConnection(s: ServerSettings): List<String> = withContext(Dispatchers.IO) {
        val client = newClient(s)
        try {
            client.connect()
            client.serverVersion()
        } finally {
            client.close()
        }
    }

    private suspend fun <T> withServer(network: Network, block: suspend (ElectrumClient) -> T): T =
        withContext(Dispatchers.IO) {
            val client = newClient(serverSettings(network))
            try {
                client.connect()
                client.serverVersion()
                block(client)
            } finally {
                client.close()
            }
        }

    /** Full wallet sync: balance, history with deltas, first unused receive index. */
    // ---- last-known snapshot cache: screens render these instantly; auto-refresh is
    // skipped while a snapshot is fresh so switching screens never re-syncs ----
    private val snapshotCache = java.util.concurrent.ConcurrentHashMap<VaultData, WalletSnapshot>()
    private val mwebSnapshotCache = java.util.concurrent.ConcurrentHashMap<VaultData, MwebdService.MwebSnapshot>()
    private val snapshotAt = java.util.concurrent.ConcurrentHashMap<VaultData, Long>()
    private val mwebSnapshotAt = java.util.concurrent.ConcurrentHashMap<VaultData, Long>()

    fun cachedSnapshot(data: VaultData): WalletSnapshot? = snapshotCache[data]
    fun cachedMwebSnapshot(data: VaultData): MwebdService.MwebSnapshot? = mwebSnapshotCache[data]

    /** Forces the next Home visit to refresh — called after a successful send. */
    fun invalidateSnapshots(vararg wallets: VaultData?) {
        for(data in wallets.filterNotNull()) {
            snapshotAt.remove(data)
            mwebSnapshotAt.remove(data)
        }
    }

    fun snapshotFresh(data: VaultData, maxAgeMs: Long = 60_000): Boolean =
        snapshotCache[data] != null &&
            snapshotAt[data]?.let { System.currentTimeMillis() - it < maxAgeMs } == true

    fun mwebSnapshotFresh(data: VaultData, maxAgeMs: Long = 60_000): Boolean =
        mwebSnapshotCache[data] != null &&
            mwebSnapshotAt[data]?.let { System.currentTimeMillis() - it < maxAgeMs } == true

    suspend fun syncWallet(data: VaultData, account: HDKey): WalletSnapshot =
        withServer(network(data)) { WalletScanner(it).sync(account) }
            .also { snapshotCache[data] = it; snapshotAt[data] = System.currentTimeMillis() }

    // ---- Sending ----

    /** A signed-but-not-broadcast spend, ready for the user to confirm. */
    data class SendPreview(
        val toAddress: String,
        val amount: Long,       // litoshis actually sent to the destination
        val fee: Long,          // litoshis
        val feeRatePerVb: Long, // litoshis per vbyte used
        val txid: String,
        val txHex: String
    )

    /** Returns a user-facing error for an unusable destination address, or null if it's fine. */
    fun validateAddress(address: String, network: Network): String? =
        try {
            Addresses.toScriptPubKey(address, network)
            null
        } catch(e: Exception) {
            e.message ?: "Invalid address"
        }

    /**
     * Scans utxos, selects coins, and builds a fully signed transaction — without
     * broadcasting. The UI shows the resulting fee/amount and only then calls [broadcast].
     * With [sendMax] the whole spendable balance minus fee goes to the destination.
     */
    suspend fun prepareSend(data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean): SendPreview {
        val net = network(data)
        if(isMwebAddress(toAddress, net)) {
            return preparePegIn(data, account, toAddress, amountLitoshis, sendMax)
        }
        val destScript = Addresses.toScriptPubKey(toAddress, net)
        return withServer(net) { client ->
            val feeRate = feeRatePerVb(client.estimateFee(2))
            val (candidates, changeScript) = collectSpendables(client, account)
            val built = TxBuilder.build(candidates, destScript, amountLitoshis, feeRate, changeScript, sendMax)
            SendPreview(toAddress, built.amount, built.fee, feeRate, built.tx.txid(), built.hex())
        }
    }

    /** Coins must have this many confirmations before the wallet will spend them. */
    val minConfirmations = 6

    private fun confirmedEnough(tip: Long, height: Int): Boolean =
        height in 1..(tip - (minConfirmations - 1))

    /** Spendable balance of an MWEB snapshot under the confirmation rule. */
    fun mwebSpendableBalance(snap: MwebdService.MwebSnapshot): Long =
        snap.utxos.filter { !it.spent && confirmedEnough(snap.status.blockHeaderHeight.toLong(), it.height) }
            .sumOf { it.value }

    private suspend fun collectSpendables(client: ElectrumClient, account: HDKey): Pair<List<SpendableInput>, ByteArray> {
        val tip = client.tipHeight()
        val scan = WalletScanner(client).p2wpkhUtxos(account)
        val candidates = scan.utxos
            .filter { confirmedEnough(tip, it.utxo.height) }
            .map {
                SpendableInput(
                    it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value,
                    account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong())
                )
            }
        val changePub = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong()).pubKey
        return candidates to Scripts.p2wpkhOutput(changePub)
    }

    class UnsignedSendPreview(
        val toAddress: String,
        val amount: Long,
        val fee: Long,
        val feeRatePerVb: Long,
        val txid: String, // P2WPKH txid excludes witnesses — final txid matches
        val psbtBase64: String,
        /** Peg-ins: the MWEB extension blob stays here and is re-attached on finalize. */
        val mwebBlob: ByteArray? = null
    )

    /**
     * Builds the same transaction as [prepareSend] but leaves it UNSIGNED, packaged as
     * a BIP174 PSBT for an external signer (desktop Sparrow, hardware wallet). Public
     * destinations only — peg-ins are signed inside the MWEB scanner and can't be
     * externally signed anywhere yet.
     */
    suspend fun prepareSendPsbt(
        data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean
    ): UnsignedSendPreview {
        val net = network(data)
        if(isMwebAddress(toAddress, net)) {
            return preparePegInPsbt(data, account, toAddress, amountLitoshis, sendMax)
        }
        val destScript = Addresses.toScriptPubKey(toAddress, net)
        return withServer(net) { client ->
            val feeRate = feeRatePerVb(client.estimateFee(2))
            val tip = client.tipHeight()
            val scan = WalletScanner(client).p2wpkhUtxos(account)
            val accountPath = parsePathChildren(data.derivationPath)
            val pathByKey = HashMap<HDKey, List<Long>>()
            val candidates = scan.utxos
                .filter { confirmedEnough(tip, it.utxo.height) }
                .map {
                    val key = account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong())
                    pathByKey[key] = accountPath + listOf(it.chain.toLong(), it.index.toLong())
                    SpendableInput(it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value, key)
                }
            val changeKey = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong())
            val unsigned = TxBuilder.buildUnsigned(
                candidates, destScript, amountLitoshis, feeRate,
                Scripts.p2wpkhOutput(changeKey.pubKey), sendMax
            )
            val psbt = Psbt.create(
                unsigned, masterKey(data).fingerprint,
                pathFor = { pathByKey.getValue(it.key) },
                changeIndex = if(unsigned.change > 0) 1 else null,
                changePubKey = changeKey.pubKey,
                changePath = accountPath + listOf(1L, scan.nextChangeIndex.toLong())
            )
            UnsignedSendPreview(toAddress, unsigned.amount, unsigned.fee, feeRate,
                unsigned.tx.txid(), Psbt.toBase64(psbt))
        }
    }

    /** Peg-in as a PSBT: mwebd builds/signs the MWEB side; the public inputs go out for
     *  external signing; the blob stays in [UnsignedSendPreview.mwebBlob] until finalize. */
    private suspend fun preparePegInPsbt(
        data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean
    ): UnsignedSendPreview {
        val net = network(data)
        val program = Bech32.decodeWitness(toAddress.trim()).program
        require(program.size == 66) { "Not a valid MWEB address" }
        val feeRate = withServer(net) { feeRatePerVb(it.estimateFee(2)) }
        val service = mwebd(data)

        val (scan, tip) = withServer(net) { WalletScanner(it).p2wpkhUtxos(account) to it.tipHeight() }
        val accountPath = parsePathChildren(data.derivationPath)
        val pathByKey = HashMap<HDKey, List<Long>>()
        val candidates = scan.utxos
            .filter { confirmedEnough(tip, it.utxo.height) }
            .map {
                val key = account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong())
                pathByKey[key] = accountPath + listOf(it.chain.toLong(), it.index.toLong())
                SpendableInput(it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value, key)
            }
        val changeKey = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong())
        val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)

        val mwebFeeEst = MwebdService.estimateMwebFee(
            listOf(MwebdService.OutSpec(0, program)), feeRate * 1000, includeChange = false)
        val probeScriptSize = service.createPegIn(
            mwebScanSecret(data), mwebSpendSecret(data), program, TxBuilder.DUST_LIMIT, feeRate * 1000
        ).peginScript.size

        var effectiveRate = feeRate
        var attempt = 0
        while(true) {
            attempt++
            val amount = if(sendMax) {
                val total = candidates.sumOf { it.value }
                val publicFee = TxBuilder.estimateVsize(candidates.size, listOf(probeScriptSize)) * effectiveRate
                val sweep = total - publicFee - mwebFeeEst
                require(sweep >= TxBuilder.DUST_LIMIT) { "Balance is too small to cover the peg-in fees" }
                sweep
            } else {
                amountLitoshis
            }
            val template = service.createPegIn(
                mwebScanSecret(data), mwebSpendSecret(data), program, amount, feeRate * 1000)
            val unsigned = TxBuilder.buildUnsigned(
                candidates, template.peginScript, template.peginValue, effectiveRate, changeScript, sendMax)
            if(sendMax) {
                require(unsigned.tx.outputs[0].value == template.peginValue) {
                    "Peg-in max calculation mismatch — enter a specific amount instead"
                }
            }
            val mwebFee = template.peginValue - amount
            val canonicalVsize = TxBuilder.estimateVsize(
                unsigned.inputs.size, unsigned.tx.outputs.map { it.script.size })
            val requiredFee = (canonicalVsize + 41L) * feeRate + mwebFee
            if(unsigned.fee + mwebFee >= requiredFee || attempt >= 4) {
                val psbt = Psbt.create(
                    unsigned, masterKey(data).fingerprint,
                    pathFor = { pathByKey.getValue(it.key) },
                    changeIndex = if(unsigned.change > 0) 1 else null,
                    changePubKey = changeKey.pubKey,
                    changePath = accountPath + listOf(1L, scan.nextChangeIndex.toLong())
                )
                return UnsignedSendPreview(toAddress, amount, unsigned.fee + mwebFee, feeRate,
                    unsigned.tx.txid(), Psbt.toBase64(psbt), template.mwebBlob)
            }
            effectiveRate += (requiredFee - (unsigned.fee + mwebFee) + canonicalVsize - 1) / canonicalVsize
        }
    }

    /**
     * Signs whatever inputs of a PSBT belong to this wallet (matched by master
     * fingerprint and derivation path) — the phone acting as the external signer
     * for a coordinator like desktop Sparrow or a watch-only wallet.
     */
    fun signPsbt(data: VaultData, psbtBase64: String): String {
        val parsed = Psbt.parse(Psbt.fromBase64(psbtBase64))
        val master = masterKey(data)
        val fingerprint = master.fingerprint.toHex()
        var signedCount = 0
        val signed = Psbt.sign(parsed) { pubHex ->
            parsed.inputs.firstNotNullOfOrNull { it.derivations[pubHex] }?.let { (fp, path) ->
                if(!fp.toHex().equals(fingerprint, ignoreCase = true)) return@let null
                var key = master
                for(child in path) key = key.deriveChild(child)
                key.takeIf { it.pubKey.toHex() == pubHex }?.also { signedCount++ }
            }
        }
        require(signedCount > 0) { "This PSBT has no inputs belonging to this wallet" }
        return Psbt.toBase64(signed)
    }

    private fun parsePathChildren(path: String): List<Long> =
        path.trim().split("/").drop(1).filter { it.isNotEmpty() }.map {
            val hardened = it.endsWith("'") || it.endsWith("h") || it.endsWith("H")
            (if(hardened) it.dropLast(1) else it).toLong() + if(hardened) 0x80000000L else 0L
        }

    /** Finalizes an externally signed PSBT (base64) and broadcasts it; returns the txid.
     *  [mwebBlob] re-attaches a peg-in's MWEB extension that never left the phone. */
    suspend fun finalizeAndBroadcast(network: Network, psbtBase64: String, mwebBlob: ByteArray? = null): String {
        val tx = Psbt.finalize(Psbt.parse(Psbt.fromBase64(psbtBase64)))
        tx.mwebExtension = mwebBlob
        return broadcast(network, tx.serialize().toHex())
    }

    /** Broadcasts a previously prepared transaction; returns the txid the server accepted. */
    suspend fun broadcast(network: Network, txHex: String): String =
        withServer(network) { it.broadcast(txHex) }

    /**
     * Server estimatefee is LTC/kB (-1 when unknown). Converts to litoshis/vbyte,
     * falling back to 10 lit/vB and clamping against absurd server values.
     */
    /** Current network fee rate (lit/vB) for up-front estimates on the send screens. */
    suspend fun currentFeeRate(network: Network): Long =
        withServer(network) { feeRatePerVb(it.estimateFee(2)) }

    /** Spendable utxo values (confirmation rule applied), largest first — for live estimates. */
    suspend fun utxoValues(data: VaultData, account: HDKey): List<Long> =
        withServer(network(data)) { client ->
            val tip = client.tipHeight()
            WalletScanner(client).p2wpkhUtxos(account).utxos
                .filter { confirmedEnough(tip, it.utxo.height) }
                .map { it.utxo.value }
        }.sortedDescending()

    private fun feeRatePerVb(ltcPerKb: Double): Long =
        if(ltcPerKb <= 0) 10L else ceil(ltcPerKb * 1e8 / 1000).toLong().coerceIn(1L, 10_000L)

    // ---- Sparrow Link (desktop ↔ mobile transfer) ----

    /**
     * Writes this wallet as a desktop-Sparrow-compatible encrypted file for pushing over
     * the link; the vault password doubles as the file password, so the wallet opens on
     * desktop with the same password it has here. Argon2 is memory-hard — a few seconds.
     */
    suspend fun exportSparrowWallet(name: String, data: VaultData, password: String): ByteArray =
        withContext(Dispatchers.Default) {
            val net = network(data)
            SparrowExport.exportFile(
                name = name,
                network = net.id,
                seedType = data.seedType,
                mnemonic = data.mnemonic,
                derivationPath = data.derivationPath,
                masterFingerprint = masterKey(data).fingerprint.toHex(),
                xpub = accountKey(data).xpub(net.xpubHeader),
                password = password
            )
        }

    /** Phone side of desktop-push: connects and receives the wallet (plus linked private wallet, if sent). */
    suspend fun linkReceiveWallet(pairing: SparrowLink.Pairing): SparrowLink.ReceivedPayload =
        withContext(Dispatchers.IO) { SparrowLink.receiveWallet(pairing) }

    /** Phone side of desktop-pull: connects and sends the exported wallet file. */
    suspend fun linkSendWallet(pairing: SparrowLink.Pairing, name: String, fileBytes: ByteArray) =
        withContext(Dispatchers.IO) { SparrowLink.sendWallet(pairing, name, fileBytes) }

    // ---- MWEB scanning (mwebd on-device) ----

    private suspend fun mwebd(data: VaultData): MwebdService = withContext(Dispatchers.IO) {
        val net = network(data)
        val s = serverSettings(net)
        MwebdService.get(appContext, net, if(s.useProxy) "${s.proxyHost}:${s.proxyPort}" else null)
    }

    /** Current mwebd sync heights (starts the daemon on first call; it syncs from LTC peers). */
    suspend fun mwebStatus(data: VaultData): MwebdService.SyncStatus = mwebd(data).status()

    /**
     * First block the wallet could have coins in, from the desktop-imported history —
     * lets the coin scan skip trial-decrypting everything older (with a reorg margin).
     */
    private fun mwebBirthHeight(data: VaultData): Int =
        data.mwebHistory.mapNotNull { entry -> entry.height.takeIf { it > 0 } }
            .minOrNull()?.let { (it - 1000).coerceAtLeast(0) } ?: 0

    /** Scans for this wallet's MWEB coins and their spent state, plus sync status. */
    suspend fun mwebSync(data: VaultData): MwebdService.MwebSnapshot {
        val scanSecret = masterKey(data).derivePath("${mwebAccountPath(data)}/0'").privKey
        return mwebd(data).snapshot(scanSecret, mwebBirthHeight(data))
            .also { mwebSnapshotCache[data] = it; mwebSnapshotAt[data] = System.currentTimeMillis() }
    }

    fun isMwebAddress(address: String, network: Network): Boolean =
        address.trim().lowercase().startsWith(network.mwebHrp + "1")

    /** The wallet's MWEB change address (stealth index 0) — distinguishes change from real receives. */
    fun mwebChangeAddress(data: VaultData): String =
        Addresses.mwebStealth(mwebScanSecret(data), mwebSpendPub(data), 0, network(data).mwebHrp)

    private fun mwebScanSecret(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/0'").privKey
    private fun mwebSpendSecret(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/1'").privKey
    private fun mwebSpendPub(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/1'").pubKey

    /** A signed-but-not-broadcast MWEB spend (peg-out or MWEB→MWEB), awaiting confirmation. */
    class MwebSendPreview(
        val toAddress: String,
        val amount: Long,
        val fee: Long,
        val pegOut: Boolean,
        val rawTx: ByteArray
    )

    /**
     * Builds and signs a spend from an MWEB wallet through the on-phone daemon:
     * an LTC destination is a peg-out (funds leave via the kernel and mature on the
     * public side), an MWEB destination stays fully confidential.
     */
    suspend fun prepareMwebSend(data: VaultData, toAddress: String, amountLitoshis: Long, sendMax: Boolean): MwebSendPreview {
        val net = network(data)
        val scanSecret = mwebScanSecret(data)
        val spendPub = mwebSpendPub(data)
        val service = mwebd(data)

        val destScript: ByteArray
        val pegOut: Boolean
        if(isMwebAddress(toAddress, net)) {
            destScript = Bech32.decodeWitness(toAddress.trim()).program
            require(destScript.size == 66) { "Not a valid MWEB address" }
            pegOut = false
        } else {
            destScript = Addresses.toScriptPubKey(toAddress, net)
            pegOut = true
        }

        val feeRatePerKb = withServer(net) { feeRatePerVb(it.estimateFee(2)) } * 1000
        val snapshot = service.snapshot(scanSecret, mwebBirthHeight(data))
        // map each coin's stealth address back to its index (change = 0, receive i = i+1)
        val indexByAddress = buildMap {
            put(Addresses.mwebStealth(scanSecret, spendPub, 0, net.mwebHrp), 0)
            for(i in 0 until 20) {
                put(Addresses.mwebStealth(scanSecret, spendPub, i + 1, net.mwebHrp), i + 1)
            }
        }
        val coins = snapshot.utxos
            .filter { !it.spent && confirmedEnough(snapshot.status.blockHeaderHeight.toLong(), it.height) }
            .mapNotNull { u -> indexByAddress[u.address]?.let { MwebdService.SpendCoin(u.outputId, it, u.value) } }
            .sortedByDescending { it.value }
        val total = coins.sumOf { it.value }
        require(coins.isNotEmpty()) {
            "No private coins with $minConfirmations+ confirmations yet — recent receives need to mature"
        }

        val dest = MwebdService.OutSpec(amountLitoshis, destScript)
        val feeNoChange = MwebdService.estimateMwebFee(listOf(dest), feeRatePerKb, false)
        val feeWithChange = MwebdService.estimateMwebFee(listOf(dest), feeRatePerKb, true)

        val amount = if(sendMax) total - feeNoChange else amountLitoshis
        require(amount > 0) { "Amount too small" }

        val selected = ArrayList<MwebdService.SpendCoin>()
        var selectedTotal = 0L
        for(coin in coins) {
            selected.add(coin)
            selectedTotal += coin.value
            if(selectedTotal >= amount + feeWithChange) break
        }
        if(sendMax) {
            selected.clear(); selected.addAll(coins); selectedTotal = total
        }

        val outputs = ArrayList<MwebdService.OutSpec>()
        outputs.add(MwebdService.OutSpec(amount, destScript))
        var fee = feeNoChange
        val changeCost = feeWithChange - feeNoChange
        val surplus = selectedTotal - amount - feeWithChange
        when {
            sendMax -> { /* exact: amount = total - feeNoChange */ }
            surplus > changeCost -> {
                val changeProgram = Bech32.decodeWitness(
                    Addresses.mwebStealth(scanSecret, spendPub, 0, net.mwebHrp)
                ).program
                outputs.add(MwebdService.OutSpec(surplus, changeProgram))
                fee = feeWithChange
            }
            selectedTotal >= amount + feeNoChange -> fee = selectedTotal - amount // tiny surplus burns as fee
            else -> throw IllegalArgumentException(
                "Insufficient MWEB funds: need ${amount + feeWithChange} litoshis, have $selectedTotal")
        }

        val result = service.create(scanSecret, mwebSpendSecret(data), selected, outputs, feeRatePerKb)
        return MwebSendPreview(toAddress, amount, fee, pegOut, result.rawTx)
    }

    suspend fun broadcastMweb(data: VaultData, rawTx: ByteArray): String = mwebd(data).broadcastRaw(rawTx)

    /**
     * Peg-in: a public wallet paying an MWEB address. mwebd builds the confidential side
     * and hands back a kernel-bound peg-in output, which is then funded and signed like a
     * normal transaction, with the MWEB blob attached.
     */
    private suspend fun preparePegIn(data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean): SendPreview {
        val net = network(data)
        val program = Bech32.decodeWitness(toAddress.trim()).program
        require(program.size == 66) { "Not a valid MWEB address" }

        val feeRate = withServer(net) { feeRatePerVb(it.estimateFee(2)) }
        val (candidates, changeScript) = withServer(net) { collectSpendables(it, account) }
        val service = mwebd(data)

        // Relay rules charge fee on the MWEB blob's bytes too (min relay = size × rate),
        // so the public side must pay for base bytes AND blob bytes. Build, check the fee
        // against the actual serialized size, and bump the effective rate until it clears.
        val mwebFeeEst = MwebdService.estimateMwebFee(
            listOf(MwebdService.OutSpec(0, program)), feeRate * 1000, includeChange = false)
        val probe = service.createPegIn(
            mwebScanSecret(data), mwebSpendSecret(data), program, TxBuilder.DUST_LIMIT, feeRate * 1000)
        val probeScriptSize = probe.peginScript.size

        var effectiveRate = feeRate
        var attempt = 0
        while(true) {
            attempt++
            val amount = if(sendMax) {
                val total = candidates.sumOf { it.value }
                val publicFee = TxBuilder.estimateVsize(candidates.size, listOf(probeScriptSize)) * effectiveRate
                val sweep = total - publicFee - mwebFeeEst
                require(sweep >= TxBuilder.DUST_LIMIT) { "Balance is too small to cover the peg-in fees" }
                sweep
            } else {
                amountLitoshis
            }

            val template = service.createPegIn(
                mwebScanSecret(data), mwebSpendSecret(data), program, amount, feeRate * 1000
            )
            val built = TxBuilder.build(candidates, template.peginScript, template.peginValue,
                effectiveRate, changeScript, sendMax)
            if(sendMax) {
                require(built.tx.outputs[0].value == template.peginValue) {
                    "Peg-in max calculation mismatch — enter a specific amount instead"
                }
            }
            val canonicalVsize = built.vsize.toLong() // before the blob attaches
            built.tx.mwebExtension = template.mwebBlob
            val mwebFee = template.peginValue - amount

            // node policy: a peg-in also pays for the future HogEx input that spends the
            // pegin output (41 vB) — same allowance desktop's MwebFeeEstimator adds
            val requiredFee = (canonicalVsize + 41L) * feeRate + mwebFee
            if(built.fee + mwebFee >= requiredFee || attempt >= 4) {
                return SendPreview(
                    toAddress, amount,
                    built.fee + mwebFee, // public fee (incl HogEx allowance) + mweb fee
                    feeRate, built.tx.txid(), built.hex()
                )
            }
            val shortfall = requiredFee - (built.fee + mwebFee)
            effectiveRate += (shortfall + canonicalVsize - 1) / canonicalVsize
        }
    }
}
