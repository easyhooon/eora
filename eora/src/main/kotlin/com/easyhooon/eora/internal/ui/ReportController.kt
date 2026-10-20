package com.easyhooon.eora.internal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.easyhooon.eora.IssueLookupResult
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
import com.easyhooon.eora.internal.storage.AttachDecision
import com.easyhooon.eora.internal.storage.DraftStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface IssueLookupState {
    data object Idle : IssueLookupState
    data object Loading : IssueLookupState
    data class Error(val message: String) : IssueLookupState
}

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

    private val _issueLookup = MutableStateFlow<IssueLookupState>(IssueLookupState.Idle)
    val issueLookup: StateFlow<IssueLookupState> = _issueLookup.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uploaderName: String get() = runtime.config.uploader.displayName

    val isSending: Boolean get() = sender.isSending(reportId)

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
        onDone()
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

    /** Writes the metadata file the user will review. Unchanged content keeps the same artifact and approval. */
    fun prepareReview(onDone: () -> Unit) = work {
        val draft = requireDraft()
        val text = MetadataReportWriter.render(draft.metadata, draft.description).toByteArray()
        val existing = draft.attachments.firstOrNull { it.kind == ArtifactKind.METADATA }
        if (existing == null || withContext(Dispatchers.IO) { !store.artifactFile(reportId, existing).readBytes().contentEquals(text) }) {
            if (!draft.hasTransferStarted) replaceArtifact(ArtifactKind.METADATA, "text/plain", "txt", text)
        }
        onDone()
    }

    fun readText(attachment: DraftAttachment): String = store.artifactFile(reportId, attachment).readText()

    fun fileOf(attachment: DraftAttachment): File = store.artifactFile(reportId, attachment)

    fun lookupIssue(rawKey: String) {
        val key = rawKey.trim().uppercase()
        if (key.isEmpty()) return
        _issueLookup.value = IssueLookupState.Loading
        runtime.scope.launch {
            val result = runCatching { runtime.config.uploader.findIssue(key) }
                .getOrElse { IssueLookupResult.Failed(it.message ?: str(R.string.eora_lookup_failed)) }
            _issueLookup.value = when (result) {
                is IssueLookupResult.Found -> {
                    guarded { commit(requireDraft().edit { it.copy(targetIssue = result.issue) }) }
                    IssueLookupState.Idle
                }
                IssueLookupResult.NotFound -> IssueLookupState.Error(str(R.string.eora_issue_not_found, key))
                IssueLookupResult.Forbidden -> IssueLookupState.Error(str(R.string.eora_issue_forbidden, key))
                is IssueLookupResult.Failed -> IssueLookupState.Error(result.message)
            }
        }
    }

    fun approve() = guarded { commit(requireDraft().approve()) }

    /** Starts a send of the files that may be sent. A second tap while sending does nothing. */
    fun send() {
        val draft = _draft.value ?: return
        runtime.scope.launch {
            try {
                sender.send(draft)
            } catch (e: DraftError) {
                _message.value = messageFor(e)
            }
        }
    }

    fun resolveUnknown(attachment: DraftAttachment, arrived: Boolean) =
        guarded { commit(requireDraft().resolveUnknown(attachment.artifactId, arrived)) }

    /** Deletes the local draft and its files. Files already attached to the tracker stay there. */
    fun deleteDraft() {
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
        commit(requireDraft().edit { draft -> draft.copy(attachments = draft.attachments.filterNot { it.kind == kind } + attachment) })
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
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        runtime.scope.launch {
            try {
                block()
            } catch (e: Exception) {
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
        else -> e.message ?: str(R.string.eora_generic_error)
    }
}
