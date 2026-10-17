package io.github.dotnetsupport.razor.lang.syntax

/** `RazorLanguageVersion`: `RazorLangVersion` of the project; the SDK gives 10.0 to net10.0 projects, `latest` is 11.0. */
data class RazorLanguageVersion(val major: Int, val minor: Int) : Comparable<RazorLanguageVersion> {
    override fun compareTo(other: RazorLanguageVersion): Int = if (major != other.major) major.compareTo(other.major) else minor.compareTo(other.minor)
    override fun toString(): String = "$major.$minor"

    companion object {
        val VERSION_1_0 = RazorLanguageVersion(1, 0)
        val VERSION_1_1 = RazorLanguageVersion(1, 1)
        val VERSION_2_0 = RazorLanguageVersion(2, 0)
        val VERSION_2_1 = RazorLanguageVersion(2, 1)
        val VERSION_3_0 = RazorLanguageVersion(3, 0)
        val VERSION_5_0 = RazorLanguageVersion(5, 0)
        val VERSION_6_0 = RazorLanguageVersion(6, 0)
        val VERSION_7_0 = RazorLanguageVersion(7, 0)
        val VERSION_8_0 = RazorLanguageVersion(8, 0)
        val VERSION_9_0 = RazorLanguageVersion(9, 0)
        val VERSION_10_0 = RazorLanguageVersion(10, 0)
        val VERSION_11_0 = RazorLanguageVersion(11, 0)
        val VERSION_12_0 = RazorLanguageVersion(12, 0)
        val LATEST = VERSION_11_0
        val PREVIEW = VERSION_12_0
        val EXPERIMENTAL = RazorLanguageVersion(1337, 1337)

        /** `RazorLanguageVersion.TryParse`: `latest`, `experimental`, `preview`?, or `major.minor` / `major`. */
        fun parse(text: String): RazorLanguageVersion? = when (text.lowercase()) {
            "latest" -> LATEST
            "experimental" -> EXPERIMENTAL
            "preview" -> PREVIEW
            else -> {
                val parts = text.split('.')
                val major = parts.getOrNull(0)?.toIntOrNull()
                val minor = if (parts.size > 1) parts[1].toIntOrNull() else 0
                if (major == null || minor == null || parts.size > 2) null else RazorLanguageVersion(major, minor)
            }
        }
    }
}

/** `RazorFileKind`: a `.razor` component, `_Imports.razor`, a `.cshtml` view or page (legacy). */
enum class RazorFileKind {
    Component, ComponentImport, Legacy;

    val isComponent: Boolean get() = this != Legacy

    companion object {
        /** `FileKinds.GetFileKindFromPath`: by the file name; anything else is legacy. */
        fun fromPath(path: String): RazorFileKind {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            return when {
                name.equals("_Imports.razor", ignoreCase = true) -> ComponentImport
                name.endsWith(".razor", ignoreCase = true) -> Component
                else -> Legacy
            }
        }
    }
}

/**
 * `RazorParserOptions`: the language version, the file kind, the directives the parser knows, and the flags the compiler derives from
 * them (`GetDefaultFlags`). [enableSpanEditHandlers] is the tooling's switch (on in the compiler's test baselines).
 */
class RazorParserOptions(
    val languageVersion: RazorLanguageVersion = RazorLanguageVersion.LATEST,
    val fileKind: RazorFileKind = RazorFileKind.Legacy,
    val directives: List<DirectiveDescriptor> = RazorDirectives.forFileKind(fileKind, languageVersion),
    val useRoslynTokenizer: Boolean = false,
    val enableSpanEditHandlers: Boolean = false,
    val parseLeadingDirectives: Boolean = false,
) {
    val allowCSharpInMarkupAttributeArea: Boolean = !fileKind.isComponent
    val allowMinimizedBooleanTagHelperAttributes: Boolean = languageVersion >= RazorLanguageVersion.VERSION_2_1
    val allowHtmlCommentsInTagHelpers: Boolean = languageVersion >= RazorLanguageVersion.VERSION_2_1
    val allowComponentFileKind: Boolean = languageVersion >= RazorLanguageVersion.VERSION_3_0
    val allowRazorInAllCodeBlocks: Boolean = languageVersion >= RazorLanguageVersion.VERSION_3_0
    val allowUsingVariableDeclarations: Boolean = languageVersion >= RazorLanguageVersion.VERSION_3_0
    val allowNullableForgivenessOperator: Boolean = languageVersion >= RazorLanguageVersion.VERSION_3_0
    val allowConditionalDataDashAttributes: Boolean = fileKind.isComponent || languageVersion >= RazorLanguageVersion.EXPERIMENTAL

    fun copy(
        languageVersion: RazorLanguageVersion = this.languageVersion, fileKind: RazorFileKind = this.fileKind, directives: List<DirectiveDescriptor> = this.directives,
        useRoslynTokenizer: Boolean = this.useRoslynTokenizer, enableSpanEditHandlers: Boolean = this.enableSpanEditHandlers, parseLeadingDirectives: Boolean = this.parseLeadingDirectives,
    ) = RazorParserOptions(languageVersion, fileKind, directives, useRoslynTokenizer, enableSpanEditHandlers, parseLeadingDirectives)

    companion object {
        /** The options of a file of the project engine as the SDK configures it: the built-in directives of the kind, the Roslyn tokenizer. */
        fun forFile(path: String, languageVersion: RazorLanguageVersion = RazorLanguageVersion.LATEST, useRoslynTokenizer: Boolean = true): RazorParserOptions {
            val kind = RazorFileKind.fromPath(path)
            return RazorParserOptions(languageVersion, kind, RazorDirectives.forFileKind(kind, languageVersion), useRoslynTokenizer)
        }
    }
}
