package io.github.dotnetsupport.lang

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiManager
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.lang.semantic.SemanticType
import java.net.URLDecoder
import java.net.URLEncoder
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
import io.github.dotnetsupport.lsp.RoslynOptions

/**
 * Quick documentation (Ctrl+Q, the popup on hover) of C# on the plugin's semantics (CSHARP_PSI_MIGRATION.md, task C3), behind
 * [CSharpFeature.DOCUMENTATION]: the symbol under the caret as the resolver of layers 11a–11b finds it (a declaration documents itself), its
 * first line as Roslyn's Quick Info writes it ([CSharpSymbolText.quickInfo]), then the XML documentation — the `///` comment of a declaration
 * of the solution, the documentation file of an assembly ([io.github.dotnetsupport.index.AssemblyDocs]) — as summary, parameters, type
 * parameters, returns, value, exceptions and remarks, the way Rider lays them out.
 *
 * The platform shows the targets of all providers as pages of one popup, so with the switch on Built-in the module `roslyn` turns the
 * server's hover off (its `hoverCustomizer`): one page, the plugin's (robot, E-83: the server's was the second page, 0.1.72).
 */
class NativeCSharpDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        val csharp = file as? CSharpFile ?: return emptyList()
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, file)) return emptyList()
        val doc = NativeCSharpDocumentation.at(csharp, offset) ?: return emptyList()
        return listOf(NativeCSharpDocumentationTarget(doc, file.project))
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

/** A computed documentation: strings only, so the pointer may hold it (nothing of the PSI is kept); F4 goes to [NativeCSharpDocumentation.Doc.location]. */
class NativeCSharpDocumentationTarget(val doc: NativeCSharpDocumentation.Doc, val project: Project) : DocumentationTarget {
    override fun createPointer(): Pointer<out DocumentationTarget> = Pointer.hardPointer(this)
    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(doc.title).presentation()
    override fun computeDocumentationHint(): String = doc.html
    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(doc.html)
    override val navigatable: Navigatable? get() = doc.location?.let { NativeCSharpDocumentation.navigatable(project, it) }
}

/** A click on a `cref` of the documentation: the documentation of what it names, which F4 opens (the source, or the metadata view). */
class NativeCSharpDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is NativeCSharpDocumentationTarget || !url.startsWith(NativeCSharpDocumentation.LINK)) return null
        val project = target.project
        val doc = runReadAction { NativeCSharpDocumentation.resolveLink(project, url) } ?: return null
        return LinkResolveResult.resolvedTarget(NativeCSharpDocumentationTarget(doc, project))
    }
}

object NativeCSharpDocumentation {
    /** The links of a documentation: `psi_element://` so the popup keeps them inside, then the encoded place (see [link]). */
    const val LINK = "psi_element://dotnet-doc/"

    /**
     * [definition]: the Quick Info line; [xml]: the inner XML of the documentation, null when there is none; [links]: the text of a `cref`
     * to its link; [location]: the link of the symbol itself, for F4.
     */
    class Doc(val title: String, val definition: String, val xml: String?, val links: Map<String, String> = emptyMap(), val location: String? = null, val extra: String? = null) {
        val html: String get() = render(definition, xml, links, extra)
    }

    /** The documentation of the name at [offset] of [file] (or just before it: the caret after the name), null when nothing is resolved. */
    fun at(file: CSharpFile, offset: Int): Doc? {
        if (file.compilationUnit == null || DumbService.isDumb(file.project)) return null
        val leaf = identifierAt(file, offset) ?: return null
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        varType(leaf, resolver)?.let { return it }
        val symbols = resolver.resolve(leaf)?.symbols ?: declared(leaf, resolver) ?: return null
        val symbol = symbols.firstOrNull() ?: return null
        return of(symbol, resolver, leaf.text, overloads(symbol, resolver, symbols.size))
    }

    private fun of(symbol: CSharpSymbol, resolver: CSharpNameResolver, title: String, overloads: Int = 0, extra: String? = null): Doc? {
        val definition = CSharpSymbolText(resolver).quickInfo(symbol, overloads) ?: return null
        val xml = xml(symbol, resolver)
        return Doc(title, definition, xml?.text, xml?.links.orEmpty() + libraryLinks(xml?.text, resolver), location(symbol, resolver), extra)
    }

