package com.easyhooon.eora.internal.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.EoraRuntime
import java.io.File
import java.util.UUID

/**
 * Records the screen while the user reproduces a bug. Declared only by the opt-in `eora-recording` artifact, which
 * also brings the foreground-service permissions, so apps that do not want recording keep a permission-free Eora.
 *
 * Runs as a mediaProjection foreground service (required on Android 14+). Stops on a shake, the notification's
 * stop action, the system's own stop control, or after [RecordingSpec.MAX_DURATION_MS].
 */
internal class ScreenRecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish(keep = true)
            ACTION_START -> start(intent)
            // Restarted by the system without the consent token: nothing can be recorded.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (recorder != null) return
        // Must be in the foreground before the projection is created (Android 14+).
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType())
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: error("No consent data")
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data) ?: error("Consent was not granted")
            this.projection = projection
            projection.registerCallback(
                object : MediaProjection.Callback() {
                    // The user stopped sharing from the system UI, or the screen was locked.
                    override fun onStop() = finish(keep = true)
                },
                handler,
            )
            val metrics = screenMetrics()
            val (width, height) = RecordingSpec.videoSize(metrics.widthPixels, metrics.heightPixels)
            val file = File(recordingDir(this), "${UUID.randomUUID()}.mp4").also { output = it }
            val recorder = newRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(RecordingSpec.FRAME_RATE)
                setVideoEncodingBitRate(RecordingSpec.BIT_RATE)
                setMaxDuration(RecordingSpec.MAX_DURATION_MS)
                setMaxFileSize(EoraRuntime.current()?.config?.storage?.maxAttachmentBytes ?: DEFAULT_MAX_BYTES)
                setOutputFile(file.path)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                    ) {
                        finish(keep = true)
                    }
                }
                prepare()
            }
            this.recorder = recorder
            virtualDisplay = projection.createVirtualDisplay(
                "eora-recording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                handler,
            )
            recorder.start()
            EoraRuntime.current()?.onRecordingStarted()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Could not start recording", e)
            finish(keep = false)
        }
    }

    /** [keep] false discards the file, e.g. when starting failed. Safe to call more than once. */
    private fun finish(keep: Boolean) {
        if (finished) return
        finished = true
        var ok = keep
        recorder?.let {
            // stop() throws when no frame was recorded; that file is unusable.
            ok = ok && runCatching { it.stop() }.isSuccess
            it.release()
        }
        recorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        val file = output?.takeIf { ok && it.length() > 0 }
        if (file == null) output?.delete()
        EoraRuntime.current()?.onRecordingFinished(file) ?: file?.delete()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        finish(keep = true)
        super.onDestroy()
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

    /**
     * Shown only if the host app already holds the notification permission; Eora never asks for it. Shaking the
     * device stops the recording either way.
     */
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.eora_recording_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.eora_recording_title))
            .setContentText(getString(R.string.eora_recording_text))
            .setOngoing(true)
            .addAction(0, getString(R.string.eora_recording_stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "EoraRecording"
        private const val CHANNEL_ID = "eora_recording"
        private const val NOTIFICATION_ID = 0xE0A
        private const val ACTION_START = "com.easyhooon.eora.recording.START"
        private const val ACTION_STOP = "com.easyhooon.eora.recording.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024

        /** Recordings in progress or unfinished from a dead process; cleared when Eora installs. */
        fun recordingDir(context: Context): File = File(context.cacheDir, "eora-recording").apply { mkdirs() }

        /** True when the app depends on `eora-recording`, which declares this service and its permissions. */
        fun isDeclared(context: Context): Boolean = runCatching {
            context.packageManager.getServiceInfo(android.content.ComponentName(context, ScreenRecordingService::class.java), 0)
        }.isSuccess

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenRecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent = Intent(context, ScreenRecordingService::class.java).setAction(ACTION_STOP)
    }
}
