package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.XmlAttributeInsertHandler
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseNamespaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.razor.lang.codegen.mvc.ViewComponentMetadata
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDocumentContext
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions

/**
 * The components and tag helpers in the markup of a Razor file (RAZOR_PLAN.md, R5e): what the compiler binds there — the descriptors in scope
 * of the file ([TagHelperDocumentContext] of its C# document: its project's, filtered by `@using` / `@addTagHelper`) — offered by completion
 * (a tag of a component or a tag helper, the attributes of the element: parameters, `@bind-…`, `@onclick`, `asp-for`) and reached by
 * Go to Declaration (the tag → the component: its `.razor`, its class, its type in an assembly; an attribute → the parameter's property).
 */
object RazorTagHelperEditor {
    /** The Razor file of [file]'s view (the HTML file of the markup and the Razor file share it); null for another file. */
    fun razorFile(file: PsiFile?): RazorFile? = file?.viewProvider?.takeIf { it.baseLanguage == RazorLanguage }?.getPsi(RazorLanguage) as? RazorFile

    /** The descriptors in scope of [file]; null before the project's are discovered. */
    fun context(file: RazorFile): TagHelperDocumentContext? = RazorCSharpDocument.generate(file)?.tagHelperContext

    /** The descriptors that may apply to an element named [tagName] in [parentTag]: its tag (or `*`) in one of their rules. */
    fun candidates(context: TagHelperDocumentContext, tagName: String, parentTag: String?): List<TagHelperDescriptor> {
        val name = tagName.removePrefix(context.prefix.orEmpty())
        return context.tagHelpers.filter { descriptor ->
            descriptor.tagMatchingRules.any { rule ->
                (rule.tagName == TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME || rule.tagName.equals(name, ignoreCase = !rule.caseSensitive)) &&
                    (rule.parentTag == null || parentTag != null && rule.parentTag.equals(parentTag, ignoreCase = !rule.caseSensitive))
            }
        }
    }

    /** The descriptors an element named [tagName] is (its tag in their rules, not `*`): the component, the tag helper. */
    fun tagDescriptors(context: TagHelperDocumentContext, tagName: String): List<TagHelperDescriptor> {
        val name = tagName.removePrefix(context.prefix.orEmpty())
        return context.tagHelpers.filter { d -> d.tagMatchingRules.any { it.tagName != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME && it.tagName.equals(name, ignoreCase = !it.caseSensitive) } }
    }

    /** The bound attribute of [descriptors] an attribute named [name] sets: a parameter, `@bind-Value` (`:event` and the other parameters of a name stripped). */
    fun boundAttribute(descriptors: List<TagHelperDescriptor>, name: String): Pair<TagHelperDescriptor, BoundAttributeDescriptor>? {
        val bare = name.substringBefore(':')
        for (descriptor in descriptors) {
            for (attribute in descriptor.boundAttributes) {
                if (attribute.name.equals(bare, ignoreCase = !attribute.caseSensitive)) return descriptor to attribute
                val prefix = attribute.indexerNamePrefix
                if (prefix != null && prefix.isNotEmpty() && bare.startsWith(prefix, ignoreCase = !attribute.caseSensitive)) return descriptor to attribute
            }
        }
        return null
    }

    // ---- navigation

    /** The declaration of the component or tag helper [descriptor] describes: its `.razor` file, its class of the sources, its type in an assembly. */
    fun typeTarget(file: RazorFile, descriptor: TagHelperDescriptor): PsiElement? {
        val typeName = typeNameOf(descriptor) ?: return null
        sourceType(file, typeName)?.let { return it }
        return libraryType(file, typeName)?.let { (project, type) -> AssemblyNavigation.target(project, type) }
    }

    /** The property a bound attribute sets, in the class of [descriptor]. */
    fun propertyTarget(file: RazorFile, descriptor: TagHelperDescriptor, attribute: BoundAttributeDescriptor): PsiElement? {
        val typeName = typeNameOf(descriptor) ?: return null
        val property = attribute.propertyName
        val type = sourceType(file, typeName)
        if (type is RazorFile) {
            // a parameter of a component's `@code`: its name where it is declared in the file
            val text = type.text
            val at = Regex("""\b${Regex.escape(property)}\s*\{""").find(text)?.range?.first ?: return type
            return type.findElementAt(at) ?: type
        }
        if (type != null) {
            val declarations = sourceDeclarations(file, typeName)
            for (declaration in declarations) {
                (declaration as? CSharpTypeDeclaration)?.members?.firstOrNull { it is CSharpPropertyDeclaration && it.identifier?.text == property }?.let { return it }
            }
            return type
        }
        val (project, indexed) = libraryType(file, typeName) ?: return null
        val member = AssemblyIndexService.getInstance(project).symbols(projectOf(file) ?: return null).members(indexed)
            .map { it.member }.firstOrNull { it.name == property && it.kind == IndexedMemberKind.PROPERTY }
        return member?.let { AssemblyNavigation.target(project, it) } ?: AssemblyNavigation.target(project, indexed)
    }

