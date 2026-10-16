package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.TemplateLanguageFileType
import com.intellij.psi.templateLanguages.TemplateLanguage
import io.github.dotnetsupport.DotNetIcons
import javax.swing.Icon

/**
 * Razor (RAZOR_PLAN.md, R1): `.razor` components and `.cshtml` views and pages. The base language of a file of three — markup is HTML, the
 * template data language ([RazorFileViewProvider]); the C# in it is coloured by the plugin's C# lexer ([RazorLexer]), not yet a C# tree (R3).
 */
object RazorLanguage : Language("Razor"), TemplateLanguage {
    private fun readResolve(): Any = RazorLanguage
    override fun getDisplayName(): String = "Razor"
    override fun isCaseSensitive(): Boolean = true
}

/**
 * The C# statements and members of a Razor file (`@{ }`, `@code { }`, `@if ...`): the parent of their tokens in the tree, so the language
 * at a line of code is told from the markup and the directives (Ctrl+/ puts `//` there, `@* *@` elsewhere; [RazorCommentProvider]).
 */
object RazorCodeLanguage : Language(RazorLanguage, "RazorCSharp") {
    private fun readResolve(): Any = RazorCodeLanguage
    override fun getDisplayName(): String = "Razor C#"
}

/** One file type for both kinds, as Rider's "Razor": what differs is the kind ([RazorFileKind]), told by the extension. */
object RazorFileType : LanguageFileType(RazorLanguage), TemplateLanguageFileType {
    override fun getName(): String = "Razor"
    override fun getDisplayName(): String = "Razor"
    override fun getDescription(): String = "Razor component or view"
    override fun getDefaultExtension(): String = "razor"
    override fun getIcon(): Icon = DotNetIcons.Razor
}

/**
 * The two kinds of the Razor compiler (`RazorFileKind`): a component (`.razor`, `_Imports.razor` included) and a legacy file — an MVC view
 * or a Razor page (`.cshtml`). They have different directives, and only in a component an `@` where an attribute's name goes is a
 * directive attribute (`@onclick`, `@bind-Value`): `RazorParserOptions` clears `AllowCSharpInMarkupAttributeArea` for components.
 */
enum class RazorFileKind(val directives: Set<String>) {
    COMPONENT(
        setOf(
            "page", "using", "inject", "inherits", "layout", "namespace", "attribute", "implements", "typeparam", "rendermode",
            "preservewhitespace", "code", "functions", "addTagHelper", "removeTagHelper", "tagHelperPrefix",
        ),
    ),
    LEGACY(
        setOf(
            "page", "model", "using", "inject", "inherits", "namespace", "attribute", "implements", "section", "functions",
            "addTagHelper", "removeTagHelper", "tagHelperPrefix",
        ),
    );

    companion object {
        fun of(fileName: String?): RazorFileKind = if (fileName != null && fileName.endsWith(".cshtml", ignoreCase = true)) LEGACY else COMPONENT
    }
}
