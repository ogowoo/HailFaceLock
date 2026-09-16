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

class UnsuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PACKAGE_UNSUSPENDED_MANUALLY) return
        runCatching {
            val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)!!
            // Unfreezes initiated by Hail itself (also fire this broadcast on some ROMs) pass through.
            val intentional = UnfreezeGate.consume(pkg)
            if (HailData.biometricUnfreeze && !intentional && AppManager.setAppFrozen(pkg, true)) {
                // The user unsuspended the app from the system dialog.
                // Re-freeze it immediately and require biometric authentication to truly unfreeze.
                // Note: freezing again also restores the restricted standby bucket (API 31+).
                notifyAuthRequired(pkg)
            } else if (HTarget.P) setAppRestricted(pkg, false)
            app.setAutoFreezeService()
        }
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
        private const val CHANNEL_ID = "unfreeze_auth"
        private const val NOTIFICATION_ID = 200
    }
}