    /** The CLR name of the type a descriptor is of: a view component's own class, a child content's component; type arguments dropped. */
    private fun typeNameOf(descriptor: TagHelperDescriptor): String? {
        val raw = when {
            descriptor.metadata is ViewComponentMetadata -> (descriptor.metadata as ViewComponentMetadata).originalTypeName
            descriptor.kind == TagHelperKind.ChildContent -> descriptor.typeName.substringBeforeLast('.')
            else -> descriptor.typeName
        } ?: return null
        return raw.substringBefore('<')
    }

    private fun projectOf(file: RazorFile): VirtualFile? = CompilationModel.getInstance(file.project).projectOf(file.originalFile.viewProvider.virtualFile)

    /** A type of the sources named [qualifiedName]: a component of a `.razor` file (the file), else the declaration of a C# class. */
    private fun sourceType(file: RazorFile, qualifiedName: String): PsiElement? {
        razorComponent(file, qualifiedName)?.let { return it }
        return sourceDeclarations(file, qualifiedName).firstOrNull { it.containingFile?.viewProvider?.virtualFile?.let(RazorTagHelpers::isRazorOutput) != true }
    }

    private fun sourceDeclarations(file: RazorFile, qualifiedName: String): List<PsiElement> {
        val context = RazorTagHelpers.declarationFile(file) ?: return emptyList()
        val syntax = CSharpSemanticSession(file.project).resolver(context).syntax
        for (arity in 0..8) syntax.typeInfo(qualifiedName, arity)?.let { info -> return info.targets() }
        return emptyList()
    }

    /** The `.razor` file whose component is [qualifiedName] (its declaration names it). */
    private fun razorComponent(file: RazorFile, qualifiedName: String): RazorFile? {
        val project = file.project
        val psi = PsiManager.getInstance(project)
        for (virtualFile in FileTypeIndex.getFiles(RazorFileType, GlobalSearchScope.projectScope(project))) {
            if (virtualFile.nameWithoutExtension != qualifiedName.substringAfterLast('.')) continue
            val razor = razorFile(psi.findFile(virtualFile)) ?: continue
            val declaration = RazorTagHelpers.declarationFile(razor) ?: continue
            val syntax = CSharpSemanticSession(project).resolver(declaration).syntax
            fun declares(members: List<CSharpMemberDeclaration>): Boolean = members.any { member ->
                when (member) {
                    is CSharpBaseNamespaceDeclaration -> declares(PsiTreeUtil.getChildrenOfTypeAsList(member, CSharpMemberDeclaration::class.java))
                    is CSharpBaseTypeDeclaration -> syntax.qualifiedName(member) == qualifiedName
                    else -> false
                }
            }
            if (declares(declaration.compilationUnit?.members.orEmpty())) return razor
        }
        return null
    }

    private fun libraryType(file: RazorFile, qualifiedName: String): Pair<com.intellij.openapi.project.Project, io.github.dotnetsupport.index.IndexedType>? {
        val projectFile = projectOf(file) ?: return null
        val set = AssemblyIndexService.getInstance(file.project).symbols(projectFile)
        for (arity in 0..8) {
            val metadataName = if (arity == 0) qualifiedName else "$qualifiedName`$arity"
            set.findType(metadataName)?.let { return file.project to it }
        }
        return null
    }
}

