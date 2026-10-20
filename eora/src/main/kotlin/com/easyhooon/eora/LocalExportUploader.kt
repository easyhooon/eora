package com.easyhooon.eora

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DEVELOPMENT PoC ONLY. Instead of attaching to a tracker, copies the approved files to
 * `Android/data/<package>/files/eora-export/<issue>/<reportId>-r<revision>/` so a developer can inspect exactly what
 * would be sent (e.g. with Device Explorer). It is not a QA workflow: QA users should never have to move files.
 */
class LocalExportUploader(context: Context) : IssueUploader {
    private val root = File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "eora-export")

    override val displayName: String = "Local export (development only)"

    override suspend fun findIssue(issueKey: String): IssueLookupResult =
        if (ISSUE_KEY.matches(issueKey)) {
            IssueLookupResult.Found(IssueRef(key = issueKey, title = "Local export, no tracker", project = issueKey.substringBefore('-')))
        } else {
            IssueLookupResult.NotFound
        }

    override suspend fun upload(request: UploadRequest): ArtifactUploadResult = withContext(Dispatchers.IO) {
        val dir = File(File(root, request.issueKey), "${request.reportId}-r${request.revision}").apply { mkdirs() }
        request.file.copyTo(File(dir, request.fileName), overwrite = true)
        ArtifactUploadResult.Uploaded(remoteAttachmentId = "local:${request.fileName}")
    }

    private companion object {
        val ISSUE_KEY = Regex("^[A-Z][A-Z0-9_]+-[0-9]+$")
    }
}
