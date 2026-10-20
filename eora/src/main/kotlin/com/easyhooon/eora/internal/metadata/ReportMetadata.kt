package com.easyhooon.eora.internal.metadata

/** One reviewed line of the metadata report. [label] is shown to the user; [key] is written to the file. */
internal data class MetadataEntry(val key: String, val label: String, val value: String)

/** Facts about the device and app gathered without any permission. Kept separate so collection is testable. */
internal data class DeviceFacts(
    val appVersionName: String?,
    val appVersionCode: Long?,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
)

/** What the user typed, in the same fields as the reference QA form. Only [title] is required. */
internal data class ReproDescription(
    val title: String = "",
    val preconditions: String = "",
    val observed: String = "",
    val expected: String = "",
    val actual: String = "",
    val notes: String = "",
) {
    val hasTitle: Boolean get() = title.isNotBlank()
}

/**
 * Builds the allowlisted metadata. Anything not listed here never reaches the report: there is no generic
 * "add everything" path. Host values pass [SafeValues] checks and are dropped, not cleaned, when they fail.
 */
internal object MetadataCollector {
    fun collect(
        reportId: String,
        createdAtIso: String,
        environmentLabel: String,
        device: DeviceFacts,
        screenIdentifier: String?,
        customFields: Map<String, String>,
        allowedCustomKeys: Set<String>,
        deviceMarketingNames: Map<String, String> = emptyMap(),
    ): List<MetadataEntry> = buildList {
        add(MetadataEntry("report_id", "Report ID", reportId))
        add(MetadataEntry("created_at", "Created at", createdAtIso))
        SafeValues.shortText(environmentLabel)?.let { add(MetadataEntry("environment", "Environment", it)) }
        device.appVersionName?.let { SafeValues.shortText(it) }?.let { add(MetadataEntry("app_version_name", "App version", it)) }
        device.appVersionCode?.let { add(MetadataEntry("app_version_code", "App version code", it.toString())) }
        add(MetadataEntry("android_version", "Android", "${device.androidRelease} (API ${device.sdkInt})"))
        add(MetadataEntry("device", "Device", DeviceNames.display(device.manufacturer, device.model, deviceMarketingNames)))
        screenIdentifier?.let { SafeValues.screenIdentifier(it) }?.let { add(MetadataEntry("screen", "Screen", it)) }
        customFields.toSortedMap().forEach { (key, value) ->
            if (key !in allowedCustomKeys || !SafeValues.isCustomKey(key)) return@forEach
            val safe = SafeValues.shortText(value) ?: return@forEach
            add(MetadataEntry("custom.$key", key, safe))
        }
    }
}

internal object SafeValues {
    private const val MAX_SCREEN_ID = 80
    private const val MAX_SHORT_TEXT = 200
    private val screenIdPattern = Regex("^[A-Za-z0-9_./:-]+$")
    private val customKeyPattern = Regex("^[A-Za-z0-9_.-]{1,40}$")

    // A JWT, or a "bearer"/"token=" style secret.
    private val secretLike = Regex(
        "(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})|(bearer\\s)|((token|secret|password|cookie|session)\\s*[=:])",
        RegexOption.IGNORE_CASE,
    )
    private val longRun = Regex("[A-Za-z0-9_+/=-]{24,}")
    private const val MIN_DIGITS_IN_OPAQUE_TOKEN = 4

    /**
     * Readable names like `checkout_payment_confirmation` are long too, so an opaque token is a long run that
     * mixes letters with several digits (API keys, ids, hashes).
     */
    private fun looksSecret(value: String): Boolean = secretLike.containsMatchIn(value) ||
        longRun.findAll(value).any { run ->
            run.value.count(Char::isDigit) >= MIN_DIGITS_IN_OPAQUE_TOKEN && run.value.any(Char::isLetter)
        }

    /**
     * A screen identifier is a route name like `checkout/payment`, never a URL. Rejected: schemes, hosts, queries,
     * fragments and anything token-like, because URL paths and queries can carry user identifiers.
     */
    fun screenIdentifier(value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_SCREEN_ID) return null
        if (!screenIdPattern.matches(v)) return null
        if ("://" in v || v.startsWith("//") || looksSecret(v)) return null
        return v
    }

    fun isCustomKey(key: String): Boolean = customKeyPattern.matches(key)

    /** Single-line, bounded, not URL- or token-like. */
    fun shortText(value: String): String? {
        val v = value.trim()
        if (v.isEmpty() || v.length > MAX_SHORT_TEXT) return null
        if (v.any { it == '\n' || it == '\r' }) return null
        if (Regex("[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(v)) return null
        if (looksSecret(v)) return null
        return v
    }
}

/** Renders the metadata file exactly as it will be attached. The review screen shows these same bytes. */
internal object MetadataReportWriter {
    fun render(entries: List<MetadataEntry>, description: ReproDescription): String = buildString {
        appendLine("# ${description.title.trim().ifEmpty { "Eora report" }}")
        entries.forEach { appendLine("${it.key}: ${it.value}") }
        appendLine()
        appendSection("Preconditions", description.preconditions)
        appendSection("Observed", description.observed)
        appendSection("Expected", description.expected)
        appendSection("Actual", description.actual)
        appendSection("Notes", description.notes)
    }

    private fun StringBuilder.appendSection(title: String, body: String) {
        appendLine("## $title")
        appendLine(body.trim().ifEmpty { "(not provided)" })
        appendLine()
    }
}
