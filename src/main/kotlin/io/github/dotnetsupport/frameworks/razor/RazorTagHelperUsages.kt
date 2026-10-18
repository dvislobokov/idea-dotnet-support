package io.github.dotnetsupport.frameworks.razor

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.intellij.util.Processor
import com.intellij.xml.util.XmlTagUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseNamespaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpRenameParticipant
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.CSharpSymbol
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.razor.lang.discovery.HtmlConventions
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions

/**
 * The places of Razor's markup that name a component or a parameter (RAZOR_PLAN.md, R5e part 2): the tags of a component (`<Counter>`,
 * `</Counter>`, the last name of `<App.Components.Counter>`), the attributes that set a parameter (`Step="2"`, the `Value` of `@bind-Value`
 * and `@bind-Value:after`), the child content tags of a `RenderFragment` parameter (`<ChildContent>` in its component). What binds them is
 * the compiler's — the descriptors in scope of the file ([RazorTagHelperEditor.context]), as Go to Declaration reads them. Find Usages
 * shows them ([RazorMarkupReferencesSearcher]), rename writes over them ([RazorRenameParticipant]).
 */
object RazorTagHelperUsages {
    /**
     * A place of markup: [range] of [file] (the Razor file of the view) names the component or parameter; [element] is the name's token.
     * [spelling]: how a rename writes the new name there (`mail-to` of `MailTo`); not [renamable]: found, but its name is no name of the C#
     * (the tag of a tag helper, an attribute named by `[HtmlAttributeName]`).
     */
    class Place(val file: RazorFile, val element: PsiElement, val range: TextRange, val spelling: ((String) -> String)? = null, val renamable: Boolean = true)

    /** The CLR name of the component [element] is: a component's `.razor` file, a class of the C# (of the sources or an assembly's view). */
    fun componentOf(element: PsiElement): String? {
        if (element is RazorFile) return componentName(element)
        if (element is PsiFile) return null
        val target = CSharpSolutionSearch.targetOf(element)?.takeIf { it.isType } ?: return null
        (target.library as? CSharpSymbol.LibraryType)?.let { return it.type.fullName.substringBefore('`') }
        val declaration = element as? CSharpBaseTypeDeclaration ?: return null
        return qualifiedName(declaration)
    }

    /** The component and the property a parameter [element] is: a property of a C# class, of a component's `@code`. */
    fun parameterOf(element: PsiElement): Pair<String, String>? {
        val property = element as? CSharpPropertyDeclaration ?: return null
        val name = property.identifier?.text?.removePrefix("@") ?: return null
        val owner = CSharpSolutionSearch.ownerType(property) ?: return null
        return (qualifiedName(owner) ?: return null) to name
    }

    /** The component a `.razor` file declares (not `_Imports.razor`, not a page of `.cshtml`): the class of its declaration. */
    fun componentName(file: RazorFile): String? {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        if (virtualFile.extension != "razor" || virtualFile.nameWithoutExtension.equals("_Imports", ignoreCase = true)) return null
        val declaration = RazorTagHelpers.declarationFile(file) ?: return null
        val syntax = CSharpSemanticSession(file.project).resolver(declaration).syntax
        fun first(members: List<CSharpMemberDeclaration>): CSharpBaseTypeDeclaration? = members.firstNotNullOfOrNull { member ->
            when (member) {
                is CSharpBaseNamespaceDeclaration -> first(PsiTreeUtil.getChildrenOfTypeAsList(member, CSharpMemberDeclaration::class.java))
                is CSharpBaseTypeDeclaration -> member
                else -> null
            }
        }
        return first(declaration.compilationUnit?.members.orEmpty())?.let(syntax::qualifiedName)
    }

    private fun qualifiedName(type: CSharpBaseTypeDeclaration): String? {
        val file = type.containingFile as? CSharpFile ?: return null
        return CSharpSemanticSession(type.project).resolver(file).syntax.qualifiedName(type)
    }

