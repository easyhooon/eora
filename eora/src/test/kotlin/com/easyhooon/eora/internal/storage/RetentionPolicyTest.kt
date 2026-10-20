package com.easyhooon.eora.internal.storage

import com.easyhooon.eora.StorageLimits
import org.junit.Assert.assertEquals
import org.junit.Test

class RetentionPolicyTest {
    private val policy = RetentionPolicy(StorageLimits(draftTtlMs = 100, maxDrafts = 3, maxTotalBytes = 1_000, maxAttachmentBytes = 400, maxAttachmentsPerReport = 2))

    @Test
    fun `expiry is based on creation time`() {
        val drafts = listOf(StoredDraftInfo("old", 0, 1), StoredDraftInfo("new", 50, 1))
        assertEquals(listOf("old"), policy.expired(drafts, nowMs = 100).map { it.reportId })
    }

    @Test
    fun `creation respects count and total size`() {
        assertEquals(CapacityDecision.Allowed, policy.canCreate(listOf(StoredDraftInfo("a", 0, 500)), 400))
        assertEquals(CapacityDecision.NotEnoughSpace(500, 1_000), policy.canCreate(listOf(StoredDraftInfo("a", 0, 500)), 600))
        assertEquals(CapacityDecision.TooManyDrafts(3), policy.canCreate(List(3) { StoredDraftInfo("$it", 0, 1) }, 0))
    }

    @Test
    fun `attachments respect count, file size and total`() {
        assertEquals(AttachDecision.TooManyFiles(2), policy.canAttach(2, 1, 0))
        assertEquals(AttachDecision.FileTooLarge(400), policy.canAttach(0, 401, 0))
        assertEquals(AttachDecision.NotEnoughSpace(1_000), policy.canAttach(0, 300, 800))
        assertEquals(AttachDecision.Allowed, policy.canAttach(1, 300, 100))
    }
}
