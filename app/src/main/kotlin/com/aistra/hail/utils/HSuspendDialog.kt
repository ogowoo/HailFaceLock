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

    /** Button text resolves against the suspending app's resources, which is Hail in owner mode. */
    val moreDetailsWithText: Any? by lazy { build(BUTTON_ACTION_MORE_DETAILS, withText = true) }

    /**
     * Same action without the text: the framework only shows the neutral button when it resolves
     * `ACTION_SHOW_SUSPENDED_APP_DETAILS` inside the suspending package. With Shizuku that package
     * is `com.android.shell`, which has no such activity, so the button disappears and the app
     * cannot be unsuspended from the dialog at all. The system dialog still works otherwise.
     */
    val moreDetailsWithoutText: Any? by lazy { build(BUTTON_ACTION_MORE_DETAILS, withText = false) }

    /** For suspenders whose package hosts [com.aistra.hail.ui.api.SuspendedDialogActivity]. */
    val current: Any? get() = if (HailData.biometricUnfreeze) moreDetailsWithText else unsuspend

    /** For suspenders that cannot host our activity: hide the neutral button instead. */
    val currentBlocking: Any? get() = if (HailData.biometricUnfreeze) moreDetailsWithoutText else unsuspend

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
