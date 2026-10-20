package com.easyhooon.eora.internal.metadata

/**
 * Android has no API for a device's retail name, and the user-set device name can contain a person's name, so
 * Samsung model codes are mapped by prefix (the last letters only mark the region, e.g. SM-S936N vs SM-S936B).
 * Hosts can add or override entries through [com.easyhooon.eora.EoraConfig.deviceMarketingNames].
 */
internal object DeviceNames {
    private val builtIn = mapOf(
        "SM-S901" to "Galaxy S22",
        "SM-S906" to "Galaxy S22+",
        "SM-S908" to "Galaxy S22 Ultra",
        "SM-S911" to "Galaxy S23",
        "SM-S916" to "Galaxy S23+",
        "SM-S918" to "Galaxy S23 Ultra",
        "SM-S711" to "Galaxy S23 FE",
        "SM-S921" to "Galaxy S24",
        "SM-S926" to "Galaxy S24+",
        "SM-S928" to "Galaxy S24 Ultra",
        "SM-S721" to "Galaxy S24 FE",
        "SM-S931" to "Galaxy S25",
        "SM-S936" to "Galaxy S25+",
        "SM-S937" to "Galaxy S25 Edge",
        "SM-S938" to "Galaxy S25 Ultra",
        "SM-S731" to "Galaxy S25 FE",
        "SM-F936" to "Galaxy Z Fold4",
        "SM-F721" to "Galaxy Z Flip4",
        "SM-F946" to "Galaxy Z Fold5",
        "SM-F731" to "Galaxy Z Flip5",
        "SM-F956" to "Galaxy Z Fold6",
        "SM-F741" to "Galaxy Z Flip6",
        "SM-F966" to "Galaxy Z Fold7",
        "SM-F766" to "Galaxy Z Flip7",
        "SM-F761" to "Galaxy Z Flip7 FE",
    )

    /** Longest matching prefix wins, so a host can map a single exact model inside a broader family. */
    fun marketingName(model: String, extra: Map<String, String> = emptyMap()): String? {
        val table = builtIn + extra
        return table.keys.filter { model.startsWith(it, ignoreCase = true) }.maxByOrNull { it.length }?.let { table[it] }
    }

    /** "Galaxy S25+ (samsung SM-S936N)", or "samsung SM-S936N" when the name is unknown. */
    fun display(manufacturer: String, model: String, extra: Map<String, String> = emptyMap()): String {
        val code = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        return marketingName(model, extra)?.let { "$it ($code)" } ?: code
    }
}
