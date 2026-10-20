package com.easyhooon.eora.internal.capture

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager

internal sealed interface CaptureResult {
    class Success(val bitmap: Bitmap) : CaptureResult

    /** FLAG_SECURE screens are not captured; Eora tells the user instead of working around it. */
    data object Protected : CaptureResult
    data class Failed(val reason: String) : CaptureResult
}

/**
 * Copies the activity's window with PixelCopy (API 26+). Only that window is copied: dialogs, popups and other
 * windows on top are not part of the result, and SurfaceView/video content depends on the device.
 */
internal object ScreenCapturer {
    fun capture(activity: Activity, callback: (CaptureResult) -> Unit) {
        val window = activity.window
        if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            callback(CaptureResult.Protected)
            return
        }
        val decor = window.peekDecorView()
        if (decor == null || decor.width <= 0 || decor.height <= 0) {
            callback(CaptureResult.Failed("The screen has no size yet"))
            return
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    callback(CaptureResult.Success(bitmap))
                } else {
                    bitmap.recycle()
                    callback(CaptureResult.Failed("PixelCopy error $result"))
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: IllegalArgumentException) {
            bitmap.recycle()
            callback(CaptureResult.Failed(e.message ?: "Window is not ready"))
        }
    }
}
