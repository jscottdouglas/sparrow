package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Scripts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end proof that the mobile Electrum client talks to a real Litecoin server:
 * connect → server.version handshake → live fee estimate → balance for a real derived
 * address. Network-dependent by design; tries several public servers before giving up.
 */
class ElectrumLiveSmokeTest {
    private val servers = listOf(
        Triple("electrum-ltc.bysh.me", 50002, true),
        Triple("backup.electrum-ltc.org", 443, true),
        Triple("ltc.rentonisk.com", 50002, true)
    )

    @Test
    fun fetchesLiveDataFromPublicServer() = runBlocking {
        // A real derived LTC address (drongo-test-seed, m/84'/2'/0'/0/0)
        val account = HDKey.fromSeed(Bip39.seed(
            "absent essay fox snake vast pumpkin height crouch silent bulb excuse razor"))
            .derivePath("m/84'/2'/0'")
        val pub = account.deriveChild(0).deriveChild(0).pubKey
        val scriptHash = ElectrumProtocol.scriptHash(Scripts.p2wpkhOutput(pub))

        var lastError: Throwable? = null
        for((host, port, tls) in servers) {
            val client = ElectrumClient(host, port, tls)
            try {
                withTimeout(20_000) {
                    client.connect()
                    val version = client.serverVersion()
                    val fee = client.estimateFee(6)
                    val balance = client.getBalance(scriptHash)
                    val history = client.getHistory(scriptHash)

                    println("=== Electrum live smoke: $host:$port ===")
                    println("server.version : $version")
                    println("estimatefee(6) : $fee LTC/kB")
                    println("balance        : confirmed=${balance.confirmed} unconfirmed=${balance.unconfirmed} litoshis")
                    println("history entries: ${history.size}")

                    assertTrue(version.isNotEmpty(), "server.version should return data")
                    assertTrue(fee > 0.0, "a live server should return a positive fee estimate")
                }
                return@runBlocking // success
            } catch(t: Throwable) {
                println("server $host:$port failed: ${t.message}")
                lastError = t
            } finally {
                client.close()
            }
        }
        throw AssertionError("All public Electrum-LTC servers unreachable (network/test env?)", lastError)
    }
}
