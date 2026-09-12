package dev.simpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchAuditTest {
    @Test
    fun matchingRecentRequestIsAttributedToApp() {
        val pending = PendingSwitch(targetSubId = 4, origin = "wifi_restore", requestedAt = 10_000L)

        assertEquals("app/wifi_restore", SwitchAudit.classifyActor(4, pending, 20_000L))
    }

    @Test
    fun differentTargetIsExternal() {
        val pending = PendingSwitch(targetSubId = 4, origin = "ui_manual", requestedAt = 10_000L)

        assertEquals("external_user_or_system", SwitchAudit.classifyActor(5, pending, 20_000L))
    }

    @Test
    fun expiredRequestIsExternal() {
        val pending = PendingSwitch(targetSubId = 4, origin = "auto_failover", requestedAt = 10_000L)

        assertEquals("external_user_or_system", SwitchAudit.classifyActor(4, pending, 70_001L))
    }
}
