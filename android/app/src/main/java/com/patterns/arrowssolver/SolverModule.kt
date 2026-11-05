package com.patterns.arrowssolver

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

/** JS bridge for the setup screen; the solver itself lives in [SolverService]. */
class SolverModule(private val ctx: ReactApplicationContext) : NativeSolverSpec(ctx) {
    override fun getName() = NAME

    override fun isServiceEnabled(): Boolean {
        val me = ComponentName(ctx, SolverService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    override fun openAccessibilitySettings() {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Android 13+ blocks accessibility for side-loaded apps until "Allow restricted settings" is chosen here. */
    override fun openAppInfo() {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun openGame(): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(SolverService.GAME) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    override fun getStatus(): String =
        if (SolverService.instance == null) "Bubble service is off." else SolverService.lastStatus

    override fun getStats(): WritableMap {
        val p = ctx.getSharedPreferences(SolverService.STATS, android.content.Context.MODE_PRIVATE)
        return Arguments.createMap().apply {
            putInt("levels", p.getInt("levels", 0)); putInt("arrows", p.getInt("arrows", 0)); putDouble("ms", p.getLong("ms", 0).toDouble())
        }
    }

    companion object { const val NAME = "NativeSolver" }
}

class SolverPackage : BaseReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        if (name == SolverModule.NAME) SolverModule(reactContext) else null

    override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
        mapOf(SolverModule.NAME to ReactModuleInfo(SolverModule.NAME, SolverModule.NAME, false, false, false, true))
    }
}
