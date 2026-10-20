package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.internal.metadata.ReproDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDraftTest {
    private fun attachment(id: String, sha: String = "sha-$id") =
        DraftAttachment(id, ArtifactKind.MEDIA, "f-$id", "image/png", 10, sha)

    private val reviewable = ReportDraft("r1", 0).edit {
        it.copy(targetIssue = IssueRef("QA-1"), attachments = listOf(attachment("a"), attachment("b")))
    }

    @Test
    fun `status follows the content`() {
        assertEquals(ReportStatus.DRAFT, ReportDraft("r1", 0).status)
        assertEquals(ReportStatus.REVIEWABLE, reviewable.status)
        assertEquals(ReportStatus.APPROVED, reviewable.approve().status)
    }

    @Test
    fun `every edit bumps the revision and drops approval`() {
        val approved = reviewable.approve()
        val edited = approved.edit { it.copy(description = ReproDescription(title = "x")) }
        assertEquals(approved.revision + 1, edited.revision)
        assertFalse(edited.isApproved)
        assertTrue(edited.sendableAttachments().isEmpty())
    }

    @Test
    fun `an approval does not survive a changed file even if copied over`() {
        val approved = reviewable.approve()
        val swapped = approved.copy(attachments = listOf(attachment("a"), attachment("b", sha = "other")))
        assertFalse(swapped.isApproved)
    }

    @Test(expected = DraftError.NotReviewable::class)
    fun `cannot approve without an issue`() {
        ReportDraft("r1", 0).edit { it.copy(attachments = listOf(attachment("a"))) }.approve()
    }

    @Test
    fun `nothing is sendable before approval`() {
        assertTrue(reviewable.sendableAttachments().isEmpty())
        assertEquals(listOf("a", "b"), reviewable.approve().sendableAttachments().map { it.artifactId })
    }

    @Test
    fun `partial success keeps uploaded files out of the retry`() {
        val sent = reviewable.approve()
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Failed("offline"))
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, sent.status)
        assertEquals(listOf("b"), sent.sendableAttachments().map { it.artifactId })
    }

    @Test
    fun `rejected files are not retried`() {
        val sent = reviewable.approve()
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Rejected(RejectReason.TOO_LARGE))
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, sent.status)
        assertTrue(sent.sendableAttachments().isEmpty())
    }

    @Test
    fun `unknown results need the user before any retry`() {
        val sent = reviewable.approve()
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Unknown("timeout"))
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, sent.status)
        assertTrue(sent.sendableAttachments().isEmpty())

        val missing = sent.resolveUnknown("b", arrived = false)
        assertEquals(listOf("b"), missing.sendableAttachments().map { it.artifactId })

        val arrived = sent.resolveUnknown("b", arrived = true)
        assertEquals(ReportStatus.SUCCEEDED, arrived.status)
    }

    @Test
    fun `nothing uploaded means failed`() {
        val sent = reviewable.approve()
            .applyResult("a", ArtifactUploadResult.Failed(null))
            .applyResult("b", ArtifactUploadResult.Rejected(RejectReason.PERMISSION_DENIED))
        assertEquals(ReportStatus.FAILED, sent.status)
    }

    @Test(expected = DraftError.Locked::class)
    fun `content is frozen once a file may have left the device`() {
        reviewable.approve().markUploading("a").applyResult("a", ArtifactUploadResult.Unknown(null))
            .edit { it.copy(targetIssue = IssueRef("QA-2")) }
    }

    @Test
    fun `an interrupted upload becomes unknown, never pending`() {
        val recovered = reviewable.approve().markUploading("a").recoverInterruptedTransfer()
        assertTrue(recovered.attachments.first { it.artifactId == "a" }.status is ArtifactStatus.Unknown)
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, recovered.status)
    }

    @Test
    fun `a cancelled report sends nothing`() {
        val cancelled = reviewable.approve().cancel()
        assertEquals(ReportStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.sendableAttachments().isEmpty())
    }
}