    /** The tags of the component [typeName] in [scope]. */
    fun tags(project: Project, typeName: String, scope: SearchScope): List<Place> {
        val simpleName = typeName.substringAfterLast('.')
        val found = ArrayList<Place>()
        forEachTag(project, simpleName, scope) { file, context, tag ->
            val descriptors = descriptorsOf(context, tag)
            if (descriptors.any { it.kind == TagHelperKind.Component && RazorTagHelperEditor.typeNameOf(it) == typeName }) names(file, tag, simpleName, found)
        }
        // the tags of a tag helper of `.cshtml` (`<email>` of EmailTagHelper): where it applies to the element, by its name and attributes
        val tagNames = tagHelpersOf(project, typeName).flatMap { d -> d.tagMatchingRules.map { it.tagName } }.filter { it != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME }.distinct()
        for (tagName in tagNames) forEachTag(project, wordOf(tagName), scope) { file, context, tag ->
            if (!tag.name.removePrefix(context.prefix.orEmpty()).equals(tagName, ignoreCase = true)) return@forEachTag
            if (context.tagHelpers.any { it.kind == TagHelperKind.ITagHelper && RazorTagHelperEditor.typeNameOf(it) == typeName && RazorTagHelperEditor.applies(context, it, tag) }) {
                names(file, tag, tag.name, found, renamable = false)
            }
        }
        // a tag helper of every element (`[HtmlTargetElement(Attributes = "tooltip")]`): the elements it applies to, by their start tag
        val attributeWords = tagHelpersOf(project, typeName).flatMap { d -> d.tagMatchingRules.filter { it.tagName == TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME } }
            .flatMap { rule -> rule.attributes.map { it.name } }.filter { it.isNotEmpty() }.distinct()
        for (attribute in attributeWords) forEachTag(project, wordOf(attribute), scope) { file, context, tag ->
            val applies = context.tagHelpers.any { it.kind == TagHelperKind.ITagHelper && RazorTagHelperEditor.typeNameOf(it) == typeName && RazorTagHelperEditor.applies(context, it, tag) }
            val name = XmlTagUtil.getStartTagNameElement(tag) ?: return@forEachTag
            if (applies && found.none { it.file == file && it.element == name }) found += Place(file, name, name.textRange, renamable = false)
        }
        return found
    }

    /** The tag helpers of `.cshtml` of the class [typeName] the projects discovered (the names of their tags and attributes are theirs). */
    private fun tagHelpersOf(project: Project, typeName: String): List<TagHelperDescriptor> =
        RazorTagHelperService.getInstance(project).known().filter { it.kind == TagHelperKind.ITagHelper && RazorTagHelperEditor.typeNameOf(it) == typeName }.distinctBy { it.name }

    /** The word of the index that a file naming [name] (`mail-to`, `asp-for`) has: its longest part. */
    private fun wordOf(name: String): String = name.split(Regex("[^A-Za-z0-9_]+")).maxByOrNull { it.length } ?: name

    /**
     * The attributes and child content tags that set the parameter [property] of the component [typeName] — or of a component deriving from
     * it, when [typeName] is a class of the C# — in [scope].
     */
    fun attributes(project: Project, typeName: String, property: String, scope: SearchScope, derived: Set<String> = emptySet(), explicitName: Boolean = false): List<Place> {
        val components = derived + typeName
        val found = ArrayList<Place>()
        tagHelperAttributes(project, components, property, scope, explicitName, found)
        forEachTag(project, property, scope) { file, context, tag ->
            val descriptors = RazorTagHelperEditor.candidates(context, tag.name, tag.parentTag?.name)
                .filter { (it.kind == TagHelperKind.Component || it.kind == TagHelperKind.Bind) && RazorTagHelperEditor.typeNameOf(it) in components }
            for (attribute in tag.attributes) {
                val name = attribute.nameElement ?: continue
                val bare = attribute.name.substringBefore(':')
                val sets = descriptors.flatMap { it.boundAttributes }.any { bound ->
                    bound.propertyName == property && bound.name.equals(bare, ignoreCase = !bound.caseSensitive)
                }
                if (!sets) continue
                // `@bind-Value`: the property's name after the directive
                val start = if (bare.startsWith("@bind-", ignoreCase = true)) "@bind-".length else 0
                if (bare.length - start != property.length) continue
                found += Place(file, name, TextRange.from(name.textRange.startOffset + start, property.length))
            }
            // `<ChildContent>` in its component: the child content descriptor of the component's parameter
            val childContent = descriptorsOf(context, tag).any { it.kind == TagHelperKind.ChildContent && RazorTagHelperEditor.typeNameOf(it) in components }
            if (childContent && tag.localName == property) names(file, tag, property, found)
        }
        return found
    }

