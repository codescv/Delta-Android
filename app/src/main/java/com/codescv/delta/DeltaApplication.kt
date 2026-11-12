package com.codescv.delta

import android.app.Application

class DeltaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: DeltaApplication
            private set
    }
}
