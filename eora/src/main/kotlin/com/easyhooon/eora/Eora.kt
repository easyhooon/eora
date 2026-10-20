package com.easyhooon.eora

import android.app.Application
import com.easyhooon.eora.internal.EoraRuntime

/**
 * Entry point. Call [install] from STG code only; release builds depend on `eora-noop`, which has the same calls
 * and does nothing.
 */
object Eora {
    /** Starts listening for shakes while one of the host's screens is in the foreground. Later calls are ignored. */
    @JvmStatic
    fun install(application: Application, config: EoraConfig) {
        EoraRuntime.install(application, config)
    }

    /** Manual trigger for devices without an accelerometer, emulators, or users who cannot shake the device. */
    @JvmStatic
    fun startReport() {
        EoraRuntime.current()?.requestReport(fromShake = false)
    }

    /**
     * Records the screen until a shake, the notification's stop action or 60 s, then opens the report with the video
     * attached. Does nothing unless the app depends on `eora-recording`.
     */
    @JvmStatic
    fun startRecording() {
        EoraRuntime.current()?.startRecording(from = null)
    }

    /** Opens the list of local drafts, e.g. to finish or check a report that was sent only partly. */
    @JvmStatic
    fun openDrafts() {
        EoraRuntime.current()?.openDrafts()
    }

    /** Persisted on the device. Defaults to [ShakeConfig.enabled]. */
    @JvmStatic
    var isShakeEnabled: Boolean
        get() = EoraRuntime.current()?.isShakeEnabled ?: false
        set(value) {
            EoraRuntime.current()?.isShakeEnabled = value
        }
}