    /**
     * The attributes of the tag helpers of `.cshtml` [types] that set [property] (`mail-to`, `asp-for`), where the tag helper applies to the
     * element. A rename writes the new property's name in HTML case there (`MailTo` → `mail-to`) — unless the attribute's name is not the
     * property's ([explicitName]: `[HtmlAttributeName]`), which stays.
     */
    private fun tagHelperAttributes(project: Project, types: Set<String>, property: String, scope: SearchScope, explicitName: Boolean, found: MutableList<Place>) {
        val bounds = types.flatMap { tagHelpersOf(project, it) }.flatMap { it.boundAttributes }.filter { it.propertyName == property }
        // a dictionary property sets its items by the attributes of a prefix too: `RouteValues` by `asp-route-id`
        val names = (bounds.map { it.name } + bounds.mapNotNull { it.indexerNamePrefix?.takeIf(String::isNotEmpty) }).distinct()
        val seen = HashSet<Pair<RazorFile, TextRange>>()
        for (attributeName in names) forEachTag(project, wordOf(attributeName), scope) { file, context, tag ->
            val descriptors = context.tagHelpers.filter { it.kind == TagHelperKind.ITagHelper && RazorTagHelperEditor.typeNameOf(it) in types && RazorTagHelperEditor.applies(context, it, tag) }
            if (descriptors.isEmpty()) return@forEachTag
            for (attribute in tag.attributes) {
                val name = attribute.nameElement ?: continue
                val bound = descriptors.flatMap { it.boundAttributes }.firstOrNull { it.propertyName == property && it.name.equals(attribute.name, ignoreCase = !it.caseSensitive) }
                if (bound == null) {
                    // an item of the dictionary (`asp-route-id`): a usage, its name is the item's — no rename writes it
                    val prefixed = descriptors.flatMap { it.boundAttributes }.any { b ->
                        b.propertyName == property && b.indexerNamePrefix?.takeIf(String::isNotEmpty)?.let { attribute.name.startsWith(it, ignoreCase = !b.caseSensitive) } == true
                    }
                    if (prefixed && seen.add(file to name.textRange)) found += Place(file, name, name.textRange, renamable = false)
                    continue
                }
                if (!seen.add(file to name.textRange)) continue
                val conventional = !explicitName && bound.name == HtmlConventions.toHtmlCase(property)
                found += Place(file, name, name.textRange, if (conventional) HtmlConventions::toHtmlCase else null, renamable = conventional)
            }
        }
    }

    /** The descriptors an element is by its name (its tag in their rules, not `*`), the child content ones only in their parent. */
    private fun descriptorsOf(context: io.github.dotnetsupport.razor.lang.syntax.TagHelperDocumentContext, tag: XmlTag): List<TagHelperDescriptor> =
        RazorTagHelperEditor.candidates(context, tag.name, tag.parentTag?.name).filter { d -> d.tagMatchingRules.any { it.tagName != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME } }

    /** The start and end tag names of [tag], their last name ([name]: the part after the namespace of `<App.Components.Counter>`). */
    private fun names(file: RazorFile, tag: XmlTag, name: String, found: MutableList<Place>, renamable: Boolean = true) {
        for (element in listOfNotNull(XmlTagUtil.getStartTagNameElement(tag), XmlTagUtil.getEndTagNameElement(tag))) {
            val text = element.text
            if (!text.endsWith(name) || text.length > name.length && text[text.length - name.length - 1] != '.') continue
            found += Place(file, element, TextRange.from(element.textRange.endOffset - name.length, name.length), renamable = renamable)
        }
    }

