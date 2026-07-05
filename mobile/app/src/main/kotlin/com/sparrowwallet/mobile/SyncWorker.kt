package com.sparrowwallet.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sparrowwallet.mobile.crypto.Network
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Periodic background check for new funds, using only the Keystore-cached view keys
 * (see [SyncStore]) — no wallet password, no spend capability. Public wallets are
 * polled over Electrum from the cached xpub; MWEB wallets through the on-device
 * scanner from the cached scan key. The first run per wallet records a baseline;
 * later runs notify on anything new.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = SyncStore(applicationContext)
        val repo = WalletRepository(applicationContext)
        val received = mutableListOf<String>()

        for(creds in store.loadAll()) {
            try {
                val network = Network.fromId(creds.network)
                if(creds.scriptType == "MWEB") {
                    val scanSecret = creds.scanSecretHex ?: continue
                    // give the scanner a bounded window to finish its catch-up pass
                    var snap = repo.mwebSnapshotFromScanKey(network, scanSecret, creds.birthHeight)
                    val deadline = System.currentTimeMillis() + 6 * 60_000
                    while(!snap.initialScanComplete && System.currentTimeMillis() < deadline) {
                        delay(10_000)
                        snap = repo.mwebSnapshotFromScanKey(network, scanSecret, creds.birthHeight)
                    }
                    if(!snap.initialScanComplete) continue // not caught up — next period retries
                    val unspentIds = snap.utxos.filter { !it.spent }.map { it.outputId }
                    if(creds.knownOutputIds.isNotEmpty()) {
                        val known = creds.knownOutputIds.toSet()
                        val fresh = snap.utxos.filter { !it.spent && it.outputId !in known }.sumOf { it.value }
                        if(fresh > 0) received += "${formatLtc(fresh)} → ${creds.name} (private)"
                    }
                    store.save(creds.copy(knownOutputIds = unspentIds))
                } else {
                    val xpub = creds.xpub ?: continue
                    val snap = repo.scanXpub(network, xpub)
                    val txids = snap.history.map { it.txid }
                    if(creds.knownTxids.isNotEmpty()) {
                        val known = creds.knownTxids.toSet()
                        val fresh = snap.history.filter { it.txid !in known && it.delta > 0 }.sumOf { it.delta }
                        if(fresh > 0) received += "${formatLtc(fresh)} → ${creds.name}"
                    }
                    store.save(creds.copy(knownTxids = txids))
                }
            } catch(t: Throwable) {
                // per-wallet best effort — a dead server or offline scanner retries next period
            }
        }

        if(received.isNotEmpty() && store.notifyEnabled) {
            notifyReceived(received)
        }
        return Result.success()
    }

    private fun notifyReceived(lines: List<String>) {
        if(Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wallet activity", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle(if(lines.size == 1) "Litecoin received" else "Litecoin received (${lines.size})")
            .setContentText(lines.joinToString("; "))
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL = "wallet-activity"
        const val WORK_NAME = "wallet-sync"
        private const val NOTIFICATION_ID = 1001

        /** (Re)schedules the periodic check; 0 minutes cancels it. Android's floor is 15. */
        fun schedule(context: Context, minutes: Int) {
            val workManager = WorkManager.getInstance(context)
            if(minutes <= 0) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().build()
            )
        }
    }
}
