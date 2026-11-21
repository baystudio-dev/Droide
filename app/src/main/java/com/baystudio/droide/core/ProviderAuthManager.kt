package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

// Errors are never treated as successful validation.
object ProviderAuthManager {
    fun authMethods(provider: AiProvider): List<ProviderAuthMethodDescriptor> = ProviderAuthMethods.forProvider(provider)

    private const val MAX_MODEL_RESPONSE_BYTES = 2_000_000L
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun validate(provider: AiProvider, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (provider.needsKey && apiKey.isBlank()) {
            if (!ProviderAuthMethods.canResolveWithoutStoredSecret(provider))
                return@withContext Result.failure(Exception("API key is empty"))
        }

        try {
            if (provider.id.startsWith("user.") && provider.configuredModels.isNotEmpty()) {
                return@withContext Result.success(UserProviderModelProbe.validate(provider, apiKey, client))
            }
            if (provider.authScheme == ProviderAuthScheme.AWS_SIGV4_BEDROCK) {
                return@withContext validateBedrock(provider, apiKey)
            }
            if (provider.configuredModels.isNotEmpty() && provider.authScheme in TOKEN_RESOLVED_SCHEMES) {
                ProviderRequestAuthorizer.resolveOnly(provider, apiKey)
                return@withContext Result.success(provider.configuredModels)
            }
            // Stage all requested pages before publishing live capabilities. A failed or
            // cancelled continuation must not be certified as a successful first page.
            val entries = ProviderModelPagination.collect(provider, json) { modelsPath ->
                val modelsUrl = provider.baseUrl.trimEnd('/') + modelsPath
                val target = NetworkSecurity.validateProviderTarget(provider, modelsUrl)
                val builder = Request.Builder().url(target.url).addHeader("Accept", "application/json")
                ProviderTransportV2.applyAuth(builder, provider, apiKey)
                val req = ProviderRequestAuthorizer.authorize(builder.get().build(), provider, apiKey)
                client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { response ->
                    val responseLimit = if (ProviderModelCatalog.limit(provider) > ProviderModelCatalog.DEFAULT_LIMIT)
                        ProviderModelCatalog.GATEWAY_RESPONSE_BYTES else MAX_MODEL_RESPONSE_BYTES
                    val body = boundedBody(response, responseLimit)
                    if (!response.isSuccessful) throw IllegalStateException(ProviderFailure.describe(response.code,
                        ProviderFailure.redact(body, listOf(apiKey) + ProviderFailure.requestSecrets(req::header))))
                    body
                }
            }
            val router = provider.id == NineRouterProvider.ID
            val capabilities = entries.mapNotNull { ProviderRuntimeCapabilities.parse(provider, it) }
            ModelCapabilityRegistry.installRuntime(capabilities, if (router) provider.id else null)
            val liveModels = entries.map { it.id }
            val catalogModels = if (router || provider.wireProtocol == ProviderWireProtocol.GOOGLE_GENERATE_CONTENT) emptyList()
                else ModelCapabilityRegistry.catalogModels(provider.id)
            val liveAndCatalog = (liveModels.asSequence() + catalogModels.asSequence()).distinct()
            val models = (liveAndCatalog + provider.configuredModels.asSequence())
                .distinct().filter { ProviderModelRequestPolicy.isConversational(provider, it) }.take(ProviderModelCatalog.limit(provider)).toList()
            if (models.isEmpty()) Result.failure(Exception("Provider returned no usable chat/generation model IDs"))
            else Result.success(models)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(Exception("Connection failed: ${ProviderFailure.message(e, listOf(apiKey))}", e))
        }
    }


    private suspend fun validateBedrock(provider: AiProvider, apiKey: String): Result<List<String>> {
        val region = provider.authRegion ?: runCatching {
            java.net.URI(provider.baseUrl).host.split('.').dropWhile { it != "bedrock-runtime" && it != "bedrock-mantle" }.getOrNull(1)
        }.getOrNull() ?: return Result.failure(Exception("Bedrock region is required"))
        val url = "https://bedrock.$region.amazonaws.com/foundation-models"
        val target = NetworkSecurity.validatePublicHttpsTarget(url)
        val unsigned = Request.Builder().url(target.url).header("Accept", "application/json").get().build()
        val controlProvider = provider.copy(baseUrl = "https://bedrock.$region.amazonaws.com", authRegion = region)
        val req = ProviderRequestAuthorizer.authorize(unsigned, controlProvider, apiKey)
        return client.newBuilder().dns(NetworkSecurity.pinnedDns(target)).build().newCall(req).awaitResponse().use { r ->
            val body = boundedBody(r)
            if (!r.isSuccessful) return@use Result.failure(Exception(ProviderFailure.describe(r.code, ProviderFailure.redact(body, listOf(apiKey) + ProviderFailure.requestSecrets(req::header)))))
            val root = runCatching { json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            val summaries = root?.get("modelSummaries") as? kotlinx.serialization.json.JsonArray
            val live = summaries.orEmpty().mapNotNull { item ->
                ((item as? kotlinx.serialization.json.JsonObject)?.get("modelId") as? kotlinx.serialization.json.JsonPrimitive)?.content
            }
            val models = (live.asSequence() + provider.configuredModels.asSequence()).distinct().take(1_000).toList()
            if (models.isEmpty()) Result.failure(Exception("Bedrock returned no models; configure explicit models/inference profiles if required"))
            else Result.success(models)
        }
    }

    private fun boundedBody(response: okhttp3.Response, limit: Long = MAX_MODEL_RESPONSE_BYTES): String = response.body?.source()?.let { source ->
        source.request(limit + 1L)
        if (source.buffer.size > limit) throw IllegalStateException("Provider model catalog is too large")
        source.buffer.readUtf8()
    } ?: ""

    private val TOKEN_RESOLVED_SCHEMES = setOf(
        ProviderAuthScheme.GOOGLE_ADC,
        ProviderAuthScheme.AZURE_ENTRA,
        ProviderAuthScheme.GITHUB_COPILOT,
    )

     
    fun cleanupLegacyWorkspaceSecrets(workDir: File): Unit =
        ExportWriterAdmission.withWriter(workDir, "Legacy workspace credential cleanup") {
            runCatching { PathSecurity.resolveWithin(workDir, ".droide/auth.json").delete() }
            Unit
        }
}
