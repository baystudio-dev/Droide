package com.baystudio.droide.core

internal object WorkspaceNpmTool {
    // Resolve in the guest so workspaces with a different host path use their own packages.
    private val lookup = """
        dir="${'$'}1"; tool="${'$'}2"; shift 2
        while :; do
            candidate="${'$'}dir/node_modules/.bin/${'$'}tool"
            if [ -f "${'$'}candidate" ] && [ -x "${'$'}candidate" ]; then exec "${'$'}candidate" "${'$'}@"; fi
            [ "${'$'}dir" != . ] || break
            dir=${'$'}{dir%/*}
        done
        exec "${'$'}tool" "${'$'}@"
    """.trimIndent()

    fun command(source: String, argv: List<String>): List<String> {
        val tool = argv.first()
        require(tool in setOf("tsc", "eslint", "prettier"))
        val parts = source.removePrefix("./").split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid workspace source path" }
        val parent = (listOf(".") + parts.dropLast(1)).joinToString("/")
        return listOf("sh", "-c", lookup, "droide-project-tool", parent, tool) + argv.drop(1)
    }
}
