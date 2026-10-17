package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.lang.CSharpLanguage
import io.github.dotnetsupport.razor.lang.syntax.RazorSyntaxKind

/**
 * The virtual C# document of a Razor file (RAZOR_PLAN.md, R3): one C# injection of many pieces, the C# literals of the tree as written,
 * glued by text after the design-time output of the Razor compiler — the class of the component or the page, `__o = <expr>;` for the
 * expressions of the markup, the members of `@code` / `@functions`, the properties of `@inject`, the `using` directives. The platform maps
 * the positions: completion, navigation, errors and rename work in the Razor editor through the pieces. The header (the namespace, the base
 * class, `_Imports.razor`) is R3b; here it is the file's own directives.
 */
object RazorCSharpDocument {
    /** A piece of the document: text of the generator, or a host of the Razor file taken as written. */
    sealed class Piece {
        class Text(val text: String) : Piece()
        class Host(val host: RazorCodeLiteral) : Piece()
    }

    /** A place of the injection: the host with the text before and after it. */
    class Place(val prefix: String, val host: RazorCodeLiteral, var suffix: String)

    private enum class Level { FILE, CLASS, RENDER }

    /** The places of [file], in order; empty when the file has no C# at all. */
    fun places(file: RazorFile): List<Place> {
        val document = file.document ?: return emptyList()
        val pieces = Emitter(file).run { walk(document); finish() }
        val places = ArrayList<Place>()
        val pending = StringBuilder()
        for (piece in pieces) when (piece) {
            is Piece.Text -> pending.append(piece.text)
            is Piece.Host -> {
                places += Place(pending.toString(), piece.host, "")
                pending.setLength(0)
            }
        }
        if (places.isEmpty()) return emptyList()
        places.last().suffix = pending.toString()
        return places
    }

    /** The text of the document as the C# parser sees it: the places glued, for tests and the trace. */
    fun text(file: RazorFile): String = places(file).joinToString("") { it.prefix + it.host.text + it.suffix }

    private class Emitter(val file: RazorFile) {
        val pieces = ArrayList<Piece>()
        val legacy = file.kind == RazorFileKind.LEGACY
        var level = Level.FILE
        val header = Header(file)

        /** Inside the members of `@code` or a template (a lambda): what renders there does not open the render method. */
        var nested = 0
        var renders = 0

        fun text(s: String) {
            pieces += Piece.Text(s)
        }

        fun host(literal: RazorCodeLiteral) {
            if (literal.isValidHost) pieces += Piece.Host(literal)
        }

        fun render() {
            if (nested == 0) ensure(Level.RENDER)
        }

        fun ensure(wanted: Level) {
            if (level == wanted) return
            if (level == Level.FILE) {
                text(header.classHeader())
                level = Level.CLASS
            }
            if (wanted == Level.RENDER) {
                // the markup after a `@code` block renders in a method of its own: the pieces keep the order of the file
                renders++
                text(
                    when {
                        renders > 1 -> "private async global::System.Threading.Tasks.Task __RazorRender$renders()\n{\n"
                        legacy -> "public override async global::System.Threading.Tasks.Task ExecuteAsync()\n{\n"
                        else -> "protected override void BuildRenderTree(global::Microsoft.AspNetCore.Components.Rendering.RenderTreeBuilder __builder)\n{\n"
                    },
                )
                level = Level.RENDER
            } else if (level == Level.RENDER) {
                text("}\n")
                level = Level.CLASS
            }
        }

        fun finish(): List<Piece> {
            if (level == Level.RENDER) text("}\n")
            if (level != Level.FILE) text("}\n" + header.classFooter())
            return pieces
        }

