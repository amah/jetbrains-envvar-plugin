package com.example.pluginenvvar

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Formats environment variables for UI consumption, masking sensitive values and
 * providing a deterministic ordering for stable diffing on the frontend.
 */
object EnvFormatter {
    private val redactedKeywords = setOf("KEY", "SECRET", "TOKEN", "PASSWORD", "PWD", "CREDENTIAL")
    private val json = Json { encodeDefaults = true }

    @Serializable
    data class EnvVariablePayload(
        val key: String,
        val value: String,
        val sensitive: Boolean
    )

    fun buildPayload(rawEnv: Map<String, String>): List<EnvVariablePayload> {
        return rawEnv
            .map { (key, value) ->
                val sensitive = isSensitive(key)
                EnvVariablePayload(key, if (sensitive) mask(value) else value, sensitive)
            }
            .sortedBy { it.key.lowercase() }
    }

    fun asJson(rawEnv: Map<String, String>): String = json.encodeToString(buildPayload(rawEnv))

    private fun isSensitive(key: String): Boolean {
        val upper = key.uppercase()
        return redactedKeywords.any { keyword -> upper.contains(keyword) }
    }

    private fun mask(value: String): String = if (value.isEmpty()) "" else "••••"
}
