package com.easyhooon.eora.sample

import android.app.Application

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Defined per flavor: stg installs Eora, prod does nothing, so prod never builds a config or uploader.
        QaSetup.install(this)
    }
}
