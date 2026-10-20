package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.CreateIssueRequest
import com.easyhooon.eora.IssueCreateResult
import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.IssueUploader
import com.easyhooon.eora.UploadRequest
import com.easyhooon.eora.internal.metadata.MetadataEntry
import com.easyhooon.eora.internal.metadata.MetadataReportWriter
import com.easyhooon.eora.internal.metadata.ReproDescription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReportSenderTest {
    private class ScriptedUploader(
        private val onCreate: (CreateIssueRequest) -> IssueCreateResult = { IssueCreateResult.Created(IssueRef("QA-1")) },
        private val onUpload: (UploadRequest) -> ArtifactUploadResult = { ArtifactUploadResult.Uploaded("x") },
    ) : IssueUploader {
        val creates = mutableListOf<CreateIssueRequest>()
        val uploads = mutableListOf<UploadRequest>()
        var gate: CompletableDeferred<Unit>? = null
        override val displayName = "test"
        override suspend fun createIssue(request: CreateIssueRequest): IssueCreateResult {
            creates += request
            gate?.await()
            return onCreate(request)
        }

        override suspend fun upload(request: UploadRequest): ArtifactUploadResult {
            uploads += request
            gate?.await()
            return onUpload(request)
        }
    }

    private val saved = mutableListOf<ReportDraft>()
    private val draft = ReportDraft("r1", 0).edit {
        it.copy(
            description = ReproDescription(title = "Wrong total", actual = "12,300"),
            metadata = listOf(MetadataEntry("device", "Device", "Pixel")),
            attachments = listOf("a", "b", "c").map { id -> DraftAttachment(id, ArtifactKind.MEDIA, "f-$id", "image/png", 1, "s-$id") },
        )
    }.approve()

    private fun sender(uploader: IssueUploader) = ReportSender(uploader, { _, a -> File(a.artifactId) }) { saved += it }

    @Test
    fun `creates one issue with the reviewed text, then uploads each file to it`() = runTest {
        val uploader = ScriptedUploader()
        val result = sender(uploader).send(draft)!!
        assertEquals(ReportStatus.SUCCEEDED, result.status)
        val create = uploader.creates.single()
        assertEquals("Wrong total", create.title)
        assertEquals(MetadataReportWriter.render(draft.metadata, draft.description), create.description)
        assertEquals(draft.revision, create.revision)
        assertEquals(listOf("a", "b", "c"), uploader.uploads.map { it.artifactId })
        assertTrue(uploader.uploads.all { it.issueKey == "QA-1" && it.reportId == "r1" })
    }

    @Test
    fun `a failed create uploads nothing and can be retried`() = runTest {
        val failed = sender(ScriptedUploader(onCreate = { IssueCreateResult.Failed("offline") })).send(draft)!!
        assertEquals(ReportStatus.FAILED, failed.status)
        assertTrue(failed.canSend)
        val retry = ScriptedUploader()
        assertEquals(ReportStatus.SUCCEEDED, sender(retry).send(failed)!!.status)
        assertEquals(1, retry.creates.size)
    }

    @Test
    fun `a retry after partial success only sends missing files and never recreates the issue`() = runTest {
        val first = sender(ScriptedUploader(onUpload = { if (it.artifactId == "b") ArtifactUploadResult.Failed("offline") else ArtifactUploadResult.Uploaded("x") }))
            .send(draft)!!
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, first.status)
        val retry = ScriptedUploader()
        val second = sender(retry).send(first)!!
        assertTrue(retry.creates.isEmpty())
        assertEquals(listOf("b"), retry.uploads.map { it.artifactId })
        assertEquals(ReportStatus.SUCCEEDED, second.status)
    }

    @Test
    fun `an exception while creating is unknown and stops the send`() = runTest {
        val uploader = ScriptedUploader(onCreate = { error("socket closed") })
        val result = sender(uploader).send(draft)!!
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, result.status)
        assertTrue(uploader.uploads.isEmpty())
    }

    @Test
    fun `an exception while uploading is unknown, not failed`() = runTest {
        val result = sender(ScriptedUploader(onUpload = { error("socket closed") })).send(draft)!!
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, result.status)
        assertTrue(result.sendableAttachments().isEmpty())
    }

    @Test
    fun `state is saved before each request`() = runTest {
        sender(ScriptedUploader()).send(draft)
        assertEquals(IssueStatus.Creating, saved.first().issue)
        assertTrue(saved.first { it.attachments.any { a -> a.status is ArtifactStatus.Uploading } }.createdIssue != null)
    }

    @Test
    fun `a second send of the same report while sending is ignored`() = runTest {
        val uploader = ScriptedUploader().apply { gate = CompletableDeferred() }
        val s = sender(uploader)
        val first = async { s.send(draft) }
        testScheduler.runCurrent()
        assertNull(s.send(draft))
        uploader.gate!!.complete(Unit)
        assertEquals(ReportStatus.SUCCEEDED, first.await()!!.status)
        assertEquals(1, uploader.creates.size)
        assertEquals(3, uploader.uploads.size)
    }

    @Test
    fun `cancelling while creating marks the issue unknown`() = runTest {
        val uploader = ScriptedUploader().apply { gate = CompletableDeferred() }
        val job = async { sender(uploader).send(draft) }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.runCurrent()
        assertTrue(saved.last().issue is IssueStatus.Unknown)
    }

    @Test(expected = DraftError.NotApproved::class)
    fun `an unapproved draft is refused`() = runTest {
        sender(ScriptedUploader()).send(draft.edit { it })
    }
}
