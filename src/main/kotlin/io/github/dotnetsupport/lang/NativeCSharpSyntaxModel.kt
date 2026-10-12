package io.github.dotnetsupport.lang

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testFramework.LightVirtualFile
import io.github.dotnetsupport.csharp.lang.lexer.CSharpTokenTypes as NativeTokenTypes
import io.github.dotnetsupport.csharp.lang.psi.CSharpAliasQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseNamespaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBasePropertyDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpClassDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpConstructorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpConversionOperatorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpDeclarationNames
import io.github.dotnetsupport.csharp.lang.psi.CSharpDelegateDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpDestructorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpEnumDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpEnumMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpEventDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpEventFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpExtensionBlockDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpIndexerDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpInterfaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMemberDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpName
import io.github.dotnetsupport.csharp.lang.psi.CSharpNamespaceDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpOperatorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpPropertyDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpRecordDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.CSharpStructDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import java.util.IdentityHashMap

/**
 * [CSharpSyntaxModel] over csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 7): a `BaseNamespaceDeclaration`, type, delegate or member
 * node per [CSharpDeclarationInfo], one per `VariableDeclarator` of a field with several (`int a, b;` is two fields), the `Block` or the braces of a
 * type or an accessor list as [CSharpDeclarationInfo.body], the extern aliases and using directives of the `CompilationUnit` as
 * [CSharpFileStructure.usings]. Member bodies are not looked into (local functions are no declarations), nor are top-level statements;
 * members of an `extension` block count as members of the type around it.
 *
 * A file can keep the other tree for a while after the switch of [CSharpSyntaxTrees]: questions about a heuristic file or its elements
 * go to [HeuristicCSharpSyntaxModel], so either model answers right on either tree. [declarations] of a text does not depend on the
 * switch at all: the text is parsed into a native file of its own.
 */
object NativeCSharpSyntaxModel : CSharpSyntaxModel {
    override fun declarations(text: CharSequence): CSharpFileStructure = withRead { Collector().collect(parse(text)).structure }

    override fun declarations(file: PsiFile): CSharpFileStructure = native(file)?.structure ?: HeuristicCSharpSyntaxModel.declarations(file)

    override fun declarationOf(element: PsiElement): CSharpDeclarationInfo? = when (element) {
        is CSharpMemberDeclaration, is CSharpVariableDeclarator -> native(element.containingFile)?.infos?.get(element.node)
        is CSharpElement -> null
        else -> HeuristicCSharpSyntaxModel.declarationOf(element)
    }

    override fun childDeclarations(parent: PsiElement): List<NavigatablePsiElement> {
        val members = when (parent) {
            is CSharpFile -> parent.compilationUnit?.members ?: return HeuristicCSharpSyntaxModel.childDeclarations(parent)
            is CSharpBaseNamespaceDeclaration -> parent.members
            is CSharpTypeDeclaration -> parent.members
            is CSharpEnumDeclaration -> parent.members
            is CSharpElement -> return emptyList()
            else -> return HeuristicCSharpSyntaxModel.childDeclarations(parent)
        }
        val infos = native(parent.containingFile)?.infos ?: return emptyList()
        return members.flatMap(::declarationElements).filter { it.node in infos }.map { it as NavigatablePsiElement }
    }

    override fun declarationElementAt(file: PsiFile, offset: Int): NavigatablePsiElement? {
        val native = native(file) ?: return HeuristicCSharpSyntaxModel.declarationElementAt(file, offset)
        return native.structure.pathTo(offset).lastOrNull()?.let { native.elements[it] as? NavigatablePsiElement }
    }

