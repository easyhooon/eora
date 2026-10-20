package com.easyhooon.eora.internal.recording

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.easyhooon.eora.internal.EoraOwnActivity
import com.easyhooon.eora.internal.EoraRuntime

/** Invisible activity that shows the system's screen-capture consent, which is asked for every recording. */
internal class RecordingConsentActivity : ComponentActivity(), EoraOwnActivity {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(this, ScreenRecordingService.startIntent(this, result.resultCode, data))
        } else {
            EoraRuntime.current()?.onRecordingDeclined()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated after rotation while the dialog is open: the launcher result still arrives, don't ask twice.
        if (savedInstanceState == null) {
            consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }
}
