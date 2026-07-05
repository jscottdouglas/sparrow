package com.sparrowwallet.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.PaymentUri
import com.sparrowwallet.mobile.electrum.WalletSnapshot
import com.sparrowwallet.mobile.storage.SparrowLink
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = WalletRepository(applicationContext)
        SyncWorker.schedule(applicationContext, repo.syncStore.intervalMinutes)
        setContent { App(repo) }
    }
}

private sealed interface Screen {
    data object WalletList : Screen
    data class Create(val mnemonic: String) : Screen
    data object Restore : Screen
    data class ImportSparrow(val payload: SparrowLink.ReceivedPayload) : Screen
    data class LinkReceive(val pairing: SparrowLink.Pairing) : Screen
    data class LinkSend(val pairing: SparrowLink.Pairing) : Screen
    data class Unlock(val name: String) : Screen
    /** A linked pair unlocks together: [linkedName]/[linkedData] hold the counterpart wallet. */
    data class Home(
        val name: String,
        val data: VaultData,
        val linkedName: String? = null,
        val linkedData: VaultData? = null,
        /** Session password from unlock — needed to re-seal the vault on label edits. */
        val password: String = ""
    ) : Screen {
        fun switched(): Home? = if(linkedName != null && linkedData != null)
            Home(linkedName, linkedData, name, data, password) else null
    }
    data class Send(val home: Home) : Screen
    data class Receive(val home: Home, val startIndex: Int) : Screen
    data class Server(val home: Home) : Screen
    data class Details(val home: Home) : Screen
    data class TxDetail(val home: Home, val tx: TxInfo) : Screen
    data class LabelSync(val home: Home, val pairing: SparrowLink.Pairing) : Screen
    data class PsbtSign(val home: Home, val unsigned: WalletRepository.UnsignedSendPreview) : Screen
    data class SignPsbt(val home: Home) : Screen
}

