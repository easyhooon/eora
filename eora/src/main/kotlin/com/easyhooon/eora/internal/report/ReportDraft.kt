package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.internal.metadata.MetadataEntry
import com.easyhooon.eora.internal.metadata.ReproDescription
import java.security.MessageDigest

internal enum class ArtifactKind { SCREENSHOT, MEDIA, METADATA }

internal sealed interface ArtifactStatus {
    data object Pending : ArtifactStatus

    /** Persisted while the request is in flight; a draft loaded in this state becomes [Unknown]. */
    data object Uploading : ArtifactStatus
    data class Uploaded(val remoteAttachmentId: String?, val confirmedByUser: Boolean = false) : ArtifactStatus
    data class Rejected(val reason: RejectReason, val message: String?) : ArtifactStatus
    data class Failed(val message: String?) : ArtifactStatus
    data class Unknown(val message: String?) : ArtifactStatus
}

/**
 * A final file as it will be sent. Only finished files become attachments: the unredacted capture is kept apart
 * in the draft directory and has no [DraftAttachment], so it can never be uploaded.
 */
internal data class DraftAttachment(
    val artifactId: String,
    val kind: ArtifactKind,
    /** Generated name, never the user's original file name, which may itself be sensitive. */
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: ArtifactStatus = ArtifactStatus.Pending,
)

internal enum class ReportStatus {
    /** Missing a target issue or files. */
    DRAFT,

    /** Complete, waiting for the user to approve exactly what will be sent. */
    REVIEWABLE,
    APPROVED,
    SENDING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,

    /** At least one file may or may not have arrived; the user has to check before anything is retried. */
    NEEDS_CONFIRMATION,
    CANCELLED,
}

internal sealed class DraftError(message: String) : IllegalStateException(message) {
    class Locked : DraftError("Files were already sent; this report can no longer be edited.")
    class NotReviewable : DraftError("Choose an issue and add at least one file first.")
    class NotApproved : DraftError("Review and approve the report before sending.")
    class Cancelled : DraftError("This report was cancelled.")
    class UnknownArtifact(id: String) : DraftError("No attachment $id in this report.")
}

/**
 * A report and its transfer state. Every edit bumps [revision] and so invalidates an earlier approval: approval
 * is bound to a fingerprint of what is sent, not to a flag.
 */
internal data class ReportDraft(
    val reportId: String,
    val createdAtMs: Long,
    val revision: Int = 1,
    val targetIssue: IssueRef? = null,
    val description: ReproDescription = ReproDescription(),
    val metadata: List<MetadataEntry> = emptyList(),
    val attachments: List<DraftAttachment> = emptyList(),
    val approvedFingerprint: String? = null,
    val cancelled: Boolean = false,
) {
    val isApproved: Boolean get() = approvedFingerprint != null && approvedFingerprint == fingerprint()

    /** True once any file left (or may have left) the device. From then on the content is frozen. */
    val hasTransferStarted: Boolean get() = attachments.any { it.status != ArtifactStatus.Pending }

    val isReviewable: Boolean get() = targetIssue != null && attachments.isNotEmpty()

    val status: ReportStatus
        get() {
            if (cancelled) return ReportStatus.CANCELLED
            val statuses = attachments.map { it.status }
            if (statuses.any { it is ArtifactStatus.Uploading }) return ReportStatus.SENDING
            if (!hasTransferStarted) {
                return when {
                    !isReviewable -> ReportStatus.DRAFT
                    isApproved -> ReportStatus.APPROVED
                    else -> ReportStatus.REVIEWABLE
                }
            }
            if (statuses.any { it is ArtifactStatus.Unknown }) return ReportStatus.NEEDS_CONFIRMATION
            val uploaded = statuses.count { it is ArtifactStatus.Uploaded }
            return when (uploaded) {
                statuses.size -> ReportStatus.SUCCEEDED
                0 -> ReportStatus.FAILED
                else -> ReportStatus.PARTIALLY_SUCCEEDED
            }
        }

    /** Applies a content change. Fails once transfer started, so the same revision never means two contents. */
    fun edit(change: (ReportDraft) -> ReportDraft): ReportDraft {
        if (cancelled) throw DraftError.Cancelled()
        if (hasTransferStarted) throw DraftError.Locked()
        val changed = change(this)
        return changed.copy(
            reportId = reportId,
            createdAtMs = createdAtMs,
            revision = revision + 1,
            approvedFingerprint = null,
            attachments = changed.attachments.map { it.copy(status = ArtifactStatus.Pending) },
        )
    }

    fun approve(): ReportDraft {
        if (cancelled) throw DraftError.Cancelled()
        if (!isReviewable) throw DraftError.NotReviewable()
        return copy(approvedFingerprint = fingerprint())
    }

    /**
     * Files the next user-started send should upload. Rejected files are left out because the same bytes will be
     * refused again, unknown ones because the user has to check them first, uploaded ones so nothing is attached twice.
     */
    fun sendableAttachments(): List<DraftAttachment> {
        if (cancelled || !isApproved) return emptyList()
        return attachments.filter { it.status is ArtifactStatus.Pending || it.status is ArtifactStatus.Failed }
    }

    fun markUploading(artifactId: String): ReportDraft = updateStatus(artifactId) { ArtifactStatus.Uploading }

    fun applyResult(artifactId: String, result: ArtifactUploadResult): ReportDraft = updateStatus(artifactId) {
        when (result) {
            is ArtifactUploadResult.Uploaded -> ArtifactStatus.Uploaded(result.remoteAttachmentId)
            is ArtifactUploadResult.Rejected -> ArtifactStatus.Rejected(result.reason, result.message)
            is ArtifactUploadResult.Failed -> ArtifactStatus.Failed(result.message)
            is ArtifactUploadResult.Unknown -> ArtifactStatus.Unknown(result.message)
        }
    }

    /** The user checked the tracker by hand. Only then may an unknown file be retried. */
    fun resolveUnknown(artifactId: String, arrived: Boolean): ReportDraft = updateStatus(artifactId) { current ->
        check(current is ArtifactStatus.Unknown) { "Attachment $artifactId is not waiting for confirmation." }
        if (arrived) ArtifactStatus.Uploaded(remoteAttachmentId = null, confirmedByUser = true) else ArtifactStatus.Failed("Confirmed not attached")
    }

    /** After a crash or process death nobody knows whether in-flight files arrived. */
    fun recoverInterruptedTransfer(): ReportDraft = copy(
        attachments = attachments.map {
            if (it.status is ArtifactStatus.Uploading) it.copy(status = ArtifactStatus.Unknown("Interrupted before a response")) else it
        },
    )

    fun cancel(): ReportDraft = copy(cancelled = true)

    private fun updateStatus(artifactId: String, next: (ArtifactStatus) -> ArtifactStatus): ReportDraft {
        if (attachments.none { it.artifactId == artifactId }) throw DraftError.UnknownArtifact(artifactId)
        return copy(attachments = attachments.map { if (it.artifactId == artifactId) it.copy(status = next(it.status)) else it })
    }

    internal fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val content = buildString {
            append(reportId).append('|').append(revision).append('|').append(targetIssue?.key.orEmpty())
            attachments.sortedBy { it.artifactId }.forEach { append('|').append(it.artifactId).append(':').append(it.sha256) }
        }
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
