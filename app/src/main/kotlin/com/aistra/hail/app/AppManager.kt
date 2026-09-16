package com.aistra.hail.app

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.aistra.hail.BuildConfig
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AppManager {
    private val suspendSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val lockScreen: Boolean
        get() = when {
            HailData.workingMode.startsWith(HailData.OWNER) -> HPolicy.lockScreen
            HailData.workingMode.startsWith(HailData.DHIZUKU) -> HDhizuku.lockScreen
            HailData.workingMode.startsWith(HailData.SU) -> HShell.lockScreen
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.lockScreen
            else -> false
        }

    fun isAppFrozen(packageName: String): Boolean = when {
        HailData.workingMode.endsWith(HailData.STOP) -> HPackages.isAppStopped(packageName)
        HailData.workingMode.endsWith(HailData.DISABLE) -> HPackages.isAppDisabled(packageName)
        HailData.workingMode.endsWith(HailData.HIDE) -> HPackages.isAppHidden(packageName)
        HailData.workingMode.endsWith(HailData.SUSPEND) -> HPackages.isAppSuspended(packageName)
        else -> HPackages.isAppDisabled(packageName)
                || HPackages.isAppHidden(packageName)
                || HPackages.isAppSuspended(packageName)
    }

    fun setListFrozen(frozen: Boolean, vararg appInfo: AppInfo): String? {
        val excludeMe = appInfo.filter { it.packageName != BuildConfig.APPLICATION_ID }
        var i = 0
        var denied = false
        var name = String()
        when (HailData.workingMode) {
            // call setListFrozen for some batch-style working mode here
            // fallback to setAppFrozen otherwise
            else -> {
                excludeMe.forEach {
                    when {
                        setAppFrozen(it.packageName, frozen) -> {
                            i++
                            name = it.name.toString()
                        }

                        it.applicationInfo != null -> denied = true
                    }
                }
            }
        }
        return if (denied && i == 0) null else if (i == 1) name else i.toString()
    }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean =
        packageName != BuildConfig.APPLICATION_ID && run {
            if (!frozen) UnfreezeGate.expect(packageName)
            when (HailData.workingMode) {
            HailData.MODE_OWNER_HIDE -> HPolicy.setAppHidden(packageName, frozen)
            HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, frozen)
            HailData.MODE_DHIZUKU_HIDE -> HDhizuku.setAppHidden(packageName, frozen)
            HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_SU_STOP -> !frozen || HShell.forceStopApp(packageName)
            HailData.MODE_SU_DISABLE -> HShell.setAppDisabled(packageName, frozen)
            HailData.MODE_SU_HIDE -> HShell.setAppHidden(packageName, frozen)
            HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, frozen)
            HailData.MODE_SHIZUKU_STOP -> !frozen || HShizuku.forceStopApp(packageName)
            HailData.MODE_SHIZUKU_DISABLE -> HShizuku.setAppDisabled(packageName, frozen)
            HailData.MODE_SHIZUKU_HIDE -> HShizuku.setAppHidden(packageName, frozen)
            HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, frozen)
            HailData.MODE_ISLAND_HIDE -> HIsland.setAppHidden(packageName, frozen)
            HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, frozen)
            HailData.MODE_PRIVAPP_STOP -> !frozen || HPackages.forceStopApp(packageName)
            HailData.MODE_PRIVAPP_DISABLE -> HPackages.setAppDisabled(packageName, frozen)
            else -> false
            }
        }

    /**
     * Re-applies an existing suspension so it carries the current SuspendDialogInfo.
     * The dialog info is stored together with each suspension, so toggling the biometric gate
     * only affects apps that are suspended again afterwards.
     */
    fun refreshSuspendInfo(packageName: String): Boolean = when (HailData.workingMode) {
        HailData.MODE_SHIZUKU_SUSPEND -> HShizuku.setAppSuspended(packageName, true, forceStop = false)
        HailData.MODE_SU_SUSPEND -> HShell.setAppSuspended(packageName, true)
        HailData.MODE_OWNER_SUSPEND -> HPolicy.setAppSuspended(packageName, true)
        HailData.MODE_DHIZUKU_SUSPEND -> HDhizuku.setAppSuspended(packageName, true)
        HailData.MODE_ISLAND_SUSPEND -> HIsland.setAppSuspended(packageName, true)
        else -> false
    }

    /**
     * Re-applies the suspension of every frozen app when the dialog information may have changed
     * (biometric gate toggled, working mode changed, or first launch after an update). The dialog
     * info is stored together with each suspension, so apps suspended under the old setting keep
     * the old dialog until they are suspended again.
     */
    fun syncSuspendDialogs() {
        if (!HailData.workingMode.endsWith(HailData.SUSPEND)) return
        val state = "${HailData.biometricUnfreeze}:${HailData.workingMode}"
        if (HailData.suspendDialogState == state) return
        suspendSyncScope.launch {
            val frozen = HailData.checkedList
                .filter { it.applicationInfo != null && isAppFrozen(it.packageName) }
                .map { it.packageName }
            frozen.forEach { runCatching { refreshSuspendInfo(it) } }
            HailData.setSuspendDialogState(state)
            HLogFile.append("suspend dialog refreshed for ${frozen.size} app(s), state=$state")
            mainHandler.post {
                HUI.showToast(app.getString(R.string.suspend_dialog_refreshed, frozen.size), true)
            }
        }
    }

    fun uninstallApp(packageName: String): Boolean {
        when {
            HailData.workingMode.startsWith(HailData.OWNER) ->
                if (HPolicy.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.DHIZUKU) ->
                if (HDhizuku.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SU) ->
                if (HShell.uninstallApp(packageName)) return true

            HailData.workingMode.startsWith(HailData.SHIZUKU) ->
                if (HShizuku.uninstallApp(packageName)) return true
        }
        HUI.startActivity(Intent.ACTION_DELETE, HPackages.packageUri(packageName))
        return false
    }

    fun reinstallApp(packageName: String): Boolean = when {
        HailData.workingMode.startsWith(HailData.SU) -> HShell.reinstallApp(packageName)
        HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.reinstallApp(packageName)
        else -> false
    }

    suspend fun execute(command: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        when {
            HailData.workingMode.startsWith(HailData.SU) -> HShell.execute(command, true)
            HailData.workingMode.startsWith(HailData.SHIZUKU) -> HShizuku.execute(command)
            else -> 0 to null
        }
    }
}