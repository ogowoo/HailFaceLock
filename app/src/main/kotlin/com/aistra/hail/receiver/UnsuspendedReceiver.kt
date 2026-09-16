package com.aistra.hail.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.UnfreezeAuthActivity
import com.aistra.hail.utils.HLogFile
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku.setAppRestricted
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.UnfreezeGate

/**
 * Observes unsuspends that Hail did not perform.
 *
 * - [ACTION_PACKAGE_UNSUSPENDED_MANUALLY] is sent by the system dialog but only to the package
 *   that performed the suspension. That works when Hail is the suspender (device owner, Dhizuku
 *   or root mode); in Shizuku mode the suspender is `com.android.shell`, so Hail never sees it.
 * - [ACTION_PACKAGES_UNSUSPENDED] is the global broadcast carrying the changed package list. It
 *   is sent with FLAG_RECEIVER_REGISTERED_ONLY, so a live process with a runtime-registered
 *   receiver is required -- see UnfreezeGuardService. Not every ROM delivers it, which is why
 *   the guard service also polls the suspension state.
 */
class UnsuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PACKAGE_UNSUSPENDED_MANUALLY -> intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
                ?.let { packages ->
                    HLogFile.append("broadcast MANUAL: $packages")
                    handle(listOf(packages))
                }

            ACTION_PACKAGES_UNSUSPENDED -> {
                val list = intent.getStringArrayExtra(EXTRA_CHANGED_PACKAGE_LIST)?.toList().orEmpty()
                HLogFile.append("broadcast PACKAGES_UNSUSPENDED: $list")
                handle(list)
            }
        }
    }

    private fun handle(packages: List<String>) = runCatching {
        var changed = false
        packages.forEach { pkg ->
            // Unfreezes initiated by Hail itself also fire these broadcasts on some ROMs.
            if (UnfreezeGate.isExpected(pkg)) {
                HLogFile.append("skip $pkg (unfreezed by Hail)")
                return@forEach
            }
            if (!HailData.isChecked(pkg)) {
                HLogFile.append("skip $pkg (not managed by Hail)")
                return@forEach
            }
            if (!AppManager.isAppFrozen(pkg)) {
                HLogFile.append("skip $pkg (already unfrozen)")
                return@forEach
            }
            if (HailData.biometricUnfreeze && blockManualUnsuspend(pkg)) changed = true
            else if (HTarget.P) setAppRestricted(pkg, false)
        }
        if (changed) app.setAutoFreezeService()
    }

    companion object {
        private const val ACTION_PACKAGE_UNSUSPENDED_MANUALLY =
            "android.intent.action.PACKAGE_UNSUSPENDED_MANUALLY"
        const val ACTION_PACKAGES_UNSUSPENDED = "android.intent.action.PACKAGES_UNSUSPENDED"
        private const val EXTRA_CHANGED_PACKAGE_LIST = "android.intent.extra.changed_package_list"
        private const val CHANNEL_ID = "unfreeze_auth"
        private const val NOTIFICATION_ID = 200
        private const val DEDUPE_MS = 10_000L

        private val handledAt = HashMap<String, Long>()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /**
         * Re-freezes [packageName] after an unsuspend that Hail did not authorize and posts a
         * notification asking for biometric verification. Returns true when it was handled.
         */
        @Synchronized
        fun blockManualUnsuspend(packageName: String): Boolean {
            val now = System.currentTimeMillis()
            handledAt.entries.removeAll { now - it.value > DEDUPE_MS }
            if (handledAt.containsKey(packageName)) return false
            if (!AppManager.setAppFrozen(packageName, true)) {
                HLogFile.append("re-freeze FAILED: $packageName")
                return false
            }
            handledAt[packageName] = now
            HLogFile.append("re-froze $packageName, asking for verification")
            notifyAuthRequired(packageName)
            return true
        }

        /**
         * Must run on the main thread: Toast and notification builders require a Looper, and
         * the guard service calls this from a coroutine on a background dispatcher.
         */
        private fun notifyAuthRequired(packageName: String) {
            val name = HPackages.getApplicationInfoOrNull(packageName)
                ?.loadLabel(app.packageManager)?.toString() ?: packageName
            val text = app.getString(R.string.unfreeze_auth_text, name)
            mainHandler.post {
                runCatching {
                    HUI.showToast(text)
                    NotificationManagerCompat.from(app).createNotificationChannel(
                        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                            .setName(app.getString(R.string.unfreeze_auth_channel)).build()
                    )
                    val contentIntent = PendingIntent.getActivity(
                        app, packageName.hashCode(),
                        Intent(app, UnfreezeAuthActivity::class.java)
                            .putExtra(HailData.KEY_PACKAGE, packageName)
                            .putExtra(UnfreezeAuthActivity.EXTRA_LAUNCH, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_outline_lock)
                        .setContentTitle(app.getString(R.string.unfreeze_auth_title))
                        .setContentText(text)
                        .setContentIntent(contentIntent)
                        .setAutoCancel(true)
                        .build()
                    NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
                    HLogFile.append("verification notification posted for $packageName")
                }.onFailure { HLogFile.append("notification FAILED for $packageName: $it") }
            }
        }
    }
}
