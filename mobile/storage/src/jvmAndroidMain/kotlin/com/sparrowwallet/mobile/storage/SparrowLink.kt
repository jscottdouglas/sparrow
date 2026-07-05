package com.sparrowwallet.mobile.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Sparrow Link": moves a wallet file between desktop and phone over a direct TCP
 * connection paired by QR code. The desktop (no camera) always displays the QR and
 * listens; the phone scans and connects — over WiFi LAN or USB tethering, so it also
 * works with an offline desktop. Every frame is AES-256-GCM sealed with the ephemeral
 * session key carried in the QR; the wallet file itself additionally stays
 * password-encrypted (SPRW1), so the password never crosses the link.
 *
 * QR text: sparrowlink1;m=<push|pull>;h=<ip[,ip...]>;p=<port>;k=<base64url key32>
 *   push = desktop pushes its wallet to the phone; pull = desktop waits to receive.
 * Frame:  u32 length | nonce12 | AES-GCM ciphertext (AAD "SLNK1").
 * Wallet frame JSON: {"op":"wallet","name":<string>,"file":<base64>} → {"ok":true}.
 */
object SparrowLink {
    const val QR_PREFIX = "sparrowlink1;"
    private const val AAD = "SLNK1"
    private const val MAX_FRAME = 16 * 1024 * 1024
    private val random = SecureRandom()

    class LinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Pairing(val mode: String, val hosts: List<String>, val port: Int, val key: ByteArray)

