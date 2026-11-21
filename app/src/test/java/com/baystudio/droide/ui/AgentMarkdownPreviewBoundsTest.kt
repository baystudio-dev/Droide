package com.baystudio.droide.ui

import org.junit.Assert.*
import org.junit.Test

/** Small deterministic versions of the donor bounds cases, with no timing or platform fixture. */
class AgentMarkdownPreviewBoundsTest {
    @Test fun allNewlineFormsRespectTheLogicalLineLimit() {
        for (separator in listOf("\n", "\r", "\r\n")) {
            val source = ("- x" + separator).repeat(MAX_AGENT_MARKDOWN_PREVIEW_LINES) + "TAIL"
            val preview = agentMarkdownPreview(source)
            assertTrue(preview.shortened)
            assertEquals(MAX_AGENT_MARKDOWN_PREVIEW_LINES, preview.text.lineSequence().count())
            assertFalse(preview.text.contains("TAIL"))
            assertTrue(source.endsWith("TAIL"))
        }
    }

    @Test fun exactCharacterAndLineBoundariesRemainUnshortened() {
        val atChars = "a".repeat(MAX_AGENT_MARKDOWN_PREVIEW_CHARS)
        val atLines = (1..MAX_AGENT_MARKDOWN_PREVIEW_LINES).joinToString("\n") { "x" }
        for (source in listOf("", "small 😄", atChars, atLines)) {
            val preview = agentMarkdownPreview(source)
            assertFalse(preview.shortened)
            assertSame(source, preview.text)
        }
        assertTrue(agentMarkdownPreview(atChars + "x").shortened)
        assertTrue(agentMarkdownPreview(atLines + "\n").shortened)
    }

    @Test fun characterCapNeverCutsASupplementaryCharacterInHalf() {
        val source = "a".repeat(MAX_AGENT_MARKDOWN_PREVIEW_CHARS - 1) + "😄TAIL"
        val preview = agentMarkdownPreview(source)
        assertTrue(preview.shortened)
        assertEquals(MAX_AGENT_MARKDOWN_PREVIEW_CHARS - 1, preview.text.length)
        assertFalse(preview.text.last().isHighSurrogate())
        assertTrue(source.endsWith("😄TAIL"))
    }

    @Test fun listRemainderIsLiteralWithinTheSharedNodeAllowance() {
        val source = "- a\n- b\n- c\n- tail"
        val blocks = AgentMarkdownParser.parse(source, maxRenderNodes = 2)
        assertEquals(3, blocks.sumOf { it.renderCost() }) // Two nodes plus one literal remainder.
        assertEquals(2, (blocks.first() as AgentMarkdownBlock.ListBlock).items.size)
        assertEquals("- c\n- tail", (blocks.last() as AgentMarkdownBlock.Paragraph).text)
    }

    @Test fun tableExceedingRemainingNodesFallsBackWithoutDroppingCells() {
        val source = "| A | B |\n| --- | --- |\n| a | tail |"
        val blocks = AgentMarkdownParser.parse(source, maxRenderNodes = 3)
        assertEquals(1, blocks.sumOf { it.renderCost() })
        assertEquals(source, (blocks.single() as AgentMarkdownBlock.Paragraph).text)
    }

    @Test fun streamingResetAndFinalizationKeepOnlyTheCurrentSource() {
        val projector = AgentStreamingMarkdownProjector()
        projector.project("Old.\n\n", false)
        val replacement = projector.project("New.\n\ntail", false)
        assertEquals(listOf(AgentMarkdownBlock.Paragraph("New.")), replacement.stableBlocks)
        assertEquals("tail", replacement.tail)
        val final = projector.project("New.\n\ntail", true)
        assertEquals(AgentMarkdownParser.parse("New.\n\ntail"), final.stableBlocks)
        assertEquals("", final.tail)
    }

    @Test fun resumingAfterCompletionCanExtendTheFinalParagraphOrFence() {
        val prefix = "Intro.\n\n"
        for ((partial, rest) in listOf("Hello" to ", world!\n\n", "~~~kt\nval a" to " = 1\n~~~\n")) {
            val projector = AgentStreamingMarkdownProjector()
            projector.project(prefix, false)
            val previous = projector.project(prefix + partial, true)
            val resumed = prefix + partial + rest + "next"
            assertEquals(AgentStreamingMarkdownProjector().project(resumed, false), projector.project(resumed, false))
            assertEquals(AgentMarkdownParser.parse(prefix + partial), previous.stableBlocks)
        }
    }

    @Test fun aRewrittenStreamDropsItsOldFenceAndRespectsTheNodeBudget() {
        val projector = AgentStreamingMarkdownProjector(2)
        projector.project("Title\n\n~~~\nstill open\n", false)
        val replacement = "New\n\n~~~\n\n~~~\n"
        val fresh = AgentStreamingMarkdownProjector(2)
        for (source in listOf(replacement, replacement + "more\n\n", replacement + "more\n\nend")) {
            assertEquals(fresh.project(source, false), projector.project(source, false))
        }
    }
}
