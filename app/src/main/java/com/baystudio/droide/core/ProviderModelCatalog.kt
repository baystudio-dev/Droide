package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object ProviderModelCatalog {
    data class Entry(val id: String, val metadata: JsonObject?)

    const val DEFAULT_LIMIT = 1_000
    const val GATEWAY_RESPONSE_BYTES = 8L * 1024 * 1024

    fun limit(providerId: String): Int = when {
        providerId == NineRouterProvider.ID || providerId.startsWith("user.") || providerId.startsWith("custom.") -> 10_000
        else -> DEFAULT_LIMIT
    }

    fun limit(provider: AiProvider): Int =
        if (provider.wireProtocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) limit(provider.id) else DEFAULT_LIMIT

    fun modelsPath(provider: AiProvider): String = when {
        provider.id == "openrouter" -> "/models?supported_parameters=tools&output_modalities=text"
        provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT &&
            java.net.URI(provider.baseUrl).host == "generativelanguage.googleapis.com" -> "/models?pageSize=1000"
        else -> "/models"
    }

    fun parse(body: String, provider: AiProvider, json: Json): List<Entry> {
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("Provider model catalog must be an object")
        return parse(root, provider)
    }

    fun parse(root: JsonObject, provider: AiProvider): List<Entry> {
        val entries = (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
            ?: throw IllegalArgumentException("Provider returned no model catalog")
        val limit = limit(provider)
        val models = entries.asSequence().mapNotNull { entry ->
            when (entry) {
                is JsonObject -> {
                    if (provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
                        val actions = (entry["supportedGenerationMethods"] as? JsonArray) ?: (entry["supportedActions"] as? JsonArray)
                        if (actions != null && actions.none { (it as? JsonPrimitive)?.contentOrNull == "generateContent" }) return@mapNotNull null
                    }
                    val raw = (entry["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?: (entry["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                    val id = modelId(raw, provider) ?: return@mapNotNull null
                    Entry(id, entry)
                }
                is JsonPrimitive -> entry.takeIf { it.isString }?.let { modelId(it.content, provider)?.let { id -> Entry(id, null) } }
                else -> null
            }
        }.distinctBy { it.id }.take(if (limit > DEFAULT_LIMIT) limit + 1 else limit).toList()
        require(models.size <= limit) { "Provider catalog exceeds the limit of $limit distinct model IDs." }
        return models
    }

    private fun modelId(raw: String, provider: AiProvider): String? {
        val canonical = ProviderModelIdentity.normalizeOrNull(raw) ?: return null
        // Only Google's catalog uses this resource prefix. Other providers may own
        // an actual namespaced model ID such as "models/company-model".
        return if (provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) {
            ProviderModelIdentity.normalizeOrNull(canonical.removePrefix("models/"))
        } else canonical
    }
}
