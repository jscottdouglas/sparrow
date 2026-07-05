package com.sparrowwallet.mobile.electrum

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** A SOCKS5 proxy endpoint — with Orbot/Tor this is 127.0.0.1:9050. */
data class SocksProxy(val host: String, val port: Int)

/**
 * Minimal Electrum JSON-RPC client over a raw TCP/TLS socket (newline-delimited JSON),
 * matching how desktop Sparrow talks to Electrum servers. java.net transport so it can:
 *  - route through a SOCKS5 proxy (Tor/Orbot), resolving hostnames at the proxy so
 *    .onion servers work, and
 *  - optionally accept self-signed certificates, which most public Electrum-LTC
 *    servers use (desktop Sparrow does trust-on-first-use; a pin store can follow).
 * Use [connect] then the query methods, and [close] when done.
 */
class ElectrumClient(
    private val host: String,
    private val port: Int,
    private val useTls: Boolean = true,
    private val proxy: SocksProxy? = null,
    private val allowSelfSigned: Boolean = false,
    private val timeoutMs: Int = 30_000
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var nextId = 0

    suspend fun connect() = withContext(Dispatchers.IO) {
        val raw = if(proxy != null) {
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxy.host, proxy.port)))
        } else {
            Socket()
        }
        // unresolved endpoint with a proxy → hostname is sent to the proxy (needed for .onion)
        val endpoint = if(proxy != null) InetSocketAddress.createUnresolved(host, port)
                       else InetSocketAddress(host, port)
        raw.connect(endpoint, timeoutMs)
        raw.soTimeout = timeoutMs
        val transport = if(useTls) {
            val factory = if(allowSelfSigned) trustAllContext().socketFactory
                          else SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, host, port, true) as SSLSocket).also { it.startHandshake() }
        } else {
            raw
        }
        socket = transport
        reader = BufferedReader(InputStreamReader(transport.getInputStream(), Charsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(transport.getOutputStream(), Charsets.UTF_8))
    }

    /** server.version handshake — returns [serverSoftware, protocolVersion]. */
    suspend fun serverVersion(clientName: String = "sparrow-ltc-mobile", protocol: String = "1.4"): List<String> {
        val result = call("server.version", listOf(JsonPrimitive(clientName), JsonPrimitive(protocol)))
        return result.jsonArray.map { it.jsonPrimitive.content }
    }

    suspend fun getBalance(scriptHash: String): Balance {
        val obj = call("blockchain.scripthash.get_balance", listOf(JsonPrimitive(scriptHash))).jsonObject
        return Balance(obj["confirmed"]!!.jsonPrimitive.long, obj["unconfirmed"]!!.jsonPrimitive.long)
    }

    suspend fun getHistory(scriptHash: String): List<HistoryEntry> {
        return call("blockchain.scripthash.get_history", listOf(JsonPrimitive(scriptHash))).jsonArray.map {
            val o = it.jsonObject
            HistoryEntry(o["tx_hash"]!!.jsonPrimitive.content, o["height"]!!.jsonPrimitive.int)
        }
    }

    suspend fun listUnspent(scriptHash: String): List<Utxo> {
        return call("blockchain.scripthash.listunspent", listOf(JsonPrimitive(scriptHash))).jsonArray.map {
            val o = it.jsonObject
            Utxo(o["tx_hash"]!!.jsonPrimitive.content, o["tx_pos"]!!.jsonPrimitive.int,
                o["height"]!!.jsonPrimitive.int, o["value"]!!.jsonPrimitive.long)
        }
    }

    /** Verbose transaction JSON from the server's daemon (vin/vout/confirmations/time). */
    suspend fun getTransactionVerbose(txid: String): JsonObject =
        call("blockchain.transaction.get", listOf(JsonPrimitive(txid), JsonPrimitive(true))).jsonObject

    suspend fun broadcast(txHex: String): String =
        call("blockchain.transaction.broadcast", listOf(JsonPrimitive(txHex))).jsonPrimitive.content

    /** Current chain tip height (first response of the headers subscription). */
    suspend fun tipHeight(): Long =
        call("blockchain.headers.subscribe", emptyList()).jsonObject["height"]!!.jsonPrimitive.long

    /** Estimated fee in LTC/kB for confirmation within [targetBlocks]; -1 if unknown. */
    suspend fun estimateFee(targetBlocks: Int): Double =
        call("blockchain.estimatefee", listOf(JsonPrimitive(targetBlocks))).jsonPrimitive.content.toDouble()

    private suspend fun call(method: String, params: List<JsonElement>): JsonElement = withContext(Dispatchers.IO) {
        val out = writer ?: error("Not connected — call connect() first")
        val id = nextId++
        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            putJsonArray("params") { params.forEach { add(it) } }
        }
        out.write(json.encodeToString(JsonObject.serializer(), request))
        out.write("\n")
        out.flush()

        while(true) {
            val line = reader!!.readLine() ?: error("Connection closed by server")
            val response = json.parseToJsonElement(line).jsonObject
            if(response["id"]?.jsonPrimitive?.int != id) continue // skip subscription notifications
            response["error"]?.let { error("Electrum error: $it") }
            return@withContext response["result"] ?: error("No result in response")
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    fun close() {
        runCatching { socket?.close() }
    }

    private fun trustAllContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
    }
}