    /** Every tag of the markup of the Razor files in [scope] whose text has [word], with the descriptors in scope of its file. */
    private fun forEachTag(project: Project, word: String, scope: SearchScope, action: (RazorFile, io.github.dotnetsupport.razor.lang.syntax.TagHelperDocumentContext, XmlTag) -> Unit) {
        if (DumbService.isDumb(project)) return
        for (file in filesWithWord(project, word, scope)) {
            val context = RazorTagHelperEditor.context(file) ?: continue
            val html = file.viewProvider.getPsi(HTMLLanguage.INSTANCE) ?: continue
            for (tag in SyntaxTraverser.psiTraverser(html).filter(XmlTag::class.java)) {
                if (scope is LocalSearchScope && !scope.containsRange(html, tag.textRange)) continue
                action(file, context, tag)
            }
        }
    }

    private fun filesWithWord(project: Project, word: String, scope: SearchScope): List<RazorFile> {
        if (scope is LocalSearchScope) return scope.scope.mapNotNull { RazorTagHelperEditor.razorFile(it.containingFile) }.distinct()
        val global = scope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project)
        val files = LinkedHashSet<RazorFile>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord(word, global, { file ->
            RazorTagHelperEditor.razorFile(file)?.let(files::add)
            true
        }, true)
        return files.toList()
    }

    /** The places that name [element] (a component, a parameter) in [scope]. */
    fun places(element: PsiElement, scope: SearchScope): List<Place> {
        val project = element.project
        // the names of a tag helper's tags and attributes are its descriptors': those of its own project at least, discovered now if need be
        element.containingFile?.virtualFile?.let { CompilationModel.getInstance(project).projectOf(it) }?.let { RazorTagHelperService.getInstance(project).tagHelpersNow(it) }
        componentOf(element)?.let { return tags(project, it, scope) }
        val (type, property) = parameterOf(element) ?: return emptyList()
        return attributes(project, type, property, scope, derivedComponents(element), explicitName = hasExplicitName(element))
    }

    /** A property of a tag helper named in the markup by `[HtmlAttributeName("…")]`: its attribute keeps that name on a rename. */
    private fun hasExplicitName(property: PsiElement): Boolean =
        Regex("""\bHtmlAttributeName(Attribute)?\s*\(\s*"""").containsMatchIn((property as? CSharpPropertyDeclaration)?.attributeLists?.joinToString(" ") { it.text }.orEmpty())

    /** The classes of the C# deriving from the owner of the parameter [property]: their tags set it too. */
    private fun derivedComponents(property: PsiElement): Set<String> {
        val owner = CSharpSolutionSearch.ownerType(property) ?: return emptySet()
        if (owner.containingFile?.let { InjectedLanguageManager.getInstance(property.project).isInjectedFragment(it) } == true) return emptySet()
        val target = CSharpSolutionSearch.targetOf(owner) ?: return emptySet()
        val session = CSharpSemanticSession(property.project)
        return CSharpSolutionSearch.allSubtypes(property.project, target, session).mapNotNullTo(HashSet()) { (it as? CSharpBaseTypeDeclaration)?.let(::qualifiedName) }
    }

    /**
     * The property of the source generator's output (`Counter_razor.g.cs`) that a property of a component's `@code` [property] (of its C#
     * document) is: the C# files resolve the component's members there, so its usages are this one's too.
     */
    fun generatedTwin(property: PsiElement): CSharpPropertyDeclaration? {
        if (property !is CSharpPropertyDeclaration) return null
        val file = property.containingFile ?: return null
        val manager = InjectedLanguageManager.getInstance(property.project)
        if (!manager.isInjectedFragment(file)) return null
        val host = RazorTagHelperEditor.razorFile(manager.getTopLevelFile(file)) ?: return null
        val (typeName, name) = parameterOf(property) ?: return null
        return csharpTypeDeclarations(host, typeName)
            .filter { it.containingFile?.viewProvider?.virtualFile?.let(RazorTagHelpers::isRazorOutput) == true }
            .flatMap { (it as? io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration)?.members.orEmpty() }
            .filterIsInstance<CSharpPropertyDeclaration>().firstOrNull { it.identifier?.text?.removePrefix("@") == name }
    }

    /**
     * The member of a component's `.razor` (its C# document: `@code`) that a member of the generator's output [member] is — the other way
     * of [generatedTwin]: Find Usages started in a `.cs` names the output's member, the code of `@code` names the document's (RAZOR_PLAN.md,
     * R4: "Find Usages of a `@code` member started from a `.cs` misses its uses in `@code`").
     */
    fun sourceTwin(member: PsiElement): io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration? {
        if (member !is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration || member is CSharpBaseTypeDeclaration) return null
        val file = member.containingFile?.viewProvider?.virtualFile ?: return null
        if (!RazorTagHelpers.isRazorOutput(file)) return null
        val owner = CSharpSolutionSearch.ownerType(member) ?: return null
        val typeName = qualifiedName(owner) ?: return null
        val name = io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.name(member) ?: return null
        val project = member.project
        val razor = RazorTagHelperEditor.razorComponent(anyRazorFile(project) ?: return null, typeName) ?: return null
        val manager = InjectedLanguageManager.getInstance(project)
        val host = RazorCSharpDocument.places(razor).firstOrNull()?.host ?: return null
        val injected = manager.getInjectedPsiFiles(host)?.firstOrNull()?.first as? CSharpFile ?: return null
        // the member `@code` writes, not one of the glue (the class around, `BuildRenderTree`)
        return PsiTreeUtil.findChildrenOfType(injected, io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration::class.java).firstOrNull { candidate ->
            if (candidate is CSharpBaseTypeDeclaration || io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.name(candidate) != name) return@firstOrNull false
            val nameElement = io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames.nameElement(candidate) ?: return@firstOrNull false
            io.github.dotnetsupport.lang.semantic.CSharpInjections.isWritten(injected, nameElement.textRange)
        }
    }

    /** Some Razor file of [project]: the context [RazorTagHelperEditor.razorComponent] resolves names in. */
    private fun anyRazorFile(project: Project): RazorFile? {
        val psi = PsiManager.getInstance(project)
        return com.intellij.psi.search.FileTypeIndex.getFiles(RazorFileType, GlobalSearchScope.projectScope(project)).asSequence()
            .mapNotNull { RazorTagHelperEditor.razorFile(psi.findFile(it)) }.firstOrNull()
    }

    /** The usages of [sourceTwin] in [scope]: the code of `@code` and of the markup naming the member a `.cs` searched in the output. */
    fun sourceTwinUsages(member: PsiElement, scope: SearchScope): List<PsiElement> {
        val twin = sourceTwin(member) ?: return emptyList()
        val target = CSharpSolutionSearch.targetOf(twin) ?: return emptyList()
        return CSharpSolutionSearch.usages(member.project, target, scope).filter { !it.implicit }.map { it.leaf }
    }

    /** The usages of [generatedTwin] in [scope]: the names of the C# that resolve to the generator's output. */
    fun twinUsages(property: PsiElement, scope: SearchScope): List<PsiElement> {
        val twin = generatedTwin(property) ?: return emptyList()
        val target = CSharpSolutionSearch.targetOf(twin) ?: return emptyList()
        return CSharpSolutionSearch.usages(property.project, target, scope).filter { !it.implicit }.map { it.leaf }
    }

    /** The class of the component of [file] in the C# files: its code-behind, the source generator's output; empty when the C# has none. */
    fun componentDeclarations(file: RazorFile): List<PsiElement> = componentName(file)?.let { csharpTypeDeclarations(file, it) }.orEmpty()

    /**
     * The declarations of the class [typeName] in the C# files: of the sources (a code-behind) and of the generators' output — the folder of
     * the helper is outside the project (the scope of the C# sources has it), where the resolution from a `.razor` file does not look.
     */
    fun csharpTypeDeclarations(file: RazorFile, typeName: String): List<PsiElement> {
        val project = file.project
        val found = LinkedHashSet<PsiElement>()
        RazorTagHelperEditor.sourceDeclarations(file, typeName).filterTo(found) { it.containingFile?.viewProvider?.virtualFile?.isInLocalFileSystem == true }
        val simpleName = typeName.substringAfterLast('.')
        com.intellij.psi.stubs.StubIndex.getInstance().processElements(io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys.TYPE_NAMES, simpleName, project,
            io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(project), io.github.dotnetsupport.csharp.lang.psi.CSharpElement::class.java) { element ->
            if (element is CSharpBaseTypeDeclaration && qualifiedName(element) == typeName) found += element
            true
        }
        return found.toList()
    }


    /** The names of the C# that name the class of the component of [file] (`typeof(Counter)`, `Counter counter`). */
    fun componentCSharpUsages(file: RazorFile, scope: SearchScope): List<PsiElement> {
        val declarations = componentDeclarations(file).ifEmpty { return emptyList() }
        val name = componentName(file)?.substringAfterLast('.') ?: return emptyList()
        val target = io.github.dotnetsupport.lang.semantic.CSharpSearchTarget(name, io.github.dotnetsupport.lang.semantic.CSharpSearchTarget.Kind.TYPE, declarations)
        return CSharpSolutionSearch.usages(file.project, target, scope).map { it.leaf }
    }

    /** The `.razor` file of the component [typeName] and the files nested under it
 (`Counter.razor.cs`, `.razor.css`, `.razor.js`). */
    fun componentFiles(project: Project, file: RazorFile): List<PsiFile> {
        val virtualFile = file.originalFile.viewProvider.virtualFile
        val prefix = virtualFile.name + "."
        val psi = PsiManager.getInstance(project)
        val nested = virtualFile.parent?.children.orEmpty().filter { !it.isDirectory && it.name.startsWith(prefix) }.mapNotNull(psi::findFile)
        return listOf(file) + nested
    }
}

