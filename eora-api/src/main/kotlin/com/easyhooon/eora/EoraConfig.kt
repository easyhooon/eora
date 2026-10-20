package com.easyhooon.eora

/**
 * Everything the host decides about Eora. Build it only in the STG source set so the uploader and providers it
 * references never compile into a release build.
 */
class EoraConfig(
    /** Sends approved reports to the issue tracker. There is no default: the host decides where reports go. */
    val uploader: IssueUploader,
    val shake: ShakeConfig = ShakeConfig(),
    /** Returns a safe screen identifier for the captured screen, never a full URL. Values that look unsafe are dropped. */
    val screenIdentifierProvider: ScreenIdentifierProvider = ScreenIdentifierProvider { null },
    /** Extra host fields. Only keys listed in [allowedCustomKeys] reach the report. */
    val metadataProvider: MetadataProvider = MetadataProvider { emptyMap() },
    val allowedCustomKeys: Set<String> = emptySet(),
    val storage: StorageLimits = StorageLimits(),
    /** Shown in the report so a reader knows which environment the evidence came from. */
    val environmentLabel: String = "STG",
    /**
     * Extra model-code prefixes to retail names, e.g. `"SM-S947" to "Galaxy S26+"`, for devices Eora does not know
     * yet. Shown as "Galaxy S26+ (samsung SM-S947N)".
     */
    val deviceMarketingNames: Map<String, String> = emptyMap(),
)

/**
 * Shake detection tuning. A shake is [requiredPeaks] samples whose acceleration (gravity removed) reaches
 * [thresholdG] within [windowMs]. After a detection further shakes are ignored for [cooldownMs].
 */
data class ShakeConfig(
    val enabled: Boolean = true,
    val thresholdG: Float = 2.5f,
    val requiredPeaks: Int = 4,
    val windowMs: Long = 1_000L,
    val cooldownMs: Long = 2_000L,
    val minSampleIntervalMs: Long = 20L,
) {
    init {
        require(thresholdG > 0f) { "thresholdG must be positive" }
        require(requiredPeaks >= 2) { "requiredPeaks must be at least 2" }
        require(windowMs > 0L && cooldownMs >= 0L && minSampleIntervalMs >= 0L) { "durations must not be negative" }
    }
}

/** Limits for local drafts kept in app-private storage. */
data class StorageLimits(
    val draftTtlMs: Long = 72L * 60 * 60 * 1000,
    val maxDrafts: Int = 20,
    val maxTotalBytes: Long = 200L * 1024 * 1024,
    val maxAttachmentBytes: Long = 50L * 1024 * 1024,
    val maxAttachmentsPerReport: Int = 10,
) {
    init {
        require(draftTtlMs > 0L && maxDrafts > 0 && maxTotalBytes > 0L && maxAttachmentBytes > 0L) { "limits must be positive" }
        require(maxAttachmentsPerReport > 0) { "maxAttachmentsPerReport must be positive" }
    }
}

fun interface ScreenIdentifierProvider {
    /** Called on the main thread with the activity being captured. */
    fun screenIdentifier(activity: android.app.Activity): String?
}

fun interface MetadataProvider {
    /** Called on the main thread when a report starts. */
    fun customFields(): Map<String, String>
}
