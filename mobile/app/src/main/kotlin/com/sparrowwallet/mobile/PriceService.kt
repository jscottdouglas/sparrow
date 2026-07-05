package com.sparrowwallet.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * LTC/USD prices from CoinGecko (no API key): the current spot price (cached a few
 * minutes) and historical daily closes (cached permanently per date — they never
 * change). Fetches are best-effort: on rate limits or network failure callers get
 * null and show a placeholder.
 */
object PriceService {
    private const val CURRENT_URL = "https://api.coingecko.com/api/v3/simple/price?ids=litecoin&vs_currencies=usd"
    private const val HISTORY_URL = "https://api.coingecko.com/api/v3/coins/litecoin/history?date=%s&localization=false"

    private var current: Pair<Double, Long>? = null // price, fetchedAtMs
    private val mutex = Mutex()

    suspend fun currentUsd(): Double? = mutex.withLock {
        current?.takeIf { System.currentTimeMillis() - it.second < 5 * 60_000 }?.first
            ?: fetch(CURRENT_URL)?.let {
                runCatching { JSONObject(it).getJSONObject("litecoin").getDouble("usd") }.getOrNull()
            }?.also { current = it to System.currentTimeMillis() }
    }

    /** USD price on the day of [unixSeconds], cached permanently. */
    suspend fun usdOn(context: Context, unixSeconds: Long): Double? {
        val format = SimpleDateFormat("dd-MM-yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val day = format.format(Date(unixSeconds * 1000))
        val prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE)
        val key = "usd.$day"
        if(prefs.contains(key)) {
            return prefs.getFloat(key, 0f).toDouble().takeIf { it > 0 }
        }
        val price = mutex.withLock {
            fetch(HISTORY_URL.format(day))?.let {
                runCatching {
                    JSONObject(it).getJSONObject("market_data").getJSONObject("current_price").getDouble("usd")
                }.getOrNull()
            }
        }
        if(price != null) {
            prefs.edit().putFloat(key, price.toFloat()).apply()
        }
        return price
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            try {
                if(connection.responseCode == 200) connection.inputStream.bufferedReader().readText() else null
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