/** A tag or attribute of Razor's markup naming a component or parameter, for Find Usages: the name's token, resolved to the declaration. */
class RazorMarkupReference(element: PsiElement, range: TextRange, private val target: PsiElement) : PsiReferenceBase<PsiElement>(element, range, true) {
    override fun resolve(): PsiElement = target
    override fun isReferenceTo(element: PsiElement): Boolean = element == target || element.manager.areElementsEquivalent(element, target)
    override fun handleElementRename(newElementName: String): PsiElement = element // the rename of the C# writes the markup ([RazorRenameParticipant])
}

/** Find Usages and Show Usages of a component and of a parameter: their tags and attributes in the markup. */
class RazorMarkupReferencesSearcher : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
    override fun processQuery(parameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {
        val target = parameters.elementToSearch
        // a member of a component's `@code` searched in a `.cs` (it names the generator's output): its uses in the `.razor`
        if (target is io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration && target !is CSharpBaseTypeDeclaration) {
            for (leaf in RazorTagHelperUsages.sourceTwinUsages(target, parameters.effectiveSearchScope)) {
                if (!consumer.process(io.github.dotnetsupport.lang.CSharpSolutionReference(leaf, target))) return
            }
        }
        if (target !is RazorFile && target !is CSharpBaseTypeDeclaration && target !is CSharpPropertyDeclaration) return
        for (place in RazorTagHelperUsages.places(target, parameters.effectiveSearchScope)) {
            val range = place.range.shiftLeft(place.element.textRange.startOffset)
            if (!consumer.process(RazorMarkupReference(place.element, range, target))) return
        }
        // a component of a `.razor` file: the C# naming its class (its code-behind, the generator's output)
        if (target is RazorFile) for (leaf in RazorTagHelperUsages.componentCSharpUsages(target, parameters.effectiveSearchScope)) {
            if (!consumer.process(io.github.dotnetsupport.lang.CSharpSolutionReference(leaf, target))) return
        }
        // a parameter of `@code`: the C# files name it in the generator's output
        for (leaf in RazorTagHelperUsages.twinUsages(target, parameters.effectiveSearchScope)) {
            if (!consumer.process(io.github.dotnetsupport.lang.CSharpSolutionReference(leaf, target))) return
        }
    }
}