    /**
     * `var` of a local, a `foreach`, a `using`: the type it stands for, as Roslyn's Quick Info shows it on `var` (`class System.String`), with
     * what the type parameters stand for (`T is string`).
     */
    private fun varType(leaf: PsiElement, resolver: CSharpNameResolver): Doc? {
        if (leaf.text != "var") return null
        val name = leaf.parent as? CSharpIdentifierName ?: return null
        if (name.parent !is CSharpVariableDeclaration && name.parent !is CSharpForEachStatement) return null
        val type = resolver.expressionType(name) ?: return null
        val symbol: CSharpSymbol = when (type) {
            is SemanticType.Library -> CSharpSymbol.LibraryType(type.type)
            is SemanticType.Source -> CSharpSymbol.SourceType(type.info)
            else -> return null
        }
        val parameters = when (type) {
            is SemanticType.Library -> type.type.typeParameters.map { it.name }
            is SemanticType.Source -> resolver.typeParameterNames(type.info.parts.firstOrNull { it.arity > 0 }?.element())
            else -> emptyList()
        }
        val arguments = when (type) {
            is SemanticType.Library -> type.arguments
            is SemanticType.Source -> type.arguments
            else -> emptyList()
        }
        val known = parameters.zip(arguments).mapNotNull { (parameter, argument) -> argument?.minimalDisplay?.let { "$parameter is $it" } }
        return of(symbol, resolver, "var", extra = known.joinToString("<br>") { StringUtil.escapeXmlEntities(it) }.ifEmpty { null })
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

    /** The inner XML of a documentation and the links of the `cref`s of a `///` comment it came from. */
    private class Xml(val text: String, val links: Map<String, String>)

    private fun xml(symbol: CSharpSymbol, resolver: CSharpNameResolver, depth: Int = 0): Xml? {
        val own = ownXml(symbol, resolver) ?: return null
        if (!IndexedDoc(own.text).inherits || depth > MAX_INHERITANCE) return own
        // `<inheritdoc/>`: the documentation of what it overrides or implements (or of its `cref`), its own parts first
        val cref = INHERIT_CREF.find(own.text)?.groupValues?.get(1)?.let(StringUtil::unescapeXmlEntities)
        val sources = if (cref != null) listOfNotNull(own.links[cref]?.let { linkSymbol(it, resolver) } ?: librarySymbol(cref, resolver.assemblies)) else bases(symbol, resolver)
        val inherited = sources.firstNotNullOfOrNull { xml(it, resolver, depth + 1) } ?: return Xml(own.text.replace(INHERIT, "").trim(), own.links)
        return Xml(merge(own.text.replace(INHERIT, "").trim(), inherited.text), inherited.links + own.links)
    }

    private fun ownXml(symbol: CSharpSymbol, resolver: CSharpNameResolver): Xml? = when (symbol) {
        is CSharpSymbol.LibraryMember -> resolver.assemblies.doc(symbol.member.docId)?.xml?.let { Xml(it, emptyMap()) }
        is CSharpSymbol.LibraryType -> resolver.assemblies.doc(symbol.type.docId)?.xml?.let { Xml(it, emptyMap()) }
        is CSharpSymbol.SourceType -> symbol.info.parts.firstNotNullOfOrNull { part -> part.element()?.let { sourceXml(it, resolver) } }
        is CSharpSymbol.SourceMember -> sourceXml(symbol.element.let { if (it is CSharpVariableDeclarator) it.parent?.parent ?: it else it }, resolver)
        is CSharpSymbol.Local -> parameterDoc(symbol, resolver)
        is CSharpSymbol.Namespace -> null
    }

    private fun sourceXml(owner: PsiElement, resolver: CSharpNameResolver): Xml? = docComment(owner)?.let { Xml(it, sourceLinks(owner, resolver)) }

    /** The `<param>` of a parameter in the documentation of its method (inherited too), as the summary of the parameter. */
    private fun parameterDoc(local: CSharpSymbol.Local, resolver: CSharpNameResolver): Xml? {
        if (local.symbol.kind != LocalSymbolKind.PARAMETER && local.symbol.kind != LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) return null
        val parameter = local.symbol.declaration.parent as? CSharpParameter ?: return null
        val owner = generateSequence(parameter.parent) { it.parent }.firstOrNull { it is CSharpMemberDeclaration || it is CSharpLocalFunctionStatement } ?: return null
        val method = (owner as? CSharpMethodDeclaration)?.let { declared(it.identifier ?: return@let null, resolver)?.firstOrNull() }
        val documentation = (if (method != null) xml(method, resolver) else sourceXml(owner, resolver)) ?: return null
        val text = IndexedDoc(documentation.text).parameter(local.symbol.name) ?: return null
        return Xml("<summary>$text</summary>", documentation.links)
    }

    /** What a symbol with `<inheritdoc/>` inherits the documentation of: the member it overrides or implements, the base type and interfaces. */
    private fun bases(symbol: CSharpSymbol, resolver: CSharpNameResolver): List<CSharpSymbol> = when (symbol) {
        is CSharpSymbol.SourceType -> resolver.baseTypes(resolver.selfType(symbol.info)).mapNotNull(::typeSymbol)
        is CSharpSymbol.LibraryType -> resolver.session.baseTypes(resolver.assemblies, symbol.type).map { CSharpSymbol.LibraryType(it.type) }
        is CSharpSymbol.SourceMember -> {
            val element = symbol.element
            val name = (element as? CSharpMethodDeclaration)?.identifier?.text ?: (element as? CSharpPropertyDeclaration)?.identifier?.text
                ?: (element as? CSharpEventDeclaration)?.identifier?.text
            val container = PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java)
            val info = container?.let(resolver.syntax::declaredType)
            if (name == null || info == null) emptyList()
            else {
                val count = parameterCount(symbol)
                resolver.baseTypes(resolver.selfType(info)).flatMap { base -> resolver.membersNamed(base, name, 0) }.filter { it != symbol && parameterCount(it) == count }
            }
        }
        is CSharpSymbol.LibraryMember -> {
            val member = symbol.member
            val count = member.parameters.size
            resolver.session.baseTypes(resolver.assemblies, member.type).flatMap { base ->
                base.type.members.filter { it.name == member.name && it.kind == member.kind && it.parameters.size == count }.map { CSharpSymbol.LibraryMember(it) }
            }
        }
        else -> emptyList()
    }