    override fun attributedMethods(text: CharSequence, attributes: Set<String>): CSharpAttributedMethods = withRead {
        val methods = ArrayList<CSharpAttributedMethod>()
        val types = ArrayList<Pair<String, TextRange>>()
        fun visit(members: List<CSharpMemberDeclaration>, namespaces: List<String>, typeNames: List<String>) {
            for (member in members) when (member) {
                is CSharpBaseNamespaceDeclaration -> visit(member.members, namespaces + CSharpDeclarationNames.name(member).orEmpty(), typeNames)
                is CSharpClassDeclaration, is CSharpStructDeclaration, is CSharpRecordDeclaration -> {
                    val type = member as CSharpTypeDeclaration
                    val identifier = type.identifier ?: continue
                    val nested = typeNames + identifier.text
                    val typeName = (namespaces.filter { it.isNotEmpty() } + nested.joinToString("+")).joinToString(".")
                    types += typeName to identifier.textRange
                    for (method in type.members.filterIsInstance<CSharpMethodDeclaration>()) {
                        val name = method.identifier ?: continue
                        if (method.attributeLists.any { list -> list.attributes.any { simpleName(it.nameElement) in attributes } }) methods += CSharpAttributedMethod(typeName, name.text, name.textRange)
                    }
                    visit(type.members, namespaces, nested)
                }
                is CSharpTypeDeclaration -> if (member !is CSharpInterfaceDeclaration) visit(member.members, namespaces, typeNames)
            }
        }
        visit(parse(text).compilationUnit?.members.orEmpty(), emptyList(), emptyList())
        CSharpAttributedMethods(methods, types)
    }

    /** The declaration model of a file of the native tree, once per change of it; null for a file of the heuristic tree or of no C#. */
    private fun native(file: PsiFile?): NativeStructure? {
        if (file !is CSharpFile || file.compilationUnit == null) return null
        return CachedValuesManager.getCachedValue(file, KEY) { CachedValueProvider.Result.create(Collector().collect(file), file) }
    }

    /** [text] as a native C# file of its own (not of a project, not of the switch): `#if` with the IDE's default symbols. */
    internal fun parse(text: CharSequence): CSharpFile {
        val manager = PsiManager.getInstance(ProjectManager.getInstance().defaultProject)
        val provider = SingleRootFileViewProvider(manager, LightVirtualFile("syntax.cs", CSharpFileType, text), false)
        return CSharpFile(provider).also { provider.forceCachedPsi(it) }
    }

    private fun <T> withRead(compute: () -> T): T =
        if (ApplicationManager.getApplication().isReadAccessAllowed) compute() else ReadAction.compute<T, RuntimeException>(compute)

    /** `Fact` of `[Fact]`, `[Xunit.Fact]`, `[global::Xunit.Fact]`. */
    private fun simpleName(name: CSharpName?): String? = when (name) {
        is CSharpSimpleName -> name.identifier?.text
        is CSharpQualifiedName -> simpleName(name.right)
        is CSharpAliasQualifiedName -> simpleName(name.nameElement)
        else -> null
    }

    /** The elements [member] stands for as declarations: the declarators of a field with several, the members of an `extension` block, else itself. */
    private fun declarationElements(member: CSharpMemberDeclaration): List<CSharpElement> = when (member) {
        is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().takeIf { it.size > 1 } ?: listOf(member)
        is CSharpExtensionBlockDeclaration -> member.members.flatMap(::declarationElements)
        else -> listOf(member)
    }

    private val KEY = Key.create<CachedValue<NativeStructure>>("dotnet.csharp.nativeSyntaxModel")

    private class NativeStructure(val structure: CSharpFileStructure, val infos: Map<ASTNode, CSharpDeclarationInfo>, val elements: Map<CSharpDeclarationInfo, CSharpElement>)

    private class Collector {
        private val infos = HashMap<ASTNode, CSharpDeclarationInfo>()
        private val elements = IdentityHashMap<CSharpDeclarationInfo, CSharpElement>()

        fun collect(file: CSharpFile): NativeStructure {
            val unit = file.compilationUnit
            val directives = unit?.let { it.externs + it.usings }.orEmpty()
            val usings = if (directives.isEmpty()) null else TextRange(range(directives.first()).startOffset, range(directives.last()).endOffset)
            return NativeStructure(CSharpFileStructure(members(unit?.members.orEmpty(), inType = false), usings), infos, elements)
        }

