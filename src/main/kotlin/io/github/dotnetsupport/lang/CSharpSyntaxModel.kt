package io.github.dotnetsupport.lang

import com.intellij.openapi.util.TextRange
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * What the plugin asks of the syntax of a C# file: its declarations (namespaces, types, members with their ranges, names,
 * modifiers, bodies), the PSI elements that stand for them, and the methods marked with given attributes. Every consumer of
 * the declarations goes through here (CSHARP_PSI_MIGRATION.md, step 7), so that the swap of the parser replaces
 * [current] and nothing else; the snapshot goldens of `CSharpSyntaxSnapshotTest` are what the two implementations are
 * compared on.
 *
 * Two implementations, chosen with the tree ([CSharpSyntaxTrees.nativeTree]): [HeuristicCSharpSyntaxModel] (the scanner [CSharpDeclarations] and
 * the declaration nodes it groups the tokens into) and [NativeCSharpSyntaxModel] (csharp-psi's tree: a `BaseNamespaceDeclaration`,
 * `BaseTypeDeclaration`/`DelegateDeclaration` or `MemberDeclaration` per [CSharpDeclarationInfo], one per `VariableDeclarator` of a field,
 * `Block` bodies as [CSharpDeclarationInfo.body], `UsingDirective`s of the `CompilationUnit` as [CSharpFileStructure.usings]). Each answers
 * for the elements and files of the other tree too, by delegation: a file keeps its tree for a while after the switch.
 */
interface CSharpSyntaxModel {
    /** The declarations of [text]: for text that is not (or not yet) a PSI file, such as the content being indexed or a modified copy. */
    fun declarations(text: CharSequence): CSharpFileStructure

    /** The declarations of [file] as its current text has them, computed once per change of it. */
    fun declarations(file: PsiFile): CSharpFileStructure

    /** The declaration a PSI element of the tree stands for, or null when it is not a declaration. Cheap for any element, of any language. */
    fun declarationOf(element: PsiElement): CSharpDeclarationInfo?

    /** The elements of the declarations directly under [parent] (a file, a namespace, a type), in the order of the text. */
    fun childDeclarations(parent: PsiElement): List<NavigatablePsiElement>

    /** The element of the innermost declaration around [offset] of [file]; its own name included, its leading attributes too. */
    fun declarationElementAt(file: PsiFile, offset: Int): NavigatablePsiElement?

    /** Methods directly in a type that carry one of [attributes] (simple names as written: `Fact`, `FactAttribute`), and the types of [text]. */
    fun attributedMethods(text: CharSequence, attributes: Set<String>): CSharpAttributedMethods

    companion object {
        /**
         * The implementation in charge. Not chosen per project: the switch that is to choose it (`CSharpFeature.SYNTAX_TREE`) depends on the
         * application settings only, so callers that have nothing but text get the same answer as those that have a file.
         */
        val current: CSharpSyntaxModel get() = if (CSharpSyntaxTrees.nativeTree()) NativeCSharpSyntaxModel else HeuristicCSharpSyntaxModel
    }
}

enum class DeclarationKind(val title: String, val isType: Boolean = false) {
    NAMESPACE("namespace"),
    CLASS("class", true), STRUCT("struct", true), INTERFACE("interface", true), ENUM("enum", true), RECORD("record", true), DELEGATE("delegate", true),
    CONSTRUCTOR("constructor"), METHOD("method"), OPERATOR("operator"), PROPERTY("property"), INDEXER("indexer"), FIELD("field"), EVENT("event"), ENUM_MEMBER("enum member"),
}

/**
 * A declaration of a [CSharpSyntaxModel]. [range] runs from the first token of the declaration (its attributes included) to its
 * closing brace or semicolon; [body] is the `{ ... }` of it, braces included. A destructor is a [DeclarationKind.CONSTRUCTOR]
 * named `~Name`, a conversion operator an [DeclarationKind.OPERATOR]; `int a, b;` is one field named after its first variable in the
 * heuristic model, two fields in the native one.
 */
class CSharpDeclarationInfo(
    val kind: DeclarationKind,
    val name: String,
    val nameRange: TextRange,
    val range: TextRange,
    val body: TextRange?,
    /** `(int a, string b)` of a method, as written. */
    val parameters: String?,
    /** The return type of a method, the type of a property or a field. */
    val type: String?,
    val modifiers: Set<String>,
    val children: List<CSharpDeclarationInfo>,
) {
    /** `Total(decimal discount): decimal`, `Name: string`, `Order`. */
    val presentation: String get() = name + parameters.orEmpty() + type?.let { ": $it" }.orEmpty()

    fun flatten(): Sequence<CSharpDeclarationInfo> = sequenceOf(this) + children.asSequence().flatMap { it.flatten() }
}

class CSharpFileStructure(val declarations: List<CSharpDeclarationInfo>, /** The block of using directives at the top of the file. */ val usings: TextRange?) {
    fun all(): Sequence<CSharpDeclarationInfo> = declarations.asSequence().flatMap { it.flatten() }

    /** Declarations around [offset], the outermost first. */
    fun pathTo(offset: Int): List<CSharpDeclarationInfo> {
        val path = ArrayList<CSharpDeclarationInfo>()
        var level = declarations
        while (true) {
            val next = level.firstOrNull { it.range.containsOffset(offset) } ?: return path
            path += next
            level = next.children
        }
    }

    /** The namespaces and types around [declaration], the outermost first. */
    fun containersOf(declaration: CSharpDeclarationInfo): List<CSharpDeclarationInfo> = pathTo(declaration.nameRange.startOffset).takeWhile { it !== declaration }

    /** `Shop.Orders.OrderService.Total` for the path of a declaration. */
    fun qualifiedName(declaration: CSharpDeclarationInfo): String = pathTo(declaration.nameRange.startOffset).joinToString(".") { it.name }
}

/**
 * A method with one of the attributes asked for. [typeName] is the type that declares it as `dotnet test` filters name it: namespaces with
 * dots, nested types with `+`, no generic arity (`Shop.Tests.OrderTests+Nested`).
 */
class CSharpAttributedMethod(val typeName: String, val name: String, val nameRange: TextRange)

/** [types]: every type of the file, named as [CSharpAttributedMethod.typeName], with the range of its name, in the order of the text. */
class CSharpAttributedMethods(val methods: List<CSharpAttributedMethod>, val types: List<Pair<String, TextRange>>)
