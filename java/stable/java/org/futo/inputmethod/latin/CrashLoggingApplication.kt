package org.futo.inputmethod.latin

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
//import androidx.work.Configuration
import org.acra.ACRA
import androidx.core.content.edit
import org.acra.builder.ReportBuilder
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.CrashReportDataFactory
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.addons.AddonManager
import org.futo.inputmethod.latin.uix.DataStoreHelper
import org.futo.inputmethod.latin.uix.settings.LocalDataStoreCache
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.pages.copyToClipboard
import kotlin.collections.plus

private fun Application.isMainApplicationProcess(): Boolean {
    val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }
            ?.processName
    }
    return processName == applicationInfo.processName
}

class CrashLoggingApplication : Application() /*, Configuration.Provider*/ {
    //override val workManagerConfiguration: Configuration
    //    get() = Configuration.Builder().build()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        if(isDirectBootUnlocked) {
            try {
                if (getSharedPreferences("migrate", MODE_PRIVATE).getBoolean(
                        "wiped_work",
                        false
                    ) == false
                ) {
                    deleteDatabase("androidx.work.workdb")
                    getSharedPreferences("androidx.work.util.preferences", MODE_PRIVATE)
                        .edit { clear() }
                    getSharedPreferences("migrate", MODE_PRIVATE)
                        .edit { putBoolean("wiped_work", true) }
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }

        // Wisp intentionally does not initialize the upstream project's crash sender.
        // Users can copy diagnostics and attach them to a Wisp issue.
    }

    companion object {
        var acraInitialized = false

        fun logPreferences(preferences: Preferences) {
            if(acraInitialized) {
                preferences.asMap().forEach {
                    ACRA.errorReporter.putCustomData(it.key.name, it.value.toString())
                }
            }
        }

        @Composable fun CopyLogsOption() {
            val data = LocalDataStoreCache.current
            val context = LocalContext.current
            NavigationItem(
                title = "Copy logs",
                subtitle = "May contain sensitive data",
                style = NavigationItemStyle.MiscNoArrow,
                navigate = {
                    val json = CrashReportDataFactory(context, CoreConfigurationBuilder().build())
                        .createCrashData(ReportBuilder().message("Copy logs").customData(
                            data!!.currPreferences.asMap().map {
                                it.key.name to it.value.toString()
                            }.toMap() + mapOf("Settings" to Settings.getInstance().current.dump())
                        ))
                        .toJSON()

                    context.copyToClipboard(json)
                },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (isDirectBootUnlocked && isMainApplicationProcess()) {
            DataStoreHelper.init(this)
            Thread({ AddonManager.get(this) }, "AddonManager-Init").start()
        }
    }
}
