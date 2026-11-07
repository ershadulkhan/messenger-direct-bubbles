package com.ershad.directbubbles

import android.app.Application
import com.ershad.directbubbles.core.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
