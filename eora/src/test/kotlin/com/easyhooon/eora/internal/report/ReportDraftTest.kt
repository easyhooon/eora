package com.easyhooon.eora.internal.report

import com.easyhooon.eora.ArtifactUploadResult
import com.easyhooon.eora.IssueCreateResult
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
        it.copy(description = ReproDescription(title = "Wrong total"), attachments = listOf(attachment("a"), attachment("b")))
    }

    private val created = reviewable.approve().markCreating().applyCreateResult(IssueCreateResult.Created(IssueRef("QA-7")))

    @Test
    fun `status follows the content`() {
        assertEquals(ReportStatus.DRAFT, ReportDraft("r1", 0).status)
        assertEquals(ReportStatus.REVIEWABLE, reviewable.status)
        assertEquals(ReportStatus.APPROVED, reviewable.approve().status)
    }

    @Test
    fun `a title is required`() {
        val untitled = reviewable.edit { it.copy(description = ReproDescription(title = " ")) }
        assertFalse(untitled.isReviewable)
        assertEquals(ReportStatus.DRAFT, untitled.status)
    }

    @Test
    fun `every edit bumps the revision and drops approval`() {
        val approved = reviewable.approve()
        val edited = approved.edit { it.copy(description = ReproDescription(title = "Other")) }
        assertEquals(approved.revision + 1, edited.revision)
        assertFalse(edited.isApproved)
        assertFalse(edited.canSend)
    }

    @Test
    fun `an approval does not survive a changed file even if copied over`() {
        val approved = reviewable.approve()
        val swapped = approved.copy(attachments = listOf(attachment("a"), attachment("b", sha = "other")))
        assertFalse(swapped.isApproved)
    }

    @Test(expected = DraftError.NotReviewable::class)
    fun `cannot approve without a title`() {
        ReportDraft("r1", 0).edit { it.copy(attachments = listOf(attachment("a"))) }.approve()
    }

    @Test
    fun `files wait for the issue`() {
        val approved = reviewable.approve()
        assertTrue(approved.canSend)
        assertTrue(approved.sendableAttachments().isEmpty())
        assertEquals(listOf("a", "b"), created.sendableAttachments().map { it.artifactId })
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, created.status)
    }

    @Test
    fun `partial success keeps uploaded files out of the retry`() {
        val sent = created
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Failed("offline"))
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, sent.status)
        assertEquals(listOf("b"), sent.sendableAttachments().map { it.artifactId })
    }

    @Test
    fun `rejected files are not retried`() {
        val sent = created
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Rejected(RejectReason.TOO_LARGE))
        assertEquals(ReportStatus.PARTIALLY_SUCCEEDED, sent.status)
        assertFalse(sent.canSend)
    }

    @Test
    fun `all files attached is success`() {
        val sent = created.applyResult("a", ArtifactUploadResult.Uploaded("1")).applyResult("b", ArtifactUploadResult.Uploaded("2"))
        assertEquals(ReportStatus.SUCCEEDED, sent.status)
    }

    @Test
    fun `unknown file results need the user before any retry`() {
        val sent = created
            .applyResult("a", ArtifactUploadResult.Uploaded("10"))
            .applyResult("b", ArtifactUploadResult.Unknown("timeout"))
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, sent.status)
        assertTrue(sent.sendableAttachments().isEmpty())
        assertEquals(listOf("b"), sent.resolveUnknown("b", arrived = false).sendableAttachments().map { it.artifactId })
        assertEquals(ReportStatus.SUCCEEDED, sent.resolveUnknown("b", arrived = true).status)
    }

    @Test
    fun `an unknown issue blocks everything until the user checks`() {
        val lost = reviewable.approve().markCreating().applyCreateResult(IssueCreateResult.Unknown("timeout"))
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, lost.status)
        assertFalse(lost.canSend)

        val missing = lost.resolveUnknownIssue(null)
        assertEquals(ReportStatus.FAILED, missing.status)
        assertTrue(missing.canSend)

        val found = lost.resolveUnknownIssue("QA-9")
        assertEquals("QA-9", found.createdIssue?.key)
        assertEquals(listOf("a", "b"), found.sendableAttachments().map { it.artifactId })
    }

    @Test
    fun `a refused issue is not resent as is but can be fixed`() {
        val refused = reviewable.approve().markCreating().applyCreateResult(IssueCreateResult.Rejected(RejectReason.INVALID_FIELDS))
        assertEquals(ReportStatus.FAILED, refused.status)
        assertFalse(refused.canSend)
        assertFalse(refused.hasTransferStarted)

        val fixed = refused.edit { it.copy(description = ReproDescription(title = "Shorter")) }
        assertEquals(IssueStatus.NotCreated, fixed.issue)
        assertFalse(fixed.isApproved)
        assertTrue(fixed.approve().canSend)
    }

    @Test(expected = IllegalStateException::class)
    fun `only an issue key is accepted when confirming a lost create`() {
        reviewable.approve().markCreating().applyCreateResult(IssueCreateResult.Unknown(null)).resolveUnknownIssue("https://evil/QA-1")
    }

    @Test(expected = DraftError.Locked::class)
    fun `content is frozen once the issue may exist`() {
        reviewable.approve().markCreating().edit { it.copy(description = ReproDescription(title = "Other")) }
    }

    @Test
    fun `interrupted requests become unknown, never pending`() {
        val creating = reviewable.approve().markCreating().recoverInterruptedTransfer()
        assertTrue(creating.issue is IssueStatus.Unknown)
        val uploading = created.markUploading("a").recoverInterruptedTransfer()
        assertTrue(uploading.attachments.first { it.artifactId == "a" }.status is ArtifactStatus.Unknown)
        assertEquals(ReportStatus.NEEDS_CONFIRMATION, uploading.status)
    }

    @Test
    fun `a cancelled report sends nothing`() {
        val cancelled = reviewable.approve().cancel()
        assertEquals(ReportStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.canSend)
    }
}
