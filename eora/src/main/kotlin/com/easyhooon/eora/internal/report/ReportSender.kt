package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.CreateIssueRequest
import com.easyhooon.eora.IssueCreateResult
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.UploadRequest
import com.easyhooon.eora.internal.metadata.MetadataReportWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends an approved draft: creates its issue if that has not happened yet, then uploads the sendable files one at
 * a time. The state is saved before and after every request, so a crash mid-send leaves at most one step in
 * flight, which is recovered as unknown on the next load.
 *
 * Only one send per report runs at a time: a second tap while sending returns null instead of sending twice.
 */
internal class ReportSender(
    private val uploader: IssueUploader,
    private val fileOf: (reportId: String, attachment: DraftAttachment) -> File,
    private val save: (ReportDraft) -> Unit,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    suspend fun send(draft: ReportDraft): ReportDraft? {
        if (!inFlight.add(draft.reportId)) return null
        try {
            if (!draft.isReviewable) throw DraftError.NotReviewable()
            if (!draft.isApproved) throw DraftError.NotApproved()
            var current = draft
            if (current.issue == IssueStatus.NotCreated || current.issue is IssueStatus.Failed) {
                current = createIssue(current)
            }
            val issueKey = current.createdIssue?.key ?: return current
            for (attachment in current.sendableAttachments()) {
                current = upload(current, issueKey, attachment)
            }
            return current
        } finally {
            inFlight.remove(draft.reportId)
        }
    }

    fun isSending(reportId: String): Boolean = reportId in inFlight

    private suspend fun createIssue(draft: ReportDraft): ReportDraft {
        val creating = draft.markCreating().also(save)
        val request = CreateIssueRequest(
            reportId = draft.reportId,
            revision = draft.revision,
            title = draft.description.title.trim(),
            // The same text the review screen showed.
            description = MetadataReportWriter.render(draft.metadata, draft.description),
        )
        val result = guarded(
            onCancel = { save(creating.applyCreateResult(IssueCreateResult.Unknown("Cancelled while creating"))) },
            onError = { IssueCreateResult.Unknown(it) },
        ) { uploader.createIssue(request) }
        return creating.applyCreateResult(result).also(save)
    }

    private suspend fun upload(draft: ReportDraft, issueKey: String, attachment: DraftAttachment): ReportDraft {
        val uploading = draft.markUploading(attachment.artifactId).also(save)
        val request = UploadRequest(
            reportId = draft.reportId,
            revision = draft.revision,
            issueKey = issueKey,
            artifactId = attachment.artifactId,
            file = fileOf(draft.reportId, attachment),
            fileName = attachment.fileName,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            sha256 = attachment.sha256,
        )
        val result = guarded(
            onCancel = { save(uploading.applyResult(attachment.artifactId, ArtifactUploadResult.Unknown("Cancelled while sending"))) },
            onError = { ArtifactUploadResult.Unknown(it) },
        ) { uploader.upload(request) }
        return uploading.applyResult(attachment.artifactId, result).also(save)
    }

    /**
     * A request may already have reached the tracker when it fails or is cancelled, so neither ever means
     * "not sent": exceptions become unknown, and cancellation records unknown before propagating.
     */
    private suspend fun <T> guarded(onCancel: () -> Unit, onError: (String) -> T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { onCancel() }
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        onError(e.message ?: e.javaClass.simpleName)
    }
}
