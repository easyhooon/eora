package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueCreateResult
import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.internal.metadata.MetadataEntry
import com.easyhooon.eora.internal.metadata.ReproDescription
import java.security.MessageDigest

/** [METADATA] only appears in drafts saved before the report text became the issue description. */
internal enum class ArtifactKind { SCREENSHOT, MEDIA, METADATA }

/** The new issue for this report. Files are attached only after it is [Created]. */
internal sealed interface IssueStatus {
    data object NotCreated : IssueStatus

    /** Persisted while the request is in flight; a draft loaded in this state becomes [Unknown]. */
    data object Creating : IssueStatus
    data class Created(val issue: IssueRef, val confirmedByUser: Boolean = false) : IssueStatus
    data class Rejected(val reason: RejectReason, val message: String?) : IssueStatus
    data class Failed(val message: String?) : IssueStatus
    data class Unknown(val message: String?) : IssueStatus
}

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
    /** Missing a title or files. */
    DRAFT,

    /** Complete, waiting for the user to approve exactly what will be sent. */
    REVIEWABLE,
    APPROVED,
    SENDING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,

    /** The issue or a file may or may not have arrived; the user has to check before anything is retried. */
    NEEDS_CONFIRMATION,
    CANCELLED,
}

internal sealed class DraftError(message: String) : IllegalStateException(message) {
    class Locked : DraftError("Files were already sent; this report can no longer be edited.")
    class NotReviewable : DraftError("Enter a title and add at least one file first.")
    class NotApproved : DraftError("Review and approve the report before sending.")
    class Cancelled : DraftError("This report was cancelled.")
    class UnknownArtifact(id: String) : DraftError("No attachment $id in this report.")
    class IssueNotCreated : DraftError("The issue has not been created yet.")
}

/**
 * A report and its transfer state: first one new issue, then its files. Every edit bumps [revision] and so
 * invalidates an earlier approval: approval is bound to a fingerprint of what is sent, not to a flag.
 */
internal data class ReportDraft(
    val reportId: String,
    val createdAtMs: Long,
    val revision: Int = 1,
    val issue: IssueStatus = IssueStatus.NotCreated,
    val description: ReproDescription = ReproDescription(),
    val metadata: List<MetadataEntry> = emptyList(),
    val attachments: List<DraftAttachment> = emptyList(),
    val approvedFingerprint: String? = null,
    val cancelled: Boolean = false,
) {
    val isApproved: Boolean get() = approvedFingerprint != null && approvedFingerprint == fingerprint()

    val createdIssue: IssueRef? get() = (issue as? IssueStatus.Created)?.issue

    /**
     * True once the issue or any file reached (or may have reached) the tracker. From then on the content is frozen.
     * A creation that certainly failed or was refused created nothing, so the user may still fix and resend.
     */
    val hasTransferStarted: Boolean
        get() = when (issue) {
            IssueStatus.NotCreated, is IssueStatus.Failed, is IssueStatus.Rejected -> false
            else -> true
        } || attachments.any { it.status != ArtifactStatus.Pending }

    val isReviewable: Boolean get() = description.hasTitle && attachments.isNotEmpty()

    /** Whether a user-started send would do anything: create the issue, or upload files still missing from it. */
    val canSend: Boolean
        get() = !cancelled && isApproved && when (issue) {
            IssueStatus.NotCreated, is IssueStatus.Failed -> true
            is IssueStatus.Created -> sendableAttachments().isNotEmpty()
            else -> false
        }

    val status: ReportStatus
        get() {
            if (cancelled) return ReportStatus.CANCELLED
            val statuses = attachments.map { it.status }
            if (issue == IssueStatus.Creating || statuses.any { it is ArtifactStatus.Uploading }) return ReportStatus.SENDING
            if (issue is IssueStatus.Failed || issue is IssueStatus.Rejected) return ReportStatus.FAILED
            if (!hasTransferStarted) {
                return when {
                    !isReviewable -> ReportStatus.DRAFT
                    isApproved -> ReportStatus.APPROVED
                    else -> ReportStatus.REVIEWABLE
                }
            }
            if (issue is IssueStatus.Unknown || statuses.any { it is ArtifactStatus.Unknown }) return ReportStatus.NEEDS_CONFIRMATION
            // Without an issue nothing reached the tracker.
            if (issue !is IssueStatus.Created) return ReportStatus.FAILED
            val uploaded = statuses.count { it is ArtifactStatus.Uploaded }
            // The issue exists, so anything short of every file is a partial success.
            return if (uploaded == statuses.size) ReportStatus.SUCCEEDED else ReportStatus.PARTIALLY_SUCCEEDED
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
            issue = IssueStatus.NotCreated,
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
        if (cancelled || !isApproved || issue !is IssueStatus.Created) return emptyList()
        return attachments.filter { it.status is ArtifactStatus.Pending || it.status is ArtifactStatus.Failed }
    }

    fun markCreating(): ReportDraft = copy(issue = IssueStatus.Creating)

    fun applyCreateResult(result: IssueCreateResult): ReportDraft = copy(
        issue = when (result) {
            is IssueCreateResult.Created -> IssueStatus.Created(result.issue)
            is IssueCreateResult.Rejected -> IssueStatus.Rejected(result.reason, result.message)
            is IssueCreateResult.Failed -> IssueStatus.Failed(result.message)
            is IssueCreateResult.Unknown -> IssueStatus.Unknown(result.message)
        },
    )

    /**
     * The user checked the tracker by hand. [createdKey] is the key of the issue they found, or null if it does not
     * exist; only then may creation be retried.
     */
    fun resolveUnknownIssue(createdKey: String?): ReportDraft {
        check(issue is IssueStatus.Unknown) { "The issue is not waiting for confirmation." }
        // Files go to whatever key is entered, so accept only something shaped like an issue key.
        check(createdKey == null || ISSUE_KEY.matches(createdKey.trim())) { "Not an issue key: $createdKey" }
        return copy(
            issue = if (createdKey.isNullOrBlank()) {
                IssueStatus.Failed("Confirmed not created")
            } else {
                IssueStatus.Created(IssueRef(createdKey.trim()), confirmedByUser = true)
            },
        )
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
        issue = if (issue == IssueStatus.Creating) IssueStatus.Unknown("Interrupted before a response") else issue,
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
            // The revision covers the title, description and metadata, which are sent as the issue text.
            append(reportId).append('|').append(revision)
            attachments.sortedBy { it.artifactId }.forEach { append('|').append(it.artifactId).append(':').append(it.sha256) }
        }
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private val ISSUE_KEY = Regex("^[A-Z][A-Z0-9_]+-[0-9]+$")