/** The tags and attributes of the markup are a group of their own in the usages view. */
class RazorMarkupUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        if (element.node?.elementType != XmlTokenType.XML_NAME || RazorTagHelperEditor.razorFile(element.containingFile) == null) return null
        return if (element.parent is XmlAttribute) ATTRIBUTE else TAG
    }

    private companion object {
        val TAG = UsageType { "Tag in markup" }
        val ATTRIBUTE = UsageType { "Attribute in markup" }
    }
}

/**
 * Find Usages (Alt+F7) on a tag or an attribute of the markup: of the component or the parameter it names, not of the HTML element — the
 * platform would search the tag's name as text.
 */
class RazorMarkupFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
    override fun canFindUsages(element: PsiElement): Boolean = (element is XmlTag || element is XmlAttribute) && target(element) != null

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        val target = target(element) ?: return null
        return object : FindUsagesHandler(target) {}
    }

    private fun target(element: PsiElement): PsiElement? {
        if (DumbService.isDumb(element.project)) return null
        return RazorTagHelperEditor.markupTarget(element)
    }
}

/**
 * The target of Find Usages and Show Usages at an attribute of Razor's markup that sets a parameter: the attribute (the XML evaluator this one
 * extends takes no attribute as a target, its reference resolves to nothing), which [RazorMarkupFindUsagesHandlerFactory] turns into the
 * parameter. Registered for XML: the platform asks the evaluator of the language of the leaf at the caret, and a name of the markup is a
 * token of XML. Everything else — every other XML and HTML file — as the XML evaluator answers.
 */
