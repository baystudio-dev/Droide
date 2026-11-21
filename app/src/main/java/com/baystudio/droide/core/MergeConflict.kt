package com.baystudio.droide.core

enum class ConflictChoice { CURRENT, INCOMING, BOTH }

data class MergeConflictBlock(
    val index: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val current: String,
    val incoming: String,
    val base: String? = null,
)

object MergeConflictResolver {
    private data class ParsedBlock(
        val preview: MergeConflictBlock,
        val start: Int,
        val end: Int,
        val current: String,
        val incoming: String,
    )

    fun blocks(text: String, maxBlocks: Int = 200): List<MergeConflictBlock> =
        parse(text, maxBlocks).map { it.preview }

    fun hasMarkers(text: String): Boolean = text.lineSequence().any { line ->
        line.startsWith("<<<<<<<") || line.startsWith("=======") || line.startsWith(">>>>>>>") || line.startsWith("|||||||")
    }

    fun resolve(text: String, blockIndex: Int, choice: ConflictChoice): String {
        val block = parse(text, 500).getOrNull(blockIndex)
            ?: throw IllegalArgumentException("Conflict block $blockIndex no longer exists")
        val selected = when (choice) {
            ConflictChoice.CURRENT -> block.current
            ConflictChoice.INCOMING -> block.incoming
            ConflictChoice.BOTH -> block.current + block.incoming
        }
        val replacement = if (block.end == text.length && !text.endsWith('\n')) withoutLastLineEnding(selected) else selected
        return text.replaceRange(block.start, block.end, replacement)
    }

    private fun withoutLastLineEnding(text: String): String =
        if (text.endsWith("\r\n")) text.dropLast(2) else text.removeSuffix("\n")

    private fun display(text: String): String = withoutLastLineEnding(text).replace("\r\n", "\n")

    private fun parse(text: String, maxBlocks: Int): List<ParsedBlock> {
        fun nextLine(start: Int): Int {
            val newline = text.indexOf('\n', start)
            return if (newline < 0) text.length else newline + 1
        }
        fun marker(from: Int, primary: String, alternate: String? = null): Int {
            var line = from
            while (line < text.length && !text.startsWith(primary, line) &&
                (alternate == null || !text.startsWith(alternate, line))) line = nextLine(line)
            return line
        }
        val blocks = mutableListOf<ParsedBlock>()
        var cursor = 0
        while (cursor < text.length && blocks.size < maxBlocks.coerceIn(1, 500)) {
            val start = marker(cursor, "<<<<<<<")
            if (start == text.length) break
            val currentStart = nextLine(start)
            val currentEnd = marker(currentStart, "=======", "|||||||")
            val hasBase = text.startsWith("|||||||", currentEnd)
            val baseStart = if (hasBase) nextLine(currentEnd) else currentEnd
            val divider = if (hasBase) marker(baseStart, "=======") else currentEnd
            if (divider == text.length) break
            val incomingStart = nextLine(divider)
            val close = marker(incomingStart, ">>>>>>>")
            if (close == text.length) break
            cursor = nextLine(close)
            val current = text.substring(currentStart, currentEnd)
            val incoming = text.substring(incomingStart, close)
            val preview = MergeConflictBlock(
                blocks.size, text.substring(start + 7, currentStart).trim().ifBlank { "Current" },
                text.substring(close + 7, cursor).trim().ifBlank { "Incoming" }, display(current), display(incoming),
                if (hasBase) display(text.substring(baseStart, divider)) else null,
            )
            blocks += ParsedBlock(preview, start, cursor, current, incoming)
        }
        return blocks
    }
}
