package com.aistra.hail.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.api.UnfreezeAuthActivity
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HShizuku.setAppRestricted
import com.aistra.hail.utils.HTarget
import com.aistra.hail.utils.HUI
import com.aistra.hail.utils.UnfreezeGate

/**
 * Observes unsuspends that Hail did not perform.
 *
 * - [ACTION_PACKAGE_UNSUSPENDED_MANUALLY] is sent by the system dialog but only to the
 *   package that performed the suspension. That works when Hail is the suspender
 *   (device owner, Dhizuku or root mode); in Shizuku mode the suspender is
 *   `com.android.shell`, so Hail never sees it.
 * - [ACTION_PACKAGES_UNSUSPENDED] is a global broadcast carrying the changed package list.
 *   It is sent with FLAG_RECEIVER_REGISTERED_ONLY, so it can only be received by a
 *   runtime-registered receiver inside a live process -- see UnfreezeGuardService.
 *
 * When the biometric gate is enabled and the user manually unsuspends an app, it is
 * re-frozen immediately and a notification asks for authentication.
 */
class UnsuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PACKAGE_UNSUSPENDED_MANUALLY ->
                intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)?.let { handle(listOf(it)) }

            ACTION_PACKAGES_UNSUSPENDED ->
                intent.getStringArrayExtra(EXTRA_CHANGED_PACKAGE_LIST)?.let { handle(it.toList()) }
        }
    }

    private fun handle(packages: List<String>) = runCatching {
        var changed = false
        packages.forEach { pkg ->
            // Unfreezes initiated by Hail itself also fire these broadcasts on some ROMs.
            if (UnfreezeGate.consume(pkg)) return@forEach
            // Only apps managed by Hail, and only when they are actually frozen again by the user.
            if (!HailData.isChecked(pkg) || !AppManager.isAppFrozen(pkg)) return@forEach
            if (HailData.biometricUnfreeze && AppManager.setAppFrozen(pkg, true)) {
                // Re-freeze and require biometric authentication to truly unfreeze.
                // Note: freezing again also restores the restricted standby bucket (API 31+).
                notifyAuthRequired(pkg)
            } else if (HTarget.P) {
                setAppRestricted(pkg, false)
            }
            changed = true
        }
        if (changed) app.setAutoFreezeService()
    }

    private fun notifyAuthRequired(pkg: String) {
        val name = HPackages.getApplicationInfoOrNull(pkg)?.loadLabel(app.packageManager)?.toString() ?: pkg
        val text = app.getString(R.string.unfreeze_auth_text, name)
        HUI.showToast(text)
        runCatching {
            NotificationManagerCompat.from(app).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(app.getString(R.string.unfreeze_auth_channel)).build()
            )
            val contentIntent = PendingIntent.getActivity(
                app, pkg.hashCode(),
                Intent(app, UnfreezeAuthActivity::class.java)
                    .putExtra(HailData.KEY_PACKAGE, pkg)
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
        }
    }

    companion object {
        private const val ACTION_PACKAGE_UNSUSPENDED_MANUALLY =
            "android.intent.action.PACKAGE_UNSUSPENDED_MANUALLY"
        const val ACTION_PACKAGES_UNSUSPENDED = "android.intent.action.PACKAGES_UNSUSPENDED"
        private const val EXTRA_CHANGED_PACKAGE_LIST = "android.intent.extra.changed_package_list"
        private const val CHANNEL_ID = "unfreeze_auth"
        private const val NOTIFICATION_ID = 200
    }
}
