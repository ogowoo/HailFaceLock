package com.aistra.hail.ui.api

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HBiometric
import com.aistra.hail.utils.HLogFile
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown after the user unsuspends an app from the system dialog.
 * The app has been re-frozen by [com.aistra.hail.receiver.UnsuspendedReceiver];
 * it is only unfreezed (and optionally launched) after biometric authentication succeeds.
 */
class UnfreezeAuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val pkg = intent.getStringExtra(HailData.KEY_PACKAGE)
        if (pkg.isNullOrEmpty() || !HailData.biometricUnfreeze) {
            finish()
            return
        }
        if (!HBiometric.isAvailable) {
            HUI.showToast(R.string.biometric_unavailable)
            HLogFile.append("auth activity: biometrics unavailable for $pkg")
            finish()
            return
        }
        HLogFile.append("auth activity: asking for verification of $pkg")
        HBiometric.authenticate(
            this,
            onSuccess = {
                HLogFile.append("auth activity: verification passed for $pkg")
                lifecycleScope.launch {
                    runCatching {
                        if (AppManager.setAppFrozen(pkg, false)) {
                            HLogFile.append("auth activity: unfroze $pkg")
                            runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
                            app.setAutoFreezeService()
                            if (intent.getBooleanExtra(EXTRA_LAUNCH, false)) {
                                // Wait until the frozen state has actually lifted before launching.
                                var retries = 20
                                while (AppManager.isAppFrozen(pkg) && retries-- > 0) delay(100)
                                packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity)
                            }
                        } else {
                            HLogFile.append("auth activity: unfreeze FAILED for $pkg")
                            HUI.showToast(R.string.permission_denied)
                        }
                    }
                    finish()
                }
            },
            onDismiss = {
                HLogFile.append("auth activity: verification dismissed for $pkg")
                finish()
            }
        )
    }

    companion object {
        const val EXTRA_LAUNCH = "launch"
        private const val NOTIFICATION_ID = 200
    }
}
