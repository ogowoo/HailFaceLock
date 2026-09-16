package com.aistra.hail.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.receiver.UnsuspendedReceiver
import com.aistra.hail.receiver.UnsuspendedReceiver.Companion.blockManualUnsuspend
import com.aistra.hail.ui.main.MainActivity
import com.aistra.hail.utils.HLogFile
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.UnfreezeGate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches for unsuspends that Hail did not authorize, so a manual unsuspend from the system
 * dialog cannot bypass the biometric gate.
 *
 * Two detectors run while this service lives:
 *  - a runtime-registered receiver for ACTION_PACKAGES_UNSUSPENDED (instant, but not every ROM
 *    delivers it -- it is sent with FLAG_RECEIVER_REGISTERED_ONLY), and
 *  - a poller over the apps Hail currently considers frozen (works everywhere, at most one
 *    poll interval of latency).
 *
 * The service only runs while the "biometric verification to unfreeze" setting is on.
 */
class UnfreezeGuardService : Service() {
    private val receiver by lazy { UnsuspendedReceiver() }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            HLogFile.append("guard error: $e")
        }
    )
    private val powerManager by lazy { getSystemService<PowerManager>() }

    /** True when ApplicationInfo.FLAG_SUSPENDED can be used to query all apps in one call. */
    private var bulkSuspendFlag = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(UnsuspendedReceiver.ACTION_PACKAGES_UNSUSPENDED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure { HLogFile.append("registerReceiver failed: $it") }
        HLogFile.append("guard service started")
        HLogFile.append(
            "android ${Build.VERSION.SDK_INT}, mode=${HailData.workingMode}, " +
                    "gate=${HailData.biometricUnfreeze}, checked=${HailData.checkedList.size}"
        )
        scope.launch {
            bulkSuspendFlag = HailData.workingMode.endsWith(HailData.SUSPEND) && detectBulkSuspendFlag()
            HLogFile.append("bulk suspend query: $bulkSuspendFlag")
            watch()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        HLogFile.append("guard service stopped")
        scope.cancel()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Polls the apps Hail currently considers frozen. Any of them that becomes unfrozen without
     * Hail doing it is a manual unsuspend: re-freeze it and ask for biometric verification.
     */
    private suspend fun watch() {
        var baseline = frozenSet()
        var ticks = 0
        var screenWasOff = false
        HLogFile.append("watching ${baseline.size} frozen app(s)")
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            runCatching {
                if (!HailData.biometricUnfreeze) {
                    baseline = frozenSet()
                    return@runCatching
                }
                if (powerManager?.isInteractive == false) {
                    screenWasOff = true
                    return@runCatching
                }
                if (screenWasOff) {
                    // Nothing can be unsuspended while locked, so a fresh baseline is safe here
                    // and avoids re-freezing apps Hail unfreezed during a long screen-off period.
                    screenWasOff = false
                    baseline = frozenSet()
                    return@runCatching
                }
                if (++ticks % HEARTBEAT_TICKS == 0) HLogFile.append("heartbeat, watching ${baseline.size} app(s)")
                val now = frozenSet()
                val escaped = baseline - now
                baseline = now
                escaped.forEach { pkg ->
                    runCatching {
                        if (UnfreezeGate.isExpected(pkg)) {
                            HLogFile.append("poller: $pkg unfreezed by Hail, ok")
                            return@forEach
                        }
                        if (!HailData.isChecked(pkg)) return@forEach
                        HLogFile.append("poller: $pkg was unfreezed outside Hail")
                        if (blockManualUnsuspend(pkg)) baseline.add(pkg)
                    }.onFailure { HLogFile.append("handle $pkg failed: $it") }
                }
            }.onFailure { HLogFile.append("poll failed: $it") }
        }
    }

    private suspend fun frozenSet(): MutableSet<String> = withContext(Dispatchers.IO) {
        val managed = HailData.checkedList.filter { it.applicationInfo != null }.map { it.packageName }
        if (managed.isEmpty()) return@withContext mutableSetOf()
        if (bulkSuspendFlag) {
            // One IPC for all installed apps instead of one per managed app.
            val managedSet = managed.toHashSet()
            return@withContext HPackages.getInstalledApplications()
                .asSequence()
                .filter { it.packageName in managedSet && it.flags and ApplicationInfo.FLAG_SUSPENDED != 0 }
                .mapTo(mutableSetOf()) { it.packageName }
        }
        managed.filterTo(mutableSetOf()) { AppManager.isAppFrozen(it) }
    }

    /** Checks whether ApplicationInfo.FLAG_SUSPENDED is usable, using a known frozen app. */
    private suspend fun detectBulkSuspendFlag(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val sample = HailData.checkedList.firstOrNull {
                it.applicationInfo != null && AppManager.isAppFrozen(it.packageName)
            } ?: return@withContext false
            HPackages.getInstalledApplications()
                .firstOrNull { it.packageName == sample.packageName }
                ?.let { it.flags and ApplicationInfo.FLAG_SUSPENDED != 0 } == true
        }.getOrDefault(false)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_outline_lock)
        .setContentTitle(getString(R.string.unfreeze_guard_title))
        .setContentText(getString(R.string.unfreeze_guard_text))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_MIN)
                .setName(getString(R.string.unfreeze_guard_channel)).build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "unfreeze_guard"
        private const val NOTIFICATION_ID = 201
        private const val POLL_INTERVAL_MS = 500L
        private const val HEARTBEAT_TICKS = 300
    }
}
