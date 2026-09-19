package io.github.dotnetsupport.templates

/**
 * Renders the item templates of `resources/dotnetTemplates`.
 *
 * A C# template is an optional block of `using` lines, a `---` separator and the declarations written without
 * a namespace. The namespace is added here, because its form (file-scoped or block) changes the indentation of
 * everything inside, which a plain text template cannot express.
 */
object TemplateRenderer {
    private const val SEPARATOR = "---"
    private const val INDENT = "    "
    private val VARIABLE = Regex("""\$\{([A-Z_]+)}""")

    private val SDK_IMPLICIT_USINGS = setOf(
        "System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Net.Http", "System.Threading", "System.Threading.Tasks",
    )
    private val WEB_IMPLICIT_USINGS = setOf(
        "System.Net.Http.Json", "Microsoft.AspNetCore.Builder", "Microsoft.AspNetCore.Hosting", "Microsoft.AspNetCore.Http",
        "Microsoft.AspNetCore.Routing", "Microsoft.Extensions.Configuration", "Microsoft.Extensions.DependencyInjection",
        "Microsoft.Extensions.Hosting", "Microsoft.Extensions.Logging",
    )

    class CSharpContext(
        val namespace: String?,
        val fileScopedNamespace: Boolean,
        /** `<ImplicitUsings>enable</ImplicitUsings>`: the usings the SDK adds globally are left out. */
        val implicitUsings: Boolean = false,
        val webSdk: Boolean = false,
        val extraUsings: List<String> = emptyList(),
    )

    fun load(resource: String): String =
        TemplateRenderer::class.java.getResourceAsStream("/dotnetTemplates/$resource")
            ?.use { it.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n") }
            ?: error("Template not found: $resource")

    /** Replaces `${NAME}`-style variables; anything unknown (`"$schema"`, `${{ github.ref }}`) is left as it is. */
    fun substitute(text: String, variables: Map<String, String>): String =
        VARIABLE.replace(text) { variables[it.groupValues[1]] ?: it.value }

    fun renderPlain(template: String, variables: Map<String, String>): String = substitute(template, variables)

    fun renderCSharp(template: String, variables: Map<String, String>, context: CSharpContext): String {
        val lines = substitute(template, variables).trimEnd().lines()
        val separator = lines.indexOf(SEPARATOR)
        val declared = if (separator < 0) emptyList() else lines.take(separator).map { it.trim() }.filter { it.isNotEmpty() }
        val body = (if (separator < 0) lines else lines.drop(separator + 1)).dropWhile { it.isBlank() }

        val implicit = when {
            !context.implicitUsings -> emptySet()
            context.webSdk -> SDK_IMPLICIT_USINGS + WEB_IMPLICIT_USINGS
            else -> SDK_IMPLICIT_USINGS
        }
        val usings = (declared + context.extraUsings.map { "using $it;" })
            .distinct()
            .filter { it.removePrefix("using ").removeSuffix(";").trim() !in implicit }
            .sortedWith(compareBy({ !it.startsWith("using System") }, { it }))

        return buildString {
            usings.forEach { appendLine(it) }
            if (usings.isNotEmpty()) appendLine()
            when {
                context.namespace.isNullOrEmpty() -> body.forEach { appendLine(it) }
                context.fileScopedNamespace -> {
                    appendLine("namespace ${context.namespace};")
                    appendLine()
                    body.forEach { appendLine(it) }
                }
                else -> {
                    appendLine("namespace ${context.namespace}")
                    appendLine("{")
                    body.forEach { appendLine(if (it.isBlank()) "" else INDENT + it) }
                    appendLine("}")
                }
            }
        }
    }
}
