package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class DroideSyntaxSemanticEngineTest {
    @Test fun configurationAndPlainTextUseTheirOwnRules() {
        for (name in listOf("app/tsconfig.json", "jsconfig.build.json", "config/tsconfig-test.json", ".eslintrc.json")) {
            assertEquals(name, "jsonc", LanguageRegistry.forFile(name)?.id)
            assertEquals(name, "jsonc", LanguageServerRegistry.protocolLanguageId(name))
        }
        assertEquals("json", LanguageRegistry.forFile("app/package.json")?.id)
        assertTrue(DroideSyntaxSemanticEngine.highlight("if (x) return 42", "plaintext").spans.isEmpty())
    }

    @Test fun jsoncCommentsKeepLineStateAndJsonPropertiesKeepTheirColor() {
        val source = "{/* first\n end */ \"url\": \"https://site/#a\", // note\n \"n\": 1e-2}"
        val spans = DroideSyntaxSemanticEngine.highlight(source, "jsonc").spans
        assertTrue(spans.any { it.line == 0 && it.start == 1 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(spans.any { it.line == 1 && it.start == 0 && it.end == 7 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(spans.any { it.line == 1 && it.start == 8 && it.end == 13 && it.kind == DroideHighlightKind.PROPERTY })
        assertEquals(2, spans.count { it.line == 1 && it.kind == DroideHighlightKind.COMMENT })
        assertFalse(DroideSyntaxSemanticEngine.highlight("// note", "json").spans.any { it.kind == DroideHighlightKind.COMMENT })
    }

    @Test fun numericOperatorsAndQuotedYamlFragmentsKeepTheirBoundaries() {
        val code = "1+foo(1e-2)-0xF"
        val tokens = DroideSyntaxSemanticEngine.highlight(code, "kotlin").spans
        assertEquals(listOf("1", "1e-2", "0xF"), tokens.filter { it.kind == DroideHighlightKind.NUMBER }.map { code.substring(it.start, it.end) })
        assertTrue(tokens.any { it.kind == DroideHighlightKind.FUNCTION && code.substring(it.start, it.end) == "foo" })
        val yaml = """url: "https://site/#anchor" # comment"""
        val comment = DroideSyntaxSemanticEngine.highlight(yaml, "yaml").spans.single { it.kind == DroideHighlightKind.COMMENT }
        assertEquals("# comment", yaml.substring(comment.start))
    }

    @Test fun unmappedOrMisplacedSemanticTokensLeaveTheLexicalSpansIntact() {
        val tokens = listOf(LspSemanticToken(0, 0, 3, "custom.unknown"), LspSemanticToken(0, 4, 99, "class"))
        val source = "fun main()"
        assertEquals(DroideSyntaxSemanticEngine.highlight(source, "kotlin").spans,
            DroideSyntaxSemanticEngine.highlight(source, "kotlin", tokens).spans)
    }

    @Test fun lexicalScannerTracksMultilineStateAndCommonKinds() {
        val source = """fun greet(name: String) {
            val message = "hello"
            /* block
               comment */
            println(message)
        }""".trimIndent()
        val result = DroideSyntaxSemanticEngine.highlight(source, "kotlin")
        assertFalse(result.skippedForSize)
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.KEYWORD })
        assertTrue(result.spans.any { it.line == 0 && it.kind == DroideHighlightKind.FUNCTION })
        assertTrue(result.spans.any { it.line == 1 && it.kind == DroideHighlightKind.STRING })
        assertTrue(result.spans.any { it.line == 2 && it.kind == DroideHighlightKind.COMMENT })
        assertTrue(result.spans.any { it.line == 3 && it.kind == DroideHighlightKind.COMMENT })
    }

    @Test fun semanticTokensCarryCategoryAndModifiersOverLexicalSpan() {
        val result = DroideSyntaxSemanticEngine.highlight(
            "foo(value)",
            "kotlin",
            listOf(LspSemanticToken(0, 0, 3, "parameter", setOf("definition", "deprecated"))),
        )
        assertEquals(DroideHighlightKind.FUNCTION, result.spans.first { !it.semantic && it.start == 0 && it.end == 3 }.kind)
        val semantic = result.spans.first { it.semantic && it.start == 0 && it.end == 3 }
        assertEquals(DroideHighlightKind.VARIABLE, semantic.kind)
        assertTrue(semantic.bold)
        assertTrue(semantic.strike)
    }

    @Test fun oversizedDocumentsSkipLocalHighlightingSafely() {
        val result = DroideSyntaxSemanticEngine.highlight(
            "x".repeat(DroideSyntaxSemanticEngine.MAX_HIGHLIGHT_CHARS + 1),
            "kotlin",
        )
        assertTrue(result.skippedForSize)
        assertTrue(result.spans.isEmpty())
    }
}
