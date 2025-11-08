package com.example.pluginenvvar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvFormatterTest {

    @Test
    fun `masks sensitive keys`() {
        val payload = EnvFormatter.buildPayload(
            mapOf(
                "API_TOKEN" to "abcd",
                "HOME" to "/tmp"
            )
        )

        val token = payload.first { it.key == "API_TOKEN" }
        assertTrue(token.sensitive)
        assertEquals("••••", token.value)

        val home = payload.first { it.key == "HOME" }
        assertFalse(home.sensitive)
        assertEquals("/tmp", home.value)
    }

    @Test
    fun `produces deterministic ordering`() {
        val payload = EnvFormatter.buildPayload(
            mapOf(
                "B_KEY" to "2",
                "a_key" to "1",
                "C_KEY" to "3"
            )
        )

        val keys = payload.map { it.key }
        assertEquals(listOf("a_key", "B_KEY", "C_KEY"), keys)
    }
}
