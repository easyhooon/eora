package com.easyhooon.eora.sample

import android.content.Context
import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.CreateIssueRequest
import com.easyhooon.eora.IssueCreateResult
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.LocalExportUploader
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.UploadRequest
import kotlinx.coroutines.delay

/**
 * Demonstrates every outcome without a real tracker. Issues and files really end up in the local export folder.
 * Start the title with one of these to pick a scenario:
 * - `[lost]`: the first create request loses its response (unknown).
 * - `[partial]`: the first upload of the screenshot loses its response.
 * - `[video]`: videos are refused as too large.
 * - `[403]`: no permission to create issues.
 */
class DemoUploader(context: Context) : IssueUploader {
    private val export = LocalExportUploader(context)
    private val lostOnce = mutableSetOf<String>()
    private val titles = mutableMapOf<String, String>()

    override val displayName = "Demo tracker (local export)"

    override suspend fun createIssue(request: CreateIssueRequest): IssueCreateResult {
        delay(500)
        if (request.title.startsWith("[403]")) return IssueCreateResult.Rejected(RejectReason.PERMISSION_DENIED)
        val result = export.createIssue(request)
        if (result is IssueCreateResult.Created) titles[result.issue.key] = request.title
        if (request.title.startsWith("[lost]") && lostOnce.add(request.reportId)) return IssueCreateResult.Unknown("Response lost (demo)")
        return result
    }

    override suspend fun upload(request: UploadRequest): ArtifactUploadResult {
        delay(500)
        val title = titles[request.issueKey].orEmpty()
        if (title.startsWith("[video]") && request.mimeType.startsWith("video/")) {
            return ArtifactUploadResult.Rejected(RejectReason.TOO_LARGE)
        }
        val result = export.upload(request)
        if (title.startsWith("[partial]") && request.mimeType == "image/png" && lostOnce.add(request.artifactId)) {
            return ArtifactUploadResult.Unknown("Response lost (demo)")
        }
        return result
    }
}
