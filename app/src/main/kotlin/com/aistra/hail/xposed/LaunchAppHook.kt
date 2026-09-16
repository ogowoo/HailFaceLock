package com.aistra.hail.xposed

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.aistra.hail.BuildConfig
import com.aistra.hail.app.HailApi
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.HTarget
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class LaunchAppHook : XposedModule() {
    @Throws(Throwable::class)
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (!HTarget.O || !param.isFirstPackage || param.packageName == BuildConfig.APPLICATION_ID) {
            return
        }
        if (param.packageName == SYSTEM_FRAMEWORK_PACKAGE) {
            hookSuspendedDialog()
            return
        }
        hookLauncherApp()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun hookLauncherApp() {
        hook(
            Activity::class.java.getMethod(
                "startActivityForResult", Intent::class.java, Int::class.java, Bundle::class.java
            )
        ).intercept { unsuspendBeforeLaunch(it) }
        hook(
            TileService::class.java.getMethod(
                "startActivityAndCollapse", Intent::class.java
            )
        ).intercept { unsuspendBeforeLaunch(it) }
        hook(
            ContextWrapper::class.java.getMethod(
                "startActivity", Intent::class.java
            )
        ).intercept { unsuspendBeforeLaunch(it) }
        hook(
            ContextWrapper::class.java.getMethod(
                "startActivity", Intent::class.java, Bundle::class.java
            )
        ).intercept { unsuspendBeforeLaunch(it) }
    }

    fun unsuspendBeforeLaunch(param: XposedInterface.Chain): Any {
        if (param.args.isNotEmpty() && param.args[0] != null) {
            val intent = param.args[0] as Intent
            var packageName = intent.getPackage()
            val component = intent.component
            if (packageName == null && component != null) {
                packageName = component.packageName
            }
            val context = param.thisObject as Context
            if (packageName != null && packageName != context.packageName && packageName != BuildConfig.APPLICATION_ID) {
                unsuspendApp(context, packageName)
            }
        }
        return param.proceed()
    }

    private fun unsuspendApp(context: Context, packageName: String) {
        val packageManager = context.packageManager
        val method = packageManager.javaClass.getMethod("isPackageSuspended", String::class.java)
        if (method.invoke(packageManager, packageName) as Boolean) {
            context.startActivity(HailApi.getIntentForPackage(HailApi.ACTION_UNFREEZE, packageName))

            /**
             * It took about 500 milliseconds from [Context.startActivity]
             * to start [com.aistra.hail.ui.api.ApiActivity] and successfully unfreeze.
             * We lack communication between applications, we can only wait.
             */
            Thread.sleep(300)
            repeat(6) {
                if (!(method.invoke(packageManager, packageName) as Boolean)) return@repeat
                Thread.sleep(75)
            }
        }
    }

    /**
     * Hooks the system's "app is suspended" dialog inside the system framework process.
     *
     * When the user taps the neutral button, [android.content.pm.SuspendDialogInfo]'s
     * BUTTON_ACTION_UNSUSPEND makes the framework unsuspend the app directly, with no chance for
     * Hail to verify the user first. Rewriting `which` to another button before calling through
     * skips that branch (the dialog still finishes), and starting Hail's ApiActivity with
     * ACTION_LAUNCH lets Hail show the biometric prompt and unfreeze only after it passes.
     *
     * If anything here does not apply -- no dialog info, a different button action, an unresolvable
     * target -- the original behaviour is kept.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hookSuspendedDialog() = runCatching {
        val clazz = Class.forName("com.android.internal.app.SuspendedAppActivity")
        val onClick = clazz.getDeclaredMethod("onClick", DialogInterface::class.java, Int::class.java)
        val suspendedPackage = clazz.getDeclaredField("mSuspendedPackage").apply { isAccessible = true }
        val neutralAction = clazz.getDeclaredField("mNeutralButtonAction").apply { isAccessible = true }
        hook(onClick).intercept { param: XposedInterface.Chain ->
            runCatching {
                val which = param.args.getOrNull(1) as? Int
                val activity = param.thisObject as? Activity
                if (which != DialogInterface.BUTTON_NEUTRAL || activity == null) return@runCatching param.proceed()
                // Let the framework keep handling everything but the plain unsuspend action.
                if (neutralAction.getInt(activity) != BUTTON_ACTION_UNSUSPEND) return@runCatching param.proceed()
                val pkg = suspendedPackage.get(activity) as? String ?: return@runCatching param.proceed()
                val intent = Intent(HailApi.ACTION_LAUNCH)
                    .setComponent(ComponentName(BuildConfig.APPLICATION_ID, API_ACTIVITY))
                    .putExtra(HailData.KEY_PACKAGE, pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (activity.packageManager.resolveActivity(intent, 0) == null) {
                    log("no activity to verify $pkg, keeping the framework behaviour")
                    return@runCatching param.proceed()
                }
                log("redirecting unsuspend of $pkg to Hail for verification")
                activity.startActivity(intent)
                // Any other button value takes the switch's default path: no unsuspend, dialog closes.
                param.args[1] = DialogInterface.BUTTON_NEGATIVE
                param.proceed()
            }.getOrElse { param.proceed() }
        }
    }.onFailure { log("hookSuspendedDialog failed: $it") }

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        private const val TAG = "HailHook"
        private const val SYSTEM_FRAMEWORK_PACKAGE = "android"
        private const val API_ACTIVITY = "com.aistra.hail.ui.api.ApiActivity"
        private const val BUTTON_ACTION_UNSUSPEND = 1
    }
}
