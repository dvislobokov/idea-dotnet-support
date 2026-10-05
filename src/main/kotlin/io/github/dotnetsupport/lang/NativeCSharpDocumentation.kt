package io.github.dotnetsupport.lang

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.model.Pointer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedDoc
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.lang.semantic.CSharpSymbolText

/**
 * Quick documentation (Ctrl+Q, the popup on hover) of C# on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3), behind
 * [CSharpFeature.DOCUMENTATION]: the symbol under the caret as the resolver of layers 11a–11b finds it (a declaration documents itself), its
 * first line as Roslyn's Quick Info writes it ([CSharpSymbolText.quickInfo]), then the XML documentation — the `///` comment of a declaration
 * of the solution, the documentation file of an assembly ([io.github.dotnetsupport.index.AssemblyDocs]) — as summary, parameters, type
 * parameters, returns, value, exceptions and remarks, the way Rider lays them out.
 *
 * In front of the target provider of the LSP client of the platform (`order="first"`; the first provider with targets wins), so with the
 * switch on Built-in the server's hover is not asked for what the plugin resolves, and still is for what it does not.
 */
class NativeCSharpDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val csharp = file as? CSharpFile ?: return emptyList()
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, file.project)) return emptyList()
        val doc = NativeCSharpDocumentation.at(csharp, offset) ?: return emptyList()
        return listOf(NativeCSharpDocumentationTarget(doc))
    }
}

/**
 * The line of Ctrl + hover over a name that the native tree resolves ([CSharpGotoDeclarationHandler]): the platform asks the documentation
 * provider of the target's language for it, and C# had none, so a local showed no hint while a target of the server did (robot, E-44). The
 * Quick Info line of [NativeCSharpDocumentation] for the name under the mouse, else for the name of the target; with NAVIGATION Built-in
 * only, whoever answers the documentation (it is the hint of the native navigation).
 */
class NativeCSharpQuickNavigateInfo : AbstractDocumentationProvider() {
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        for (candidate in listOfNotNull(originalElement, element?.let { CSharpDeclarationNames.nameElement(it) ?: it })) {
            val file = candidate.containingFile as? CSharpFile ?: continue
            if (!NativeCSharpNavigation.serves(file)) return null
            NativeCSharpDocumentation.at(file, candidate.textRange.startOffset)?.let { return StringUtil.escapeXmlEntities(it.definition) }
        }
        return null
    }
}

/** A computed documentation: strings only, so the pointer may hold it (nothing of the PSI is kept). */
class NativeCSharpDocumentationTarget(val doc: NativeCSharpDocumentation.Doc) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)
    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(doc.title).presentation()
    override fun computeDocumentationHint(): String = doc.html
    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(doc.html)
}

object NativeCSharpDocumentation {
    /** [definition]: the Quick Info line; [xml]: the inner XML of the documentation, null when there is none. */
    class Doc(val title: String, val definition: String, val xml: String?) {
        val html: String get() = render(definition, xml)
    }

    /** The documentation of the name at [offset] of [file] (or just before it: the caret after the name), null when nothing is resolved. */
    fun at(file: CSharpFile, offset: Int): Doc? {
        if (file.compilationUnit == null || DumbService.isDumb(file.project)) return null
        val leaf = identifierAt(file, offset) ?: return null
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val symbols = resolver.resolve(leaf)?.symbols ?: declared(leaf, resolver) ?: return null
        val symbol = symbols.firstOrNull() ?: return null
        val text = CSharpSymbolText(resolver)
        val definition = text.quickInfo(symbol, overloads(symbol, resolver, symbols.size)) ?: return null
        return Doc(leaf.text, definition, xml(symbol, resolver))
    }

    private fun identifierAt(file: PsiFile, offset: Int): PsiElement? {
        for (at in listOf(offset, offset - 1)) {
            if (at < 0) continue
            val leaf = file.findElementAt(at) ?: continue
            if (CSharpLeaves.isIdentifier(leaf)) return leaf
        }
        return null
    }

