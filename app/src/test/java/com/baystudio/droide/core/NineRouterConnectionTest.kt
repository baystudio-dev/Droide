package com.baystudio.droide.core

import android.app.Application
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class NineRouterConnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val id = "9router"
    private val route = "ag/claude-sonnet-4-6"
    private val alias = "My-Combo"

    @After fun clear() {
        File(app.filesDir, "user-providers.json").delete()
        ProviderRegistry.installUserConfigured(emptyList())
        for (provider in listOf(id, "user.router-fixture")) {
            ProviderModelCertificationRegistry.revoke(provider)
            ModelCapabilityRegistry.revokeRuntime(provider)
        }
    }

    @Test fun endpointIdentityIsAvailableOfflineButConnectionsStillValidateTheirDestination() {
        val provider = UserProviderDefinition("user.router-fixture", "Hosted router", "https://router.invalid/proxy/v1").provider()
        ProviderRegistry.installUserConfigured(listOf(UserProviderDefinition(provider.id, provider.name, provider.baseUrl)))
        ProviderModelCertificationRegistry.certify(provider, listOf(alias))
        val target = LlmClient().ownedChatTarget(provider.id, provider.baseUrl, alias)
        assertEquals(OwnedChatEndpointIdentity.fromValidatedUrl(provider.baseUrl + "/chat/completions"), target?.endpointIdentity)
        assertThrows(java.net.UnknownHostException::class.java) {
            NetworkSecurity.validateProviderTarget(provider, provider.baseUrl + "/chat/completions")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkSecurity.validateProviderTarget(provider, "https://127.0.0.1/v1/chat/completions")
        }
    }

    @Test fun endpointRoundTripsThroughSettingsAndKeepsReverseProxyPrefix() {
        val saved = NineRouterProvider.defaults
        val paths = mapOf("" to "/v1", "/v1/" to "/v1", "/v1/models" to "/v1", "/models" to "/v1",
            "/tenant/api/v1/chat/completions/" to "/tenant/api/v1", "/gateway/api" to "/gateway/api")
        for ((input, expected) in paths) {
            val normalized = saved.copy(baseUrl = "https://example.org"+input).normalized()
            assertEquals("https://example.org"+expected, normalized.baseUrl)
            assertEquals(normalized, UserProviderCodec.decode(UserProviderCodec.encode(listOf(normalized))).single())
        }
        for (url in listOf("https://user:pass@example.org/v1", "https://example.org/v1?key=secret", "http://example.org/v1")) {
            assertThrows(IllegalArgumentException::class.java) { saved.copy(baseUrl = url).normalized() }
        }
    }

    @Test fun reasoningFollowsRouterMetadataAndRefreshDropsRemovedModels() {
        val provider = NineRouterProvider.defaults.provider()
        fun row(model: String, fields: String) = ProviderRuntimeCapabilities.parse(provider, ProviderModelCatalog.Entry(
            model, Json.parseToJsonElement(fields).jsonObject))!!
        ModelCapabilityRegistry.installRuntime(listOf(
            row("smart", """{"capabilities":{"reasoning":true}}"""),
            row(route, """{"capabilities":{"reasoning":false}}"""),
            row("limited", """{"reasoning_efforts":["high"]}"""),
        ), id)
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), ReasoningSupport.supported(id, "smart"))
        assertNull(ReasoningSupport.defaultFor(id, "smart"))
        assertNull(ReasoningSupport.wire(id, route, ReasoningEffort.HIGH))
        assertEquals(listOf(ReasoningEffort.HIGH), ReasoningSupport.supported(id, "limited"))
        ModelCapabilityRegistry.installRuntime(listOf(row(alias, "{}")), id)
        assertNull(ModelCapabilityRegistry.resolve(id, "smart"))
        assertTrue(ReasoningSupport.supported(id, alias).isEmpty())
    }

    @Test fun oversizedRouterCatalogFailsExplicitly() {
        val provider = NineRouterProvider.defaults.provider()
        val ids = buildJsonArray { repeat(ProviderModelCatalog.limit(id) + 1) { add("alias-$it") } }
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProviderModelCatalog.parse(buildJsonObject { put("data", ids) }, provider)
        }
        assertTrue(error.message.orEmpty().contains("10000"))
    }

    @Test(timeout = 60_000) fun largeCatalogAliasAndRoutedModelCompleteThroughAgent() = runBlocking {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
        val rows = buildJsonArray {
            repeat(1500) { add(buildJsonObject { put("id", "gateway/model-$it") }) }
            add(buildJsonObject { put("id", alias); put("owned_by", "combo") })
            add(buildJsonObject {
                put("id", route)
                putJsonObject("capabilities") {
                    put("contextWindow", 200000); put("maxOutput", 4096); put("tools", false)
                    put("vision", true); put("reasoning", false)
                }
            })
        }
        val requests = CopyOnWriteArrayList<JsonObject>()
        val catalogGets = AtomicInteger()
        val server = ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val (status, type, body) = when (exchange.requestMethod to exchange.requestURI.path) {
                "GET" to "/proxy/v1/models" -> {
                    catalogGets.incrementAndGet()
                    Triple(200, "application/json", buildJsonObject { put("object", "list"); put("data", rows) }.toString())
                }
                "POST" to "/proxy/v1/chat/completions" -> {
                    val posted = Json.parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() }).jsonObject
                    requests += posted
                    val choice = """{"model":"underlying-model","choices":[{"index":0,"delta":{"content":"Router reply"},"finish_reason":"stop"}]}"""
                    Triple(200, "text/event-stream", ": keep-alive\n\ndata: $choice\n\ndata: [DONE]\n\n")
                }
                else -> Triple(404, "application/json", """{"error":{"message":"unexpected route"}}""")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", type)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        val workspace = java.nio.file.Files.createTempDirectory("router-agent-").toFile()
        try {
            val backend = ProviderConnectionBackend(app)
            val definition = backend.saveCustomProvider(NineRouterProvider.defaults.copy(
                baseUrl = "http://127.0.0.1:${server.address.port}/proxy/v1", requiresKey = false)).getOrThrow()
            val connected = backend.connect(id, "none").getOrThrow()
            assertEquals(1502, connected.snapshot.modelCount)
            assertEquals(1, catalogGets.get())
            assertTrue(connected.models.containsAll(listOf(alias, route)))
            assertEquals(true, ModelCapabilityRegistry.allowsImageInput(id, route))
            assertFalse(ModelCapabilityRegistry.allowsToolUse(id, route))
            assertEquals(200000, ModelContextRegistry.resolve(id, route))
            assertNull(ModelContextRegistry.resolve(id, alias))
            for (model in listOf(alias, route)) {
                val agent = AgentService(FileRepository(workspace), AgentWorkflowTerminal(), GitManager(workspace), maxSubagentDepth = 0)
                try {
                    agent.chat("tes", AgentConfig(baseUrl = definition.baseUrl, providerId = id, model = model), maxTurns = 2)
                    assertEquals("Router reply", agent.messages.value.last { it.role == "assistant" && it.streamPart == null && it.content.isNotBlank() }.content)
                } finally { agent.shutdown() }
            }
            assertEquals(listOf(alias, route), requests.map { it.getValue("model").jsonPrimitive.content })
            assertTrue(requests.all { it["stream"] == JsonPrimitive(true) })
            assertNull(requests.last()["tools"])
            assertEquals(4096, ProviderRequestBudget.explicitOutputTokens(requests.last()))
            assertEquals(1, catalogGets.get())
        } finally { server.stop(0); workspace.deleteRecursively() }
    }
}
