package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueLookupResult
import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.UploadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportSenderTest {
    private class ScriptedUploader(private val script: (UploadRequest) -> ArtifactUploadResult) : IssueUploader {
        val requests = mutableListOf<UploadRequest>()
        var gate: CompletableDeferred<Unit>? = null
        override val displayName = "test"
        override suspend fun findIssue(issueKey: String) = IssueLookupResult.Found(IssueRef(issueKey))
        override suspend fun upload(request: UploadRequest): ArtifactUploadResult {
            requests += request
            gate?.await()
            return script(request)
        }
    }

    private val saved = mutableListOf<ReportDraft>()
    private val draft = ReportDraft("r1", 0).edit {
        it.copy(
            targetIssue = IssueRef("QA-1"),
            attachments = listOf("a", "b", "c").map { id -> DraftAttachment(id, ArtifactKind.MEDIA, "f-$id", "image/png", 1, "s-$id") },
        )
    }.approve()

    private fun sender(uploader: IssueUploader) = ReportSender(uploader, { _, a -> File(a.artifactId) }) { saved += it }

    @Test
    fun `uploads each file once with the report identity`() = runTest {
        val uploader = ScriptedUploader { ArtifactUploadResult.Uploaded("x") }
        val result = sender(uploader).send(draft)!!
        assertEquals(ReportStatus.SUCCEEDED, result.status)
        assertEquals(listOf("a", "b", "c"), uploader.requests.map { it.artifactId })
        assertTrue(uploader.requests.all { it.reportId == "r1" && it.revision == draft.revision && it.issueKey == "QA-1" })
    }

    @Test
    fun `a retry only sends what failed`() = runTest {
        val first = sender(ScriptedUploader { if (it.artifactId == "b") ArtifactUploadResult.Failed("offline") else ArtifactUploadResult.Uploaded("x") })
            .send(draft)!!
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, first.status)
        val retryUploader = ScriptedUploader { ArtifactUploadResult.Uploaded("y") }
        val second = sender(retryUploader).send(first)!!
        assertEquals(listOf("b"), retryUploader.requests.map { it.artifactId })
        assertEquals(ReportStatus.SUCCEEDED, second.status)
    }

    @Test
    fun `an exception is unknown, not failed`() = runTest {
        val result = sender(ScriptedUploader { error("socket closed") }).send(draft)!!
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, result.status)
        assertTrue(result.sendableAttachments().isEmpty())
    }

    @Test
    fun `state is saved as uploading before each request`() = runTest {
        sender(ScriptedUploader { ArtifactUploadResult.Uploaded("x") }).send(draft)
        assertTrue(saved.first().attachments.first { it.artifactId == "a" }.status is ArtifactStatus.Uploading)
    }

    @Test
    fun `a second send of the same report while sending is ignored`() = runTest {
        val uploader = ScriptedUploader { ArtifactUploadResult.Uploaded("x") }.apply { gate = CompletableDeferred() }
        val s = sender(uploader)
        val first = async { s.send(draft) }
        testScheduler.runCurrent()
        assertNull(s.send(draft))
        uploader.gate!!.complete(Unit)
        assertEquals(ReportStatus.SUCCEEDED, first.await()!!.status)
        assertEquals(3, uploader.requests.size)
    }

    @Test
    fun `cancelling while sending marks the file unknown`() = runTest {
        val uploader = ScriptedUploader { ArtifactUploadResult.Uploaded("x") }.apply { gate = CompletableDeferred() }
        val job = async { sender(uploader).send(draft) }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()
        assertTrue(saved.last().attachments.first { it.artifactId == "a" }.status is ArtifactStatus.Unknown)
    }

    @Test(expected = DraftError.NotApproved::class)
    fun `an unapproved draft is refused`() = runTest {
        sender(ScriptedUploader { ArtifactUploadResult.Uploaded("x") }).send(draft.edit { it })
    }
}