        private fun members(members: List<CSharpMemberDeclaration>, inType: Boolean): List<CSharpDeclarationInfo> = members.flatMap { member(it, inType) }

        private fun member(member: CSharpMemberDeclaration, inType: Boolean): List<CSharpDeclarationInfo> {
            val modifiers = member.modifiers.mapTo(LinkedHashSet()) { it.text }
            return when (member) {
                is CSharpBaseNamespaceDeclaration -> {
                    val body = (member as? CSharpNamespaceDeclaration)?.let { braces(it.openBraceToken, it.closeBraceToken, member) }
                    listOfNotNull(info(member, DeclarationKind.NAMESPACE, body, null, null, emptySet()) { members(member.members, inType = false) })
                }
                is CSharpExtensionBlockDeclaration -> members(member.members, inType = true)
                is CSharpTypeDeclaration -> {
                    val kind = when (member) {
                        is CSharpInterfaceDeclaration -> DeclarationKind.INTERFACE
                        is CSharpStructDeclaration -> DeclarationKind.STRUCT
                        is CSharpRecordDeclaration -> DeclarationKind.RECORD
                        else -> DeclarationKind.CLASS
                    }
                    listOfNotNull(info(member, kind, braces(member.openBraceToken, member.closeBraceToken, member), member.parameterList?.let { collapse(it.text) }, null, modifiers) {
                        members(member.members, inType = true)
                    })
                }
                is CSharpEnumDeclaration -> listOfNotNull(info(member, DeclarationKind.ENUM, braces(member.openBraceToken, member.closeBraceToken, member), null, null, modifiers) {
                    member.members.mapNotNull { info(it, DeclarationKind.ENUM_MEMBER, null, null, null, it.modifiers.mapTo(LinkedHashSet()) { m -> m.text }) }
                })
                is CSharpDelegateDeclaration -> listOfNotNull(info(member, DeclarationKind.DELEGATE, null, member.parameterList?.let { collapse(it.text) }, type(member.returnType), modifiers))
                // members of a type only: at the level of a namespace they are errors, at the level of a file top-level statements
                else -> if (inType) memberOfType(member, modifiers) else emptyList()
            }
        }

        private fun memberOfType(member: CSharpMemberDeclaration, modifiers: Set<String>): List<CSharpDeclarationInfo> = when (member) {
            is CSharpMethodDeclaration -> listOfNotNull(info(member, DeclarationKind.METHOD, member.body?.textRange, parameters(member.parameterList), type(member.returnType), modifiers))
            is CSharpConstructorDeclaration -> listOfNotNull(info(member, DeclarationKind.CONSTRUCTOR, member.body?.textRange, parameters(member.parameterList), null, modifiers))
            is CSharpDestructorDeclaration -> listOfNotNull(info(member, DeclarationKind.CONSTRUCTOR, member.body?.textRange, parameters(member.parameterList), null, modifiers))
            is CSharpOperatorDeclaration -> listOfNotNull(info(member, DeclarationKind.OPERATOR, member.body?.textRange, parameters(member.parameterList), type(member.returnType), modifiers))
            // as the heuristics have it: `operator int` names a conversion, it has no type of its own
            is CSharpConversionOperatorDeclaration -> listOfNotNull(info(member, DeclarationKind.OPERATOR, member.body?.textRange, parameters(member.parameterList), null, modifiers))
            is CSharpPropertyDeclaration -> listOfNotNull(info(member, DeclarationKind.PROPERTY, member.accessorList?.textRange, null, type(member.type), modifiers))
            is CSharpEventDeclaration -> listOfNotNull(info(member, DeclarationKind.EVENT, member.accessorList?.textRange, null, type(member.type), modifiers))
            is CSharpIndexerDeclaration -> listOfNotNull(info(member, DeclarationKind.INDEXER, member.accessorList?.textRange, member.parameterList?.let { collapse(it.text) }, type(member.type), modifiers))
            is CSharpBasePropertyDeclaration -> emptyList()
            is CSharpBaseFieldDeclaration -> fields(member, modifiers)
            else -> emptyList() // incomplete members, global statements
        }

