package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.github.dotnetsupport.index.AssemblyIndexService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.TreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import java.util.concurrent.atomic.AtomicReference

/**
 * `SEMANTIC_COLORS` on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, task A4): the palette of [CSharpColors] by syntax and the stubs of
 * the solution, without the server and without the AST of any other file. Declarations get their color from their own syntax (the kind of
 * a type, `static`, `const`, `this` on the first parameter); names from the one resolver of the native tree, [NativeCSharpResolver]: locals,
 * parameters, local functions, type parameters and labels and their uses from its scopes; a simple name that no scope declares from the
 * members of the enclosing types (their partial parts and base types of the solution included, from the stubs), of the types of
 * `using static`, and then from the types of the file and of the solution (stub index of type names, the kind from the stub; leniently, a
 * type of a namespace the file does not import still gets its color); `this.X`, `Type.X` and `new T { X = ... }` from the members of that type. What none of
 * that resolves keeps no color, except a name in a place where only a type can stand (the coarse [CSharpColors.TYPE]) and the name of an
 * attribute. Since task C1 a name left so goes to the name resolution of layer 11a ([io.github.dotnetsupport.lang.semantic.CSharpNameResolver]):
 * types and members of the referenced assemblies, members of expressions whose type is known, extension methods.
 */
object NativeCSharpSemanticColors {
    /** The native colors answer for [file]: the switch, and a file of the native tree. Settings and a child lookup: cheap. */
    fun serves(file: CSharpFile): Boolean = file.compilationUnit != null && CSharpFeatures.native(CSharpFeature.SEMANTIC_COLORS, file.project)

    /**
     * The identifiers of [file] with their keys, in the order of the text. Cached on the file until a change of PSI, of the indexes of
     * assemblies, of generated files or of dumb mode: the colors painted as the editor opens ([CSharpOpeningColors]) and the pass of the
     * daemon right after it, and the passes the daemon repeats without a change, compute them once.
     */
    fun colors(file: CSharpFile): List<Pair<TextRange, TextAttributesKey>> = CachedValuesManager.getCachedValue(file) {
        val project = file.project
        CachedValueProvider.Result.create(Colorer(file).run(), PsiModificationTracker.MODIFICATION_COUNT, AssemblyIndexService.getInstance(project).modificationTracker,
            DumbService.getInstance(project).modificationTracker, io.github.dotnetsupport.codeanalysis.CodeAnalysisService.getInstance(project).modificationTracker)
    }
}

/**
 * Registered for C#; acts on the file element only (one pass), and only while [NativeCSharpSemanticColors.serves], which is false while the
 * IDE indexes (it reads stubs). DumbAware all the same: the platform runs an annotator that is not on a file it does not index (a folder
 * opened without a module, a project outside the opened folder) never, whatever the dumb mode (`DumbService.isUsableInCurrentContext`).
 * Also grays the text of inactive `#if` branches ([NativeCSharpInactiveCode]), whoever colors the identifiers.
 */
class NativeCSharpSemanticColorsAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is CSharpFile || element.compilationUnit == null) return
        // the lexer of the editor knows no `#if` symbols: the tree, parsed with the project's, tells the inactive text (the server does not)
        for (range in NativeCSharpInactiveCode.ranges(element)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(CSharpColors.INACTIVE_BRANCH).create()
        }
        if (!NativeCSharpSemanticColors.serves(element)) return
        for ((range, key) in NativeCSharpSemanticColors.colors(element)) holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(range).textAttributes(key).create()
    }
}

/** The text of the inactive `#if` branches of a file of the native tree: its `DisabledTextTrivia`, whitespace around it left out. No index. */
object NativeCSharpInactiveCode {
    fun ranges(file: CSharpFile): List<TextRange> {
        val out = ArrayList<TextRange>()
        val text = file.viewProvider.contents
        var leaf = TreeUtil.findFirstLeaf(file.node)
        while (leaf != null) {
            if (leaf.elementType === SyntaxKind.DisabledTextTrivia) {
                var start = leaf.startOffset
                var end = start + leaf.textLength
                while (start < end && text[start].isWhitespace()) start++
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end > start) out += TextRange(start, end)
            }
            leaf = TreeUtil.nextLeaf(leaf)
        }
        return out
    }
}

/**
 * The colors on screen follow «Colors of identifiers» of the Language Server page at once: when its answer changes on Apply, highlighting
 * restarts in the open projects (the annotators and the semantic tokens of the server read the switch per pass).
 */
class NativeCSharpSemanticColorsSwitch : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) {
        val native = CSharpFeatures.native(CSharpFeature.SEMANTIC_COLORS)
        if (last.getAndSet(native) == native) return
        ApplicationManager.getApplication().invokeLater({
            for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        }, ModalityState.nonModal())
    }

    private companion object {
        val last = AtomicReference<Boolean?>(null)
    }
}

private class Colorer(private val file: CSharpFile) {
    private val resolver = NativeCSharpResolver(file)
    private val scopes = resolver.scopes
    private val out = ArrayList<Pair<TextRange, TextAttributesKey>>()

    fun run(): List<Pair<TextRange, TextAttributesKey>> {
        for (symbol in scopes.symbols) colorSymbol(symbol)
        for (declaration in scopes.declarations) colorDeclaration(declaration)
        for (name in scopes.names) {
            val leaf = name.identifier ?: continue
            if (scopes.symbolAt(leaf) != null) continue
            val key = keyOfName(name, leaf.text)
            // the coarse TYPE of a type position, or nothing: what layer 11a binds the name to (types and members of the referenced assemblies)
            (if (key == null || key == CSharpColors.TYPE) bound(name) ?: key else key)?.let { add(leaf, it) }
        }
        return out.sortedBy { it.first.startOffset }.distinctBy { it.first }
    }

