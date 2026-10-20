package com.easyhooon.eora

import java.io.File

/**
 * Creates one new issue per report and attaches the approved files to it. Eora calls [createIssue] once per report
 * revision, then [upload] once per file, and never repeats a call that succeeded. An implementation must not edit,
 * delete or replace existing issues or attachments.
 *
 * Never put tracker tokens or client secrets in the APK: an implementation should talk to a gateway the
 * organization runs, which checks the caller's access to the project. The gateway should treat [CreateIssueRequest.reportId]
 * plus [CreateIssueRequest.revision] as an idempotency key, so a repeated request returns the issue it already created.
 *
 * Both functions are called on a background thread, so blocking HTTP clients are fine.
 */
interface IssueUploader {
    /** Short label shown on the review screen, e.g. "Jira (company gateway)". */
    val displayName: String

    /**
     * Creates the issue. Throwing is treated as [IssueCreateResult.Unknown], because the issue may have been created
     * before the failure; return [IssueCreateResult.Failed] only when it certainly was not.
     */
    suspend fun createIssue(request: CreateIssueRequest): IssueCreateResult

    /**
     * Uploads one file to the issue created for this report. Throwing is treated as [ArtifactUploadResult.Unknown];
     * return [ArtifactUploadResult.Failed] only when the file certainly did not arrive.
     */
    suspend fun upload(request: UploadRequest): ArtifactUploadResult
}

/**
 * The issue to create. [description] is exactly the text the user reviewed: the allowlisted metadata followed by
 * what they typed. Rendering it in the tracker's format (e.g. Jira ADF) is the gateway's job.
 */
data class CreateIssueRequest(
    val reportId: String,
    val revision: Int,
    val title: String,
    val description: String,
)

sealed interface IssueCreateResult {
    data class Created(val issue: IssueRef) : IssueCreateResult

    /** The tracker refused; sending the same request again will not help. */
    data class Rejected(val reason: RejectReason, val message: String? = null) : IssueCreateResult

    /** The issue certainly was not created, so the user may retry. */
    data class Failed(val message: String? = null) : IssueCreateResult

    /** The issue may or may not exist (e.g. response lost). Eora asks the user to check instead of retrying. */
    data class Unknown(val message: String? = null) : IssueCreateResult
}

data class IssueRef(
    val key: String,
    val title: String? = null,
    val project: String? = null,
)

/**
 * One file of one report revision, sent to the issue created for it. [reportId] + [revision] + [artifactId] identify
 * the file across retries, so a gateway can recognize a repeat and answer with the earlier result instead of
 * attaching twice.
 */
data class UploadRequest(
    val reportId: String,
    val revision: Int,
    val issueKey: String,
    val artifactId: String,
    val file: File,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface ArtifactUploadResult {
    /** The tracker accepted the file. */
    data class Uploaded(val remoteAttachmentId: String?) : ArtifactUploadResult

    /** The tracker refused the file; retrying the same file will not help. */
    data class Rejected(val reason: RejectReason, val message: String? = null) : ArtifactUploadResult

    /** The file certainly did not arrive (e.g. no connection before sending), so the user may retry it. */
    data class Failed(val message: String? = null) : ArtifactUploadResult

    /** The file may or may not have arrived (e.g. response lost). Eora asks the user to check instead of retrying. */
    data class Unknown(val message: String? = null) : ArtifactUploadResult
}

enum class RejectReason {
    ISSUE_NOT_FOUND,
    PERMISSION_DENIED,

    /** The title or description was refused, e.g. too long or a required field is missing. */
    INVALID_FIELDS,
    TOO_LARGE,
    UNSUPPORTED_TYPE,

    /** The same report id and revision arrived earlier with different content. */
    CONFLICT,
    OTHER,
}
