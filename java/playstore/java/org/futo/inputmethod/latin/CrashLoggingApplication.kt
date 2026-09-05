package org.futo.inputmethod.latin

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.datastore.preferences.core.Preferences
import androidx.core.content.edit
//import androidx.work.Configuration
import org.futo.inputmethod.latin.uix.isDirectBootUnlocked
import org.futo.inputmethod.latin.uix.addons.AddonManager
import org.futo.inputmethod.latin.uix.DataStoreHelper

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

    companion object {
        fun logPreferences(preferences: Preferences) {

        }

        fun CopyLogsOption() {

        }
    }

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
    }

    override fun onCreate() {
        super.onCreate()
        if (isDirectBootUnlocked && isMainApplicationProcess()) {
            DataStoreHelper.init(this)
            Thread({ AddonManager.get(this) }, "AddonManager-Init").start()
        }
    }
}
