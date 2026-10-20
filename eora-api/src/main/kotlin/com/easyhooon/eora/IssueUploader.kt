package com.easyhooon.eora

import java.io.File

/**
 * Sends approved report files to an existing issue. Eora calls [upload] once per file and never for a file that
 * already succeeded, so an implementation does not need to deduplicate inside one report revision. It must not
 * create issues, edit them, or delete or replace existing attachments.
 *
 * Never put tracker tokens or client secrets in the APK: an implementation should talk to a gateway the
 * organization runs, which checks the caller's access to the project and issue.
 */
interface IssueUploader {
    /** Short label shown on the review screen, e.g. "Jira (company gateway)". */
    val displayName: String

    /** Looks up an existing issue so the user can confirm the target before sending. */
    suspend fun findIssue(issueKey: String): IssueLookupResult

    /**
     * Uploads one file. Throwing is treated as [ArtifactUploadResult.Unknown], because the file may have arrived
     * before the failure; return [ArtifactUploadResult.Failed] only when the file certainly did not arrive.
     */
    suspend fun upload(request: UploadRequest): ArtifactUploadResult
}

data class IssueRef(
    val key: String,
    val title: String? = null,
    val project: String? = null,
)

sealed interface IssueLookupResult {
    data class Found(val issue: IssueRef) : IssueLookupResult
    data object NotFound : IssueLookupResult
    data object Forbidden : IssueLookupResult
    data class Failed(val message: String) : IssueLookupResult
}

/**
 * One file of one report revision. [reportId] + [revision] + [artifactId] identify the file across retries, so a
 * gateway can recognize a repeat and answer with the earlier result instead of attaching twice.
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
    TOO_LARGE,
    UNSUPPORTED_TYPE,

    /** The same report id and revision arrived earlier with different content or target. */
    CONFLICT,
    OTHER,
}
