package io.github.dotnetsupport.lang

import com.intellij.ide.IconProvider
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.ItemPresentationProvider
import com.intellij.navigation.LocationPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.impl.CSharpStubElementImpl
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub
import javax.swing.Icon

/**
 * Rows of Go to Class / Symbol, Go to Implementation and the like for a declaration of csharp-psi's tree, as the heuristic declaration
 * nodes have them: `Area(double scale)` (name and parameters as written), in gray the namespace and types around a type or the types
 * around a member. The core gives its elements no texts or icons (CSHARP_PSI_MIGRATION.md, principle 7): they come from here. An element
 * that has a stub (Go to Class over the stub index, step 8) is shown from the stubs, without loading the AST of its file
 * ([NativeCSharpStubDeclarations]); the same row as from the declaration model (`CSharpStubNavigationTest`).
 */
class NativeCSharpPresentationProvider : ItemPresentationProvider<NavigationItem> {
    override fun getPresentation(navigationItem: NavigationItem): ItemPresentation? {
        val item = navigationItem as? CSharpElement ?: return null
        NativeCSharpStubDeclarations.stub(item)?.let { stub ->
            val kind = NativeCSharpStubDeclarations.kind(stub) ?: return null
            val containers = NativeCSharpStubDeclarations.containers(stub)
            return presentation(item, stub.name.orEmpty() + stub.parameters.orEmpty(), containers.filter { kind.isType || it.second != DeclarationKind.NAMESPACE }.joinToString(".") { it.first })
        }
        val info = NativeCSharpSyntaxModel.declarationOf(item) ?: return null
        return presentation(item, info.name + info.parameters.orEmpty(), containers(item, info).filter { info.kind.isType || it.kind != DeclarationKind.NAMESPACE }.joinToString(".") { it.name })
    }

    private fun presentation(item: PsiElement, text: String, location: String) = object : ItemPresentation, LocationPresentation {
        override fun getPresentableText(): String = text
        override fun getLocationString(): String = location
        override fun getIcon(unused: Boolean): Icon? = item.getIcon(0)
        override fun getLocationPrefix(): String = " "
        override fun getLocationSuffix(): String = ""
    }
}

/** The icons of [CSharpIcons] for the declarations of csharp-psi's tree (`ElementBase.getIcon` asks icon providers first). */
class NativeCSharpIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        if (element !is CSharpElement) return null
        NativeCSharpStubDeclarations.stub(element)?.let { stub ->
            val kind = NativeCSharpStubDeclarations.kind(stub) ?: return null
            return CSharpIcons.of(kind, NativeCSharpStubDeclarations.modifiers(stub), NativeCSharpStubDeclarations.containers(stub).lastOrNull()?.second)
        }
        val info = NativeCSharpSyntaxModel.declarationOf(element) ?: return null
        return CSharpIcons.of(info.kind, info.modifiers, containers(element, info).lastOrNull()?.kind)
    }
}

private fun containers(element: PsiElement, info: CSharpDeclarationInfo): List<CSharpDeclarationInfo> =
    NativeCSharpSyntaxModel.declarations(element.containingFile).containersOf(info)

/**
 * The declarations of [NativeCSharpSyntaxModel] as the stubs of csharp-psi have them (CSHARP_PSI_MIGRATION.md, step 8): kind, modifiers and the
 * namespaces and types around, from a stub and its parents. A stub stands for a declaration of the model when it has a name, except the
 * declarator of a field with one (the field is the declaration then).
 */
object NativeCSharpStubDeclarations {
    /** The stub of [element] when it has one (bound to the AST or not). */
    fun stub(element: PsiElement): CSharpStub? = (element as? CSharpStubElementImpl)?.greenStub

    /** The kind of the declaration [stub] stands for, null when it stands for none. */
    fun kind(stub: CSharpStub): DeclarationKind? {
        if (stub.name == null) return null
        return when (stub.elementType) {
            SyntaxKind.NamespaceDeclaration, SyntaxKind.FileScopedNamespaceDeclaration -> DeclarationKind.NAMESPACE
            SyntaxKind.ClassDeclaration -> DeclarationKind.CLASS
            SyntaxKind.StructDeclaration -> DeclarationKind.STRUCT
            SyntaxKind.InterfaceDeclaration -> DeclarationKind.INTERFACE
            SyntaxKind.EnumDeclaration -> DeclarationKind.ENUM
            SyntaxKind.RecordDeclaration, SyntaxKind.RecordStructDeclaration -> DeclarationKind.RECORD
            SyntaxKind.DelegateDeclaration -> DeclarationKind.DELEGATE
            SyntaxKind.ConstructorDeclaration, SyntaxKind.DestructorDeclaration -> DeclarationKind.CONSTRUCTOR
            SyntaxKind.MethodDeclaration -> DeclarationKind.METHOD
            SyntaxKind.OperatorDeclaration, SyntaxKind.ConversionOperatorDeclaration -> DeclarationKind.OPERATOR
            SyntaxKind.PropertyDeclaration -> DeclarationKind.PROPERTY
            SyntaxKind.IndexerDeclaration -> DeclarationKind.INDEXER
            SyntaxKind.EventDeclaration, SyntaxKind.EventFieldDeclaration -> DeclarationKind.EVENT
            SyntaxKind.FieldDeclaration -> DeclarationKind.FIELD
            SyntaxKind.EnumMemberDeclaration -> DeclarationKind.ENUM_MEMBER
            SyntaxKind.VariableDeclarator -> {
                val declaration = stub.parentStub ?: return null
                if (declaration.childrenStubs.size < 2) null else if (declaration.parentStub?.elementType == SyntaxKind.EventFieldDeclaration) DeclarationKind.EVENT else DeclarationKind.FIELD
            }
            else -> null
        }
    }

    /** The modifiers as written; a declarator has its field's. */
    fun modifiers(stub: CSharpStub): Set<String> {
        val owner = if (stub.elementType == SyntaxKind.VariableDeclarator) stub.parentStub?.parentStub as? CSharpStub ?: stub else stub
        return owner.modifiers.toSet()
    }

    /** The namespaces and types around [stub], the outermost first: name and kind. */
    fun containers(stub: CSharpStub): List<Pair<String, DeclarationKind>> {
        val containers = ArrayList<Pair<String, DeclarationKind>>()
        var parent = stub.parentStub
        while (parent is CSharpStub) {
            val kind = kind(parent)
            if (kind != null && (kind == DeclarationKind.NAMESPACE || kind.isType)) containers += parent.name!! to kind
            parent = parent.parentStub
        }
        return containers.asReversed()
    }
}
