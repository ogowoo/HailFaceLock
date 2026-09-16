package com.aistra.hail.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aistra.hail.R
import com.aistra.hail.receiver.UnsuspendedReceiver
import com.aistra.hail.ui.main.MainActivity

/**
 * Keeps a process alive so [UnsuspendedReceiver] can be registered at runtime.
 *
 * Android sends ACTION_PACKAGES_UNSUSPENDED with FLAG_RECEIVER_REGISTERED_ONLY, so a
 * manifest-declared receiver never sees it. Without this service, an app unsuspended
 * from the system dialog while Hail is not running would slip through the biometric gate.
 *
 * Only needed while the "biometric verification to unfreeze" setting is enabled.
 */
class UnfreezeGuardService : Service() {
    private val receiver by lazy { UnsuspendedReceiver() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(UnsuspendedReceiver.ACTION_PACKAGES_UNSUSPENDED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    }
}
