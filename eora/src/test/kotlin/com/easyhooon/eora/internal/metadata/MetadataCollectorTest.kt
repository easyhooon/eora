package com.easyhooon.eora.internal.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCollectorTest {
    private val device = DeviceFacts("1.2.3", 123, "15", 35, "Google", "Pixel 9")

    private fun collect(screen: String? = "checkout/payment", custom: Map<String, String> = emptyMap(), allowed: Set<String> = emptySet()) =
        MetadataCollector.collect("id-1", "2026-10-20T00:00:00Z", "STG", device, screen, custom, allowed)

    @Test
    fun `contains only allowlisted keys`() {
        val keys = collect().map { it.key }
        assertEquals(
            listOf("report_id", "created_at", "environment", "app_version_name", "app_version_code", "android_version", "device", "screen"),
            keys,
        )
    }

    @Test
    fun `custom fields need the allowlist`() {
        val entries = collect(custom = mapOf("api_env" to "stg-2", "user_email" to "a@b.c"), allowed = setOf("api_env"))
        assertTrue(entries.any { it.key == "custom.api_env" && it.value == "stg-2" })
        assertFalse(entries.any { "user_email" in it.key })
    }

    @Test
    fun `allowlisted custom values that look like secrets or urls are dropped`() {
        val entries = collect(
            custom = mapOf(
                "a" to "https://stg.example.com/orders?id=42",
                "b" to "Bearer abc",
                "c" to "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig",
                "d" to "token=abc",
                "e" to "line1\nline2",
                "f" to "AKIA1234567890ABCDEF12345",
            ),
            allowed = setOf("a", "b", "c", "d", "e", "f"),
        )
        assertFalse(entries.any { it.key.startsWith("custom.") })
    }

    @Test
    fun `screen identifiers reject urls queries and tokens`() {
        listOf(
            "https://stg.example.com/home",
            "//cdn.example.com/x",
            "orders?id=1",
            "orders#frag",
            "users/a@b.c",
            "profile/550e8400e29b41d4a716446655440000",
            "",
            "x".repeat(81),
        ).forEach { assertNull(it, SafeValues.screenIdentifier(it)) }
    }

    @Test
    fun `readable screen identifiers are kept`() {
        listOf("checkout/payment", "MainActivity", "checkout_payment_confirmation_screen_v2", "settings:notifications")
            .forEach { assertEquals(it, SafeValues.screenIdentifier(it)) }
    }

    @Test
    fun `an unsafe screen identifier is omitted rather than cleaned`() {
        assertFalse(collect(screen = "https://stg.example.com/x?token=1").any { it.key == "screen" })
    }

    @Test
    fun `report text is exactly the reviewed entries and description`() {
        val text = MetadataReportWriter.render(
            listOf(MetadataEntry("report_id", "Report ID", "id-1")),
            ReproDescription(title = "Wrong total", observed = "Open cart", actual = "12,300 KRW"),
        )
        assertEquals(
            "# Wrong total\nreport_id: id-1\n\n" +
                "## Preconditions\n(not provided)\n\n## Observed\nOpen cart\n\n## Expected\n(not provided)\n\n" +
                "## Actual\n12,300 KRW\n\n## Notes\n(not provided)\n\n",
            text,
        )
    }

    @Test
    fun `known samsung models show the retail name first`() {
        assertEquals("Galaxy S25+ (samsung SM-S936N)", DeviceNames.display("samsung", "SM-S936N"))
        assertEquals("Galaxy Z Flip7 FE (samsung SM-F761N)", DeviceNames.display("samsung", "SM-F761N"))
    }

    @Test
    fun `unknown models fall back to the model code and hosts can add names`() {
        assertEquals("samsung SM-X999N", DeviceNames.display("samsung", "SM-X999N"))
        assertEquals("Galaxy S26+ (samsung SM-S947N)", DeviceNames.display("samsung", "SM-S947N", mapOf("SM-S947" to "Galaxy S26+")))
        assertEquals("Google Pixel 9", DeviceNames.display("Google", "Pixel 9"))
    }
}
