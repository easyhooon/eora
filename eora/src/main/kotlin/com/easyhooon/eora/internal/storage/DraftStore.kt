package com.easyhooon.eora.internal.storage

import com.easyhooon.eora.StorageLimits
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.DraftAttachment
import com.easyhooon.eora.internal.report.ReportDraft
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Drafts live in app-private, non-backed-up storage: `<root>/<reportId>/draft.json`, the untouched capture
 * `original.png`, and the final files under `artifacts/`. Nothing is written to shared media.
 */
internal class DraftStore(
    private val root: File,
    limits: StorageLimits,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val policy = RetentionPolicy(limits)
    private val maxAttachmentBytes = limits.maxAttachmentBytes
    private val ttlMs = limits.draftTtlMs

    sealed interface CreateResult {
        data class Created(val draft: ReportDraft) : CreateResult
        data class Refused(val decision: CapacityDecision) : CreateResult
    }

    @Synchronized
    fun create(expectedBytes: Long): CreateResult {
        deleteExpired()
        val decision = policy.canCreate(infos(), expectedBytes)
        if (decision != CapacityDecision.Allowed) return CreateResult.Refused(decision)
        val draft = ReportDraft(reportId = UUID.randomUUID().toString(), createdAtMs = clock())
        dirOf(draft.reportId).mkdirs()
        save(draft)
        return CreateResult.Created(draft)
    }

    @Synchronized
    fun save(draft: ReportDraft) {
        val dir = dirOf(draft.reportId)
        if (!dir.isDirectory) throw IOException("Draft ${draft.reportId} was deleted")
        val tmp = File(dir, "draft.json.tmp")
        tmp.writeText(DraftJson.encode(draft))
        if (!tmp.renameTo(File(dir, DRAFT_FILE))) throw IOException("Could not save draft ${draft.reportId}")
    }

    /** Returns null for a missing, expired or unreadable draft. In-flight files from a dead process become unknown. */
    @Synchronized
    fun load(reportId: String): ReportDraft? {
        if (!isValidId(reportId)) return null
        val file = File(dirOf(reportId), DRAFT_FILE)
        if (!file.isFile) return null
        val draft = runCatching { DraftJson.decode(file.readText()) }.getOrNull() ?: return null
        if (clock() - draft.createdAtMs >= ttlMs) {
            delete(reportId)
            return null
        }
        return draft
    }

    @Synchronized
    fun list(): List<ReportDraft> = (root.listFiles()?.toList() ?: emptyList())
        .filter { it.isDirectory }
        .mapNotNull { load(it.name) }
        .sortedByDescending { it.createdAtMs }

    /** Removes the draft and every file derived from it: capture, final files and metadata. */
    @Synchronized
    fun delete(reportId: String) {
        if (isValidId(reportId)) dirOf(reportId).deleteRecursively()
    }

    @Synchronized
    fun deleteExpired(): Int {
        val expired = policy.expired(infos(), clock())
        expired.forEach { delete(it.reportId) }
        // Directories without a readable draft.json (a crash during create, a corrupt file) can hold an unredacted
        // capture, so they get the same TTL, measured from the directory's last change.
        val readable = infos().map { it.reportId }.toSet()
        root.listFiles()
            ?.filter { it.isDirectory && it.name !in readable }
            ?.filter { !File(it, DRAFT_FILE).isFile || clock() - it.lastModified() >= ttlMs }
            ?.forEach { it.deleteRecursively() }
        return expired.size
    }

    fun originalCaptureFile(reportId: String): File = File(dirOf(reportId), "original.png")

    fun artifactFile(reportId: String, attachment: DraftAttachment): File =
        File(File(dirOf(reportId), ARTIFACTS_DIR), attachment.artifactId)

    fun checkAttach(draft: ReportDraft, fileBytes: Long): AttachDecision =
        policy.canAttach(draft.attachments.size, fileBytes, infos().sumOf { it.sizeBytes })

    /**
     * Copies [source] into the draft as a final artifact, enforcing the per-file limit while copying, because the
     * size a content provider reports in advance can be wrong.
     */
    // Not synchronized: it writes a new, uniquely named file, and holding the lock during a 50 MB copy would block
    // saves from the main thread.
    fun writeArtifact(reportId: String, kind: ArtifactKind, mimeType: String, extension: String, source: InputStream): DraftAttachment {
        val artifactId = UUID.randomUUID().toString()
        val dir = File(dirOf(reportId), ARTIFACTS_DIR).apply { mkdirs() }
        val target = File(dir, artifactId)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxAttachmentBytes) throw FileTooLargeException(maxAttachmentBytes)
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
        } catch (e: IOException) {
            target.delete()
            throw e
        }
        val prefix = when (kind) {
            ArtifactKind.SCREENSHOT -> "screenshot"
            ArtifactKind.MEDIA -> "media"
            ArtifactKind.METADATA -> "report"
        }
        return DraftAttachment(
            artifactId = artifactId,
            kind = kind,
            fileName = "eora-$prefix-${reportId.take(8)}-${artifactId.take(6)}.$extension",
            mimeType = mimeType,
            sizeBytes = total,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    fun deleteArtifact(reportId: String, attachment: DraftAttachment) {
        artifactFile(reportId, attachment).delete()
    }

    private fun infos(): List<StoredDraftInfo> = (root.listFiles()?.toList() ?: emptyList())
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            val draft = runCatching { DraftJson.decode(File(dir, DRAFT_FILE).readText()) }.getOrNull() ?: return@mapNotNull null
            StoredDraftInfo(draft.reportId, draft.createdAtMs, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        }

    private fun dirOf(reportId: String) = File(root, reportId)

    // Report ids come back from intents; never let one escape the drafts directory.
    private fun isValidId(reportId: String) = runCatching { UUID.fromString(reportId).toString() == reportId }.getOrDefault(false)

    class FileTooLargeException(val limitBytes: Long) : IOException("File exceeds $limitBytes bytes")

    private companion object {
        const val DRAFT_FILE = "draft.json"
        const val ARTIFACTS_DIR = "artifacts"
        const val BUFFER_SIZE = 64 * 1024
    }
}
