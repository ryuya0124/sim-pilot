package dev.simpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchBackendResolverTest {
    private val dynamic = mapOf("data" to 41, "voice" to 44, "sms" to 47)

    @Test
    fun genericDeviceUsesOnlyRuntimeTransactions() {
        val result = SwitchBackendResolver.resolve(primaryDevice = false, runtime = dynamic)

        assertEquals("runtime_isub", result.id)
        assertEquals(dynamic, result.transactions)
    }

    @Test
    fun genericDeviceWithoutRuntimeMetadataIsSafelyUnsupported() {
        val result = SwitchBackendResolver.resolve(primaryDevice = false, runtime = emptyMap())

        assertEquals("unsupported", result.id)
        assertTrue(result.transactions.isEmpty())
    }

    @Test
    fun primaryDevicePrefersCompleteDynamicDetection() {
        val result = SwitchBackendResolver.resolve(primaryDevice = true, runtime = dynamic)

        assertEquals("samsung_sm_s948q_dynamic_verified", result.id)
        assertEquals(dynamic, result.transactions)
    }

    @Test
    fun primaryDeviceUsesFallbackOnlyForMissingDynamicRoles() {
        val result = SwitchBackendResolver.resolve(
            primaryDevice = true,
            runtime = mapOf("data" to 31, "voice" to 34),
        )

        assertEquals("samsung_sm_s948q_dynamic_hybrid", result.id)
        assertEquals(mapOf("data" to 31, "voice" to 34, "sms" to 37), result.transactions)
    }
}
