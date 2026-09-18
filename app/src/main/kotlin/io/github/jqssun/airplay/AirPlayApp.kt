package io.github.jqssun.airplay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        var visibleActivities = 0
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val idleCheck = object : Runnable {
            override fun run() {
                if (visibleActivities != 0) return
                io.github.jqssun.airplay.service.AirPlayService.stopIfIdleOutsideApp()
                main.postDelayed(this, 3000)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { visibleActivities++; main.removeCallbacks(idleCheck) }
            override fun onActivityStopped(activity: android.app.Activity) { visibleActivities--; if (visibleActivities == 0) main.postDelayed(idleCheck, 500) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        // Enable the newly requested receiver behavior once on upgrade. Later
        // choices in Settings survive every subsequent process start.
        if (!prefs.getBoolean("background_handoff_v1", false)) {
            prefs.edit()
                .putBoolean(Prefs.RUN_IN_BACKGROUND, true)
                .putBoolean(Prefs.LAUNCH_ON_CONNECT, true)
                .putBoolean("background_handoff_v1", true)
                .apply()
        }
    }
}