    private fun typeSymbol(type: SemanticType): CSharpSymbol? = when (type) {
        is SemanticType.Library -> CSharpSymbol.LibraryType(type.type)
        is SemanticType.Source -> CSharpSymbol.SourceType(type.info)
        else -> null
    }

    private fun parameterCount(symbol: CSharpSymbol): Int = when (symbol) {
        is CSharpSymbol.LibraryMember -> if (symbol.member.kind.isCallable) symbol.member.parameters.size else -1
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.parameterList?.parameters?.size ?: -1
        else -> -1
    }

    /** [own] with the parts it does not have taken from [inherited]: the summary, each `<param>` / `<typeparam>` by name, returns, value, remarks, exceptions. */
    fun merge(own: String, inherited: String): String {
        val result = StringBuilder(own)
        for (match in PART.findAll(inherited)) {
            val (tag, name) = match.destructured
            val present = if (name.isEmpty()) Regex("""<$tag[\s>]""").containsMatchIn(own) && tag != "exception" else Regex("""<$tag\s+name="${Regex.escape(name)}"""").containsMatchIn(own)
            if (!present) result.append('\n').append(match.value)
        }
        return result.toString().trim()
    }

    private const val MAX_INHERITANCE = 5
    private val INHERIT = Regex("""<inheritdoc\b[^>]*?(/>|>.*?</inheritdoc>)""", RegexOption.DOT_MATCHES_ALL)
    private val INHERIT_CREF = Regex("""<inheritdoc\s+cref\s*=\s*"([^"]*)"""")
    private val PART = Regex("""<(summary|remarks|returns|value|param|typeparam|exception)(?:\s+(?:name|cref)="([^"]*)")?\s*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL)

    // ---- links

    /** The `cref`s of the `///` comment right before [owner], resolved where they are written: their text to a link. */
    private fun sourceLinks(owner: PsiElement, resolver: CSharpNameResolver): Map<String, String> {
        val links = HashMap<String, String>()
        var first: PsiElement? = PsiTreeUtil.getDeepestFirst(owner)
        while (first != null && isTrivia(first)) first = PsiTreeUtil.nextLeaf(first)
        var previous = first?.let(PsiTreeUtil::prevLeaf)
        while (previous != null && isTrivia(previous)) {
            val comment = PsiTreeUtil.getParentOfType(previous, PsiComment::class.java, false) ?: previous
            for (attribute in PsiTreeUtil.findChildrenOfType(comment, CSharpXmlCrefAttribute::class.java)) {
                val cref = attribute.cref ?: continue
                val symbol = crefSymbol(cref, owner, resolver) ?: continue
                link(symbol, resolver)?.let { links[cref.text.trim()] = it }
            }
            previous = PsiTreeUtil.prevLeaf(comment)
        }
        return links
    }

    /** What a `cref` of a `///` comment names: by the resolver, else a member of the type around the comment, else a type of that name. */
    private fun crefSymbol(cref: CSharpCref, owner: PsiElement, resolver: CSharpNameResolver): CSharpSymbol? {
        val name = PsiTreeUtil.collectElements(cref) { CSharpLeaves.isIdentifier(it) && PsiTreeUtil.getParentOfType(it, CSharpCrefParameterList::class.java) == null }.lastOrNull() ?: return null
        runCatching { resolver.resolve(name)?.symbols?.firstOrNull() }.getOrNull()?.let { return it }
        if (cref is CSharpQualifiedCref) {
            // `Factory.Ship`: the member of the type the container names
            val container = cref.container?.let { PsiTreeUtil.collectElements(it) { leaf -> CSharpLeaves.isIdentifier(leaf) }.lastOrNull() } ?: return null
            val type = when (val symbol = runCatching { resolver.resolve(container)?.symbols?.firstOrNull() }.getOrNull()) {
                is CSharpSymbol.SourceType -> resolver.selfType(symbol.info)
                is CSharpSymbol.LibraryType -> SemanticType.Library(symbol.type, emptyList())
                else -> return null
            }
            return resolver.membersNamed(type, name.text, 0).firstOrNull()
        }
        val type = PsiTreeUtil.getParentOfType(owner, CSharpBaseTypeDeclaration::class.java, false)?.let(resolver.syntax::declaredType)
        if (type != null && cref !is CSharpQualifiedCref) {
            resolver.membersNamed(resolver.selfType(type), name.text, 0).firstOrNull()?.let { return it }
            if (type.qualifiedName.substringAfterLast('.') == name.text) return CSharpSymbol.SourceType(type)
        }
        return null
    }

    /** The links of the `cref`s of the documentation of an assembly (`T:System.String`): those it has in the index. */
    private fun libraryLinks(xml: String?, resolver: CSharpNameResolver): Map<String, String> {
        if (xml == null) return emptyMap()
        val links = HashMap<String, String>()
        for (match in CREF.findAll(xml)) {
            val cref = StringUtil.unescapeXmlEntities(match.groupValues[1])
            if (cref.getOrNull(1) != ':' || cref.startsWith("N:")) continue
            val symbol = librarySymbol(cref, resolver.assemblies) ?: continue
            link(symbol, resolver)?.let { links[cref] = it }
        }
        return links
    }

    private val CREF = Regex("""\bcref\s*=\s*"([^"]*)"""")

    /** A link to [symbol]: `S<offset>|<file url>` for a declaration of the sources, `L<doc id>|<file url>` for an assembly (the file whose assemblies have it). */
    private fun link(symbol: CSharpSymbol, resolver: CSharpNameResolver): String? {
        val payload = when (symbol) {
            is CSharpSymbol.LibraryType -> "L" + symbol.type.docId + "|" + (resolver.file.virtualFile?.url ?: return null)
            is CSharpSymbol.LibraryMember -> "L" + symbol.member.docId + "|" + (resolver.file.virtualFile?.url ?: return null)
            is CSharpSymbol.Namespace -> return null
            else -> {
                val declaration = symbol.declarations.firstOrNull() ?: return null
                val name = CSharpDeclarationNames.nameElement(declaration) ?: declaration
                "S" + name.textRange.startOffset + "|" + (declaration.containingFile?.virtualFile?.url ?: return null)
            }
        }
        return LINK + URLEncoder.encode(payload, Charsets.UTF_8)
    }

    private fun location(symbol: CSharpSymbol, resolver: CSharpNameResolver): String? = runCatching { link(symbol, resolver) }.getOrNull()

    private fun decode(url: String): Pair<Char, Pair<String, String>>? {
        val payload = URLDecoder.decode(url.removePrefix(LINK), Charsets.UTF_8)
        val kind = payload.firstOrNull() ?: return null
        val key = payload.substring(1).substringBefore('|')
        val file = payload.substringAfter('|', "").ifEmpty { return null }
        return kind to (key to file)
    }

    private fun fileOf(project: Project, url: String): CSharpFile? =
        VirtualFileManager.getInstance().findFileByUrl(url)?.let { PsiManager.getInstance(project).findFile(it) } as? CSharpFile

    /** The documentation a link of [link] leads to. Read action. */
    fun resolveLink(project: Project, link: String): Doc? {
        val (kind, place) = decode(link) ?: return null
        val file = fileOf(project, place.second) ?: return null
        return when (kind) {
            'S' -> place.first.toIntOrNull()?.let { at(file, it) }
            'L' -> {
                val resolver = CSharpSemanticSession(project).resolver(file)
                val symbol = librarySymbol(place.first, resolver.assemblies) ?: return null
                of(symbol, resolver, place.first.substringAfterLast('.').substringBefore('('))
            }
            else -> null
        }
    }

    /** The symbol of a link, for `<inheritdoc cref="..."/>`. */
    private fun linkSymbol(link: String, resolver: CSharpNameResolver): CSharpSymbol? {
        val (kind, place) = decode(link) ?: return null
        if (kind == 'L') return librarySymbol(place.first, resolver.assemblies)
        val file = fileOf(resolver.file.project, place.second) ?: return null
        val leaf = place.first.toIntOrNull()?.let(file::findElementAt) ?: return null
        val owner = CSharpSemanticSession(resolver.file.project).resolver(file)
        return owner.resolve(leaf)?.symbols?.firstOrNull() ?: declared(leaf, owner)?.firstOrNull()
    }

    /** F4 in the popup: the declaration of the sources, or the metadata view of the type of an assembly at the member. */
    fun navigatable(project: Project, link: String): Navigatable? {
        val (kind, place) = decode(link) ?: return null
        return when (kind) {
            'S' -> VirtualFileManager.getInstance().findFileByUrl(place.second)?.let { OpenFileDescriptor(project, it, place.first.toIntOrNull() ?: 0) }
            'L' -> object : Navigatable {
                override fun navigate(requestFocus: Boolean) {
                    val target = runReadAction {
                        val file = fileOf(project, place.second) ?: return@runReadAction null
                        librarySymbol(place.first, CSharpSemanticSession(project).resolver(file).assemblies)
                    }
                    when (target) {
                        is CSharpSymbol.LibraryType -> AssemblyNavigation.navigate(project, target.type, null, requestFocus)
                        is CSharpSymbol.LibraryMember -> AssemblyNavigation.navigate(project, target.member.type, target.member, requestFocus)
                        else -> {}
                    }
                }

                override fun canNavigate(): Boolean = true
                override fun canNavigateToSource(): Boolean = true
            }
            else -> null
        }
    }

    /** The type or member of an assembly a documentation id names (`T:System.String`, `M:System.Console.WriteLine(System.String)`). */
    fun librarySymbol(docId: String, assemblies: AssemblyIndexSet): CSharpSymbol? {
        if (docId.getOrNull(1) != ':') return null
        val name = docId.substring(2)
        if (docId[0] == 'T') return libraryType(name, assemblies)?.let { CSharpSymbol.LibraryType(it) }
        if (docId[0] == 'N') return null
        val path = name.substringBefore('(').substringBefore('~')
        val type = libraryType(path.substringBeforeLast('.'), assemblies) ?: return null
        val memberName = path.substringAfterLast('.').substringBefore('`').let { if (it == "#ctor") type.name.substringBefore('`') else it }
        val member = type.members.firstOrNull { it.docId == docId.substringBefore('~') } ?: type.members.firstOrNull { it.name == memberName || it.kind == IndexedMemberKind.CONSTRUCTOR && path.endsWith("#ctor") }
        return member?.let { CSharpSymbol.LibraryMember(it) }
    }

    /** `System.Collections.Generic.List`1`, a nested one by dots as in documentation ids (`Dictionary`2.Enumerator`). */
    private fun libraryType(name: String, assemblies: AssemblyIndexSet): IndexedType? {
        assemblies.findType(name)?.let { return it }
        var candidate = name
        repeat(3) {
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(0, dot) + "+" + candidate.substring(dot + 1)
            assemblies.findType(candidate)?.let { return it }
        }
        return null
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

    /** [links]: the text of a `cref` to its link (`<see cref>` and exceptions become links); [extra]: HTML after the definition (`T is string` of `var`). */
    fun render(definition: String, xml: String?, links: Map<String, String> = emptyMap(), extra: String? = null): String = buildString {
        append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities(definition)).append(DocumentationMarkup.DEFINITION_END)
        extra?.let { append(DocumentationMarkup.CONTENT_START).append(it).append(DocumentationMarkup.CONTENT_END) }
        val doc = xml?.let(::IndexedDoc) ?: return@buildString
        val inline = { text: String -> inline(text, links) }
        doc.summary?.let { append(DocumentationMarkup.CONTENT_START).append(inline(it)).append(DocumentationMarkup.CONTENT_END) }
        val sections = ArrayList<Pair<String, String>>()
        named(doc.xml, "typeparam").takeIf { it.isNotEmpty() }?.let { list -> sections += "Type parameters:" to list.joinToString("<br>") { (name, text) -> "<code>${StringUtil.escapeXmlEntities(name)}</code> – ${inline(text)}" } }
        named(doc.xml, "param").takeIf { it.isNotEmpty() }?.let { list -> sections += "Params:" to list.joinToString("<br>") { (name, text) -> "<code>${StringUtil.escapeXmlEntities(name)}</code> – ${inline(text)}" } }
        doc.returns?.let { sections += "Returns:" to inline(it) }
        doc.value?.let { sections += "Value:" to inline(it) }
        doc.exceptions.takeIf { it.isNotEmpty() }?.let { list -> sections += "Exceptions:" to list.joinToString("<br>") { (cref, text) -> "${linked(cref, links)} – ${inline(text)}" } }
        // `quick_info.dotnet_show_remarks_in_quick_info` of the server's page, obeyed by the native documentation too
        if (RoslynOptions.isOn("quick_info.dotnet_show_remarks_in_quick_info")) doc.remarks?.let { sections += "Remarks:" to inline(it) }
        if (sections.isNotEmpty()) {
            append(DocumentationMarkup.SECTIONS_START)
            for ((header, body) in sections) {
                append(DocumentationMarkup.SECTION_HEADER_START).append(header).append(DocumentationMarkup.SECTION_SEPARATOR).append(body).append(DocumentationMarkup.SECTION_END)
            }
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /** A `cref` as code, inside a link where [links] has one. */
    private fun linked(cref: String, links: Map<String, String>): String {
        val code = "<code>${StringUtil.escapeXmlEntities(crefName(StringUtil.unescapeXmlEntities(cref)))}</code>"
        return links[StringUtil.unescapeXmlEntities(cref)]?.let { "<a href=\"${StringUtil.escapeXmlEntities(it)}\">$code</a>" } ?: code
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
     * output is built tag by tag from a fixed set, attribute values are escaped, and links are only `http` / `https` — or the plugin's own
     * ones of [links], for the `cref`s it has resolved.
     */
    fun inline(xml: String, links: Map<String, String> = emptyMap()): String {
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
                        attributes["cref"] != null -> links[StringUtil.unescapeXmlEntities(attributes.getValue("cref"))]
                            ?.let { "<a href=\"${StringUtil.escapeXmlEntities(it)}\"><code>" to "</code></a>" } ?: ("<code>" to "</code>")
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
        // a `cref` of a `///` comment is C# as written (`Factory.Ship`): shown so
        if (kind == null) return name
        val parts = name.split('.')
        return if (kind == 'T' || kind == 'N' || parts.size < 2) parts.last() else parts.takeLast(2).joinToString(".")
    }
}
