package com.aistra.hail.ui.api

import android.content.Intent

/**
 * Entry point for the system's suspended-app dialog.
 *
 * When Hail is the suspending package (device owner, Dhizuku or root) the suspension carries a
 * [android.content.pm.SuspendDialogInfo] whose neutral button uses BUTTON_ACTION_MORE_DETAILS, so
 * tapping it starts `ACTION_SHOW_SUSPENDED_APP_DETAILS` here instead of unsuspending the app.
 * The manifest declares the signature permission the system checks for, which also keeps other
 * apps from starting this activity.
 */
class SuspendedDialogActivity : UnfreezeAuthActivity() {
    override fun resolvePackage(intent: Intent): String? =
        intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME) ?: super.resolvePackage(intent)

    /** The user tapped "unsuspend" in the dialog, so open the app once it is unfrozen. */
    override val launchAfterAuth: Boolean get() = true
}
