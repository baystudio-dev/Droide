package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.lang.EmptyLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorLiteralReplacementTest {
    private fun withEditor(initial: String, test: (DroideCodeEditor) -> Unit) {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "kotlin",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        try {
            editor.setEditorLanguage(object : EmptyLanguage() {
                override fun getSymbolPairs() = droideSymbolPairs("kotlin")
            })
            editor.setText(initial)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!editor.isEditable && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Editor must be ready for input", editor.isEditable)
            test(editor)
        } finally {
            editor.release()
        }
    }

    @Test fun replaceAllUsesLiteralTextAndUndoesAsOneEdit() = withEditor("TOKEN TOKEN") { editor ->
        assertEquals(2, editor.replaceAllBounded("TOKEN", "("))
        assertEquals("( (", editor.text.toString())
        editor.undo()
        assertEquals("TOKEN TOKEN", editor.text.toString())
    }

    @Test fun singleReplaceSupportsQuotesAndDeletionWithoutChangingSmartTyping() = withEditor("TOKEN") { editor ->
        editor.replaceLiteralMatch(0, 5, "\"")
        assertEquals("\"", editor.text.toString())
        editor.replaceLiteralMatch(0, 1, "")
        assertEquals("", editor.text.toString())
        editor.commitText("(", false)
        assertEquals("()", editor.text.toString())
    }

    @Test fun matchLimitAndReadOnlyDocumentStayUnchanged() = withEditor("TOKEN TOKEN") { editor ->
        assertNull(editor.replaceAllBounded("TOKEN", "'", maxMatches = 1))
        editor.setEditable(false)
        editor.replaceLiteralMatch(0, 5, "")
        assertEquals(0, editor.replaceAllBounded("TOKEN", "'"))
        assertEquals("TOKEN TOKEN", editor.text.toString())
    }
}
