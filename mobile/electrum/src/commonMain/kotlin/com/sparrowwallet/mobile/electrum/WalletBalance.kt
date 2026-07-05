package com.sparrowwallet.mobile.electrum

/** A confirmed + unconfirmed balance, in litoshis (1e-8 LTC). */
data class Balance(val confirmed: Long, val unconfirmed: Long) {
    val total: Long get() = confirmed + unconfirmed
}

data class HistoryEntry(val txHash: String, val height: Int)

data class Utxo(val txHash: String, val txPos: Int, val height: Int, val value: Long)

/** One wallet transaction, summarized for the history list. */
data class TxSummary(
    val txid: String,
    val height: Int,          // <= 0 means unconfirmed
    val timestamp: Long?,     // unix seconds, null while unconfirmed
    val delta: Long,          // net litoshis in(+)/out(-) of this wallet
    val confirmations: Int
)

/** Everything the Home screen needs from one server round-trip. */
data class WalletSnapshot(
    val balance: Balance,
    val history: List<TxSummary>,
    val firstUnusedReceiveIndex: Int
)
