package com.easyhooon.eora.sample

import android.content.Context
import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueLookupResult
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.LocalExportUploader
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.UploadRequest
import kotlinx.coroutines.delay

/**
 * Demonstrates every outcome without a real tracker. Files really end up in the local export folder.
 * - `DEMO-1`: everything succeeds.
 * - `DEMO-2`: the first try of each metadata file loses its response (unknown).
 * - `DEMO-3`: videos are refused as too large.
 * - `DEMO-403`: no access to the issue.
 */
class DemoUploader(context: Context) : IssueUploader {
    private val export = LocalExportUploader(context)
    private val lostOnce = mutableSetOf<String>()

    override val displayName = "Demo tracker (local export)"

    override suspend fun findIssue(issueKey: String): IssueLookupResult {
        delay(300)
        if (issueKey == "DEMO-403") return IssueLookupResult.Forbidden
        return export.findIssue(issueKey)
    }

    override suspend fun upload(request: UploadRequest): ArtifactUploadResult {
        delay(500)
        if (request.issueKey == "DEMO-3" && request.mimeType.startsWith("video/")) {
            return ArtifactUploadResult.Rejected(RejectReason.TOO_LARGE)
        }
        val result = export.upload(request)
        if (request.issueKey == "DEMO-2" && request.mimeType == "text/plain" && lostOnce.add(request.artifactId)) {
            return ArtifactUploadResult.Unknown("Response lost (demo)")
        }
        return result
    }
}
