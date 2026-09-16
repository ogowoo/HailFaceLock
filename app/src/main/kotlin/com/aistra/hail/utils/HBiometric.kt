package com.aistra.hail.utils

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R

object HBiometric {
    val isAvailable: Boolean
        get() = BiometricManager.from(app)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows a biometric prompt (face / fingerprint / device credential).
     * [onSuccess] is only invoked after the user passes the authentication.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = activity.getString(R.string.action_biometric_unfreeze),
        subtitle: String = activity.getString(R.string.msg_biometric_unfreeze),
        onSuccess: () -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) HUI.showToast(errString)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
                .setNegativeButtonText(activity.getString(android.R.string.cancel)).build()
        )
    }
}