class RazorHtmlTargetElementEvaluator : com.intellij.codeInsight.XmlTargetElementEvaluator() {
    override fun adjustReferenceOrReferencedElement(file: PsiFile, editor: com.intellij.openapi.editor.Editor, offset: Int, flags: Int, refElement: PsiElement?): PsiElement? =
        refElement ?: attributeAt(file, offset) ?: super.adjustReferenceOrReferencedElement(file, editor, offset, flags, null)

    private fun attributeAt(file: PsiFile, offset: Int): XmlAttribute? {
        if (RazorTagHelperEditor.razorFile(file) == null || DumbService.isDumb(file.project)) return null
        for (at in listOf(offset, offset - 1)) {
            val name = file.viewProvider.findElementAt(at, HTMLLanguage.INSTANCE)?.takeIf { it.node?.elementType == XmlTokenType.XML_NAME } ?: continue
            val attribute = name.parent as? XmlAttribute ?: return null
            return attribute.takeIf { RazorTagHelperEditor.markupBinding(name) != null }
        }
        return null
    }
}

/**
 * What the rename of a component or a parameter writes in Razor's markup: the tags and attributes naming it, the `.razor` file of a component
 * whose code-behind is renamed (with the files nested under it).
 */
class RazorRenameParticipant : CSharpRenameParticipant {
    override fun occurrences(declaration: PsiElement, oldName: String): List<CSharpRenameParticipant.Occurrence> {
        if (declaration !is CSharpBaseTypeDeclaration && declaration !is CSharpPropertyDeclaration) return emptyList()
        val scope = GlobalSearchScope.projectScope(declaration.project)
        return RazorTagHelperUsages.places(declaration, scope).filter { it.renamable }.map { CSharpRenameParticipant.Occurrence(it.file, it.range, it.spelling) } +
            RazorTagHelperUsages.twinUsages(declaration, scope).map { CSharpRenameParticipant.Occurrence(it.containingFile, it.textRange) }
    }


    override fun namedFiles(declaration: PsiElement, oldName: String): List<PsiFile> {
        val type = declaration as? CSharpBaseTypeDeclaration ?: return emptyList()
        // a code-behind `Counter.razor.cs` beside `Counter.razor`: the class is the component's
        val file = type.containingFile?.viewProvider?.virtualFile ?: return emptyList()
        val razorName = file.name.removeSuffix(".cs").takeIf { it.endsWith(".razor") && it.startsWith("$oldName.") } ?: return emptyList()
        val razor = file.parent?.findChild(razorName)?.let { PsiManager.getInstance(declaration.project).findFile(it) }?.let(RazorTagHelperEditor::razorFile) ?: return emptyList()
        return RazorTagHelperUsages.componentFiles(declaration.project, razor)
    }
}