    // other files through stubs only: their AST is not loaded for a pass of colors
    private val names by lazy { CSharpSemanticSession(file.project, loadsOtherFiles = false).resolver(file) }

    private fun bound(name: CSharpSimpleName): TextAttributesKey? =
        if (name.identifier?.text in NativeCSharpScopes.CONTEXTUAL) null else names.resolveName(name)?.referenceKey

    private fun add(leaf: PsiElement, key: TextAttributesKey) {
        out += leaf.textRange to key
    }

    // ---- symbols of the scopes

    private fun colorSymbol(symbol: LocalSymbol) {
        val key = when (symbol.kind) {
            LocalSymbolKind.LOCAL -> when {
                isConstant(symbol.declaration) -> CSharpColors.CONSTANT
                symbol.isWritten -> CSharpColors.MUTABLE_LOCAL_VARIABLE
                else -> CSharpColors.LOCAL_VARIABLE
            }
            LocalSymbolKind.PARAMETER -> CSharpColors.PARAMETER
            LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> CSharpColors.PRIMARY_CONSTRUCTOR_PARAMETER
            LocalSymbolKind.LOCAL_FUNCTION -> CSharpColors.LOCAL_FUNCTION
            LocalSymbolKind.TYPE_PARAMETER -> CSharpColors.TYPE_PARAMETER
            LocalSymbolKind.LABEL -> CSharpColors.LABEL
        }
        add(symbol.declaration, key)
        for (reference in symbol.references) {
            // a member of the type hides a parameter of its primary constructor
            val member = if (symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER) resolver.enclosingMember(reference, symbol.name) else null
            add(reference, member?.referenceKey ?: key)
        }
    }

    private fun isConstant(identifier: PsiElement): Boolean {
        val statement = identifier.parent?.parent?.parent as? CSharpLocalDeclarationStatement ?: return false
        return statement.modifiers.any { it.text == "const" }
    }

    // ---- declarations of the file

    private fun colorDeclaration(declaration: PsiElement) {
        val identifier = CSharpDeclarationNames.nameElement(declaration)?.takeIf { it.node.elementType == SyntaxKind.IdentifierToken } ?: return
        val modifiers by lazy { (declaration as? CSharpMemberDeclaration)?.modifiers.orEmpty().map { it.text } }
        val key = when (declaration) {
            is CSharpBaseNamespaceDeclaration -> null // its names are colored as names
            is CSharpBaseTypeDeclaration, is CSharpDelegateDeclaration -> resolver.declaredType(declaration)?.kind?.key
            is CSharpConstructorDeclaration, is CSharpDestructorDeclaration -> resolver.enclosingTypes(declaration).firstOrNull()?.kind?.key
            is CSharpMethodDeclaration -> Member.method(modifiers, TypePart.isExtension(declaration)).declarationKey
            is CSharpPropertyDeclaration -> Member.property(modifiers).declarationKey
            is CSharpEventDeclaration -> CSharpColors.EVENT
            is CSharpEnumMemberDeclaration -> CSharpColors.CONSTANT
            is CSharpVariableDeclarator -> (declaration.parent?.parent as? CSharpBaseFieldDeclaration)?.let { field ->
                Member.field(field.modifiers.map { it.text }, field is CSharpEventFieldDeclaration).declarationKey
            }
            else -> null
        }
        key?.let { add(identifier, it) }
    }

    // ---- names no scope declares

    private fun keyOfName(name: CSharpSimpleName, text: String): TextAttributesKey? {
        var top: PsiElement = name
        while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
        val rightmost = NativeCSharpResolver.isRightmost(name, top)
        val arity = NativeCSharpResolver.arity(name)
        when (val holder = top.parent) {
            is CSharpBaseNamespaceDeclaration -> if (top == holder.nameElement) return CSharpColors.NAMESPACE
            is CSharpUsingDirective -> if (top == holder.namespaceOrType) return when {
                holder.alias == null && holder.staticKeyword == null -> CSharpColors.NAMESPACE
                !rightmost -> null
                else -> resolver.resolveType(text, arity)?.kind?.key ?: if (holder.staticKeyword != null) CSharpColors.TYPE else null
            }
            is CSharpAttribute -> if (top == holder.nameElement) return if (rightmost) CSharpColors.ATTRIBUTE else null
        }
        val parent = name.parent
        return when {
            parent is CSharpQualifiedName && name == parent.right -> {
                val qualifier = resolver.qualifierType(parent.left)
                qualifier?.let { resolver.membersOf(it)[text]?.referenceKey }
                    ?: if (NativeCSharpTypePositions.isType(name)) resolver.resolveType(text, arity)?.kind?.key ?: CSharpColors.TYPE else null
            }
            parent is CSharpMemberAccessExpression && name == parent.nameElement -> resolver.qualifierType(parent.expression)?.let { resolver.membersOf(it)[text]?.referenceKey }
            parent is CSharpAssignmentExpression && name == parent.left && NativeCSharpScopes.isObjectInitializer(parent.parent) -> {
                val creation = parent.parent?.parent as? CSharpObjectCreationExpression
                creation?.type?.let(resolver::typeOf)?.let { resolver.membersOf(it)[text]?.referenceKey }
            }
            !NativeCSharpScopes.isFreeName(name) || text in NativeCSharpScopes.CONTEXTUAL -> null
            else -> resolver.enclosingMember(name, text)?.referenceKey
                ?: resolver.staticImports.firstNotNullOfOrNull { resolver.membersOf(it)[text] }?.referenceKey
                ?: resolver.resolveType(text, arity)?.kind?.key
                ?: if (NativeCSharpTypePositions.isType(name)) CSharpColors.TYPE else null
        }
    }
}
