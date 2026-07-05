package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip32Pub
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Scans the wallet's derived addresses against an Electrum server. A pragmatic fixed
 * lookahead per chain rather than a growing gap-limit walk; enough for early wallets,
 * revisit when an address explorer screen lands.
 */
class WalletScanner(private val client: ElectrumClient) {
    suspend fun p2wpkhBalance(accountKey: Bip32Pub, addressesPerChain: Int = 20): Balance {
        var confirmed = 0L
        var unconfirmed = 0L
        for(chain in 0..1) { // 0 = receive, 1 = change
            val chainKey = accountKey.deriveChild(chain.toLong())
            for(i in 0 until addressesPerChain) {
                val pub = chainKey.deriveChild(i.toLong()).pubKey
                val scriptHash = ElectrumProtocol.scriptHash(Scripts.p2wpkhOutput(pub))
                val bal = client.getBalance(scriptHash)
                confirmed += bal.confirmed
                unconfirmed += bal.unconfirmed
            }
        }
        return Balance(confirmed, unconfirmed)
    }

    /** A wallet utxo tagged with the derivation (chain/index) of the address holding it. */
    class ChainUtxo(val utxo: Utxo, val chain: Int, val index: Int)

    class UtxoScan(val utxos: List<ChainUtxo>, val nextChangeIndex: Int)

    /**
     * Collects all spendable P2WPKH utxos over the same lookahead as [p2wpkhBalance],
     * and finds the first never-used change index for the change output.
     */
    suspend fun p2wpkhUtxos(accountKey: Bip32Pub, addressesPerChain: Int = 20): UtxoScan {
        val utxos = ArrayList<ChainUtxo>()
        var nextChangeIndex = addressesPerChain
        for(chain in 0..1) {
            val chainKey = accountKey.deriveChild(chain.toLong())
            for(i in 0 until addressesPerChain) {
                val pub = chainKey.deriveChild(i.toLong()).pubKey
                val scriptHash = ElectrumProtocol.scriptHash(Scripts.p2wpkhOutput(pub))
                for(utxo in client.listUnspent(scriptHash)) {
                    utxos.add(ChainUtxo(utxo, chain, i))
                }
                if(chain == 1 && i < nextChangeIndex && client.getHistory(scriptHash).isEmpty()) {
                    nextChangeIndex = i
                }
            }
        }
        return UtxoScan(utxos, nextChangeIndex)
    }

    /**
     * One full wallet sync: balance, transaction history with net wallet deltas, and the
     * first unused receive index. Deltas come from verbose transactions: outputs paying
     * our scripts count in, inputs spending our previous outputs count out (prev txs are
     * fetched and cached, so a wallet tx's funding side resolves in one extra call each).
     */
    suspend fun sync(accountKey: Bip32Pub, addressesPerChain: Int = 20): WalletSnapshot {
        val ourScripts = HashSet<String>()
        var confirmed = 0L
        var unconfirmed = 0L
        var firstUnusedReceive = addressesPerChain
        val heights = LinkedHashMap<String, Int>() // txid → height
        for(chain in 0..1) {
            val chainKey = accountKey.deriveChild(chain.toLong())
            for(i in 0 until addressesPerChain) {
                val script = Scripts.p2wpkhOutput(chainKey.deriveChild(i.toLong()).pubKey)
                ourScripts.add(script.toHex())
                val scriptHash = ElectrumProtocol.scriptHash(script)
                val bal = client.getBalance(scriptHash)
                confirmed += bal.confirmed
                unconfirmed += bal.unconfirmed
                val history = client.getHistory(scriptHash)
                if(chain == 0 && i < firstUnusedReceive && history.isEmpty()) {
                    firstUnusedReceive = i
                }
                for(entry in history) {
                    heights[entry.txHash] = entry.height
                }
            }
        }

        val txCache = HashMap<String, JsonObject>()
        suspend fun fetchTx(txid: String): JsonObject =
            txCache[txid] ?: client.getTransactionVerbose(txid).also { txCache[txid] = it }

        val summaries = heights.map { (txid, height) ->
            val tx = fetchTx(txid)
            var delta = 0L
            for(out in tx["vout"]!!.jsonArray) {
                val o = out.jsonObject
                val scriptHex = o["scriptPubKey"]?.jsonObject?.get("hex")?.jsonPrimitive?.content
                if(scriptHex != null && ourScripts.contains(scriptHex)) {
                    delta += ElectrumProtocol.ltcToLitoshis(o["value"]!!.jsonPrimitive.content)
                }
            }
            for(inp in tx["vin"]!!.jsonArray) {
                val i = inp.jsonObject
                val prevTxid = i["txid"]?.jsonPrimitive?.content ?: continue // coinbase
                val prevIndex = i["vout"]?.jsonPrimitive?.intOrNull ?: continue
                val prevOut = fetchTx(prevTxid)["vout"]!!.jsonArray[prevIndex].jsonObject
                val scriptHex = prevOut["scriptPubKey"]?.jsonObject?.get("hex")?.jsonPrimitive?.content
                if(scriptHex != null && ourScripts.contains(scriptHex)) {
                    delta -= ElectrumProtocol.ltcToLitoshis(prevOut["value"]!!.jsonPrimitive.content)
                }
            }
            TxSummary(
                txid = txid,
                height = height,
                timestamp = tx["time"]?.jsonPrimitive?.longOrNull,
                delta = delta,
                confirmations = tx["confirmations"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }.sortedWith(compareByDescending<TxSummary> { it.height <= 0 }.thenByDescending { it.height })

        return WalletSnapshot(Balance(confirmed, unconfirmed), summaries, firstUnusedReceive)
    }
}
