package com.easyhooon.eora.internal.storage

import com.easyhooon.eora.StorageLimits

internal data class StoredDraftInfo(val reportId: String, val createdAtMs: Long, val sizeBytes: Long)

internal sealed interface CapacityDecision {
    data object Allowed : CapacityDecision
    data class TooManyDrafts(val limit: Int) : CapacityDecision
    data class NotEnoughSpace(val usedBytes: Long, val limitBytes: Long) : CapacityDecision
}

/**
 * Pure rules for local drafts. Expired drafts are deleted; drafts within their TTL are never deleted to make room,
 * because silently losing a user's unsent evidence is worse than asking them to clean up.
 */
internal class RetentionPolicy(private val limits: StorageLimits) {
    fun expired(drafts: List<StoredDraftInfo>, nowMs: Long): List<StoredDraftInfo> =
        drafts.filter { nowMs - it.createdAtMs >= limits.draftTtlMs }

    fun canCreate(liveDrafts: List<StoredDraftInfo>, expectedBytes: Long): CapacityDecision {
        if (liveDrafts.size >= limits.maxDrafts) return CapacityDecision.TooManyDrafts(limits.maxDrafts)
        val used = liveDrafts.sumOf { it.sizeBytes }
        if (used + expectedBytes > limits.maxTotalBytes) return CapacityDecision.NotEnoughSpace(used, limits.maxTotalBytes)
        return CapacityDecision.Allowed
    }

    fun canAttach(currentAttachments: Int, fileBytes: Long, draftsBytes: Long): AttachDecision = when {
        currentAttachments >= limits.maxAttachmentsPerReport -> AttachDecision.TooManyFiles(limits.maxAttachmentsPerReport)
        fileBytes > limits.maxAttachmentBytes -> AttachDecision.FileTooLarge(limits.maxAttachmentBytes)
        draftsBytes + fileBytes > limits.maxTotalBytes -> AttachDecision.NotEnoughSpace(limits.maxTotalBytes)
        else -> AttachDecision.Allowed
    }
}

internal sealed interface AttachDecision {
    data object Allowed : AttachDecision
    data class TooManyFiles(val limit: Int) : AttachDecision
    data class FileTooLarge(val limitBytes: Long) : AttachDecision
    data class NotEnoughSpace(val limitBytes: Long) : AttachDecision
}
