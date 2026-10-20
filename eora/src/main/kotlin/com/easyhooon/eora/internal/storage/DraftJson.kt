package com.easyhooon.eora.internal.storage

import com.easyhooon.eora.IssueRef
import com.easyhooon.eora.RejectReason
import com.easyhooon.eora.internal.metadata.MetadataEntry
import com.easyhooon.eora.internal.metadata.ReproDescription
import com.easyhooon.eora.internal.report.ArtifactKind
import com.easyhooon.eora.internal.report.ArtifactStatus
import com.easyhooon.eora.internal.report.DraftAttachment
import com.easyhooon.eora.internal.report.ReportDraft
import org.json.JSONArray
import org.json.JSONObject

internal object DraftJson {
    private const val VERSION = 1

    fun encode(draft: ReportDraft): String = JSONObject().apply {
        put("version", VERSION)
        put("reportId", draft.reportId)
        put("createdAtMs", draft.createdAtMs)
        put("revision", draft.revision)
        draft.targetIssue?.let { issue ->
            put("targetIssue", JSONObject().put("key", issue.key).putOpt("title", issue.title).putOpt("project", issue.project))
        }
        put(
            "description",
            draft.description.let {
                JSONObject().put("title", it.title).put("preconditions", it.preconditions).put("observed", it.observed)
                    .put("expected", it.expected).put("actual", it.actual).put("notes", it.notes)
            },
        )
        put("metadata", JSONArray(draft.metadata.map { JSONObject().put("key", it.key).put("label", it.label).put("value", it.value) }))
        put("attachments", JSONArray(draft.attachments.map(::encodeAttachment)))
        putOpt("approvedFingerprint", draft.approvedFingerprint)
        put("cancelled", draft.cancelled)
    }.toString()

    fun decode(json: String): ReportDraft {
        val o = JSONObject(json)
        val description = o.getJSONObject("description")
        return ReportDraft(
            reportId = o.getString("reportId"),
            createdAtMs = o.getLong("createdAtMs"),
            revision = o.getInt("revision"),
            targetIssue = o.optJSONObject("targetIssue")?.let {
                IssueRef(it.getString("key"), it.optStringOrNull("title"), it.optStringOrNull("project"))
            },
            description = ReproDescription(
                title = description.optString("title"),
                preconditions = description.optString("preconditions"),
                // Drafts saved before the field split kept everything in "steps".
                observed = description.optString("observed").ifEmpty { description.optString("steps") },
                expected = description.optString("expected"),
                actual = description.optString("actual"),
                notes = description.optString("notes"),
            ),
            metadata = o.getJSONArray("metadata").objects().map { MetadataEntry(it.getString("key"), it.getString("label"), it.getString("value")) },
            attachments = o.getJSONArray("attachments").objects().map(::decodeAttachment),
            approvedFingerprint = o.optStringOrNull("approvedFingerprint"),
            cancelled = o.getBoolean("cancelled"),
        )
    }

    private fun encodeAttachment(a: DraftAttachment) = JSONObject().apply {
        put("artifactId", a.artifactId)
        put("kind", a.kind.name)
        put("fileName", a.fileName)
        put("mimeType", a.mimeType)
        put("sizeBytes", a.sizeBytes)
        put("sha256", a.sha256)
        val status = a.status
        put(
            "status",
            JSONObject().apply {
                when (status) {
                    ArtifactStatus.Pending -> put("type", "pending")
                    ArtifactStatus.Uploading -> put("type", "uploading")
                    is ArtifactStatus.Uploaded -> put("type", "uploaded").putOpt("remoteId", status.remoteAttachmentId)
                        .put("confirmedByUser", status.confirmedByUser)
                    is ArtifactStatus.Rejected -> put("type", "rejected").put("reason", status.reason.name).putOpt("message", status.message)
                    is ArtifactStatus.Failed -> put("type", "failed").putOpt("message", status.message)
                    is ArtifactStatus.Unknown -> put("type", "unknown").putOpt("message", status.message)
                }
            },
        )
    }

    private fun decodeAttachment(o: JSONObject): DraftAttachment {
        val s = o.getJSONObject("status")
        val message = s.optStringOrNull("message")
        val status = when (s.getString("type")) {
            "pending" -> ArtifactStatus.Pending
            "uploading" -> ArtifactStatus.Uploading
            "uploaded" -> ArtifactStatus.Uploaded(s.optStringOrNull("remoteId"), s.optBoolean("confirmedByUser"))
            "rejected" -> ArtifactStatus.Rejected(RejectReason.valueOf(s.getString("reason")), message)
            "failed" -> ArtifactStatus.Failed(message)
            // Anything unreadable is treated as possibly sent, never as safe to resend.
            else -> ArtifactStatus.Unknown(message)
        }
        return DraftAttachment(
            artifactId = o.getString("artifactId"),
            kind = ArtifactKind.valueOf(o.getString("kind")),
            fileName = o.getString("fileName"),
            mimeType = o.getString("mimeType"),
            sizeBytes = o.getLong("sizeBytes"),
            sha256 = o.getString("sha256"),
            status = status,
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
}
