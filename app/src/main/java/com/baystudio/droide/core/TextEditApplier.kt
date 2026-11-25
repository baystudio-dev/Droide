package com.baystudio.droide.core

 
data class TextRangeEdit(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val newText: String,
)

object TextEditApplier {
    fun apply(text: String, edits: List<TextRangeEdit>): String {
        if (edits.isEmpty()) return text
        data class OffsetEdit(val start: Int, val end: Int, val newText: String, val inputIndex: Int)
        val lines = requestedLines(text, edits)
        val offsetEdits = edits.mapIndexed { inputIndex, edit ->
            require(validUtf16(edit.newText)) { "Text edit replacement contains an incomplete UTF-16 character" }
            require(edit.startLine >= 1 && edit.startColumn >= 1 && edit.endLine >= 1 && edit.endColumn >= 1) { "Invalid text edit range" }
            val start = offset(text, lines.getValue(edit.startLine), edit.startColumn)
            val end = offset(text, lines.getValue(edit.endLine), edit.endColumn)
            require(start <= end) { "Text edit range is reversed" }
            OffsetEdit(start, end, edit.newText, inputIndex)
        // Apply equal-position insertions in reverse input order so the final text
        // retains the server's array order rather than reversing their contents.
        }.sortedWith(compareByDescending<OffsetEdit> { it.start }.thenByDescending { it.end }
            .thenByDescending { it.inputIndex })

        for (i in 0 until offsetEdits.lastIndex) {
            val later = offsetEdits[i]
            val earlier = offsetEdits[i + 1]
            require(earlier.end <= later.start) { "Overlapping text edits are not safe to apply" }
        }

        val out = StringBuilder(text)
        offsetEdits.forEach { e -> out.replace(e.start, e.end, e.newText) }
        return out.toString()
    }

    // Record only the lines referenced by this edit batch. Columns are one-based UTF-16.
    private fun requestedLines(text: String, edits: List<TextRangeEdit>): Map<Int, IntRange> {
        val requested = sortedSetOf<Int>()
        for (edit in edits) {
            requested += edit.startLine
            requested += edit.endLine
        }
        var number = 1
        var start = 0
        var end = 0
        return requested.associateWith { target ->
            require(target >= 1) { "Invalid text edit range" }
            while (true) {
                while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
                if (number == target) break
                require(end < text.length) { "Text edit line is outside the document" }
                start = end + if (text[end] == '\r' && text.getOrNull(end + 1) == '\n') 2 else 1
                end = start
                number++
            }
            start..end
        }
    }

    private fun offset(text: String, line: IntRange, column: Int): Int {
        require(column >= 1 && column - 1 <= line.last - line.first) { "Text edit column is outside the document" }
        val result = line.first + column - 1
        require(result == 0 || result == text.length || !text[result - 1].isHighSurrogate() || !text[result].isLowSurrogate()) {
            "Text edit position splits a UTF-16 character"
        }
        return result
    }

    private fun validUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char.isHighSurrogate()) {
                if (index >= text.length || !text[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
