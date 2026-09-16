package com.aistra.hail.utils

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.getSystemService
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.receiver.DeviceAdminReceiver
import org.lsposed.hiddenapibypass.HiddenApiBypass

object HPolicy {
    private val dpm = app.getSystemService<DevicePolicyManager>()!!
    private val admin = ComponentName(app, DeviceAdminReceiver::class.java)
    private val DPM_COMMAND = "dpm set-device-owner ${admin.flattenToShortString()}"
    val ADB_COMMAND = "adb shell $DPM_COMMAND"

    private val packageManager: Any? by lazy {
        runCatching {
            HiddenApiBypass.invoke(Class.forName("android.app.ActivityThread"), null, "getPackageManager")
        }.onFailure { HLog.e(it) }.getOrNull()
    }

    private val isDeviceOwner get() = dpm.isDeviceOwnerApp(app.packageName)
    val isProfileOwner get() = dpm.isProfileOwnerApp(app.packageName)
    val isAdminActive get() = dpm.isAdminActive(admin)
    val isDeviceOwnerActive get() = isDeviceOwner && isAdminActive

    val lockScreen get() = isAdminActive.also { if (it) dpm.lockNow() }

    fun setAppHidden(packageName: String, hidden: Boolean): Boolean =
        isDeviceOwnerActive && dpm.setApplicationHidden(admin, packageName, hidden)

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
        if (!isDeviceOwnerActive || !HTarget.N) return false
        // Prefer the hidden call: it accepts a SuspendDialogInfo, so the system dialog can hand the
        // "unsuspend" tap back to Hail for biometric verification. A device owner caller is allowed
        // to name itself as the suspending package, which is what routes that intent to us.
        val viaPackageManager = runCatching {
            val pm = packageManager ?: throw IllegalStateException("IPackageManager unavailable")
            val dialogInfo = if (suspended) HSuspendDialog.current else null
            val result = if (HTarget.U) {
                runCatching {
                    HiddenApiBypass.invoke(
                        pm::class.java, pm, "setPackagesSuspendedAsUser",
                        arrayOf(packageName), suspended, null, null, dialogInfo, 0,
                        app.packageName, HPackages.myUserId, HPackages.myUserId
                    )
                }.getOrElse {
                    if (it is NoSuchMethodException) setPackagesSuspendedLegacy(pm, packageName, suspended, dialogInfo)
                    else throw it
                }
            } else setPackagesSuspendedLegacy(pm, packageName, suspended, dialogInfo)
            (result as Array<*>).isEmpty()
        }.onFailure {
            HLog.e(it)
            HLogFile.append("owner suspend via IPackageManager failed: $it")
        }.getOrDefault(false)
        if (!viaPackageManager) HLogFile.append("owner suspend: fell back to DPM for $packageName")
        return viaPackageManager || dpm.setPackagesSuspended(admin, arrayOf(packageName), suspended).isEmpty()
    }

    @Suppress("SameParameterValue")
    private fun setPackagesSuspendedLegacy(pm: Any, packageName: String, suspended: Boolean, dialogInfo: Any?): Any =
        HiddenApiBypass.invoke(
            pm::class.java, pm, "setPackagesSuspendedAsUser",
            arrayOf(packageName), suspended, null, null, dialogInfo, app.packageName, HPackages.myUserId
        )

    fun uninstallApp(packageName: String): Boolean = when {
        isDeviceOwnerActive -> {
            app.packageManager.packageInstaller.uninstall(
                packageName, PendingIntent.getActivity(
                    app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            )
            true
        }

        else -> false
    }

    fun enableBackupService() {
        if (isDeviceOwnerActive && HTarget.O && !dpm.isBackupServiceEnabled(admin)) dpm.setBackupServiceEnabled(
            admin, true
        )
    }

    fun setOrganizationName(name: String? = null) {
        if (isDeviceOwnerActive && HTarget.O) dpm.setOrganizationName(admin, name)
    }

    fun removeActiveAdmin() {
        if (isAdminActive) dpm.removeActiveAdmin(admin)
    }

    @Suppress("DEPRECATION")
    fun removeProfileOwner() {
        if (isProfileOwner) dpm.clearProfileOwner(admin)
    }

    @Suppress("DEPRECATION")
    fun removeDeviceOwner() {
        if (isDeviceOwnerActive) dpm.clearDeviceOwnerApp(app.packageName)
    }
}