        fun walk(element: PsiElement) {
            if (element is RazorCodeLiteral) return host(element)
            val razor = element as? RazorElement ?: return
            when (razor.kind) {
                RazorSyntaxKind.RazorComment, RazorSyntaxKind.MarkupTransition, RazorSyntaxKind.MarkupEphemeralTextLiteral, RazorSyntaxKind.CSharpTransition -> return
                RazorSyntaxKind.RazorUsingDirective -> {
                    if (level != Level.FILE) return // already in the header, as text
                    val tokens = (razor as RazorDirectivePsi).tokens
                    tokens.forEach(::host)
                    if (tokens.lastOrNull()?.text?.trimEnd()?.endsWith(';') != true) text(";")
                    text("\n")
                }
                RazorSyntaxKind.RazorDirective -> directive(razor as RazorDirectivePsi)
                RazorSyntaxKind.CSharpStatement -> {
                    render()
                    children(razor)
                    text("\n")
                }
                RazorSyntaxKind.CSharpImplicitExpression, RazorSyntaxKind.CSharpExplicitExpression -> {
                    render()
                    text("__o = ")
                    children(razor)
                    text(";\n")
                }
                RazorSyntaxKind.CSharpExplicitExpressionBody -> {
                    text("(")
                    children(razor)
                    text(")")
                }
                RazorSyntaxKind.CSharpTemplateBlock -> {
                    text(if (legacy) "item => new global::Microsoft.AspNetCore.Mvc.Razor.HelperResult(async(__razor_template_writer) => {\n" else "(global::Microsoft.AspNetCore.Components.RenderFragment)((__builder2) => {\n")
                    nested++
                    children(razor)
                    nested--
                    text("})")
                }
                RazorSyntaxKind.CSharpCodeBlock -> {
                    // a block of statements (`@if`, `@foreach` with their markup) renders; one that only holds a directive or an expression is walked through
                    if (razor.children.any { it is RazorCodeLiteral && it.isValidHost }) render()
                    children(razor)
                }
                RazorSyntaxKind.RazorMetaCode -> return
                else -> children(razor)
            }
        }

        fun children(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                walk(child)
                child = child.nextSibling
            }
        }

        fun directive(directive: RazorDirectivePsi) {
            when (val name = directive.directiveName) {
                "code", "functions" -> {
                    ensure(Level.CLASS)
                    nested++
                    directive.codeBlock()?.let(::children)
                    nested--
                    text("\n")
                }
                "inject" -> {
                    ensure(Level.CLASS)
                    val tokens = directive.tokens
                    if (tokens.size < 2) return
                    text(if (legacy) "[global::Microsoft.AspNetCore.Mvc.Razor.Internal.RazorInjectAttribute] public " else "[global::Microsoft.AspNetCore.Components.InjectAttribute] private ")
                    host(tokens[0])
                    text(" ")
                    host(tokens[1])
                    text(" { get; set; }\n")
                }
                "section" -> {
                    ensure(Level.RENDER)
                    text("DefineSection(\"${directive.tokens.firstOrNull()?.text ?: "section"}\", async () => {\n")
                    directive.codeBlock()?.let(::children)
                    text("});\n")
                }
                else -> if (name !in Header.HEADER_DIRECTIVES && name !in Header.IGNORED_DIRECTIVES) {
                    // an unknown directive with a block (a directive of a test, a future one): its code, so the C# in it is still C#
                    directive.codeBlock()?.let { ensure(Level.RENDER); children(it) }
                }
            }
        }