    fun parseQr(text: String): Pairing {
        if(!text.startsWith(QR_PREFIX)) throw LinkException("Not a Sparrow Link QR code")
        val fields = text.removePrefix(QR_PREFIX).split(";")
            .mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 } }
            .associate { it[0] to it[1] }
        val mode = fields["m"] ?: throw LinkException("QR missing mode")
        if(mode != "push" && mode != "pull" && mode != "labels") throw LinkException("Unknown link mode: $mode")
        val hosts = fields["h"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if(hosts.isEmpty()) throw LinkException("QR missing desktop address")
        val port = fields["p"]?.toIntOrNull() ?: throw LinkException("QR missing port")
        val key = try { Base64.getUrlDecoder().decode(fields["k"] ?: "") }
                  catch(e: IllegalArgumentException) { throw LinkException("QR has a bad session key") }
        if(key.size != 32) throw LinkException("QR has a bad session key")
        return Pairing(mode, hosts, port, key)
    }

    /** Connects to the first reachable advertised address. */
    private fun connect(pairing: Pairing, timeoutMs: Int = 8_000): Socket {
        var lastError: Exception? = null
        for(host in pairing.hosts) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, pairing.port), timeoutMs)
                socket.soTimeout = 60_000
                return socket
            } catch(e: Exception) {
                lastError = e
            }
        }
        throw LinkException(
            "Could not reach the desktop (${pairing.hosts.joinToString()}). " +
            "Phone and PC must be on the same network — or enable USB tethering.", lastError
        )
    }

    class ReceivedWallet(val name: String, val file: ByteArray)

    /** A pushed wallet, possibly travelling with its linked private (MWEB) wallet. */
    class ReceivedPayload(val wallet: ReceivedWallet, val linked: ReceivedWallet?)

    /** Parses the wallet-push message JSON (exposed for tests). */
    fun parseWalletMessage(json: String): ReceivedPayload {
        val frame = Json.parseToJsonElement(json).jsonObject
        if(frame["op"]?.jsonPrimitive?.contentOrNull != "wallet") throw LinkException("Unexpected message from desktop")
        val name = frame["name"]?.jsonPrimitive?.contentOrNull ?: "Desktop wallet"
        val file = Base64.getDecoder().decode(
            frame["file"]?.jsonPrimitive?.contentOrNull ?: throw LinkException("Desktop sent no wallet data")
        )
        val linked = frame["linked"]?.jsonObject?.let { l ->
            val linkedFile = l["file"]?.jsonPrimitive?.contentOrNull ?: return@let null
            ReceivedWallet(
                l["name"]?.jsonPrimitive?.contentOrNull ?: "$name Private",
                Base64.getDecoder().decode(linkedFile)
            )
        }
        return ReceivedPayload(ReceivedWallet(name, file), linked)
    }

    /** Phone side of desktop-push: receives the wallet (and its linked private wallet, if sent). */
    fun receiveWallet(pairing: Pairing): ReceivedPayload {
        connect(pairing).use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val payload = parseWalletMessage(readFrame(input, pairing.key).decodeToString())
            writeFrame(output, pairing.key, """{"ok":true}""".encodeToByteArray())
            return payload
        }
    }

    /** Phone side of desktop-pull: sends the wallet file, waits for the ack. */
    fun sendWallet(pairing: Pairing, name: String, fileBytes: ByteArray) {
        connect(pairing).use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val message = buildJsonObject {
                put("op", "wallet")
                put("name", name)
                put("file", Base64.getEncoder().encodeToString(fileBytes))
            }.toString()
            writeFrame(output, pairing.key, message.encodeToByteArray())
            val reply = Json.parseToJsonElement(readFrame(input, pairing.key).decodeToString()).jsonObject
            if(reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw LinkException("Desktop rejected the wallet: ${reply["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}")
            }
        }
    }

    // ---- two-way label sync (mode "labels") ----

    /** One wallet's labels keyed by master fingerprint (matches across devices; names may differ). */
    class WalletLabels(val fingerprint: String, val name: String, val labels: Map<String, String>)

    /** The outcome of merging one wallet's labels: what each side was missing. */
    class LabelMerge(
        val toLocal: Map<String, String>,
        val toRemote: Map<String, String>,
        val conflicts: Int
    )

    /**
     * Fill-in-the-blanks merge: each side adopts labels it lacks; entries labeled
     * differently on both sides are left alone (kept locally) and counted, so a sync
     * never silently overwrites anything.
     */
    fun mergeLabels(local: Map<String, String>, remote: Map<String, String>): LabelMerge {
        val toLocal = HashMap<String, String>()
        val toRemote = HashMap<String, String>()
        var conflicts = 0
        for((txid, remoteLabel) in remote) {
            if(remoteLabel.isEmpty()) continue
            val localLabel = local[txid]
            when {
                localLabel.isNullOrEmpty() -> toLocal[txid] = remoteLabel
                localLabel != remoteLabel -> conflicts++
            }
        }
        for((txid, localLabel) in local) {
            if(localLabel.isEmpty()) continue
            if(remote[txid].isNullOrEmpty()) toRemote[txid] = localLabel
        }
        return LabelMerge(toLocal, toRemote, conflicts)
    }

    /** Parses the desktop's label-sync opening message (exposed for tests). */
    fun parseLabelsMessage(json: String): List<WalletLabels> {
        val frame = Json.parseToJsonElement(json).jsonObject
        if(frame["op"]?.jsonPrimitive?.contentOrNull != "labels") throw LinkException("Unexpected message from desktop")
        val wallets = frame["wallets"]?.let { it as? kotlinx.serialization.json.JsonArray }
            ?: throw LinkException("Desktop sent no label data")
        return wallets.map { w ->
            val obj = w.jsonObject
            WalletLabels(
                fingerprint = obj["fingerprint"]?.jsonPrimitive?.contentOrNull?.lowercase()
                    ?: throw LinkException("Desktop label data missing fingerprint"),
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                labels = obj["labels"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap()
            )
        }
    }

    class LabelSyncResult(
        /** fingerprint → labels the phone should adopt. */
        val toPhone: Map<String, Map<String, String>>,
        val sentToDesktop: Int,
        val conflicts: Int,
        val matchedWallets: List<String>
    )

    /**
     * Phone side of a label sync: connects, receives the desktop's labels per wallet,
     * merges against [local] (matched by master fingerprint), replies with what the
     * desktop is missing, and returns what the phone should adopt.
     */
    fun syncLabels(pairing: Pairing, local: List<WalletLabels>): LabelSyncResult {
        connect(pairing).use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val remoteWallets = parseLabelsMessage(readFrame(input, pairing.key).decodeToString())

            val toPhone = HashMap<String, Map<String, String>>()
            val matched = ArrayList<String>()
            var sent = 0
            var conflicts = 0
            val reply = buildJsonObject {
                put("op", "labels")
                put("wallets", kotlinx.serialization.json.buildJsonArray {
                    for(remote in remoteWallets) {
                        val mine = local.firstOrNull { it.fingerprint.equals(remote.fingerprint, ignoreCase = true) }
                            ?: continue
                        matched.add(remote.name.ifEmpty { mine.name })
                        val merge = mergeLabels(mine.labels, remote.labels)
                        if(merge.toLocal.isNotEmpty()) toPhone[mine.fingerprint.lowercase()] = merge.toLocal
                        sent += merge.toRemote.size
                        conflicts += merge.conflicts
                        add(buildJsonObject {
                            put("fingerprint", remote.fingerprint)
                            put("labels", buildJsonObject {
                                for((txid, label) in merge.toRemote) put(txid, label)
                            })
                        })
                    }
                })
            }.toString()
            writeFrame(output, pairing.key, reply.encodeToByteArray())

            val ack = Json.parseToJsonElement(readFrame(input, pairing.key).decodeToString()).jsonObject
            if(ack["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                throw LinkException("Desktop rejected the label sync: ${ack["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"}")
            }
            return LabelSyncResult(toPhone, sent, conflicts, matched)
        }
    }

    fun writeFrame(output: DataOutputStream, key: ByteArray, plaintext: ByteArray) {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD.encodeToByteArray())
        val sealed = cipher.doFinal(plaintext)
        output.writeInt(nonce.size + sealed.size)
        output.write(nonce)
        output.write(sealed)
        output.flush()
    }

    fun readFrame(input: DataInputStream, key: ByteArray): ByteArray {
        val length = input.readInt()
        if(length < 13 || length > MAX_FRAME) throw LinkException("Bad frame from peer")
        val frame = ByteArray(length)
        input.readFully(frame)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, frame.copyOfRange(0, 12)))
        cipher.updateAAD(AAD.encodeToByteArray())
        return try {
            cipher.doFinal(frame.copyOfRange(12, frame.size))
        } catch(e: Exception) {
            throw LinkException("Could not decrypt message — QR code and connection don't match", e)
        }
    }
}
