package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.UploadRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Uploads the sendable files of an approved draft one at a time and saves the state after every step, so a crash
 * mid-send leaves at most one file in [ArtifactStatus.Uploading] (recovered as unknown on the next load).
 *
 * Only one send per report runs at a time: a second tap while sending returns null instead of uploading twice.
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
            val issueKey = draft.targetIssue?.key ?: throw DraftError.NotReviewable()
            if (!draft.isApproved) throw DraftError.NotApproved()
            var current = draft
            for (attachment in draft.sendableAttachments()) {
                current = current.markUploading(attachment.artifactId).also(save)
                val result = try {
                    uploader.upload(
                        UploadRequest(
                            reportId = draft.reportId,
                            revision = draft.revision,
                            issueKey = issueKey,
                            artifactId = attachment.artifactId,
                            file = fileOf(draft.reportId, attachment),
                            fileName = attachment.fileName,
                            mimeType = attachment.mimeType,
                            sizeBytes = attachment.sizeBytes,
                            sha256 = attachment.sha256,
                        ),
                    )
                } catch (e: CancellationException) {
                    // The request may already have reached the tracker, so cancelling never means "not sent".
                    withContext(NonCancellable) {
                        save(current.applyResult(attachment.artifactId, ArtifactUploadResult.Unknown("Cancelled while sending")))
                    }
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    ArtifactUploadResult.Unknown(e.message ?: e.javaClass.simpleName)
                }
                current = current.applyResult(attachment.artifactId, result).also(save)
            }
            return current
        } finally {
            inFlight.remove(draft.reportId)
        }
    }

    fun isSending(reportId: String): Boolean = reportId in inFlight
}