    /** The name of a declaration: what it declares. */
    private fun declared(leaf: PsiElement, resolver: CSharpNameResolver): List<CSharpSymbol>? {
        resolver.syntax.symbolAt(leaf)?.let { return listOf(CSharpSymbol.Local(it)) }
        val parent = leaf.parent
        val modifiers = (parent as? CSharpMemberDeclaration)?.modifiers?.map { it.text }.orEmpty()
        val symbol: CSharpSymbol = when {
            parent is CSharpBaseTypeDeclaration && parent.identifier == leaf || parent is CSharpDelegateDeclaration && parent.identifier == leaf ->
                resolver.syntax.declaredType(parent)?.let { CSharpSymbol.SourceType(it) } ?: return null
            parent is CSharpMethodDeclaration && parent.identifier == leaf ->
                CSharpSymbol.SourceMember(parent, Member.method(modifiers, TypePart.isExtension(parent)).at { parent }, null)
            parent is CSharpConstructorDeclaration && parent.identifier == leaf -> CSharpSymbol.SourceMember(parent, Member.method(modifiers, false).at { parent }, null)
            parent is CSharpPropertyDeclaration && parent.identifier == leaf -> CSharpSymbol.SourceMember(parent, Member.property(modifiers).at { parent }, null)
            parent is CSharpEventDeclaration && parent.identifier == leaf -> CSharpSymbol.SourceMember(parent, Member.same(CSharpColors.EVENT).at { parent }, null)
            parent is CSharpEnumMemberDeclaration && parent.identifier == leaf -> CSharpSymbol.SourceMember(parent, Member.same(CSharpColors.CONSTANT).at { parent }, null)
            parent is CSharpVariableDeclarator && parent.identifier == leaf && parent.parent?.parent is CSharpBaseFieldDeclaration -> {
                val field = parent.parent!!.parent as CSharpBaseFieldDeclaration
                val target: PsiElement = if (field.declaration?.variables?.size == 1) field else parent
                CSharpSymbol.SourceMember(target, Member.field(field.modifiers.map { it.text }, field is CSharpEventFieldDeclaration).at { target }, null)
            }
            else -> return null
        }
        return listOf(symbol)
    }

    /** Roslyn's `(+ N overloads)`: the other methods of the name in the type the method is found in. */
    private fun overloads(symbol: CSharpSymbol, resolver: CSharpNameResolver, candidates: Int): Int = when (symbol) {
        is CSharpSymbol.LibraryMember -> if (!symbol.member.kind.isCallable && symbol.member.kind != IndexedMemberKind.CONSTRUCTOR) 0 else
            resolver.session.libraryMembers(resolver.assemblies, symbol.member.type)[symbol.member.name].orEmpty().count { it.member.kind == symbol.member.kind } - 1
        is CSharpSymbol.SourceMember -> {
            val method = symbol.element as? CSharpMethodDeclaration
            val type = method?.parent as? CSharpBaseTypeDeclaration
            val name = method?.identifier?.text
            if (type == null || name == null) 0
            else (resolver.syntax.declaredType(type)?.parts.orEmpty().sumOf { part ->
                var count = 0
                part.members { key, _ -> if (key == name) count++ }
                count
            } - 1).coerceAtLeast(candidates - 1)
        }
        else -> 0
    }.coerceAtLeast(0)

    // ---- the XML documentation

    private fun xml(symbol: CSharpSymbol, resolver: CSharpNameResolver): String? = when (symbol) {
        is CSharpSymbol.LibraryMember -> resolver.assemblies.doc(symbol.member.docId)?.xml
        is CSharpSymbol.LibraryType -> resolver.assemblies.doc(symbol.type.docId)?.xml
        is CSharpSymbol.SourceType -> symbol.info.parts.firstNotNullOfOrNull { it.element()?.let(::docComment) }
        is CSharpSymbol.SourceMember -> docComment(symbol.element.let { if (it is CSharpVariableDeclarator) it.parent?.parent ?: it else it })
        is CSharpSymbol.Local -> parameterDoc(symbol)
        is CSharpSymbol.Namespace -> null
    }