        /**
         * One field per declarator. A field with one declarator is the element of its declaration (the breadcrumb of the field is there with
         * the caret on its type as well); of `int a, b;` the declarators are, their ranges side by side: the first starts with the field,
         * the last ends at its `;`.
         */
        private fun fields(field: CSharpBaseFieldDeclaration, modifiers: Set<String>): List<CSharpDeclarationInfo> {
            val declaration = field.declaration ?: return emptyList()
            val kind = if (field is CSharpEventFieldDeclaration) DeclarationKind.EVENT else DeclarationKind.FIELD
            val type = type(declaration.type)
            if (declaration.variables.size <= 1) return listOfNotNull(info(field, kind, null, null, type, modifiers))
            val whole = range(field)
            val variables = declaration.variables.filter { it.identifier != null }
            return variables.mapIndexedNotNull { i, variable ->
                val own = range(variable)
                val range = TextRange(if (i == 0) whole.startOffset else own.startOffset, if (i == variables.lastIndex) whole.endOffset else own.endOffset)
                info(variable, kind, null, null, type, modifiers, range)
            }
        }

        private fun info(
            element: CSharpElement, kind: DeclarationKind, body: TextRange?, parameters: String?, type: String?, modifiers: Set<String>,
            range: TextRange = range(element), children: () -> List<CSharpDeclarationInfo> = { emptyList() },
        ): CSharpDeclarationInfo? {
            val name = CSharpDeclarationNames.name(element) ?: return null
            val nameRange = CSharpDeclarationNames.nameElement(element)?.textRange ?: return null
            val info = CSharpDeclarationInfo(kind, name, nameRange, range, body, parameters, type, modifiers, children())
            infos[element.node] = info
            elements[info] = element
            return info
        }

        /** `{` to `}` of a type or a namespace; to the end of the declaration while the `}` is not typed yet. */
        private fun braces(open: PsiElement?, close: PsiElement?, owner: CSharpElement): TextRange? =
            open?.let { TextRange(it.textRange.startOffset, close?.textRange?.endOffset ?: range(owner).endOffset) }

        private fun parameters(list: PsiElement?): String? = list?.let { collapse(it.text) }

        private fun type(type: PsiElement?): String? = type?.let { collapse(it.text) }.takeIf { !it.isNullOrEmpty() }
    }

    /** From the first token of [element] (its attributes included, a doc comment before it not) to its last. */
    private fun range(element: PsiElement): TextRange {
        val first = firstToken(element.node) ?: return element.textRange
        val last = lastToken(element.node) ?: return element.textRange
        return TextRange(first.startOffset, last.startOffset + last.textLength)
    }

    private fun isToken(node: ASTNode): Boolean =
        node.textLength > 0 && node.elementType !in NativeTokenTypes.WHITESPACES && node.elementType !in NativeTokenTypes.COMMENTS

    private fun firstToken(node: ASTNode): ASTNode? {
        if (!isToken(node)) return null
        var child = node.firstChildNode ?: return node
        while (true) {
            firstToken(child)?.let { return it }
            child = child.treeNext ?: return null
        }
    }

    private fun lastToken(node: ASTNode): ASTNode? {
        if (!isToken(node)) return null
        var child = node.lastChildNode ?: return node
        while (true) {
            lastToken(child)?.let { return it }
            child = child.treePrev ?: return null
        }
    }

    private val WHITESPACE = Regex("""\s+""")

    private fun collapse(text: String): String = text.replace(WHITESPACE, " ")
}
