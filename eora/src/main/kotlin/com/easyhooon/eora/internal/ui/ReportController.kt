package com.easyhooon.eora.internal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.easyhooon.eora.R
import com.easyhooon.eora.internal.EoraRuntime
import com.easyhooon.eora.internal.editor.Annotation
import com.easyhooon.eora.internal.editor.AnnotationRenderer
import com.easyhooon.eora.internal.metadata.MetadataReportWriter
import com.easyhooon.eora.internal.metadata.ReproDescription
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.DraftAttachment
import com.easyhooon.eora.internal.report.DraftError
import com.easyhooon.eora.internal.report.ReportDraft
import com.easyhooon.eora.internal.report.ReportSender
import com.easyhooon.eora.internal.report.ReportStatus
import com.easyhooon.eora.internal.storage.AttachDecision
import com.easyhooon.eora.internal.storage.DraftStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of one report screen. Lives in [EoraRuntime], not in the activity, so rotation keeps annotations and
 * never cancels an upload.
 */
internal class ReportController(
    private val runtime: EoraRuntime,
    val reportId: String,
    initialOriginal: Bitmap?,
) {
    private val store = runtime.store
    private val sender = ReportSender(runtime.config.uploader, store::artifactFile) { saved ->
        store.save(saved)
        _draft.value = saved
    }

    private val _draft = MutableStateFlow(loadDraft())
    val draft: StateFlow<ReportDraft?> = _draft.asStateFlow()

    private val _original = MutableStateFlow(initialOriginal)
    val original: StateFlow<Bitmap?> = _original.asStateFlow()

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uploaderName: String get() = runtime.config.uploader.displayName

    val isSending: Boolean get() = sender.isSending(reportId)

    private var workJob: Job? = null

    /**
     * Annotations live only in memory. A controller rebuilt from disk (process death, memory trim) has none, so a
     * screenshot it did not render itself would lose its marks, including hidden areas, if rendered again.
     */
    private var renderedScreenshot = false

    private fun loadDraft(): ReportDraft? {
        val loaded = store.load(reportId) ?: return null
        // Nothing is sending yet for a fresh controller, so an Uploading file was cut off by process death.
        val recovered = loaded.recoverInterruptedTransfer()
        if (recovered != loaded) store.save(recovered)
        return recovered
    }

    fun loadOriginalIfNeeded() {
        if (_original.value != null) return
        runtime.scope.launch {
            _original.value = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(store.originalCaptureFile(reportId).path)
            }
        }
    }

    fun hasOriginal(): Boolean = _original.value != null || store.originalCaptureFile(reportId).isFile

    fun addAnnotation(annotation: Annotation) {
        _annotations.value = _annotations.value + annotation
    }

    fun undo() {
        _annotations.value = _annotations.value.dropLast(1)
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Burns the annotations into the final screenshot, replacing any earlier one. */
    fun finishEditing(onDone: () -> Unit) = work {
        val source = _original.value ?: error("The capture is no longer available")
        val png = withContext(Dispatchers.Default) {
            val rendered = AnnotationRenderer.render(source, _annotations.value)
            ByteArrayOutputStream().also { AnnotationRenderer.writePng(rendered, it); rendered.recycle() }.toByteArray()
        }
        replaceArtifact(ArtifactKind.SCREENSHOT, "image/png", "png", png)
        renderedScreenshot = true
        onDone()
    }

    /** True when editing again would start from the bare capture and replace a screenshot whose marks are gone. */
    fun reEditingStartsOver(): Boolean =
        !renderedScreenshot && _draft.value?.attachments?.any { it.kind == ArtifactKind.SCREENSHOT } == true

    /** Whether deleting this draft throws away more than the bare capture. */
    fun hasUserContent(): Boolean {
        val draft = _draft.value ?: return false
        return draft.description != ReproDescription() || draft.attachments.any { it.kind != ArtifactKind.METADATA }
    }

    fun updateDescription(description: ReproDescription) {
        val current = _draft.value ?: return
        if (current.description == description) return
        guarded { commit(current.edit { it.copy(description = description) }) }
    }

    fun addMedia(uri: Uri) = work {
        val resolver = runtime.appContext.contentResolver
        val mime = resolver.getType(uri)
        if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
            _message.value = str(R.string.eora_only_media)
            return@work
        }
        val draft = requireDraft()
        val declaredSize = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: 0L
        val decision = withContext(Dispatchers.IO) { store.checkAttach(draft, declaredSize) }
        if (decision != AttachDecision.Allowed) {
            _message.value = attachMessage(decision)
            return@work
        }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val attachment = try {
            withContext(Dispatchers.IO) {
                resolver.openInputStream(uri)?.use { store.writeArtifact(reportId, ArtifactKind.MEDIA, mime, extension, it) }
            } ?: error(str(R.string.eora_read_failed))
        } catch (e: DraftStore.FileTooLargeException) {
            _message.value = attachMessage(AttachDecision.FileTooLarge(e.limitBytes))
            return@work
        }
        commit(requireDraft().edit { it.copy(attachments = it.attachments + attachment) })
    }

    fun removeAttachment(attachment: DraftAttachment) {
        val draft = _draft.value ?: return
        guarded {
            commit(draft.edit { it.copy(attachments = it.attachments.filterNot { a -> a.artifactId == attachment.artifactId }) })
            store.deleteArtifact(reportId, attachment)
        }
    }

    /**
     * The text sent as the issue description, rendered from the draft. The review screen shows this and the sender
     * renders it the same way, so the user approves exactly what is sent.
     */
    fun issueText(draft: ReportDraft): String = MetadataReportWriter.render(draft.metadata, draft.description)

    fun readText(attachment: DraftAttachment): String = store.artifactFile(reportId, attachment).readText()

    fun fileOf(attachment: DraftAttachment): File = store.artifactFile(reportId, attachment)

    fun approve() = guarded { commit(requireDraft().approve()) }

    /** Starts a send of the files that may be sent. A second tap while sending does nothing. */
    fun send() {
        val draft = _draft.value ?: return
        // Off the main thread: host uploaders may use blocking HTTP clients.
        runtime.scope.launch(Dispatchers.IO) {
            try {
                val result = sender.send(draft) ?: return@launch
                // The unredacted capture is only needed for editing, which is over once everything is attached.
                if (result.status == ReportStatus.SUCCEEDED) store.originalCaptureFile(reportId).delete()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // DraftError, or an IOException while saving state; the saved state is recovered on next load.
                _message.value = messageFor(e)
            }
        }
    }


    /** [createdKey] is the issue the user found in the tracker, or null when there is none. */
    fun resolveUnknownIssue(createdKey: String?) = guarded { commit(requireDraft().resolveUnknownIssue(createdKey)) }

    fun resolveUnknown(attachment: DraftAttachment, arrived: Boolean) =
        guarded { commit(requireDraft().resolveUnknown(attachment.artifactId, arrived)) }

    /** Deletes the local draft and its files. Files already attached to the tracker stay there. */
    fun deleteDraft() {
        // A render or copy still running would otherwise commit into the deleted draft after it is gone.
        workJob?.cancel()
        workJob = null
        _draft.value = null
        store.delete(reportId)
        runtime.forgetController(reportId)
    }

    /** Drops the capture without recycling it: a composition that is still on screen may draw it once more. */
    fun release() {
        _original.value = null
    }

    private suspend fun replaceArtifact(kind: ArtifactKind, mime: String, extension: String, bytes: ByteArray) {
        val attachment = withContext(Dispatchers.IO) { store.writeArtifact(reportId, kind, mime, extension, ByteArrayInputStream(bytes)) }
        val previous = requireDraft().attachments.filter { it.kind == kind }
        try {
            commit(requireDraft().edit { draft -> draft.copy(attachments = draft.attachments.filterNot { it.kind == kind } + attachment) })
        } catch (e: IllegalStateException) {
            store.deleteArtifact(reportId, attachment)
            throw e
        }
        previous.forEach { store.deleteArtifact(reportId, it) }
    }

    private fun commit(draft: ReportDraft) {
        store.save(draft)
        _draft.value = draft
    }

    private fun requireDraft(): ReportDraft = _draft.value ?: error("This draft no longer exists")

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            _message.value = messageFor(e)
        } catch (e: IOException) {
            _message.value = messageFor(e)
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        workJob = runtime.scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                _message.value = messageFor(e)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun attachMessage(decision: AttachDecision) = when (decision) {
        is AttachDecision.TooManyFiles -> str(R.string.eora_too_many_files, decision.limit)
        is AttachDecision.FileTooLarge -> str(R.string.eora_file_too_large, (decision.limitBytes / (1024 * 1024)).toInt())
        is AttachDecision.NotEnoughSpace -> str(R.string.eora_attach_storage_full)
        AttachDecision.Allowed -> ""
    }

    private fun str(id: Int, vararg args: Any): String = runtime.appContext.getString(id, *args)

    private fun messageFor(e: Exception): String = when (e) {
        is DraftError.Locked -> str(R.string.eora_locked)
        is DraftError.NotReviewable -> str(R.string.eora_not_reviewable)
        is DraftError.NotApproved -> str(R.string.eora_not_approved)
        is DraftError.IssueNotCreated -> str(R.string.eora_issue_not_created)
        else -> e.message ?: str(R.string.eora_generic_error)
    }
}