    /** The `<param>` of a parameter in the documentation of its method, as the summary of the parameter. */
    private fun parameterDoc(local: CSharpSymbol.Local): String? {
        if (local.symbol.kind != LocalSymbolKind.PARAMETER && local.symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) return null
        val parameter = local.symbol.declaration.parent as? CSharpParameter ?: return null
        val owner = generateSequence(parameter.parent) { it.parent }.firstOrNull { it is CSharpMemberDeclaration || it is CSharpLocalFunctionStatement } ?: return null
        val text = IndexedDoc(docComment(owner) ?: return null).parameter(local.symbol.name) ?: return null
        return "<summary>$text</summary>"
    }

    /** The `///` lines right before [owner] (its attributes and modifiers after them), without the slashes: the inner XML of the documentation. */
    fun docComment(owner: PsiElement): String? {
        var first: PsiElement? = PsiTreeUtil.getDeepestFirst(owner)
        while (first != null && isTrivia(first)) first = PsiTreeUtil.nextLeaf(first)
        val end = first?.textRange?.startOffset ?: return null
        var start = end
        var previous = PsiTreeUtil.prevLeaf(first!!)
        while (previous != null && isTrivia(previous)) {
            start = previous.textRange.startOffset
            previous = PsiTreeUtil.prevLeaf(previous)
        }
        if (start == end) return null
        val lines = owner.containingFile.viewProvider.contents.subSequence(start, end).lines().map { it.trim() }
        // the doc comment right before the declaration: the `///` lines after the last line that is not one
        val doc = lines.dropLastWhile { it.isEmpty() }.takeLastWhile { it.startsWith("///") }
        if (doc.isEmpty()) return null
        return doc.joinToString("\n") { it.removePrefix("///").removePrefix(" ") }
    }

    private fun isTrivia(leaf: PsiElement): Boolean = leaf is PsiWhiteSpace || PsiTreeUtil.getParentOfType(leaf, PsiComment::class.java, false) != null

    // ---- HTML

