package io.github.dotnetsupport.lang

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.CSharpAliasQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpConstructorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpObjectCreationExpression
import io.github.dotnetsupport.csharp.lang.psi.CSharpQualifiedName
import io.github.dotnetsupport.csharp.lang.psi.CSharpSimpleName
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.AssemblyNavigation
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * Go to Declaration, Ctrl + hover and the usages of a symbol in its file on csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9, feature
 * `NAVIGATION`, the syntactic part). What a name stands for is the answer of the one resolver of the native tree, [NativeCSharpResolver]
 * (the same as for colors and rename): locals, parameters, local functions, labels, range variables, type parameters by the scopes of C#;
 * members of the enclosing types, their partial parts and base classes of the solution; `this.X`, `base.X`, `Type.X`, members set by an
 * object initializer; types of the solution by simple name and arity among the namespaces the usage sees; an attribute by its name with
 * `Attribute`. What it cannot tell (null) goes on to whoever comes next: the language server when it is ready. The switch NATIVE therefore
 * never loses navigation the server gave.
 */
object NativeCSharpNavigation {
    /** The file is the native tree's and the switch gives NAVIGATION to it. Settings and dumb mode only (the stub index needs smart mode). */
    fun serves(file: PsiFile?): Boolean = file is CSharpFile && file.compilationUnit != null && CSharpFeatures.native(CSharpFeature.NAVIGATION, file)

    /**
     * Where Go to Declaration from [leaf] goes; null when the tree cannot tell, [leaf] is no name, or it names a declaration itself. What the
     * scopes do not resolve goes to the name resolution of layer 11a (task C1: members of an expression of known type, inherited ones, extension
     * methods) — for declarations in the solution only: a type or member of an assembly has no place to go to without a decompiler.
     */
    fun targets(leaf: PsiElement): List<PsiElement>? {
        val file = leaf.containingFile as? CSharpFile ?: return null
        NativeCSharpResolver(file).declarations(leaf)?.let { return constructorsOfCreated(leaf, overloadOf(file, leaf, it)) }
        if (DumbService.isDumb(file.project)) return null
        val symbols = CSharpSemanticSession(file.project).resolver(file).resolve(leaf)?.symbols ?: return null
        return symbols.flatMap { it.declarations }.distinct().ifEmpty { null }?.let { constructorsOfCreated(leaf, it) }
    }

    /**
     * The scopes find every method of the name; overload resolution (task D1) picks the called one, as the compiler does. Kept as found when
     * it cannot tell (several left, or what it picks is not among them).
     */
    private fun overloadOf(file: CSharpFile, leaf: PsiElement, found: List<PsiElement>): List<PsiElement> {
        if (found.size < 2 || found.any { it !is CSharpMethodDeclaration } || DumbService.isDumb(file.project)) return found
        val picked = CSharpSemanticSession(file.project).resolver(file).resolve(leaf)?.symbols?.flatMap { it.declarations }?.distinct() ?: return found
        return picked.takeIf { it.isNotEmpty() && it.size < found.size && found.containsAll(it) } ?: found
    }

    /**
     * The type named by `new T(…)` stands for its constructor, as with the server and in Rider: the declared instance constructors of the
     * type (several are overloads, see [CSharpGotoDeclarationHandler]); the type itself when it declares none or has a primary constructor.
     */
    private fun constructorsOfCreated(leaf: PsiElement, targets: List<PsiElement>): List<PsiElement> {
        var type: PsiElement = leaf.parent as? CSharpSimpleName ?: return targets
        while ((type.parent as? CSharpQualifiedName)?.right == type || (type.parent as? CSharpAliasQualifiedName)?.nameElement == type) type = type.parent
        if ((type.parent as? CSharpObjectCreationExpression)?.type != type) return targets
        val declarations = targets.filterIsInstance<CSharpTypeDeclaration>()
        if (declarations.size != targets.size || declarations.any { it.parameterList != null }) return targets
        val constructors = declarations.flatMap { declaration ->
            declaration.members.filterIsInstance<CSharpConstructorDeclaration>().filter { constructor -> constructor.modifiers.none { it.text == "static" } }
        }
        return constructors.ifEmpty { targets }
    }

    /**
     * The read and written ranges of the local symbol (local, parameter, local function, label, range variable, type parameter) that [leaf]
     * declares or stands for, in its file; null for members, types and what is not resolved: their uses are also in other files and after
     * a dot, which syntax does not see.
     */
    fun localUsages(leaf: PsiElement): NativeCSharpResolver.Usages? {
        val file = leaf.containingFile as? CSharpFile ?: return null
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        val resolver = NativeCSharpResolver(file)
        val symbol = resolver.symbolAt(leaf)?.takeUnless { it.isMember } ?: return null
        return resolver.usages(symbol)
    }
}

/**
 * Go to Declaration (Ctrl+B, Ctrl + click) and Ctrl + hover on the native tree when NAVIGATION is NATIVE ([NativeCSharpNavigation]).
 * First among the handlers: the platform asks them before the references, so a name the tree resolves never reaches the language
 * server; one it cannot resolve (null) goes on to the server's references (`textDocument/definition`) as with ROSLYN.
 */
class CSharpGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor?): Array<PsiElement>? {
        val leaf = sourceElement ?: return null
        if (!NativeCSharpNavigation.serves(leaf.containingFile)) return null
        // a type or member of an assembly: its metadata view (B4) when the server is not ready to give its decompiled source
        val targets = NativeCSharpNavigation.targets(leaf) ?: AssemblyNavigation.declarationTargets(leaf) ?: return null
        // overloads are not resolved by syntax (step 11d): a ready server picks the one of the call, the tree offers them all only without it
        if (targets.size > 1 && RoslynServerStatus.isReady(leaf.project, leaf.containingFile?.virtualFile)) return null
        return targets.toTypedArray()
    }
}
