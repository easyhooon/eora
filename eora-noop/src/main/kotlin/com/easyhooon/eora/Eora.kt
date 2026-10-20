package com.easyhooon.eora

import android.app.Application

/** No-op Eora for release builds: same calls as the real library, but no sensors, files, UI or network. */
object Eora {
    @JvmStatic
    fun install(application: Application, config: EoraConfig) = Unit

    @JvmStatic
    fun startReport() = Unit

    @JvmStatic
    fun openDrafts() = Unit

    @JvmStatic
    var isShakeEnabled: Boolean
        get() = false
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
}
