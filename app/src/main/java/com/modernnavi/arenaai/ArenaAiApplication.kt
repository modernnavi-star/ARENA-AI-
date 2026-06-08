package com.modernnavi.arenaai

import android.app.Application
import com.google.firebase.FirebaseApp

class ArenaAiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