@Composable
private fun App(repo: WalletRepository) {
    var screen by remember { mutableStateOf<Screen>(Screen.WalletList) }

    // one password opens a linked pair: silently unlock the counterpart with the same
    // password so switching between public and private needs no re-entry
    fun homeFor(name: String, data: VaultData, password: String): Screen.Home {
        val linkedName = repo.walletInfo(name)?.linkedWallet
        val linkedData = linkedName?.let { runCatching { repo.unlock(it, password) }.getOrNull() }
        return Screen.Home(name, data, linkedName.takeIf { linkedData != null }, linkedData, password)
    }

    // the layer the current screen belongs to drives the whole color world:
    // Litecoin blue for public, copper for private (MWEB)
    val privateLayer = when(val s = screen) {
        is Screen.Home -> s.data.scriptType == "MWEB"
        is Screen.Send -> s.home.data.scriptType == "MWEB"
        is Screen.Receive -> s.home.data.scriptType == "MWEB"
        is Screen.Server -> s.home.data.scriptType == "MWEB"
        is Screen.Details -> s.home.data.scriptType == "MWEB"
        is Screen.TxDetail -> s.tx.mweb
        is Screen.LabelSync -> s.home.data.scriptType == "MWEB"
        is Screen.PsbtSign -> false
        is Screen.SignPsbt -> false
        else -> false
    }
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = sparrowColorScheme(privateLayer, darkTheme)) {
    // edge-to-edge: the themed background runs under the system bars, but content is
    // inset so it never collides with the status bar or gesture area
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when(val s = screen) {
            is Screen.WalletList -> WalletListScreen(
                repo = repo,
                onOpen = { screen = Screen.Unlock(it) },
                onCreate = { screen = Screen.Create(repo.createNewMnemonic()) },
                onRestore = { screen = Screen.Restore },
                onImportFile = { bytes ->
                    screen = Screen.ImportSparrow(
                        SparrowLink.ReceivedPayload(SparrowLink.ReceivedWallet("", bytes), null)
                    )
                },
                onLink = { pairing ->
                    screen = if(pairing.mode == "push") Screen.LinkReceive(pairing) else Screen.LinkSend(pairing)
                }
            )
            is Screen.ImportSparrow -> ImportSparrowScreen(
                repo = repo,
                payload = s.payload,
                onImported = { name, data, password -> screen = homeFor(name, data, password) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.LinkReceive -> LinkReceiveScreen(
                repo = repo,
                pairing = s.pairing,
                onReceived = { screen = Screen.ImportSparrow(it) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.LinkSend -> LinkSendScreen(
                repo = repo,
                pairing = s.pairing,
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Create -> SaveWalletScreen(
                title = "New wallet",
                mnemonic = s.mnemonic,
                mnemonicEditable = false,
                repo = repo,
                onSaved = { name, data, password -> screen = Screen.Home(name, data, password = password) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Restore -> SaveWalletScreen(
                title = "Restore from seed",
                mnemonic = "",
                mnemonicEditable = true,
                repo = repo,
                onSaved = { name, data, password -> screen = Screen.Home(name, data, password = password) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Unlock -> UnlockScreen(
                name = s.name,
                repo = repo,
                onUnlocked = { data, password -> screen = homeFor(s.name, data, password) },
                onDeleted = { screen = Screen.WalletList },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Home -> if(s.data.scriptType == "MWEB") {
                MwebHomeScreen(
                    repo, s.name, s.data, s.linkedData,
                    onSend = { screen = Screen.Send(s) },
                    onReceive = { screen = Screen.Receive(s, 0) },
                    onDetails = { screen = Screen.Details(s) },
                    onSwitch = { screen = Screen.WalletList },
                    onSwitchPair = s.switched()?.let { paired -> { screen = paired } },
                    onTx = { tx -> screen = Screen.TxDetail(s, tx) },
                    onSyncLabels = { pairing -> screen = Screen.LabelSync(s, pairing) }
                )
            } else {
                HomeScreen(
                    repo, s.name, s.data, s.linkedData,
                    onSend = { screen = Screen.Send(s) },
                    onReceive = { index -> screen = Screen.Receive(s, index) },
                    onServer = { screen = Screen.Server(s) },
                    onDetails = { screen = Screen.Details(s) },
                    onSwitch = { screen = Screen.WalletList },
                    onSwitchPair = s.switched()?.let { paired -> { screen = paired } },
                    onTx = { tx -> screen = Screen.TxDetail(s, tx) },
                    onSyncLabels = { pairing -> screen = Screen.LabelSync(s, pairing) },
                    onSignPsbt = { screen = Screen.SignPsbt(s) }
                )
            }
            is Screen.Receive -> ReceiveScreen(repo, s.home, s.startIndex, onBack = { screen = s.home })
            is Screen.Send -> SendScreen(
                repo, s.home,
                onSignExternally = { unsigned -> screen = Screen.PsbtSign(s.home, unsigned) },
                onBack = { screen = s.home }
            )
            is Screen.PsbtSign -> PsbtSignScreen(repo, s.home, s.unsigned, onBack = { screen = s.home })
            is Screen.SignPsbt -> SignPsbtScreen(repo, s.home, onBack = { screen = s.home })
            is Screen.Server -> ServerScreen(repo, s.home.data, onBack = { screen = s.home })
            is Screen.Details -> DetailsScreen(repo, s.home.name, s.home.data, onBack = { screen = s.home })
            is Screen.TxDetail -> TxDetailScreen(
                s.tx,
                onSaveLabel = if(s.home.password.isNotEmpty()) { label: String ->
                    val newData = repo.updateLabel(s.home.name, s.home.data, s.home.password, s.tx.txid, label)
                    val cleaned = label.trim().ifEmpty { null }
                    screen = Screen.TxDetail(
                        s.home.copy(data = newData),
                        s.tx.copy(label = cleaned, kind = kindFor(s.tx.delta, cleaned))
                    )
                } else null,
                onBack = { screen = s.home }
            )
            is Screen.LabelSync -> LabelSyncScreen(
                repo, s.home, s.pairing,
                onDone = { updated ->
                    screen = s.home.copy(
                        data = updated[s.home.name] ?: s.home.data,
                        linkedData = s.home.linkedName?.let { ln -> updated[ln] } ?: s.home.linkedData
                    )
                },
                onBack = { screen = s.home }
            )
        }
    }
    }
    }
}

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { content() }
}

@Composable
private fun NetworkBadge(network: Network) {
    val color = if(network == Network.MAINNET) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
    Text(
        network.displayName.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}

@Composable
private fun WalletListScreen(
    repo: WalletRepository,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onImportFile: (ByteArray) -> Unit,
    onLink: (SparrowLink.Pairing) -> Unit
) {
    val wallets = remember { repo.listWallets() }
    val context = LocalContext.current
    var pickError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if(bytes == null || bytes.isEmpty()) pickError = "Could not read that file" else onImportFile(bytes)
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                onLink(SparrowLink.parseQr(text))
            } catch(e: Exception) {
                pickError = e.message ?: "Not a Sparrow Link QR code"
            }
        }
    }
    ScreenColumn {
        Text("Sparrow LTC", style = MaterialTheme.typography.headlineMedium)
        if(wallets.isEmpty()) {
            Text("A Litecoin wallet in sync with your desktop.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Wallets", style = MaterialTheme.typography.titleMedium)
            // a linked pair is one wallet with two layers — show it as one card
            val names = wallets.map { it.name }.toSet()
            val visible = wallets.filter { w ->
                !(w.scriptType == "MWEB" && w.linkedWallet != null && w.linkedWallet in names)
            }
            for(w in visible) {
                val pairedPrivate = w.scriptType != "MWEB" && w.linkedWallet?.let { ln ->
                    wallets.any { it.name == ln && it.scriptType == "MWEB" }
                } == true
                OutlinedButton(onClick = { onOpen(w.name) }, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(w.name)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 5.dp)) {
                                LayerTag(privateLayer = w.scriptType == "MWEB")
                                if(pairedPrivate) LayerTag(privateLayer = true)
                            }
                        }
                        NetworkBadge(w.network)
                    }
                }
            }
        }
        var restoreOpen by remember { mutableStateOf(false) }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create new wallet") }
        OutlinedButton(onClick = { restoreOpen = !restoreOpen }, modifier = Modifier.fillMaxWidth()) {
            Text(if(restoreOpen) "Restore  ▴" else "Restore  ▾")
        }
        if(restoreOpen) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from seed")
                }
                OutlinedButton(
                    onClick = {
                        scanner.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan the QR from desktop Sparrow: Tools → Link with Mobile")
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                        })
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import wallet from desktop") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import wallet from file…")
                }
                Text("Desktop import: on the PC open Tools → Link with Mobile → Push or Receive. " +
                    "Same WiFi, or turn on USB tethering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        pickError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        // background refresh: scheduled view-only checks + received-funds notifications
        val syncStore = remember { repo.syncStore }
        var interval by remember { mutableStateOf(syncStore.intervalMinutes) }
        var notify by remember { mutableStateOf(syncStore.notifyEnabled) }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        Text("BACKGROUND REFRESH", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for((label, minutes) in listOf("Off" to 0, "15m" to 15, "30m" to 30, "1h" to 60, "6h" to 360)) {
                val select = {
                    interval = minutes
                    syncStore.intervalMinutes = minutes
                    SyncWorker.schedule(context, minutes)
                    if(minutes > 0 && android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if(interval == minutes) {
                    Button(onClick = select, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                } else {
                    OutlinedButton(onClick = select, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Notify on received funds", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = notify, onCheckedChange = { notify = it; syncStore.notifyEnabled = it })
        }
        if(interval > 0) {
            TextButton(onClick = { SyncWorker.runNow(context) }) { Text("Check now") }
            Text("Checks use view-only keys sealed in this phone's secure keystore — they can see " +
                "incoming funds but can never spend. Unlock each wallet once after enabling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkReceiveScreen(
    repo: WalletRepository,
    pairing: SparrowLink.Pairing,
    onReceived: (SparrowLink.ReceivedPayload) -> Unit,
    onBack: () -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing) {
        try {
            onReceived(repo.linkReceiveWallet(pairing))
        } catch(t: Throwable) {
            error = t.message ?: "Transfer failed"
        }
    }
    ScreenColumn {
        Text("Importing from desktop", style = MaterialTheme.typography.headlineSmall)
        if(error == null) {
            CircularProgressIndicator()
            Text("Connecting to the desktop and importing the wallet…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun LinkSendScreen(repo: WalletRepository, pairing: SparrowLink.Pairing, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val wallets = remember { repo.listWallets() }
    var selected by remember { mutableStateOf(wallets.firstOrNull()?.name) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    ScreenColumn {
        Text("Send wallet to desktop", style = MaterialTheme.typography.headlineSmall)
        if(done) {
            Text("Wallet sent ✓ — on the PC use File → Open Wallet. It opens with the same password it has on this phone.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }
        if(wallets.isEmpty()) {
            Text("No wallets on this phone yet.", style = MaterialTheme.typography.bodyMedium)
            BackButton(onClick = onBack)
            return@ScreenColumn
        }
        Text("Choose the wallet to send, and enter its password (it authorizes the export and stays the wallet's password on the desktop).",
            style = MaterialTheme.typography.bodyMedium)
        for(w in wallets) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == w.name, onClick = { selected = w.name })
                Text(w.name, modifier = Modifier.weight(1f))
                NetworkBadge(w.network)
            }
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Wallet password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val name = selected ?: throw IllegalArgumentException("Pick a wallet")
                        val data = try { repo.unlock(name, password) }
                                   catch(t: Throwable) { throw IllegalArgumentException("Wrong password") }
                        val fileBytes = repo.exportSparrowWallet(name, data, password)
                        repo.linkSendWallet(pairing, name, fileBytes)
                        done = true
                    } catch(t: Throwable) {
                        error = t.message ?: "Transfer failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && selected != null && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Encrypting and sending…" else "Push to desktop") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun ImportSparrowScreen(
    repo: WalletRepository,
    payload: SparrowLink.ReceivedPayload,
    onImported: (String, VaultData, String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var filePassword by remember { mutableStateOf("") }
    var linkedPassword by remember { mutableStateOf("") }
    var devicePassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val linked = payload.linked

    ScreenColumn {
        Text("Import desktop wallet", style = MaterialTheme.typography.headlineSmall)
        Text(
            if(linked == null)
                "Wallet ${payload.wallet.name.ifBlank { "file" }} loaded (${payload.wallet.file.size} bytes). " +
                "Enter the password it has on the desktop; it's re-encrypted on this phone with a password of your choice."
            else
                "Receiving \"${payload.wallet.name}\" together with its linked private wallet \"${linked.name}\". " +
                "Enter the desktop passwords; both are re-encrypted on this phone with a password of your choice.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = filePassword, onValueChange = { filePassword = it },
            label = { Text(if(linked == null) "Desktop wallet password" else "Desktop password: ${payload.wallet.name}") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if(linked != null) {
            OutlinedTextField(
                value = linkedPassword, onValueChange = { linkedPassword = it },
                label = { Text("Desktop password: ${linked.name} (blank if same)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = devicePassword, onValueChange = { devicePassword = it },
            label = { Text("Password on this phone (can be the same)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Wallet name (optional — uses the desktop name)") },
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if(devicePassword.isEmpty()) throw IllegalArgumentException("Choose a password for this phone")
                        val walletName = repo.importSparrowPair(
                            payload, filePassword, linkedPassword.ifBlank { filePassword }, name, devicePassword
                        )
                        onImported(walletName, repo.unlock(walletName, devicePassword), devicePassword)
                    } catch(t: Throwable) {
                        error = t.message ?: "Import failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Decrypting… (takes a few seconds)" else if(linked == null) "Import wallet" else "Import both wallets") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun NetworkPicker(selected: Network, onSelect: (Network) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for(net in Network.entries) {
            RadioButton(selected = selected == net, onClick = { onSelect(net) })
            Text(net.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SaveWalletScreen(
    title: String,
    mnemonic: String,
    mnemonicEditable: Boolean,
    repo: WalletRepository,
    onSaved: (String, VaultData, String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var network by remember { mutableStateOf(Network.MAINNET) }
    var phrase by remember { mutableStateOf(mnemonic) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Wallet name") },
            modifier = Modifier.fillMaxWidth()
        )
        NetworkPicker(network) { network = it }
        if(mnemonicEditable) {
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it },
                label = { Text("Recovery phrase (12–24 words)") },
                modifier = Modifier.fillMaxWidth()
            )
            phrase.trim().takeIf { it.isNotEmpty() }?.let { p ->
                val kind = repo.classifySeed(p, network)?.first
                Text(
                    kind?.let { "Detected: $it seed" } ?: "Not a recognized seed yet",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(phrase, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text("Write these words down and keep them safe. Anyone with them controls your funds.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Wallet password (encrypts on this device)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                error = if(password.isEmpty()) "Choose a password"
                        else repo.saveWallet(name, phrase, "", password, network)
                            ?: run { onSaved(name.trim(), repo.unlock(name, password), password); null }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save wallet") }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun UnlockScreen(name: String, repo: WalletRepository, onUnlocked: (VaultData, String) -> Unit, onDeleted: () -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenColumn {
        Text("Unlock \"$name\"", style = MaterialTheme.typography.headlineSmall)
        repo.walletInfo(name)?.linkedWallet?.let {
            Text("Also unlocks the linked wallet \"$it\".", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                error = try { onUnlocked(repo.unlock(name, password), password); null }
                        catch(t: Throwable) { "Wrong password" }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Unlock") }
        BackButton(onClick = onBack)
        if(!confirmDelete) {
            TextButton(onClick = { confirmDelete = true }) { Text("Delete this wallet from device…") }
        } else {
            Text("This removes the wallet from this phone. Without the seed words it CANNOT be recovered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { repo.deleteWallet(name); onDeleted() }, modifier = Modifier.fillMaxWidth()) {
                Text("Yes, delete \"$name\"")
            }
            TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
        }
    }
}

/**
 * The transaction detail screen: everything the clean activity rows leave out —
 * confirmations, block, txid with tap-to-copy, and the fiat value then and now.
 */
@Composable
private fun TxDetailScreen(tx: TxInfo, onSaveLabel: ((String) -> Unit)? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var usdThen by remember(tx.txid) { mutableStateOf<Double?>(null) }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(tx.txid) {
        usdNow = PriceService.currentUsd()
        if(tx.timestamp != null && tx.timestamp > 0) usdThen = PriceService.usdOn(context, tx.timestamp)
    }
    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction", style = MaterialTheme.typography.headlineSmall)
            StatusPill(
                if(tx.height > 0) PillState.OK else PillState.SCAN,
                if(tx.height > 0) "Confirmed" else "Pending"
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TypeChip(tx.kind)
            Text(
                (if(tx.delta >= 0) "+" else "") + formatLtc(tx.delta),
                fontFamily = MonoFont, style = MaterialTheme.typography.headlineSmall,
                color = if(tx.delta >= 0) kindColor(TxKind.RECEIVED) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                if(usdThen != null) "${formatUsd(tx.delta, usdThen)} then → ${formatUsd(tx.delta, usdNow)} today"
                else "≈ ${formatUsd(tx.delta, usdNow)} today",
                fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if(onSaveLabel == null) {
                tx.label?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        onSaveLabel?.let { save ->
            var labelText by remember(tx.txid) { mutableStateOf(tx.label ?: "") }
            var saved by remember(tx.txid) { mutableStateOf(false) }
            val changed = labelText.trim() != (tx.label ?: "")
            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it; saved = false },
                label = { Text("Label") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if(changed) { save(labelText); saved = true } }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { save(labelText); saved = true },
                enabled = changed,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save label") }
            if(saved) {
                Text(
                    "✓ Label saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = kindColor(TxKind.RECEIVED),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun kv(k: String, v: String) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                    }
                }
                kv("Status", when {
                    tx.height <= 0 -> "unconfirmed"
                    tx.confirmations != null -> "%,d confirmations".format(tx.confirmations)
                    else -> "confirmed"
                })
                if(tx.height > 0) kv("Block", "%,d".format(tx.height))
                kv("Date", formatDate(tx.timestamp?.takeIf { it > 0 }))
                kv(if(tx.mweb && tx.txid.length != 64) "Output id" else "Txid",
                    tx.txid.take(10) + "…" + tx.txid.takeLast(8))
                usdThen?.let { kv("Value then", "$%,.2f / LTC".format(it)) }
            }
        }
        Button(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(tx.txid))
                copied = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(copied) "Copied ✓" else if(tx.mweb && tx.txid.length != 64) "Copy output id" else "Copy txid") }
        if(!tx.mweb) {
            OutlinedButton(
                onClick = {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://litecoinspace.org/tx/${tx.txid}")
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View on explorer") }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun HomeScreen(
    repo: WalletRepository,
    name: String,
    data: VaultData,
    linkedData: VaultData?,
    onSend: () -> Unit,
    onReceive: (Int) -> Unit,
    onServer: () -> Unit,
    onDetails: () -> Unit,
    onSwitch: () -> Unit,
    onSwitchPair: (() -> Unit)? = null,
    onTx: (TxInfo) -> Unit = {},
    onSyncLabels: (SparrowLink.Pairing) -> Unit = {},
    onSignPsbt: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    val account = remember(data) { repo.accountKey(data) }
    // open with the last-known snapshots so navigation never blanks the screen
    var snapshot by remember(data) { mutableStateOf(repo.cachedSnapshot(data)) }
    var privateSnap by remember(data) { mutableStateOf(linkedData?.let { repo.cachedMwebSnapshot(it) }) }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember(data) {
        mutableStateOf(
            if(repo.cachedSnapshot(data) != null && !repo.snapshotFresh(data)) "Last known — refreshing…" else null
        )
    }
    LaunchedEffect(Unit) { usdNow = PriceService.currentUsd() }

    // One wallet in two layers: refresh syncs the public side and the linked private
    // side together, and the status line reports both.
    fun refresh() {
        syncing = true
        scope.launch {
            try {
                val privateJob = if(linkedData != null && linkedData.scriptType == "MWEB")
                    async { runCatching { repo.mwebSync(linkedData) } } else null
                val publicResult = runCatching { repo.syncWallet(data, account) }
                publicResult.onSuccess { snapshot = it }
                val privateResult = privateJob?.await()
                privateResult?.onSuccess { privateSnap = it }

                status = pairStatus(
                    publicResult.fold({ "" }, { "sync failed — ${it.message}" }),
                    privateResult?.fold({ mwebStateText(it) }, { "unavailable — ${it.message}" })
                )
            } finally {
                syncing = false
            }
        }
    }

    LaunchedEffect(account) {
        // refresh only when opened stale, at the configured interval, or on demand —
        // switching between screens/layers just shows the cached snapshot
        val maxAge = repo.syncStore.intervalMinutes.takeIf { it > 0 }?.let { it * 60_000L } ?: 60_000L
        val privateFresh = linkedData == null || linkedData.scriptType != "MWEB" ||
            (repo.cachedMwebSnapshot(linkedData)?.initialScanComplete == true &&
                repo.mwebSnapshotFresh(linkedData, maxAge))
        if(!repo.snapshotFresh(data, maxAge) || !privateFresh) refresh()
        if(linkedData != null && linkedData.scriptType == "MWEB") {
            // keep the combined balance filling in while the private coin scan runs
            var polls = 0
            while(polls++ < 120) {
                if(privateSnap?.initialScanComplete == true) break
                kotlinx.coroutines.delay(6_000)
                if(!syncing) refresh()
            }
        }
    }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if(onSwitchPair != null) "${network.displayName.uppercase()} · LINKED PAIR"
                    else network.displayName.uppercase(),
                    fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onSwitchPair?.let { LayerSwitch(isPrivate = false, onSelect = { toPrivate -> if(toPrivate) it() }) }
        run {
            val publicTotal = snapshot?.balance?.total
            val privateTotal = privateSnap?.let { it.confirmed + it.unconfirmed }
            BalanceHeader(
                eyebrow = if(privateTotal != null) "Total balance" else "Balance",
                total = publicTotal?.let { it + (privateTotal ?: 0) },
                usdNow = usdNow,
                publicPart = if(privateTotal != null) publicTotal else null,
                privatePart = privateTotal,
                pending = snapshot?.let { it.balance.unconfirmed + (privateSnap?.unconfirmed ?: 0) }
            )
        }
        StatusSlot(status, when {
            status == null -> PillState.OK
            status!!.contains("failed", true) || status!!.contains("reconnect", true) -> PillState.IDLE
            status!!.contains("scanning", true) || status!!.contains("syncing", true) || syncing -> PillState.SCAN
            else -> PillState.OK
        })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
            Button(onClick = { onReceive(snapshot?.firstUnusedReceiveIndex ?: 0) }, modifier = Modifier.weight(1f)) {
                Text("Receive")
            }
            OutlinedButton(onClick = { refresh() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                Text(if(syncing) "…" else "Refresh")
            }
        }

        snapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("ACTIVITY", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp))
                    if(snap.history.isEmpty()) {
                        Text("No transactions yet.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    for(tx in snap.history.take(25)) {
                        val info = TxInfo(
                            kind = kindFor(tx.delta, data.txLabels[tx.txid]),
                            label = data.txLabels[tx.txid],
                            timestamp = tx.timestamp,
                            height = tx.height,
                            confirmations = tx.confirmations,
                            txid = tx.txid,
                            delta = tx.delta,
                            mweb = false
                        )
                        ActivityRow(info, usdNow) { onTx(info) }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
            OutlinedButton(onClick = onServer, modifier = Modifier.weight(1f)) { Text("Server") }
            OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text("Wallets") }
        }
        LabelSyncButton(onError = { status = it }, onPairing = onSyncLabels)
        onSignPsbt?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Text("Sign a transaction (PSBT)…")
            }
        }
    }
}

/** Scan-to-sync entry point: desktop Tools → Link with Mobile → Sync labels shows the QR. */
@Composable
private fun LabelSyncButton(onError: (String) -> Unit, onPairing: (SparrowLink.Pairing) -> Unit) {
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                val pairing = SparrowLink.parseQr(text)
                if(pairing.mode != "labels") {
                    throw SparrowLink.LinkException("That QR is a wallet transfer — scan it from the Wallets screen")
                }
                onPairing(pairing)
            } catch(e: Exception) {
                onError(e.message ?: "Not a Sparrow Link QR code")
            }
        }
    }
    OutlinedButton(
        onClick = {
            scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the QR from desktop: Tools → Link with Mobile → Sync Labels with Phone")
                setBeepEnabled(false)
                setOrientationLocked(true)
            })
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Sync labels with desktop") }
}

@Composable
private fun MwebHomeScreen(
    repo: WalletRepository,
    name: String,
    data: VaultData,
    linkedData: VaultData?,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDetails: () -> Unit,
    onSwitch: () -> Unit,
    onSwitchPair: (() -> Unit)? = null,
    onTx: (TxInfo) -> Unit = {},
    onSyncLabels: (SparrowLink.Pairing) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    // open with the last-known snapshots so navigation never blanks the screen
    var snapshot by remember(data) { mutableStateOf(repo.cachedMwebSnapshot(data)) }
    var publicBalance by remember(data) { // confirmed, pending
        mutableStateOf(linkedData?.takeIf { it.scriptType != "MWEB" }?.let { repo.cachedSnapshot(it) }
            ?.let { it.balance.confirmed to it.balance.unconfirmed })
    }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember(data) {
        mutableStateOf(
            if(repo.cachedMwebSnapshot(data) != null && !repo.mwebSnapshotFresh(data)) "Last known — refreshing…" else null
        )
    }
    LaunchedEffect(Unit) { usdNow = PriceService.currentUsd() }

    fun refresh() {
        syncing = true
        scope.launch {
            try {
                // one wallet: refresh both layers together, report both the same way
                val publicJob = if(linkedData != null && linkedData.scriptType != "MWEB")
                    async { runCatching { repo.syncWallet(linkedData, repo.accountKey(linkedData)) } } else null
                val privateState = try {
                    val snap = repo.mwebSync(data)
                    snapshot = snap
                    mwebStateText(snap)
                } catch(t: Throwable) {
                    "scanner error — ${t.message}"
                }
                val publicResult = publicJob?.await()
                publicResult?.onSuccess { publicBalance = it.balance.confirmed to it.balance.unconfirmed }
                status = pairStatus(
                    publicResult?.fold({ "" }, { "sync failed — ${it.message}" }),
                    privateState
                )
            } finally {
                syncing = false
            }
        }
    }

    LaunchedEffect(data) {
        // refresh only when opened stale, at the configured interval, or on demand
        val maxAge = repo.syncStore.intervalMinutes.takeIf { it > 0 }?.let { it * 60_000L } ?: 60_000L
        if(!(repo.mwebSnapshotFresh(data, maxAge) &&
                repo.cachedMwebSnapshot(data)?.initialScanComplete == true)) refresh()
        // the first coin scan streams results in — poll until it reports complete
        var polls = 0
        while(polls++ < 120) {
            if(snapshot?.initialScanComplete == true) break
            kotlinx.coroutines.delay(6_000)
            if(!syncing) refresh()
        }
    }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${network.displayName.uppercase()} · PRIVATE LAYER · MWEB",
                    fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onSwitchPair?.let { LayerSwitch(isPrivate = true, onSelect = { toPrivate -> if(!toPrivate) it() }) }
        run {
            val privateTotal = snapshot?.let { it.confirmed + it.unconfirmed }
            val publicTotal = publicBalance?.let { it.first + it.second }
            BalanceHeader(
                eyebrow = if(publicTotal != null) "Total balance" else "Private balance · MWEB",
                total = privateTotal?.let { it + (publicTotal ?: 0) },
                usdNow = usdNow,
                publicPart = if(privateTotal != null) publicTotal else null,
                privatePart = if(publicTotal != null) privateTotal else null,
                pending = snapshot?.let { it.unconfirmed + (publicBalance?.second ?: 0) },
                fiatNote = "amounts confidential on-chain"
            )
        }
        StatusSlot(status, when {
            status == null -> PillState.OK
            status!!.contains("reconnect", true) || status!!.contains("scanner:", true) -> PillState.IDLE
            status!!.contains("scanning", true) || status!!.contains("syncing", true) || syncing -> PillState.SCAN
            else -> PillState.OK
        })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
            Button(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
            OutlinedButton(onClick = { refresh() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                Text(if(syncing) "…" else "Refresh")
            }
        }

        run {
            // Desktop's records end at transfer time; MWEB prunes spent outputs so the
            // network can't backfill. Live coins the phone has seen since then are its
            // own witnessed receives — merge them in (change coins excluded).
            val changeAddress = remember(data) { repo.mwebChangeAddress(data) }
            val importedMax = remember(data) {
                data.mwebHistory.mapNotNull { e -> e.height.takeIf { it > 0 } }.maxOrNull() ?: 0
            }
            val liveReceives = snapshot?.utxos.orEmpty()
                .filter { it.address != changeAddress && (it.height > importedMax || it.height <= 0) }

            val rows = buildList {
                for(e in data.mwebHistory) {
                    val label = (data.txLabels[e.txid] ?: e.label).ifEmpty { null }
                    add(TxInfo(
                        kind = kindFor(e.value, label), label = label,
                        timestamp = e.time.takeIf { it > 0 }, height = e.height,
                        confirmations = null, txid = e.txid, delta = e.value, mweb = true
                    ))
                }
                for(u in liveReceives) add(TxInfo(
                    kind = TxKind.RECEIVED, label = data.txLabels[u.outputId],
                    timestamp = u.blockTime.takeIf { it > 0 }, height = u.height,
                    confirmations = null, txid = u.outputId, delta = u.value, mweb = true
                ))
            }.sortedWith(compareByDescending<TxInfo> { it.height <= 0 }.thenByDescending { it.height })

            if(rows.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("ACTIVITY", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                        for(r in rows) {
                            ActivityRow(r, usdNow) { onTx(r) }
                        }
                        Text("Desktop records up to the last transfer, plus receives this phone has " +
                            "seen since. Private spends made on desktop after the transfer appear " +
                            "after the next pair transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }

        snapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Coins", style = MaterialTheme.typography.titleSmall)
                    if(snap.utxos.isEmpty()) {
                        Text(
                            if(snap.initialScanComplete)
                                "Coin scan complete — no unspent private coins right now."
                            else
                                "No coins found yet. The scanner checks every private output on the " +
                                "chain against your view key — coins appear here as they're found. " +
                                "Keep the app open.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    for(utxo in snap.utxos.sortedByDescending { it.height }) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatLtc(utxo.value), style = MaterialTheme.typography.bodyMedium,
                                    color = if(utxo.spent) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.primary)
                                Text(
                                    when {
                                        utxo.spent -> "spent"
                                        utxo.height <= 0 -> "unconfirmed"
                                        else -> "height ${utxo.height}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(formatDate(utxo.blockTime.takeIf { it > 0 }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
            OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text("Wallets") }
        }
        LabelSyncButton(onError = { status = it }, onPairing = onSyncLabels)
    }
}

/**
 * External signing: shows the unsigned PSBT (QR / copy / share) for a desktop or
 * hardware signer, then takes the signed PSBT back (scan / paste), finalizes it,
 * and broadcasts. The txid shown is final — P2WPKH txids don't change on signing.
 */
@Composable
private fun PsbtSignScreen(
    repo: WalletRepository,
    home: Screen.Home,
    unsigned: WalletRepository.UnsignedSendPreview,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val network = remember(home.data) { repo.network(home.data) }
    var signedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTxid by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { signedText = it.trim(); error = null }
    }

    ScreenColumn {
        Column {
            Text("External signing", style = MaterialTheme.typography.headlineSmall)
            Text("${network.displayName.uppercase()} · UNSIGNED TRANSACTION",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        sentTxid?.let { txid ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Sent", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
                    Text("Signed transaction broadcast to the Litecoin network.", style = MaterialTheme.typography.bodySmall)
                    Text(shortAddress(txid), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }

        ReviewCard(listOf(
            "To" to shortAddress(unsigned.toAddress),
            "Amount" to formatLtc(unsigned.amount),
            "Fee" to "${formatLtc(unsigned.fee)} (${unsigned.feeRatePerVb} lit/vB)",
            "Txid" to shortAddress(unsigned.txid)
        ))

        SectionLabel("Give this to the signer")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                QrImage(unsigned.psbtBase64)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(unsigned.psbtBase64))
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) { Text(if(copied) "Copied ✓" else "Copy PSBT") }
            OutlinedButton(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, unsigned.psbtBase64)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share PSBT"))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Share…") }
        }
        Text("On desktop Sparrow: File → Open Transaction → From Text, paste, sign, then copy " +
            "the signed result back here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Signed result")
        OutlinedTextField(
            value = signedText,
            onValueChange = { signedText = it; error = null },
            label = { Text("Signed PSBT (base64)") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scanner.launch(paymentScanOptions()) }, enabled = !busy,
                modifier = Modifier.weight(1f)) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        signedText = it; error = null
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Paste") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        sentTxid = repo.finalizeAndBroadcast(network, signedText, unsigned.mwebBlob)
                        if(sentTxid != null) repo.invalidateSnapshots(home.data, home.linkedData)
                    } catch(t: Throwable) {
                        error = t.message ?: "Could not finalize the PSBT"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && signedText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Broadcasting…" else "Finalize and broadcast") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

/**
 * The phone as the external signer: takes a PSBT built elsewhere (desktop Sparrow, a
 * watch-only coordinator), signs the inputs this wallet owns, and hands the signed
 * PSBT back by QR / copy / share. Keys never leave the device.
 */
@Composable
private fun SignPsbtScreen(repo: WalletRepository, home: Screen.Home, onBack: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val walletData = if(home.data.scriptType != "MWEB") home.data
                     else home.linkedData?.takeIf { it.scriptType != "MWEB" } ?: home.data
    var inputText by remember { mutableStateOf("") }
    var signed by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { inputText = it.trim(); error = null; signed = null }
    }

    ScreenColumn {
        Column {
            Text("Sign a transaction", style = MaterialTheme.typography.headlineSmall)
            Text("PSBT · THIS PHONE IS THE SIGNER",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionLabel("Unsigned PSBT")
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it; error = null; signed = null },
            label = { Text("PSBT (base64)") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scanner.launch(paymentScanOptions()) },
                modifier = Modifier.weight(1f)) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        inputText = it; error = null; signed = null
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Paste") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                try {
                    signed = repo.signPsbt(walletData, inputText)
                    copied = false
                } catch(t: Throwable) {
                    error = t.message ?: "Could not sign the PSBT"
                }
            },
            enabled = inputText.isNotBlank() && signed == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign with this wallet") }

        signed?.let { result ->
            Text("✓ Signed", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
            SectionLabel("Give this back to the coordinator")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    QrImage(result)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(result))
                        copied = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if(copied) "Copied ✓" else "Copy") }
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, result)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share signed PSBT"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Share…") }
            }
        }
        BackButton(onClick = onBack)
    }
}

/** Runs one label-sync session against the desktop and reports the outcome. */
@Composable
private fun LabelSyncScreen(
    repo: WalletRepository,
    home: Screen.Home,
    pairing: SparrowLink.Pairing,
    onDone: (Map<String, VaultData>) -> Unit,
    onBack: () -> Unit
) {
    var result by remember { mutableStateOf<WalletRepository.LabelSyncOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing) {
        try {
            val wallets = buildList {
                add(Triple(home.name, home.data, home.password))
                if(home.linkedName != null && home.linkedData != null) {
                    add(Triple(home.linkedName, home.linkedData, home.password))
                }
            }
            result = repo.linkSyncLabels(pairing, wallets)
        } catch(t: Throwable) {
            error = t.message ?: "Label sync failed"
        }
    }
    ScreenColumn {
        Text("Label sync", style = MaterialTheme.typography.headlineSmall)
        when {
            error != null -> {
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                BackButton(onClick = onBack)
            }
            result == null -> {
                CircularProgressIndicator()
                Text("Connecting to the desktop and exchanging labels…", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text(result!!.summary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onDone(result!!.updated) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
private fun QrImage(text: String) {
    val bitmap = remember(text) {
        val size = 512
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if(matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Address QR code",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReceiveScreen(repo: WalletRepository, home: Screen.Home, startIndex: Int, onBack: () -> Unit) {
    val data = home.data
    val network = remember(data) { repo.network(data) }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Receive", style = MaterialTheme.typography.headlineSmall)
            NetworkBadge(network)
        }
        if(data.scriptType == "MWEB") {
            val address = remember(data) { repo.mwebInfo(data).firstAddress }
            QrImage(address)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Private (MWEB) address", style = MaterialTheme.typography.titleSmall)
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Text("MWEB addresses are reusable without a privacy loss — payments to this address " +
                        "aren't linkable on-chain.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            val account = remember(data) { repo.accountKey(data) }
            var offset by remember { mutableStateOf(0) }
            // stay inside the 20-address scan lookahead so a rotated-to address is still watched
            val index = (startIndex + offset).coerceAtMost(19)
            val address = remember(account, index, network) { repo.receiveAddress(account, index, network) }
            QrImage(address)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Address #$index", style = MaterialTheme.typography.titleSmall)
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { offset = (offset + 1) % 10 }) { Text("Next address") }
                        if(offset > 0) {
                            TextButton(onClick = { offset = 0 }) { Text("First unused") }
                        }
                    }
                    Text("Rotates to a fresh address automatically once one is used (gap limit 10 ahead).",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/**
 * One status vocabulary for both Homes: "Synced" when every present layer is fine,
 * otherwise only the layers with something to report. null layer = not present,
 * "" = fine, anything else = shown.
 */
private fun pairStatus(publicState: String?, privateState: String?): String =
    listOfNotNull(
        publicState?.takeIf { it.isNotEmpty() }?.let { "Public: $it" },
        privateState?.takeIf { it.isNotEmpty() }?.let { "Private: $it" }
    ).joinToString(" • ").ifEmpty { "Synced" }

/** The private layer's noteworthy state, or "" when fully caught up. */
private fun mwebStateText(s: MwebdService.MwebSnapshot): String = when {
    s.scanError != null -> "coin stream reconnecting"
    !s.status.synced -> "scanner syncing (block ${s.status.mwebUtxosHeight})"
    !s.initialScanComplete -> "scanning coins (${s.utxos.size} found, ${s.scanUptimeSeconds}s)"
    else -> ""
}

/** Parses a decimal LTC amount into litoshis, or null if it isn't a valid amount. */
private fun ltcToLitoshis(text: String): Long? =
    try {
        val litoshis = text.trim().toBigDecimal().movePointRight(8)
        if(litoshis.stripTrailingZeros().scale() > 0) null else litoshis.longValueExact().takeIf { it > 0 }
    } catch(e: Exception) {
        null
    }

/** Converts a USD amount string to litoshis at [rate] (USD per LTC), or null if invalid. */
private fun usdToLitoshis(text: String, rate: Double): Long? =
    try {
        text.trim().toBigDecimal()
            .divide(rate.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP)
            .movePointRight(8).longValueExact().takeIf { it > 0 }
    } catch(e: Exception) {
        null
    }

private fun paymentScanOptions() = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Scan a Litecoin address or payment QR")
    setBeepEnabled(false)
    setOrientationLocked(true)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Amount entry: currency toggles with a tap on the unit inside the field, Max sweeps
 * the balance, and the equivalence line keeps its slot so nothing jumps.
 */
@Composable
private fun AmountSection(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountIsUsd: Boolean,
    onUnitToggle: () -> Unit,
    sendMax: Boolean,
    onMaxToggle: () -> Unit,
    usdRate: Double?,
    amountLitoshis: Long?,
    maxHint: String
) {
    SectionLabel("Amount")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = if(sendMax) "" else amountText,
            onValueChange = onAmountChange,
            label = { Text(if(sendMax) maxHint else "Amount") },
            enabled = !sendMax,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = {
                if(usdRate != null && !sendMax) {
                    TextButton(onClick = onUnitToggle) {
                        Text(if(amountIsUsd) "USD ⇅" else "LTC ⇅", fontFamily = MonoFont)
                    }
                } else {
                    Text(if(amountIsUsd) "USD" else "LTC", fontFamily = MonoFont,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 14.dp))
                }
            },
            modifier = Modifier.weight(1f)
        )
        if(sendMax) {
            Button(onClick = onMaxToggle) { Text("Max") }
        } else {
            OutlinedButton(onClick = onMaxToggle) { Text("Max") }
        }
    }
    Text(
        when {
            sendMax -> "sending entire balance"
            amountLitoshis != null && amountIsUsd -> "≈ ${formatLtc(amountLitoshis)}"
            amountLitoshis != null -> "≈ ${formatUsd(amountLitoshis, usdRate)}"
            else -> " "
        },
        fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Review as an aligned table, mono throughout — same anatomy as the detail screen. */
@Composable
private fun ReviewCard(rows: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Review")
            for((k, v) in rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun shortAddress(address: String): String =
    if(address.length <= 22) address else address.take(12) + "…" + address.takeLast(8)

/**
 * Live public-side fee estimate: largest-first coin selection over the wallet's real
 * utxos with the P2WPKH size formula (~11 + 68/input + 31/output vB). Returns null
 * when the amount can't be covered. Review still shows the exact built fee.
 */
private fun estimatePublicFee(utxosDesc: List<Long>, amount: Long, sendMax: Boolean, ratePerVb: Long): Long? {
    fun vsize(inputs: Int, outputs: Int): Long = 11L + 68L * inputs + 31L * outputs
    if(utxosDesc.isEmpty()) return null
    if(sendMax) return vsize(utxosDesc.size, 1) * ratePerVb
    var selected = 0
    var total = 0L
    for(value in utxosDesc) {
        selected++
        total += value
        val fee = vsize(selected, 2) * ratePerVb
        if(total >= amount + fee) return fee
    }
    return null
}

@Composable
private fun SendScreen(
    repo: WalletRepository,
    home: Screen.Home,
    onSignExternally: (WalletRepository.UnsignedSendPreview) -> Unit = {},
    onBack: () -> Unit
) {
    // one wallet: both layers fund this screen; the paying layer is chosen automatically
    val publicData = if(home.data.scriptType != "MWEB") home.data
                     else home.linkedData?.takeIf { it.scriptType != "MWEB" }
    val privateData = if(home.data.scriptType == "MWEB") home.data
                      else home.linkedData?.takeIf { it.scriptType == "MWEB" }
    val scope = rememberCoroutineScope()
    val network = remember(home.data) { repo.network(home.data) }
    val publicAccount = remember(publicData) { publicData?.let { repo.accountKey(it) } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var sendMax by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Any?>(null) }
    var sentTxid by remember { mutableStateOf<String?>(null) }
    var usdRate by remember { mutableStateOf<Double?>(null) }
    var amountIsUsd by remember { mutableStateOf(false) }
    var feeRate by remember { mutableStateOf<Long?>(null) }
    var utxos by remember { mutableStateOf<List<Long>?>(null) }
    LaunchedEffect(Unit) {
        usdRate = PriceService.currentUsd()
        feeRate = runCatching { repo.currentFeeRate(network) }.getOrNull()
        utxos = publicData?.let { d ->
            runCatching { repo.utxoValues(d, publicAccount!!) }.getOrNull()
        } ?: emptyList()
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                val payment = PaymentUri.parse(text)
                address = payment.address
                payment.amount?.let { amountText = it; sendMax = false; amountIsUsd = false }
                preview = null
                error = null
            } catch(e: Exception) {
                error = e.message ?: "Couldn't read that QR code"
            }
        }
    }

    val isMwebDest = repo.isMwebAddress(address, network)
    val addressError = address.trim().takeIf { it.isNotEmpty() }
        ?.let { if(isMwebDest) null else repo.validateAddress(it, network) }
    val amountLitoshis = if(amountIsUsd) usdRate?.let { usdToLitoshis(amountText, it) }
                         else ltcToLitoshis(amountText)

    val publicAvail = utxos?.sum() ?: publicData?.let { repo.cachedSnapshot(it)?.balance?.total }
    // spendable = 6+ confirmations, matching the builder's coin rule
    val privateAvail = privateData?.let { repo.cachedMwebSnapshot(it) }?.let { repo.mwebSpendableBalance(it) }

    // fee estimate per possible funding layer (Review shows the exact built fee)
    val peginFee = if(isMwebDest && feeRate != null)
        MwebdService.estimateMwebFee(listOf(MwebdService.OutSpec(0, ByteArray(66))),
            feeRate!! * 1000, includeChange = false) +
            2300 * feeRate!! // the ~2.2KB MWEB blob pays relay fee too; exact figure in Review
    else 0L
    val publicFeeEst: Long? = run {
        val rate = feeRate ?: return@run null
        val coins = utxos ?: return@run null
        if(publicData == null) return@run null
        estimatePublicFee(coins, (amountLitoshis ?: 0L) + peginFee, sendMax, rate)?.plus(peginFee)
    }
    val privateFeeEst: Long? = run {
        val rate = feeRate ?: return@run null
        if(privateData == null) return@run null
        val destScript = try {
            if(isMwebDest || address.isBlank()) ByteArray(66)
            else com.sparrowwallet.mobile.crypto.Addresses.toScriptPubKey(address.trim(), network)
        } catch(e: Exception) { ByteArray(66) }
        MwebdService.estimateMwebFee(listOf(MwebdService.OutSpec(0, destScript)),
            rate * 1000, includeChange = true)
    }

    // pick the paying layer: user override wins; Auto = public when it can cover, else private
    var fundingOverride by remember { mutableStateOf<Boolean?>(null) } // null = Auto
    val amountEntered = (amountLitoshis ?: 0L) > 0 || sendMax
    val autoFunding: Boolean? = when {
        !amountEntered -> when {
            (publicAvail ?: 0L) > 0L -> false
            (privateAvail ?: 0L) > 0L -> true
            else -> if(publicData != null) false else true
        }
        publicFeeEst != null -> false
        privateData != null && sendMax && (privateAvail ?: 0L) > 0L -> true
        privateData != null && privateFeeEst != null &&
            (privateAvail ?: 0L) >= (amountLitoshis ?: 0L) + privateFeeEst -> true
        else -> null // insufficient in both layers
    }
    val fundingPrivate: Boolean? = when(fundingOverride) {
        null -> autoFunding
        false -> if(publicData != null) false else autoFunding
        true -> if(privateData != null) true else autoFunding
    }
    val estimatedFee = when(fundingPrivate) {
        false -> publicFeeEst
        true -> privateFeeEst
        null -> null
    }
    val fundingCovers = when(fundingPrivate) {
        false -> publicFeeEst != null
        true -> if(sendMax) (privateAvail ?: 0L) > 0L
                else privateFeeEst != null && (privateAvail ?: 0L) >= (amountLitoshis ?: 0L) + privateFeeEst
        null -> false
    }
    val amountValid = when(fundingPrivate) {
        false -> sendMax || amountLitoshis != null
        true -> sendMax || amountLitoshis != null
        null -> false
    }

    fun buildPreview() {
        busy = true; error = null
        scope.launch {
            try {
                preview = if(fundingPrivate == true) {
                    repo.prepareMwebSend(privateData!!, address.trim(), amountLitoshis ?: 0L, sendMax)
                } else {
                    repo.prepareSend(publicData!!, publicAccount!!, address.trim(), amountLitoshis ?: 0L, sendMax)
                }
            } catch(t: Throwable) {
                error = t.message ?: "Could not build the transaction"
            } finally {
                busy = false
            }
        }
    }

    fun confirmSend() {
        busy = true; error = null
        scope.launch {
            try {
                sentTxid = when(val p = preview) {
                    is WalletRepository.SendPreview -> repo.broadcast(network, p.txHex)
                    is WalletRepository.MwebSendPreview -> repo.broadcastMweb(privateData!!, p.rawTx)
                    else -> null
                }
                if(sentTxid != null) {
                    // the wallet just changed — make the Homes refresh on return
                    repo.invalidateSnapshots(publicData, privateData)
                }
            } catch(t: Throwable) {
                error = t.message ?: "Broadcast failed"
            } finally {
                busy = false
            }
        }
    }

    ScreenColumn {
        Column {
            Text("Send", style = MaterialTheme.typography.headlineSmall)
            Text("${network.displayName.uppercase()} · ONE WALLET, TWO LAYERS",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        sentTxid?.let { txid ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Sent", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
                    Text("Transaction broadcast to the Litecoin network.", style = MaterialTheme.typography.bodySmall)
                    Text(shortAddress(txid), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }

        SectionLabel("To")
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; preview = null },
            label = { Text("Address (public or private)") },
            isError = addressError != null,
            modifier = Modifier.fillMaxWidth()
        )
        addressError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scanner.launch(paymentScanOptions()) }, enabled = !busy,
                modifier = Modifier.weight(1f)) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        address = it; preview = null; error = null
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Paste") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(publicData != null && publicAccount != null) {
                OutlinedButton(
                    onClick = {
                        address = repo.receiveAddress(publicAccount,
                            repo.cachedSnapshot(publicData)?.firstUnusedReceiveIndex ?: 0, network)
                        sendMax = false; preview = null
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text("→ My Public") }
            }
            if(privateData != null) {
                OutlinedButton(
                    onClick = {
                        address = repo.mwebInfo(privateData).firstAddress
                        sendMax = false; preview = null
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text("→ My Private") }
            }
        }
        if(address.isNotBlank() && addressError == null) {
            when {
                fundingPrivate == true && isMwebDest ->
                    "Private MWEB transfer — fully confidential."
                fundingPrivate == true && !isMwebDest ->
                    "Paid from Private to a public address — pegs out of MWEB (extra confirmations to mature)."
                fundingPrivate == false && isMwebDest ->
                    "Paid from Public to a private address — pegs into MWEB."
                else -> null
            }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Paying from")
            if(fundingPrivate == null) {
                Text("not enough funds in either layer", fontFamily = MonoFont,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.error)
            } else {
                LayerTag(privateLayer = fundingPrivate)
            }
        }
        if(publicData != null && privateData != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable
                fun choice(label: String, selected: Boolean, onClick: () -> Unit) {
                    if(selected) Button(onClick = onClick, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                    else OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                }
                choice("Auto", fundingOverride == null) { fundingOverride = null; preview = null }
                choice("Public", fundingOverride == false) { fundingOverride = false; preview = null }
                choice("Private", fundingOverride == true) { fundingOverride = true; preview = null }
            }
        }
        publicAvail?.let { avail ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Public")
                Text("${formatLtcShort(avail)} LTC · ${formatUsd(avail, usdRate)}",
                    fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        privateAvail?.let { avail ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Private")
                Text("${formatLtcShort(avail)} LTC · ${formatUsd(avail, usdRate)}",
                    fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Est. fee")
            Text(
                when {
                    estimatedFee != null && fundingCovers ->
                        "≈ ${formatLtcShort(estimatedFee)} LTC · ${formatUsd(estimatedFee, usdRate)}"
                    !amountEntered -> "…"
                    fundingPrivate == false -> "public coins can't cover this"
                    fundingPrivate == true -> "private coins can't cover this"
                    else -> "not enough funds in either layer"
                },
                fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AmountSection(
            amountText = amountText,
            onAmountChange = { amountText = it; preview = null },
            amountIsUsd = amountIsUsd,
            onUnitToggle = { amountIsUsd = !amountIsUsd; amountText = ""; preview = null },
            sendMax = sendMax,
            onMaxToggle = { sendMax = !sendMax; preview = null },
            usdRate = usdRate,
            amountLitoshis = amountLitoshis,
            maxHint = if(fundingPrivate == true) "Entire private balance" else "Entire public balance"
        )

        (preview as? WalletRepository.SendPreview)?.let { p ->
            ReviewCard(listOf(
                "From" to "Public layer",
                "To" to shortAddress(p.toAddress),
                "Amount" to "${formatLtc(p.amount)} · ${formatUsd(p.amount, usdRate)}",
                "Fee" to "${formatLtc(p.fee)} · ${formatUsd(p.fee, usdRate)}",
                "Total" to "${formatLtc(p.amount + p.fee)} · ${formatUsd(p.amount + p.fee, usdRate)}"
            ))
        }
        (preview as? WalletRepository.MwebSendPreview)?.let { p ->
            ReviewCard(listOf(
                "From" to "Private layer",
                "To" to shortAddress(p.toAddress),
                "Amount" to "${formatLtc(p.amount)} · ${formatUsd(p.amount, usdRate)}",
                "MWEB fee" to "${formatLtc(p.fee)} · ${formatUsd(p.fee, usdRate)}",
                "Type" to if(p.pegOut) "Move to Public (peg-out)" else "Private transfer"
            ))
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if(preview == null) {
            Button(
                onClick = { buildPreview() },
                enabled = !busy && address.trim().isNotEmpty() && addressError == null
                        && amountValid && fundingPrivate != null && fundingCovers,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if(busy) "Building…" else "Preview") }
        } else {
            // the old Preview position becomes Edit, so a stray second tap can't broadcast
            OutlinedButton(
                onClick = { preview = null },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("← Edit") }
            Button(
                onClick = { confirmSend() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if(busy) "Broadcasting…" else "Confirm and send") }
            // external signer path: any public-funded send, peg-ins included
            if(preview is WalletRepository.SendPreview) {
                OutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                onSignExternally(repo.prepareSendPsbt(
                                    publicData!!, publicAccount!!, address.trim(),
                                    amountLitoshis ?: 0L, sendMax))
                            } catch(t: Throwable) {
                                error = t.message ?: "Could not build the PSBT"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign externally instead (PSBT)…") }
            }
        }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ServerScreen(repo: WalletRepository, data: VaultData, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    val saved = remember { repo.serverSettings(network) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var useTls by remember { mutableStateOf(saved.useTls) }
    var selfSigned by remember { mutableStateOf(saved.allowSelfSigned) }
    var useProxy by remember { mutableStateOf(saved.useProxy) }
    var proxyHost by remember { mutableStateOf(saved.proxyHost) }
    var proxyPort by remember { mutableStateOf(saved.proxyPort.toString()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun current(): WalletRepository.ServerSettings? {
        val p = port.toIntOrNull() ?: return null
        val pp = proxyPort.toIntOrNull() ?: return null
        if(host.isBlank() || (useProxy && proxyHost.isBlank())) return null
        return WalletRepository.ServerSettings(host.trim(), p, useTls, selfSigned, useProxy, proxyHost.trim(), pp)
    }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Electrum server", style = MaterialTheme.typography.headlineSmall)
            NetworkBadge(network)
        }
        Text("The node this wallet reads the Litecoin network through. Settings are stored per network.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = host, onValueChange = { host = it },
            label = { Text("Host (.onion needs the Tor proxy below)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        LabeledSwitch("Use TLS", useTls) { useTls = it }
        LabeledSwitch("Accept self-signed certificate (most public Electrum-LTC servers)", selfSigned) { selfSigned = it }
        LabeledSwitch("Route through Tor / SOCKS5 proxy (Orbot)", useProxy) { useProxy = it }
        if(useProxy) {
            OutlinedTextField(value = proxyHost, onValueChange = { proxyHost = it },
                label = { Text("Proxy host (Orbot: 127.0.0.1)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = proxyPort, onValueChange = { proxyPort = it },
                label = { Text("Proxy port (Orbot: 9050)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("Install the Orbot app and start it before syncing over Tor.",
                style = MaterialTheme.typography.bodySmall)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val s = current() ?: run { status = "Fix the host/port fields first"; return@OutlinedButton }
                    busy = true; status = "Connecting…"
                    scope.launch {
                        status = try {
                            val v = repo.testConnection(s)
                            "Connected: ${v.getOrNull(0)} (protocol ${v.getOrNull(1)})"
                        } catch(t: Throwable) {
                            "Connection failed: ${t.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Test") }
            Button(
                onClick = {
                    val s = current() ?: run { status = "Fix the host/port fields first"; return@Button }
                    repo.saveServerSettings(network, s)
                    status = "Saved for ${network.displayName}"
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun DetailsScreen(repo: WalletRepository, name: String, data: VaultData, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<VaultData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val network = remember(data) { repo.network(data) }

    ScreenColumn {
        Text("Wallet details", style = MaterialTheme.typography.headlineSmall)
        val shown = revealed
        if(shown == null) {
            Text("Re-enter the wallet password to view the seed and keys.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    error = try { revealed = repo.unlock(name, password); null }
                            catch(t: Throwable) { "Wrong password" }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reveal") }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recovery phrase", style = MaterialTheme.typography.titleSmall)
                    Text(shown.mnemonic, style = MaterialTheme.typography.bodyLarge)
                    if(shown.passphrase.isNotEmpty()) {
                        Text("Passphrase: ${shown.passphrase}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("${shown.seedType} • ${shown.derivationPath} • ${network.displayName}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Account xpub (watch-only import)", style = MaterialTheme.typography.titleSmall)
                    Text(repo.accountXpub(shown), style = MaterialTheme.typography.bodySmall)
                }
            }
            val mweb = remember(shown) { repo.mwebInfo(shown) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MWEB (private)", style = MaterialTheme.typography.titleSmall)
                    Text("First address:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.firstAddress, style = MaterialTheme.typography.bodySmall)
                    Text("View key — scan secret:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.scanSecretHex, style = MaterialTheme.typography.bodySmall)
                    Text("View key — spend pubkey:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.spendPubKeyHex, style = MaterialTheme.typography.bodySmall)
                    Text("Same two lines Electrum-LTC shows under Wallet → Information, and what desktop " +
                        "Sparrow-LTC derives — usable for MWEB watch-only tools. MWEB balance and sending " +
                        "aren't on mobile yet (needs the mwebd scanner); funds sent here are visible on desktop.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        BackButton(onClick = onBack)
    }
}
