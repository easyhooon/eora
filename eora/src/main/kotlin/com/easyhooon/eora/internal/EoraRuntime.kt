package com.easyhooon.eora.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.easyhooon.eora.EoraConfig
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.capture.CaptureResult
import com.easyhooon.eora.internal.capture.ScreenCapturer
import com.easyhooon.eora.internal.metadata.DeviceFacts
import com.easyhooon.eora.internal.metadata.MetadataCollector
import com.easyhooon.eora.internal.shake.ShakeSensor
import com.easyhooon.eora.internal.storage.CapacityDecision
import com.easyhooon.eora.internal.storage.DraftStore
import com.easyhooon.eora.internal.ui.EoraReportActivity
import com.easyhooon.eora.internal.ui.ReportController
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Marks Eora's own screens so they are never treated as the host screen to capture. */
internal interface EoraOwnActivity

/**
 * Coordinates a report: a trigger only asks; this class checks for a resumed host screen, holds the single-report
 * lock, captures before any Eora UI appears, and drops the capture if the screen changed meanwhile.
 */
internal class EoraRuntime private constructor(
    private val app: Application,
    val config: EoraConfig,
) : Application.ActivityLifecycleCallbacks {
    /** Outlives activities, so rotating the report screen never cancels an upload. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            // A failure in one report must never crash the host app it is testing.
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unexpected error in Eora", e) },
    )
    val appContext: Context get() = app
    val store = DraftStore(File(app.noBackupFilesDir, "eora/drafts"), config.storage)
    private val prefs = app.getSharedPreferences("eora", Context.MODE_PRIVATE)
    private val shakeSensor = ShakeSensor(app, config.shake) { requestReport(fromShake = true) }
    private val controllers = mutableMapOf<String, ReportController>()

    private var resumedHost: WeakReference<Activity>? = null
    private var reportInProgress = false

    var isShakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHAKE_ENABLED, config.shake.enabled)
        set(value) {
            prefs.edit().putBoolean(KEY_SHAKE_ENABLED, value).apply()
            if (value && resumedHost?.get() != null) shakeSensor.start() else shakeSensor.stop()
        }

    var isShakeNoticeAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_NOTICE_ACK, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTICE_ACK, value).apply()

    fun requestReport(fromShake: Boolean) {
        if (reportInProgress) return
        val activity = resumedHost?.get()
        if (activity == null || activity.isFinishing) {
            if (!fromShake) toast(app.getString(R.string.eora_no_screen))
            return
        }
        reportInProgress = true
        var draftId: String? = null
        try {
            val decor = activity.window.peekDecorView()
            val expectedBytes = (decor?.width ?: 0).toLong() * (decor?.height ?: 0) * 2
            val draft = when (val created = store.create(expectedBytes)) {
                is DraftStore.CreateResult.Created -> created.draft.also { draftId = it.reportId }
                is DraftStore.CreateResult.Refused -> {
                    reportInProgress = false
                    toast(capacityMessage(created.decision))
                    return
                }
            }
            // Collected now so the values describe the captured screen, not whatever is shown later.
            val metadata = MetadataCollector.collect(
                reportId = draft.reportId,
                createdAtIso = isoUtc(draft.createdAtMs),
                environmentLabel = config.environmentLabel,
                device = deviceFacts(),
                screenIdentifier = runCatching { config.screenIdentifierProvider.screenIdentifier(activity) }.getOrNull(),
                customFields = runCatching { config.metadataProvider.customFields() }.getOrDefault(emptyMap()),
                allowedCustomKeys = config.allowedCustomKeys,
                deviceMarketingNames = config.deviceMarketingNames,
            )
            store.save(draft.copy(metadata = metadata))

            ScreenCapturer.capture(activity) { result ->
                when (result) {
                    is CaptureResult.Success -> scope.launch {
                        try {
                            val written = withContext(Dispatchers.IO) {
                                runCatching { store.originalCaptureFile(draft.reportId).outputStream().use { result.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                                    .getOrDefault(false)
                            }
                            // The capture is only valid if the very same screen is still in front.
                            if (!written || resumedHost?.get() !== activity || activity.isFinishing) {
                                result.bitmap.recycle()
                                abandon(draft.reportId, app.getString(R.string.eora_screen_changed))
                                return@launch
                            }
                            controllers[draft.reportId] = ReportController(this@EoraRuntime, draft.reportId, result.bitmap)
                            activity.startActivity(
                                EoraReportActivity.reportIntent(
                                    activity,
                                    draft.reportId,
                                    openMenu = fromShake,
                                    showShakeNotice = fromShake && !isShakeNoticeAcknowledged,
                                ),
                            )
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            controllers.remove(draft.reportId)
                            result.bitmap.recycle()
                            failCapture(draft.reportId, e)
                        }
                    }
                    CaptureResult.Protected -> abandon(draft.reportId, app.getString(R.string.eora_screen_protected))
                    is CaptureResult.Failed -> {
                        Log.w(TAG, "Capture failed: ${result.reason}")
                        abandon(draft.reportId, app.getString(R.string.eora_capture_failed))
                    }
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            failCapture(draftId, e)
        } catch (e: OutOfMemoryError) {
            // A full-screen ARGB bitmap can fail to allocate on low-memory devices.
            failCapture(draftId, e)
        }
    }

    /** Frees the single-report lock and the half-made draft when starting a report fails. */
    private fun failCapture(draftId: String?, error: Throwable) {
        Log.w(TAG, "Could not start a report", error)
        draftId?.let { runCatching { store.delete(it) } }
        reportInProgress = false
        toast(app.getString(R.string.eora_capture_failed))
    }

    fun openDrafts() {
        if (reportInProgress) return
        val context = resumedHost?.get() ?: return
        reportInProgress = true
        context.startActivity(EoraReportActivity.draftsIntent(context))
    }

    /** Returns the controller for a report, rebuilding it from disk after process death. */
    fun controllerFor(reportId: String): ReportController? =
        controllers[reportId] ?: store.load(reportId)?.let { ReportController(this, it.reportId, null) }?.also { controllers[reportId] = it }

    fun isSending(reportId: String): Boolean = controllers[reportId]?.isSending == true

    fun forgetController(reportId: String) {
        controllers.remove(reportId)?.release()
    }

    /** Called when the report screen finishes for good (not on rotation). */
    fun onReportScreenClosed() {
        reportInProgress = false
        // Idle controllers hold a full-screen capture; it is reloaded from original.png if the draft is reopened.
        controllers.entries.removeAll { (_, controller) -> !controller.isSending.also { if (!it) controller.release() } }
    }

    private fun abandon(reportId: String, message: String) {
        store.delete(reportId)
        reportInProgress = false
        toast(message)
    }

    private fun toast(message: String) = Toast.makeText(app, message, Toast.LENGTH_SHORT).show()

    private fun capacityMessage(decision: CapacityDecision) = when (decision) {
        is CapacityDecision.TooManyDrafts -> app.getString(R.string.eora_too_many_drafts, decision.limit)
        is CapacityDecision.NotEnoughSpace -> app.getString(R.string.eora_storage_full)
        CapacityDecision.Allowed -> ""
    }

    private fun deviceFacts(): DeviceFacts {
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        return DeviceFacts(
            appVersionName = info?.versionName,
            appVersionCode = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() },
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
        )
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is EoraOwnActivity) return
        resumedHost = WeakReference(activity)
        if (isShakeEnabled) shakeSensor.start()
    }

    override fun onActivityPaused(activity: Activity) {
        // In multi-window another host activity may still be resumed; only stop for the one we were tracking.
        if (resumedHost?.get() === activity) {
            resumedHost = null
            shakeSensor.stop()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG = "Eora"
        private const val KEY_SHAKE_ENABLED = "shake_enabled"
        private const val KEY_NOTICE_ACK = "shake_notice_ack"

        @Volatile
        private var instance: EoraRuntime? = null

        fun current(): EoraRuntime? = instance

        fun install(application: Application, config: EoraConfig) {
            if (instance != null) {
                Log.w(TAG, "Eora.install was called more than once; keeping the first configuration.")
                return
            }
            val runtime = EoraRuntime(application, config)
            instance = runtime
            application.registerActivityLifecycleCallbacks(runtime)
            // Expired drafts from earlier runs are removed on start, not only when a new report is created.
            runtime.scope.launch(Dispatchers.IO) { runtime.store.deleteExpired() }
        }

        fun isoUtc(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))
    }
}
