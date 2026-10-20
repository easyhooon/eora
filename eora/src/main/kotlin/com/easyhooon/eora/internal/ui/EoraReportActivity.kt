package com.easyhooon.eora.internal.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.easyhooon.eora.internal.EoraOwnActivity
import com.easyhooon.eora.internal.EoraRuntime

/** Hosts the whole report flow. Never captured itself: Eora captures the host screen before opening it. */
internal class EoraReportActivity : ComponentActivity(), EoraOwnActivity {
    private var runtime: EoraRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark status bar icons on the light UI; Scaffold applies the system bar insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val runtime = EoraRuntime.current()
        if (runtime == null) {
            finish()
            return
        }
        this.runtime = runtime
        val reportId = intent.getStringExtra(EXTRA_REPORT_ID)
        val showNotice = savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHOW_SHAKE_NOTICE, false)
        setContent {
            MaterialTheme {
                EoraApp(runtime = runtime, initialReportId = reportId, showShakeNotice = showNotice, onClose = ::finish)
            }
        }
    }

    override fun onDestroy() {
        // Rotation recreates the activity; only a real exit frees the single-report lock.
        if (isFinishing) runtime?.onReportScreenClosed()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REPORT_ID = "eora.report_id"
        private const val EXTRA_SHOW_SHAKE_NOTICE = "eora.show_shake_notice"

        fun reportIntent(context: Context, reportId: String, showShakeNotice: Boolean): Intent =
            Intent(context, EoraReportActivity::class.java)
                .putExtra(EXTRA_REPORT_ID, reportId)
                .putExtra(EXTRA_SHOW_SHAKE_NOTICE, showShakeNotice)

        fun draftsIntent(context: Context): Intent = Intent(context, EoraReportActivity::class.java)
    }
}
