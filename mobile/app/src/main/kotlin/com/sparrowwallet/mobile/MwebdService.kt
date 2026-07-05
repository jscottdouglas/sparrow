package com.sparrowwallet.mobile

import android.content.Context
import com.google.protobuf.ByteString
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.sparrow.mweb.proto.BroadcastRequest
import com.sparrowwallet.sparrow.mweb.proto.CreateRequest
import com.sparrowwallet.sparrow.mweb.proto.RpcGrpc
import com.sparrowwallet.sparrow.mweb.proto.SpentRequest
import com.sparrowwallet.sparrow.mweb.proto.StatusRequest
import com.sparrowwallet.sparrow.mweb.proto.UtxosRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the mwebd scanner on the phone — the same daemon desktop Sparrow-LTC and Cake
 * Wallet use — via the gomobile-built library, and speaks the same gRPC protocol to it
 * on localhost. One daemon per network, shared by all MWEB wallets; it syncs MWEB
 * headers/utxos from Litecoin peers and finds this wallet's coins with its scan key.
 */
class MwebdService private constructor(
    private val server: mwebd.Server,
    private val channel: ManagedChannel
) {
    private val stub = RpcGrpc.newBlockingStub(channel)

    data class SyncStatus(val blockHeaderHeight: Int, val mwebHeaderHeight: Int, val mwebUtxosHeight: Int) {
        /** Heads-up display state: utxo scan caught up to the header chain. */
        val synced: Boolean get() = blockHeaderHeight > 0 && mwebUtxosHeight >= blockHeaderHeight
    }

    data class MwebUtxo(
        val outputId: String,
        val address: String,
        val value: Long,      // litoshis
        val height: Int,      // 0 while unconfirmed
        val blockTime: Long,  // unix seconds, 0 while unconfirmed
        val spent: Boolean
    )

    data class MwebSnapshot(
        val status: SyncStatus,
        val utxos: List<MwebUtxo>,
        /** Seconds since this wallet's coin stream first connected (0 = just started). */
        val scanUptimeSeconds: Long = 0,
        /** Last stream error while it retries, null when healthy. */
        val scanError: String? = null,
        /** True once the initial pass over the chain's outputs has finished. */
        val initialScanComplete: Boolean = false
    ) {
        val confirmed: Long get() = utxos.filter { !it.spent && it.height > 0 }.sumOf { it.value }
        val unconfirmed: Long get() = utxos.filter { !it.spent && it.height <= 0 }.sumOf { it.value }
    }

    suspend fun status(): SyncStatus = withContext(Dispatchers.IO) {
        val s = stub.withDeadlineAfter(20, TimeUnit.SECONDS).status(StatusRequest.newBuilder().build())
        SyncStatus(s.blockHeaderHeight, s.mwebHeaderHeight, s.mwebUtxosHeight)
    }

    /**
     * One persistent Utxos stream per scan key, mirroring desktop's MwebStreamSupervisor.
     * mwebd's Utxos RPC trial-decrypts EVERY on-chain output from [fromHeight] against the
     * scan key before the stream goes live — minutes of CPU on a phone — so the stream must
     * stay open and accumulate results across refreshes. (A fresh short-deadline stream per
     * refresh always came back empty — the v0.8/v0.9 missing-balance bug.)
     */
    class Watcher internal constructor(
        private val stub: RpcGrpc.RpcBlockingStub,
        private val scanSecret: ByteArray,
        private val fromHeight: Int
    ) {
        private val collected = java.util.concurrent.ConcurrentHashMap<String, MwebUtxo>()
        @Volatile private var startedAtMs = 0L
        @Volatile var lastError: String? = null
            private set
        /** Set once mwebd signals the end of the initial scan (see sentinel note below). */
        @Volatile var initialScanComplete = false
            private set

        init {
            Thread({ run() }, "mweb-utxo-watcher").apply { isDaemon = true }.start()
        }

        private fun run() {
            while(true) {
                try {
                    if(startedAtMs == 0L) startedAtMs = System.currentTimeMillis()
                    val request = UtxosRequest.newBuilder()
                        .setScanSecret(ByteString.copyFrom(scanSecret))
                        .setFromHeight(fromHeight)
                        .build()
                    val stream = stub.utxos(request) // deliberately no deadline
                    lastError = null
                    while(stream.hasNext()) {
                        val utxo = stream.next()
                        // mwebd sends one EMPTY Utxo as an end-of-initial-scan marker
                        // (utxoStreamer.notify). It must never reach the Spent RPC: an
                        // empty output id makes the Go side panic and abort the process.
                        if(utxo.outputId.isEmpty()) {
                            initialScanComplete = true
                            continue
                        }
                        collected[utxo.outputId] = MwebUtxo(
                            utxo.outputId, utxo.address, utxo.value, utxo.height,
                            utxo.blockTime.toLong(), spent = false
                        )
                        lastError = null
                    }
                } catch(t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
                Thread.sleep(5_000) // stream ended or failed (daemon restart?) — reconnect
            }
        }

        val uptimeSeconds: Long
            get() = if(startedAtMs == 0L) 0 else (System.currentTimeMillis() - startedAtMs) / 1000

        fun current(): List<MwebUtxo> = collected.values.toList()
    }

    private val watchers = mutableMapOf<String, Watcher>()

    /** Starts (or reuses) the persistent coin stream for this scan key. */
    @Synchronized
    fun watcher(scanSecret: ByteArray, fromHeight: Int): Watcher =
        watchers.getOrPut(scanSecret.toHex()) { Watcher(stub, scanSecret, fromHeight) }

    /**
     * Current view of the wallet's coins: whatever the persistent stream has accumulated
     * so far, with spent state re-checked now. [fromHeight] should be the wallet's first
     * known use (its birth height) so the initial scan skips older chain history.
     */
    suspend fun snapshot(scanSecret: ByteArray, fromHeight: Int = 0): MwebSnapshot = withContext(Dispatchers.IO) {
        val w = watcher(scanSecret, fromHeight)
        val currentStatus = status()
        val found = w.current().filter { it.outputId.isNotEmpty() }
        val spentIds = if(found.isEmpty()) emptySet() else
            stub.withDeadlineAfter(20, TimeUnit.SECONDS)
                .spent(SpentRequest.newBuilder().addAllOutputId(found.map { it.outputId }).build())
                .outputIdList.toSet()

        MwebSnapshot(
            currentStatus,
            found.map { it.copy(spent = it.outputId in spentIds) },
            scanUptimeSeconds = w.uptimeSeconds,
            scanError = w.lastError,
            initialScanComplete = w.initialScanComplete
        )
    }

    /** An MWEB coin to spend: its output id plus the stealth-address index that received it. */
    data class SpendCoin(val outputIdHex: String, val addressIndex: Int, val value: Long)

    /** A transaction output for the create skeleton: litoshis + script (66-byte MWEB program, or an LTC script for peg-outs). */
    data class OutSpec(val value: Long, val script: ByteArray)

    class CreateResult(val rawTx: ByteArray, val outputIds: List<String>)

    /** Everything a peg-in needs from mwebd: the kernel-bound peg-in output plus the MWEB blob. */
    class PegInTemplate(val peginValue: Long, val peginScript: ByteArray, val mwebBlob: ByteArray)

    /**
     * mwebd's Create RPC: wraps a skeleton transaction, building and signing the MWEB
     * portion with the given keys (mirrors desktop's MwebServer.create, but with the real
     * spend secret so mwebd signs — desktop routes signing through its PSBT path instead).
     */
    suspend fun create(
        scanSecret: ByteArray,
        spendSecret: ByteArray,
        coins: List<SpendCoin>,
        outputs: List<OutSpec>,
        feeRatePerKb: Long
    ): CreateResult = withContext(Dispatchers.IO) {
        val response = stub.withDeadlineAfter(60, TimeUnit.SECONDS).create(
            CreateRequest.newBuilder()
                .setRawTx(ByteString.copyFrom(skeleton(coins, outputs)))
                .setScanSecret(ByteString.copyFrom(scanSecret))
                .setSpendSecret(ByteString.copyFrom(spendSecret))
                .setFeeRatePerKb(feeRatePerKb)
                .setDryRun(false)
                .build()
        )
        CreateResult(response.rawTx.toByteArray(), response.outputIdList.toList())
    }

    /** Creates the MWEB side of a peg-in to [mwebProgram] and extracts the peg-in output + blob. */
    suspend fun createPegIn(
        scanSecret: ByteArray,
        spendSecret: ByteArray,
        mwebProgram: ByteArray,
        amount: Long,
        feeRatePerKb: Long
    ): PegInTemplate {
        val result = create(scanSecret, spendSecret, emptyList(), listOf(OutSpec(amount, mwebProgram)), feeRatePerKb)
        return parsePegIn(result.rawTx)
    }

    suspend fun broadcastRaw(rawTx: ByteArray): String = withContext(Dispatchers.IO) {
        stub.withDeadlineAfter(30, TimeUnit.SECONDS)
            .broadcast(BroadcastRequest.newBuilder().setRawTx(ByteString.copyFrom(rawTx)).build())
            .txid
    }

    companion object {
        private val running = mutableMapOf<String, MwebdService>()

        /**
         * Port of ltcd's mweb.EstimateFee: the MWEB-side fee for a transaction with these
         * outputs. Depends only on outputs (66-byte scripts are MWEB recipients; anything
         * else is a peg-out), never on the number of inputs.
         */
        fun estimateMwebFee(outputs: List<OutSpec>, feeRatePerKb: Long, includeChange: Boolean): Long {
            var weight = 3L // kernel with stealth excess
            var txOutSize = 0L
            for(out in outputs) {
                if(out.script.size == 66) {
                    weight += 18
                } else {
                    weight += (out.script.size + 41L) / 42L
                    txOutSize += 8 + varIntSize(out.script.size) + out.script.size
                }
            }
            if(includeChange) {
                weight += 18
            }
            val byteFee = (feeRatePerKb * txOutSize + 999) / 1000
            return byteFee + weight * 100
        }

        private fun varIntSize(n: Int): Int = when {
            n < 0xFD -> 1
            n <= 0xFFFF -> 3
            else -> 5
        }

        /**
         * Serializes the legacy-format skeleton tx mwebd's Create expects: MWEB inputs
         * reference coins by raw output-id bytes with the stealth-address index as vout
         * (matching mwebd's fetchCoin/SpendKey usage — no txid byte-reversal).
         */
        private fun skeleton(coins: List<SpendCoin>, outputs: List<OutSpec>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun u32le(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
            fun u64le(v: Long) = repeat(8) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
            fun varInt(v: Int) {
                when {
                    v < 0xFD -> out.write(v)
                    v <= 0xFFFF -> { out.write(0xFD); out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                    else -> { out.write(0xFE); u32le(v.toLong()) }
                }
            }
            u32le(2) // version
            if(coins.isEmpty()) {
                // zero-input legacy encoding is ambiguous (0x00 reads as a segwit flag
                // marker) — emit an explicit marker+witness-flag so the daemon's decoder
                // takes the segwit path; with no inputs there's no witness data to follow
                out.write(0x00)
                out.write(0x01)
            }
            varInt(coins.size)
            for(coin in coins) {
                val hash = ByteArray(32) { i ->
                    coin.outputIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                out.write(hash)
                u32le(coin.addressIndex.toLong())
                varInt(0) // empty scriptSig
                u32le(0xFFFFFFFFL)
            }
            varInt(outputs.size)
            for(spec in outputs) {
                u64le(spec.value)
                varInt(spec.script.size)
                out.write(spec.script)
            }
            u32le(0) // locktime
            return out.toByteArray()
        }

        /**
         * Extracts the peg-in output and MWEB blob from a Create response for a
         * transaction with no inputs and exactly one (peg-in) output. Only the plain
         * LTC envelope is parsed; the blob passes through opaquely.
         */
        fun parsePegIn(raw: ByteArray): PegInTemplate {
            var pos = 4 // version
            require(raw.size > 10 && raw[pos].toInt() == 0) { "Unexpected create response (no extension marker)" }
            val flag = raw[pos + 1].toInt()
            require(flag and 8 != 0) { "Create response has no MWEB extension" }
            pos += 2
            fun readVarInt(): Long {
                val first = raw[pos].toInt() and 0xFF
                return when {
                    first < 0xFD -> { pos += 1; first.toLong() }
                    first == 0xFD -> { val v = ((raw[pos + 2].toInt() and 0xFF) shl 8) or (raw[pos + 1].toInt() and 0xFF); pos += 3; v.toLong() }
                    else -> throw IllegalStateException("Peg-in template too large")
                }
            }
            require(readVarInt() == 0L) { "Peg-in template should have no inputs" }
            require(readVarInt() == 1L) { "Peg-in template should have exactly the peg-in output" }
            var value = 0L
            for(i in 0 until 8) {
                value = value or ((raw[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            pos += 8
            val scriptLen = readVarInt().toInt()
            val script = raw.copyOfRange(pos, pos + scriptLen)
            pos += scriptLen
            val blob = raw.copyOfRange(pos, raw.size - 4) // everything up to locktime
            return PegInTemplate(value, script, blob)
        }

        /** Starts (or reuses) the daemon for [network]; [proxy] is "host:port" or null. */
        @Synchronized
        fun get(context: Context, network: Network, proxy: String?): MwebdService =
            running.getOrPut(network.id) {
                val dataDir = File(context.filesDir, "mwebd/${network.id}").apply { mkdirs() }
                val server = mwebd.Mwebd.start(network.id, dataDir.absolutePath, proxy ?: "")
                val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port().toInt())
                    .usePlaintext()
                    .build()
                MwebdService(server, channel)
            }
    }
}
