package com.baystudio.droide.core

import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import java.io.File
import java.time.Duration
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IGrammarSource





object DeclarativeTextMateRegistry {
    private const val MAX_TOTAL_NANOS = 120_000_000L
    private val lineBudget = Duration.ofMillis(8)

    private data class LoadedGrammar(
        val extensionId: String,
        val languageId: String,
        val scopeName: String,
        val registry: GrammarRegistry,
        val grammar: IGrammar,
    )

    private val byExtension = linkedMapOf<String, List<LoadedGrammar>>()

    @Synchronized
    fun register(
        extensionId: String,
        root: File,
        languages: List<DroideLanguageContribution>,
        contributions: List<DroideGrammarContribution>,
    ) {
        unregister(extensionId)
        val configByLanguage = languages.associate { it.id to it.configuration }
        val parent = GrammarRegistry.getInstance()
        val loaded = mutableListOf<LoadedGrammar>()
        contributions.forEach { contribution ->
            runCatching {
                val grammarFile = resolve(root, contribution.path)
                require(grammarFile.isFile && grammarFile.length() <= DeclarativeVsixParser.MAX_ENTRY_BYTES) { "Grammar resource is invalid" }
                val source = IGrammarSource.fromFile(grammarFile)
                val config = configByLanguage[contribution.language]?.let { resolve(root, it) }
                val definition = if (config != null && config.isFile) {
                    DefaultGrammarDefinition.withLanguageConfiguration(
                        source,
                        config.absolutePath,
                        contribution.language,
                        contribution.scopeName,
                    )
                } else {
                    DefaultGrammarDefinition.withGrammarSource(source, contribution.language, contribution.scopeName)
                }.withEmbeddedLanguages(contribution.embeddedLanguages)
                val registry = GrammarRegistry(parent)
                val grammar = registry.loadGrammar(definition)
                loaded += LoadedGrammar(extensionId, contribution.language, contribution.scopeName, registry, grammar)
            }
        }
        if (loaded.isNotEmpty()) {
            byExtension[extensionId] = loaded
            LanguageRegistry.syntaxChanged()
        }
    }

    @Synchronized
    fun unregister(extensionId: String) {
        val removed = byExtension.remove(extensionId) ?: return
        removed.forEach { synchronized(it.grammar) { runCatching { it.registry.dispose() } } }
        LanguageRegistry.syntaxChanged()
    }

    @Synchronized
    fun hasGrammar(languageId: String): Boolean = byExtension.values.any { list -> list.any { it.languageId == languageId } }

    fun highlight(text: String, languageId: String, maxSpans: Int): List<DroideHighlightSpan>? {
        val loaded = synchronized(this) {
            byExtension.values.asSequence().flatMap { it.asSequence() }.firstOrNull { it.languageId == languageId }
        } ?: return null
        return synchronized(loaded.grammar) { highlightGrammar(loaded.grammar, text, maxSpans) }
    }

    internal fun highlightGrammar(grammar: IGrammar, text: String, maxSpans: Int): List<DroideHighlightSpan>? {
        val deadline = System.nanoTime() + MAX_TOTAL_NANOS
        val spans = ArrayList<DroideHighlightSpan>(minOf(maxSpans, text.length / 5 + 8))
        var state: IStateStack? = null
        return runCatching {
            for ((lineIndex, line) in text.splitToSequence('\n').withIndex()) {
                // A surrogate makes this tokenizer's offsets and subsequent multiline state unreliable.
                if (line.any(Char::isSurrogate) || spans.size >= maxSpans) break
                if (System.nanoTime() > deadline) error("TextMate tokenization budget exceeded")
                val result = grammar.tokenizeLine(line, state, lineBudget)
                if (result.isStoppedEarly) error("TextMate line tokenization budget exceeded")
                state = result.ruleStack
                result.tokens.forEach { token ->
                    if (spans.size >= maxSpans) return@forEach
                    val start = token.startIndex.coerceIn(0, line.length)
                    val end = token.endIndex.coerceIn(start, line.length)
                    if (end > start) {
                        val style = styleForScopes(token.scopes)
                        if (style.kind != DroideHighlightKind.NORMAL || style.bold || style.italic) {
                            spans += DroideHighlightSpan(
                                line = lineIndex,
                                start = start,
                                end = end,
                                kind = style.kind,
                                bold = style.bold,
                                italic = style.italic,
                            )
                        }
                    }
                }
            }
            spans
        }.getOrNull()
    }

    private data class ScopeStyle(
        val kind: DroideHighlightKind,
        val bold: Boolean = false,
        val italic: Boolean = false,
    )

    private fun styleForScopes(scopes: List<String>): ScopeStyle {
        var punctuation = false
        for (index in scopes.indices.reversed()) {
            val scope = scopes[index].lowercase()
            fun matches(prefix: String) = scope == prefix || scope.startsWith(prefix + ".")
            val kind = when (scope.substringBefore('.')) {
                "comment" -> return ScopeStyle(DroideHighlightKind.COMMENT, italic = true)
                "keyword" -> if (matches("keyword.operator")) DroideHighlightKind.OPERATOR else DroideHighlightKind.KEYWORD
                "string" -> if (matches("string.regexp")) DroideHighlightKind.REGEXP else DroideHighlightKind.STRING
                "constant" -> if (matches("constant.numeric")) DroideHighlightKind.NUMBER else null
                "storage" -> if (matches("storage.type")) DroideHighlightKind.TYPE else if (matches("storage.modifier")) DroideHighlightKind.KEYWORD else null
                "variable" -> if (matches("variable.other.property")) DroideHighlightKind.PROPERTY else DroideHighlightKind.VARIABLE
                "support", "entity", "meta" -> when {
                    matches("support.type.property-name") -> DroideHighlightKind.PROPERTY
                    matches("entity.other.attribute-name") -> DroideHighlightKind.ATTRIBUTE
                    matches("entity.name.decorator") || matches("meta.annotation") -> DroideHighlightKind.ANNOTATION
                    matches("entity.name.function") || matches("support.function") || matches("meta.function-call") -> DroideHighlightKind.FUNCTION
                    matches("entity.name.tag") || matches("meta.tag") -> DroideHighlightKind.TAG
                    matches("support.type") || matches("entity.name.type") || matches("entity.name.class") || matches("entity.name.section") -> DroideHighlightKind.TYPE
                    else -> null
                }
                "punctuation" -> { punctuation = true; null }
                else -> null
            }
            if (kind != null) return ScopeStyle(kind, bold = kind == DroideHighlightKind.KEYWORD)
        }
        return ScopeStyle(if (punctuation) DroideHighlightKind.OPERATOR else DroideHighlightKind.NORMAL)
    }

    private fun resolve(root: File, relative: String): File {
        val base = root.canonicalFile
        val target = File(base, DeclarativeVsixParser.normalizeBundlePath(relative)).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Grammar path escaped extension root" }
        return target
    }
}
