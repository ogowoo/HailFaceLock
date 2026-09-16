package com.aistra.hail.utils

import com.aistra.hail.R
import com.aistra.hail.app.HailData
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Builds the [android.content.pm.SuspendDialogInfo] attached to a suspension.
 *
 * [unsuspend] keeps the upstream behaviour: the neutral button of the system dialog unsuspends
 * the app directly.
 *
 * [moreDetails] switches the neutral button to BUTTON_ACTION_MORE_DETAILS: the system then starts
 * `ACTION_SHOW_SUSPENDED_APP_DETAILS` in the suspending package instead of unsuspending, so Hail
 * can verify the user first. This only resolves while Hail itself is the suspending package
 * (device owner / Dhizuku / root); with Shizuku the suspender is `com.android.shell` and the
 * button is simply hidden, which still blocks the bypass.
 */
object HSuspendDialog {
    private const val BUTTON_ACTION_UNSUSPEND = 1
    private const val BUTTON_ACTION_MORE_DETAILS = 2

    val unsuspend: Any? by lazy { build(BUTTON_ACTION_UNSUSPEND, withText = false) }
    val moreDetails: Any? by lazy { build(BUTTON_ACTION_MORE_DETAILS, withText = true) }

    /** Dialog info matching the current setting; null when the platform rejects it. */
    val current: Any? get() = if (HailData.biometricUnfreeze) moreDetails else unsuspend

    private fun build(action: Int, withText: Boolean): Any? = runCatching {
        HiddenApiBypass.newInstance(Class.forName("android.content.pm.SuspendDialogInfo\$Builder")).let {
            HiddenApiBypass.invoke(it::class.java, it, "setNeutralButtonAction", action)
            // Resolved against the suspending app's resources, which is Hail in owner/Dhizuku mode.
            if (withText && HTarget.Q) HiddenApiBypass.invoke(
                it::class.java, it, "setNeutralButtonTextResId", R.string.action_verify_unfreeze
            )
            HiddenApiBypass.invoke(it::class.java, it, "build")
        }
    }.onFailure { HLog.e(it) }.getOrNull()
}