/** Completion of the tags of components and tag helpers and of the attributes of the elements they bind, in the markup of a Razor file. */
class RazorTagHelperCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.completionType != CompletionType.BASIC) return
        val position = parameters.position
        if (position.node?.elementType != XmlTokenType.XML_NAME) return
        val razor = RazorTagHelperEditor.razorFile(parameters.originalFile) ?: return
        val context = RazorTagHelperEditor.context(razor) ?: return
        when (val parent = position.parent) {
            is XmlTag -> if (parent.firstChild?.nextSibling == position || isTagName(parent, position)) tags(context, parent.parentTag?.name, result)
            is XmlAttribute -> attributes(context, parent.parent, result)
        }
    }

    private fun isTagName(tag: XmlTag, position: PsiElement): Boolean {
        var child = tag.firstChild
        while (child != null && child.node.elementType != XmlTokenType.XML_NAME) child = child.nextSibling
        return child == position
    }

    private fun tags(context: TagHelperDocumentContext, parentTag: String?, result: CompletionResultSet) {
        val seen = HashSet<String>()
        for (descriptor in context.tagHelpers) {
            for (rule in descriptor.tagMatchingRules) {
                val name = rule.tagName
                if (name == TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME) continue
                // a child content tag only inside its component
                if (rule.parentTag != null && !rule.parentTag.equals(parentTag, ignoreCase = true)) continue
                val tag = context.prefix.orEmpty() + name
                if (!seen.add(tag)) continue
                val component = descriptor.kind == TagHelperKind.Component || descriptor.kind == TagHelperKind.ChildContent
                val element = LookupElementBuilder.create(tag)
                    .withIcon(if (component) AllIcons.Nodes.Class else AllIcons.Nodes.Tag)
                    .withTypeText(if (descriptor.kind == TagHelperKind.ChildContent) "child content" else descriptor.typeNamespace ?: descriptor.assemblyName, true)
                // the short names first, the fully qualified ones (`MudBlazor.MudButton`) after
                result.addElement(PrioritizedLookupElement.withPriority(element, if (descriptor.isFullyQualifiedNameMatch) 0.0 else 10.0))
            }
        }
    }

    private fun attributes(context: TagHelperDocumentContext, tag: XmlTag?, result: CompletionResultSet) {
        tag ?: return
        val present = tag.attributes.mapNotNull { it.name.takeIf { n -> !n.contains(com.intellij.codeInsight.completion.CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED) } }.toSet()
        val seen = HashSet<String>()
        for (descriptor in RazorTagHelperEditor.candidates(context, tag.name, tag.parentTag?.name)) {
            for (attribute in descriptor.boundAttributes) {
                val name = attribute.name
                if (name.isEmpty() || name.endsWith("...") || name in present || !seen.add(name)) continue
                val type = BoundAttributeSimpleNames[attribute.typeName] ?: attribute.typeName.replace(Qualifier, "")
                val element = LookupElementBuilder.create(name)
                    .withIcon(if (attribute.isDirectiveAttribute) AllIcons.Nodes.Annotationtype else AllIcons.Nodes.Property)
                    .withTypeText(type, true)
                    .withInsertHandler(XmlAttributeInsertHandler.INSTANCE)
                // the element's own parameters first, the directive attributes of every element after
                val own = descriptor.tagMatchingRules.any { it.tagName != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME }
                result.addElement(PrioritizedLookupElement.withPriority(element, if (own) 10.0 else 0.0))
            }
        }
    }

    private companion object {
        /** The namespaces of every name in a type (`EventCallback<Microsoft.AspNetCore.Components.Web.MouseEventArgs>` → `EventCallback<MouseEventArgs>`). */
        val Qualifier = Regex("""(?:global::)?\b(?:[A-Za-z_]\w*\.)+""")
        val BoundAttributeSimpleNames = mapOf(
            "System.String" to "string", "System.Boolean" to "bool", "System.Int32" to "int", "System.Object" to "object", "System.Double" to "double",
            "System.Int64" to "long", "System.Decimal" to "decimal", "System.Single" to "float",
        )
    }
}

/** Go to Declaration from the markup of a Razor file: a tag of a component or tag helper → its declaration, its attribute → the property it sets. */
class RazorTagHelperGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val element = sourceElement ?: return null
        if (element.node?.elementType != XmlTokenType.XML_NAME) return null
        val razor = RazorTagHelperEditor.razorFile(element.containingFile) ?: return null
        val context = RazorTagHelperEditor.context(razor) ?: return null
        return when (val parent = element.parent) {
            is XmlTag -> {
                val descriptors = RazorTagHelperEditor.tagDescriptors(context, parent.name)
                descriptors.firstNotNullOfOrNull { RazorTagHelperEditor.typeTarget(razor, it) }?.let { arrayOf(it) }
            }
            is XmlAttribute -> {
                val tag = parent.parent ?: return null
                val (descriptor, attribute) = RazorTagHelperEditor.boundAttribute(RazorTagHelperEditor.candidates(context, tag.name, tag.parentTag?.name), parent.name) ?: return null
                // a directive attribute of every element (`@onclick`) has no property to go to
                if (descriptor.tagMatchingRules.all { it.tagName == TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME }) return null
                RazorTagHelperEditor.propertyTarget(razor, descriptor, attribute)?.let { arrayOf(it) }
            }
            else -> null
        }
    }
}
