package com.easyhooon.eora

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DEVELOPMENT PoC ONLY. Instead of creating an issue in a tracker, writes it to
 * `Android/data/<package>/files/eora-export/<LOCAL-n>/` (`issue.txt` plus the attached files) so a developer can
 * inspect exactly what would be sent (e.g. with Device Explorer). It is not a QA workflow: QA users should never
 * have to move files.
 */
class LocalExportUploader(context: Context) : IssueUploader {
    private val root = File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "eora-export")

    override val displayName: String = "Local export (development only)"

    override suspend fun createIssue(request: CreateIssueRequest): IssueCreateResult = withContext(Dispatchers.IO) {
        // Same report and revision maps to the same local issue, like the idempotency a real gateway should offer.
        val existing = root.listFiles()?.firstOrNull { File(it, "${request.reportId}-r${request.revision}").exists() }
        val next = (root.listFiles()?.mapNotNull { it.name.removePrefix("LOCAL-").toIntOrNull() }?.maxOrNull() ?: 0) + 1
        val dir = existing ?: File(root, "LOCAL-$next").apply { mkdirs() }
        File(dir, "${request.reportId}-r${request.revision}").writeText("")
        File(dir, "issue.txt").writeText("${request.title}\n\n${request.description}")
        IssueCreateResult.Created(IssueRef(key = dir.name, title = request.title, project = "LOCAL"))
    }

    override suspend fun upload(request: UploadRequest): ArtifactUploadResult = withContext(Dispatchers.IO) {
        val dir = File(root, request.issueKey)
        if (!dir.isDirectory) return@withContext ArtifactUploadResult.Rejected(RejectReason.ISSUE_NOT_FOUND)
        request.file.copyTo(File(dir, request.fileName), overwrite = true)
        ArtifactUploadResult.Uploaded(remoteAttachmentId = "local:${request.fileName}")
    }
}
