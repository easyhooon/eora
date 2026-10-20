package com.easyhooon.eora.internal.storage

import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.StorageLimits
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.ArtifactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

// Robolectric only for a real org.json implementation.
@RunWith(RobolectricTestRunner::class)
class DraftStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private val limits = StorageLimits(draftTtlMs = 10_000, maxDrafts = 2, maxTotalBytes = 1_000, maxAttachmentBytes = 100)
    private fun store() = DraftStore(tmp.root, limits) { now }

    private fun DraftStore.newDraft() = (create(0) as DraftStore.CreateResult.Created).draft

    @Test
    fun `round trips a draft with transfer state`() {
        val store = store()
        val draft = store.newDraft()
        val attachment = store.writeArtifact(draft.reportId, ArtifactKind.MEDIA, "image/png", "png", ByteArrayInputStream(ByteArray(10)))
        val saved = draft.edit { it.copy(targetIssue = IssueRef("QA-1", "Title"), attachments = listOf(attachment)) }.approve()
            .markUploading(attachment.artifactId)
        store.save(saved)
        assertEquals(saved.copy(), store.load(draft.reportId)!!)
        assertTrue(store.load(draft.reportId)!!.isApproved)
        assertTrue(store.load(draft.reportId)!!.attachments.single().status is ArtifactStatus.Uploading)
    }

    @Test
    fun `artifacts get generated names and a digest`() {
        val store = store()
        val draft = store.newDraft()
        val a = store.writeArtifact(draft.reportId, ArtifactKind.SCREENSHOT, "image/png", "png", ByteArrayInputStream("abc".toByteArray()))
        assertTrue(a.fileName.startsWith("eora-screenshot-"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", a.sha256)
        assertEquals(3, a.sizeBytes)
    }

    @Test(expected = DraftStore.FileTooLargeException::class)
    fun `the per-file limit is enforced while copying`() {
        val store = store()
        val draft = store.newDraft()
        store.writeArtifact(draft.reportId, ArtifactKind.MEDIA, "video/mp4", "mp4", ByteArrayInputStream(ByteArray(101)))
    }

    @Test
    fun `refuses new drafts at the limit instead of deleting live ones`() {
        val store = store()
        store.newDraft()
        store.newDraft()
        assertTrue(store.create(0) is DraftStore.CreateResult.Refused)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `expired drafts and all their files are deleted`() {
        val store = store()
        val draft = store.newDraft()
        store.originalCaptureFile(draft.reportId).writeBytes(ByteArray(5))
        now += 10_000
        assertEquals(1, store.deleteExpired())
        assertFalse(tmp.root.resolve(draft.reportId).exists())
        assertNull(store.load(draft.reportId))
    }

    @Test
    fun `expired drafts free room for new ones`() {
        val store = store()
        store.newDraft()
        store.newDraft()
        now += 10_000
        assertTrue(store.create(0) is DraftStore.CreateResult.Created)
    }

    @Test
    fun `report ids cannot escape the drafts directory`() {
        val store = store()
        tmp.newFolder("outside")
        store.delete("../outside")
        assertTrue(tmp.root.resolve("outside").exists())
        assertNull(store.load("../outside"))
    }

    @Test
    fun `total size counts toward attach decisions`() {
        val store = store()
        val draft = store.newDraft()
        store.writeArtifact(draft.reportId, ArtifactKind.MEDIA, "image/png", "png", ByteArrayInputStream(ByteArray(100)))
        val decision = store.checkAttach(store.load(draft.reportId)!!, 1_000)
        assertTrue(decision is AttachDecision.FileTooLarge)
    }
}