    fun render(definition: String, xml: String?): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(definition)).append(DocumentationMarkup.DEFINITION_END)
        val doc = xml?.let(::IndexedDoc) ?: return@buildString
        doc.summary?.let { append(DocumentationMarkup.CONTENT_START).append(inline(it)).append(DocumentationMarkup.CONTENT_END) }
        val sections = ArrayList<Pair<String, String>>()
        named(doc.xml, "typeparam").takeIf { it.isNotEmpty() }?.let { list -> sections += "Type parameters:" to list.joinToString("<br>") { (name, text) -> "<code>${StringUtil.escapeXmlEntities(name)}</code> – ${inline(text)}" } }
        named(doc.xml, "param").takeIf { it.isNotEmpty() }?.let { list -> sections += "Params:" to list.joinToString("<br>") { (name, text) -> "<code>${StringUtil.escapeXmlEntities(name)}</code> – ${inline(text)}" } }
        doc.returns?.let { sections += "Returns:" to inline(it) }
        doc.value?.let { sections += "Value:" to inline(it) }
        doc.exceptions.takeIf { it.isNotEmpty() }?.let { list -> sections += "Exceptions:" to list.joinToString("<br>") { (cref, text) -> "<code>${StringUtil.escapeXmlEntities(crefName(cref))}</code> – ${inline(text)}" } }
        doc.remarks?.let { sections += "Remarks:" to inline(it) }
        if (sections.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            for ((header, body) in sections) {
                append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(DocumentationMarkup.SECTION_SEPARATOR).append(body).append(DocumentationMarkup.SECTION_END)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    private fun named(xml: String, element: String): List<Pair<String, String>> =
        Regex("""<$element\s+name="([^"]*)"\s*>(.*?)</$element>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(StringUtil.unescapeXmlEntities(text))

    private val TAG = Regex("""<(/?)([A-Za-z][\w:.-]*)((?:\s+[\w:.-]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(/?)>""")
    private val ATTRIBUTE = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val SPACES = Regex("""\s+""")

    /**
     * The inline XML of a part of the documentation as HTML: references as code, paragraphs, lists; other tags dropped, their text kept.
     * The XML comes from any `///` and from the documentation files of any package, so nothing of it reaches the HTML unescaped: the
     * output is built tag by tag from a fixed set, attribute values are escaped, and links are only `http` / `https`.
     */
    fun inline(xml: String): String {
        val out = StringBuilder()
        // what each open element of the input closes with in the output (`see` is code, a link or nothing, depending on its attributes)
        val closers = ArrayDeque<String>()
        var at = 0
        for (match in TAG.findAll(xml)) {
            out.append(escape(xml.substring(at, match.range.first).replace("<", "").replace(">", "")))
            at = match.range.last + 1
            val (closing, name, attributesText, selfClosing) = match.destructured
            if (closing.isNotEmpty()) {
                out.append(closers.removeLastOrNull() ?: "")
                continue
            }
            val attributes = ATTRIBUTE.findAll(attributesText).associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groups[3]?.value ?: "") }
            val (open, close) = when (name) {
                "see", "seealso" -> {
                    val href = attributes["href"]?.let(StringUtil::unescapeXmlEntities)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    when {
                        attributes["cref"] != null -> "<code>" to "</code>"
                        attributes["langword"] != null -> "<code>" to "</code>"
                        href != null -> "<a href=\"${StringUtil.escapeXmlEntities(href)}\">" to "</a>"
                        else -> "" to ""
                    }
                }
                "paramref", "typeparamref", "code", "c" -> "<code>" to "</code>"
                "para" -> "<p>" to ""
                "br" -> "<br>" to ""
                "list" -> "<ul>" to "</ul>"
                "item" -> "<li>" to "</li>"
                "term" -> "" to " – "
                else -> "" to ""
            }
            out.append(open)
            if (selfClosing.isEmpty() && name != "br") {
                closers.addLast(close)
                continue
            }
            // an empty element: what it names is its text
            val shown = when (name) {
                "see", "seealso" -> attributes["cref"]?.let { crefName(StringUtil.unescapeXmlEntities(it)) }
                    ?: attributes["langword"]?.let(StringUtil::unescapeXmlEntities)
                    ?: attributes["href"]?.let(StringUtil::unescapeXmlEntities)?.takeIf { open.startsWith("<a") }
                "paramref", "typeparamref" -> attributes["name"]?.let(StringUtil::unescapeXmlEntities)
                else -> null
            }
            shown?.let { out.append(StringUtil.escapeXmlEntities(it)) }
            out.append(close)
        }
        out.append(escape(xml.substring(at).replace("<", "").replace(">", "")))
        while (closers.isNotEmpty()) out.append(closers.removeLast())
        return out.toString().replace(SPACES, " ").replace(" – </li>", "</li>").trim()
    }

    /** `T:System.String` -> `String`, `M:System.Console.WriteLine(System.String)` -> `Console.WriteLine`. */
    fun crefName(cref: String): String {
        val kind = cref.getOrNull(1)?.takeIf { it == ':' }?.let { cref[0] }
        val name = (if (kind != null) cref.substring(2) else cref).substringBefore('(').replace(Regex("""`+\d+"""), "").replace('{', '<').replace('}', '>')
        val parts = name.split('.')
        return if (kind == 'T' || kind == 'N' || kind == null || parts.size < 2) parts.last() else parts.takeLast(2).joinToString(".")
    }
}