        /** The content of a block directive between its braces. */
        private fun RazorDirectivePsi.codeBlock(): PsiElement? {
            val open = openBrace ?: return null
            var sibling = open.nextSibling
            while (sibling != null) {
                if ((sibling as? RazorElement)?.kind == RazorSyntaxKind.CSharpCodeBlock || (sibling as? RazorElement)?.kind == RazorSyntaxKind.MarkupBlock) return sibling
                sibling = sibling.nextSibling
            }
            return null
        }
    }

    /** What the class header takes from the file's own directives: the base class, the interfaces, the type parameters, the attributes, the namespace. */
    class Header(file: RazorFile) {
        private val legacy = file.kind == RazorFileKind.LEGACY
        private val isPage: Boolean
        private val className: String = file.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_]"), "_").let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }
        private val namespace: String?
        private val inherits: String?
        private val model: String?
        private val implements: List<String>
        private val typeParameters: List<String>
        private val attributes: List<String>
        private val lateUsings: List<String>

        init {
            val directives = file.directives
            isPage = legacy && directives.any { it.directiveName == "page" }
            namespace = directives.lastOrNull { it.directiveName == "namespace" }?.argument
            inherits = directives.lastOrNull { it.directiveName == "inherits" }?.argument
            model = directives.lastOrNull { it.directiveName == "model" }?.argument
            implements = directives.filter { it.directiveName == "implements" }.mapNotNull { it.argument }
            typeParameters = directives.filter { it.directiveName == "typeparam" }.mapNotNull { it.argument?.substringBefore(' ') }
            attributes = directives.filter { it.directiveName == "attribute" }.mapNotNull { it.argument }
            // a `@using` after a member-level directive cannot be a host any more: the header carries it as text
            var members = false
            val late = ArrayList<String>()
            for (directive in directives) {
                if (directive.directiveName in MEMBER_DIRECTIVES) members = true
                else if (directive.directiveName == "using" && members) directive.argument?.let { late += it }
            }
            lateUsings = late
        }

        fun classHeader(): String {
            val sb = StringBuilder()
            for (using in if (legacy) LEGACY_USINGS else COMPONENT_USINGS) sb.append("using ").append(using).append(";\n")
            for (using in lateUsings) sb.append(using).append(";\n")
            if (namespace != null) sb.append("namespace ").append(namespace).append("\n{\n")
            for (attribute in attributes) sb.append(attribute).append('\n')
            val base = when {
                inherits != null -> inherits
                !legacy -> "global::Microsoft.AspNetCore.Components.ComponentBase"
                isPage -> "global::Microsoft.AspNetCore.Mvc.RazorPages.Page"
                else -> "global::Microsoft.AspNetCore.Mvc.Razor.RazorPage<" + (model ?: "dynamic") + ">"
            }
            sb.append("public partial class ").append(className)
            if (typeParameters.isNotEmpty()) sb.append(typeParameters.joinToString(", ", "<", ">"))
            sb.append(" : ").append(base)
            for (i in implements) sb.append(", ").append(i)
            sb.append("\n{\n")
            sb.append("#pragma warning disable 0414\nprivate static object __o = null;\n#pragma warning restore 0414\n")
            if (isPage && model != null) sb.append("public ").append(model).append(" Model => (").append(model).append(")ViewContext.ViewData.Model;\n")
            return sb.toString()
        }

        fun classFooter(): String = if (namespace != null) "}\n" else ""

        companion object {
            val HEADER_DIRECTIVES = setOf("inherits", "model", "implements", "typeparam", "attribute", "namespace", "layout", "page", "rendermode", "preservewhitespace", "using")
            val IGNORED_DIRECTIVES = setOf("addTagHelper", "removeTagHelper", "tagHelperPrefix")
            val MEMBER_DIRECTIVES = setOf("code", "functions", "inject", "section")
            val COMPONENT_USINGS = listOf("global::System", "global::System.Collections.Generic", "global::System.Linq", "global::System.Threading.Tasks", "global::Microsoft.AspNetCore.Components")
            val LEGACY_USINGS = listOf("global::System", "global::System.Collections.Generic", "global::System.Linq", "global::System.Threading.Tasks", "global::Microsoft.AspNetCore.Mvc", "global::Microsoft.AspNetCore.Mvc.Rendering", "global::Microsoft.AspNetCore.Mvc.ViewFeatures")
        }
    }
}

/** Injects the virtual C# document ([RazorCSharpDocument]) into a Razor file: asked at its root ([RazorDocumentPsi]), the hosts are the C# literals. */
class RazorCSharpInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(RazorDocumentPsi::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val file = context.containingFile as? RazorFile ?: return
        val places = RazorCSharpDocument.places(file)
        if (places.isEmpty()) return
        registrar.startInjecting(CSharpLanguage)
        for (place in places) registrar.addPlace(place.prefix, place.suffix, place.host, TextRange(0, place.host.textLength))
        registrar.doneInjecting()
    }
}
