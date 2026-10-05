package io.github.dotnetsupport.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import java.util.IdentityHashMap

/** What a [LocalSymbol] is: the names a method body (or a type, for its type and primary constructor parameters) declares. */
enum class LocalSymbolKind { LOCAL, PARAMETER, PRIMARY_CONSTRUCTOR_PARAMETER, LOCAL_FUNCTION, TYPE_PARAMETER, LABEL }

/**
 * A name declared inside a type or a body: a local (also of `foreach`, `catch`, `using`, a pattern, `out var`, a query), a parameter (of a
 * method, lambda, local function, delegate, indexer), a parameter of a primary constructor, a local function, a type parameter, a label.
 * [declaration] and [references] are identifier leaves; [isWritten] — assigned, incremented or passed by `ref` / `out` after its declaration.
 * [scope] is the element whose scope declares it (a block, a statement, a function, a type; the function for a label). [isMember]: a
 * positional parameter of a record, which is a property as well — used by name outside its file, so no local symbol to rename or highlight.
 */
class LocalSymbol(val name: String, val kind: LocalSymbolKind, val declaration: PsiElement, val scope: PsiElement, val isMember: Boolean = false) {
    val references: MutableList<PsiElement> = ArrayList(2)

    var isWritten: Boolean = false
        internal set
}

/**
 * The syntactic scopes of a file of csharp-psi's tree (CSHARP_PSI_MIGRATION.md, step 9; the one resolver of A2 navigation, A4 colors and A5
 * rename, with [NativeCSharpResolver] for members and types): which local, parameter, local function, type parameter or label each simple
 * name of the file stands for, by the rules of C# lookup inside a body, without any index or other file. The innermost scope wins (a
 * lambda's parameter hides a local); a local and a local function are visible in their whole block (a use before the declaration is an
 * error in C#, but it is still the local's); expression variables (`out var x`, `is T x`) leak into the block from a declaration, an
 * expression statement, `if`, `return`, `throw`, `yield` and `switch`, and stay in `while`, `do`, `for`, `foreach`, `using`, `lock`,
 * `fixed`, an embedded statement, a lambda, a switch section or arm, a field or property initializer; a query continuation (`into g`) hides
 * the range variables of the query it continues; a label is visible in its whole function; the parameters of a primary constructor are
 * not visible in a nested type, and a positional parameter of a record is a property in the body (only its declaration is a
 * [LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER]).
 *
 * A name that no scope declares (a member, a type, something of a library) has no symbol; [names] lists every simple name for who resolves
 * those, [declarations] every namespace, type and member declaration (a field by its declarators) — the file is walked once.
 */
class NativeCSharpScopes private constructor(
    val symbols: List<LocalSymbol>,
    private val byLeaf: Map<PsiElement, LocalSymbol>,
    val names: List<CSharpSimpleName>,
    val declarations: List<PsiElement>,
) {
    /** The symbol whose declaration or reference [leaf] (an identifier) is, null for a name no scope of the file declares. */
    fun symbolAt(leaf: PsiElement): LocalSymbol? = byLeaf[leaf]

    companion object {
        /** Cached until the file changes; empty for a file of the heuristic tree. */
        fun of(file: CSharpFile): NativeCSharpScopes = CachedValuesManager.getCachedValue(file) { CachedValueProvider.Result.create(build(file), file) }

        fun build(file: CSharpFile): NativeCSharpScopes {
            val unit = file.compilationUnit ?: return NativeCSharpScopes(emptyList(), emptyMap(), emptyList(), emptyList())
            return Walker().run(unit)
        }

        /** Identifiers that are contextual keywords or implicit names where they stand in a type or an expression: never a symbol of the file. */
        val CONTEXTUAL: Set<String> = setOf("var", "dynamic", "nint", "nuint", "nameof", "unmanaged", "notnull", "_")

        /**
         * [name] is looked up in scopes by itself: not the member of `a.b`, `a?.b`, `A.B`, `alias::B`, not the name of a named argument, of a
         * property in `new { X = 1 }`, an object or `with` initializer, a property pattern, nor the label of `goto`.
         */
        fun isFreeName(name: CSharpSimpleName): Boolean {
            val parent = name.parent ?: return false
            return when (parent) {
                is CSharpMemberAccessExpression -> name != parent.nameElement
                is CSharpMemberBindingExpression -> false
                is CSharpQualifiedName -> name != parent.right
                is CSharpAliasQualifiedName -> false
                is CSharpNameColon, is CSharpNameEquals, is CSharpGotoStatement -> false
                is CSharpAssignmentExpression -> !(name == parent.left && isObjectInitializer(parent.parent))
                else -> !inPropertyPattern(name)
            }
        }

        /** `{ X = 1 }` of `new T { ... }` or `a with { ... }`: its names are members of the created type. */
        fun isObjectInitializer(element: PsiElement?): Boolean =
            element is CSharpInitializerExpression && element.node.elementType.let { it == SyntaxKind.ObjectInitializerExpression || it == SyntaxKind.WithInitializerExpression }

        // `{ A.B: 1 }` (an extended property pattern): `A` names a member of the matched type
        private fun inPropertyPattern(name: CSharpSimpleName): Boolean {
            var current: PsiElement = name
            while (current.parent is CSharpMemberAccessExpression && (current.parent as CSharpMemberAccessExpression).expression == current) current = current.parent
            return current.parent is CSharpExpressionColon && current.parent.parent is CSharpSubpattern
        }
    }

    /** [hidesUpTo]: the scope of a query continuation hides the scopes of the query it continues, up to that of the query expression. */
    private class Scope(val parent: Scope?, val element: PsiElement, val type: PsiElement? = null, val hidesUpTo: Scope? = null) {
        val names = HashMap<String, LocalSymbol>(4)
    }

    private class Walker {
        private val symbols = ArrayList<LocalSymbol>()
        private val byLeaf = IdentityHashMap<PsiElement, LocalSymbol>()
        private val names = ArrayList<CSharpSimpleName>()
        private val declarations = ArrayList<PsiElement>()
        private val labels = IdentityHashMap<PsiElement, HashMap<String, LocalSymbol>>()
        private val gotos = ArrayList<Pair<PsiElement, PsiElement>>()
        private lateinit var scope: Scope
        private lateinit var function: PsiElement

        fun run(unit: CSharpCompilationUnit): NativeCSharpScopes {
            function = unit
            scope = Scope(null, unit)
            predeclare(unit.members.mapNotNull { (it as? CSharpGlobalStatement)?.statement })
            visitChildren(unit)
            for ((owner, leaf) in gotos) labels[owner]?.get(leaf.text)?.let { reference(it, leaf) }
            return NativeCSharpScopes(symbols, byLeaf, names, declarations)
        }

        private fun declare(identifier: PsiElement?, kind: LocalSymbolKind, into: Scope? = scope, isMember: Boolean = false): LocalSymbol? {
            if (identifier == null || identifier.node.elementType != SyntaxKind.IdentifierToken) return null
            // declared up front by its block (see [predeclare]): visible before its declaration
            byLeaf[identifier]?.let { return it }
            val symbol = LocalSymbol(identifier.text, kind, identifier, into?.element ?: if (kind == LocalSymbolKind.LABEL) function else scope.element, isMember)
            symbols += symbol
            byLeaf[identifier] = symbol
            into?.names?.put(symbol.name, symbol)
            return symbol
        }

        private fun reference(symbol: LocalSymbol, leaf: PsiElement) {
            symbol.references += leaf
            byLeaf[leaf] = symbol
            if (symbol.kind == LocalSymbolKind.LOCAL && NativeCSharpUsageKinds.kindOfLeaf(leaf) == CSharpUsageKind.WRITE) symbol.isWritten = true
        }

        /** Innermost first; the primary constructor parameters of an outer type are not visible in a nested one. */
        private fun lookup(name: String): LocalSymbol? {
            var current: Scope? = scope
            var typeSeen = false
            var hidden: Scope? = null
            while (current != null) {
                if (hidden == null) current.names[name]?.let { symbol -> if (!(typeSeen && symbol.kind == LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER)) return symbol }
                if (current === hidden) hidden = null else if (hidden == null && current.hidesUpTo != null) hidden = current.hidesUpTo
                if (current.type != null) typeSeen = true
                current = current.parent
            }
            return null
        }

        private inline fun scoped(element: PsiElement, type: PsiElement? = null, hidesUpTo: Scope? = null, body: () -> Unit) {
            val saved = scope
            scope = Scope(saved, element, type, hidesUpTo)
            try {
                body()
            } finally {
                scope = saved
            }
        }

        private inline fun inFunction(owner: PsiElement, body: () -> Unit) {
            val saved = function
            function = owner
            try {
                scoped(owner, body = body)
            } finally {
                function = saved
            }
        }

        /**
         * What the statements of a block declare for the whole block, before any of them is visited: their locals and local functions, and
         * the variables of the expressions at their top (`out var`, patterns) where C# lets them leak — a declaration, an expression
         * statement, `if`, `return`, `throw`, `yield`, `switch`. Those of the other statements are declared in their own scope on the way.
         */
        private fun predeclare(statements: List<CSharpStatement>) {
            for (statement in statements) leak(statement)
        }

        private fun leak(statement: CSharpStatement) {
            when (statement) {
                is CSharpLabeledStatement -> statement.statement?.let(::leak)
                is CSharpLocalDeclarationStatement -> {
                    statement.declaration?.variables?.forEach { declare(it.identifier, LocalSymbolKind.LOCAL) }
                    designations(statement)
                }
                is CSharpLocalFunctionStatement -> declare(statement.identifier, LocalSymbolKind.LOCAL_FUNCTION)
                is CSharpExpressionStatement, is CSharpIfStatement, is CSharpReturnStatement, is CSharpThrowStatement, is CSharpYieldStatement, is CSharpSwitchStatement ->
                    designations(statement)
                else -> {}
            }
        }

        /** Variables designated in [root] (`out var x`, `is T x`, `var (a, b)`), not in what has its own scope inside it. */
        private fun designations(root: PsiElement) {
            if (root is CSharpSingleVariableDesignation) declare(root.identifier, LocalSymbolKind.LOCAL)
            var child = root.firstChild
            while (child != null) {
                if (child is CSharpElement && !ownScope(child)) designations(child)
                child = child.nextSibling
            }
        }

        private fun ownScope(element: PsiElement): Boolean =
            element is CSharpStatement || element is CSharpAnonymousFunctionExpression || element is CSharpQueryExpression || element is CSharpSwitchSection ||
                element is CSharpSwitchExpressionArm || element is CSharpMemberDeclaration

        /** A statement that is the body of another one (`if (a) x();`, `else if`, `while (b) y();`): C# gives it a scope of its own. */
        private fun isEmbedded(statement: CSharpStatement): Boolean {
            val parent = statement.parent
            return statement !is CSharpBlock && (parent is CSharpElseClause || parent is CSharpStatement && parent !is CSharpBlock && parent !is CSharpLabeledStatement)
        }

        /** The scope of the nearest query expression: what a continuation hides. */
        private fun queryScope(): Scope? = generateSequence(scope) { it.parent }.firstOrNull { it.element is CSharpQueryExpression }

        private fun visitChildren(element: PsiElement) {
            var child = element.firstChild
            while (child != null) {
                visit(child)
                child = child.nextSibling
            }
        }

        private fun declareParameters(list: CSharpBaseParameterList?, kind: LocalSymbolKind = LocalSymbolKind.PARAMETER, into: Scope? = scope) {
            list?.parameters?.forEach { declare(it.identifier, kind, into) }
        }

        private fun declareTypeParameters(list: CSharpTypeParameterList?) {
            list?.parameters?.forEach { declare(it.identifier, LocalSymbolKind.TYPE_PARAMETER) }
        }

        private fun visit(element: PsiElement) {
            if (element is CSharpStatement && isEmbedded(element)) scoped(element) { visitNode(element) } else visitNode(element)
        }

        private fun visitNode(element: PsiElement) {
            when (element) {
                is CSharpBaseNamespaceDeclaration -> {
                    declarations += element
                    visitChildren(element)
                }
                is CSharpTypeDeclaration -> {
                    declarations += element
                    scoped(element, type = element) {
                        declareTypeParameters(element.typeParameterList)
                        // a positional parameter of a record is a property in the body: its declaration alone is the parameter's
                        val record = element is CSharpRecordDeclaration
                        // the receiver of `extension(string s) { ... }` is a parameter of each of its members
                        val extension = element.node.elementType == SyntaxKind.ExtensionBlockDeclaration
                        val kind = if (extension) LocalSymbolKind.PARAMETER else LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER
                        if (record) element.parameterList?.parameters?.forEach { declare(it.identifier, kind, into = null, isMember = true) }
                        else declareParameters(element.parameterList, kind)
                        visitChildren(element)
                    }
                }
                is CSharpBaseTypeDeclaration -> {
                    declarations += element
                    scoped(element, type = element) { visitChildren(element) }
                }
                is CSharpDelegateDeclaration -> {
                    declarations += element
                    scoped(element) {
                        declareTypeParameters(element.typeParameterList)
                        declareParameters(element.parameterList)
                        visitChildren(element)
                    }
                }
                is CSharpBaseMethodDeclaration -> {
                    if (element !is CSharpOperatorDeclaration && element !is CSharpConversionOperatorDeclaration) declarations += element
                    inFunction(element) {
                        if (element is CSharpMethodDeclaration) declareTypeParameters(element.typeParameterList)
                        declareParameters(element.parameterList)
                        visitChildren(element)
                    }
                }
                is CSharpIndexerDeclaration -> scoped(element) {
                    declareParameters(element.parameterList)
                    visitChildren(element)
                }
                is CSharpBasePropertyDeclaration -> {
                    declarations += element
                    visitChildren(element)
                }
                is CSharpEnumMemberDeclaration -> {
                    declarations += element
                    visitChildren(element)
                }
                is CSharpAccessorDeclaration, is CSharpArrowExpressionClause -> if (element.parent is CSharpBaseMethodDeclaration || element.parent is CSharpLocalFunctionStatement) {
                    visitChildren(element)
                } else {
                    inFunction(element) { visitChildren(element) }
                }
                is CSharpLocalFunctionStatement -> {
                    // declared by its block beforehand (it is callable above its declaration); a stray one (an embedded statement) here
                    if (byLeaf[element.identifier] == null) declare(element.identifier, LocalSymbolKind.LOCAL_FUNCTION)
                    inFunction(element) {
                        declareTypeParameters(element.typeParameterList)
                        declareParameters(element.parameterList)
                        visitChildren(element)
                    }
                }
                is CSharpSimpleLambdaExpression -> inFunction(element) {
                    declare(element.parameter?.identifier, LocalSymbolKind.PARAMETER)
                    visitChildren(element)
                }
                is CSharpParenthesizedLambdaExpression -> inFunction(element) {
                    declareParameters(element.parameterList)
                    visitChildren(element)
                }
                is CSharpAnonymousMethodExpression -> inFunction(element) {
                    declareParameters(element.parameterList)
                    visitChildren(element)
                }
                is CSharpBlock -> scoped(element) {
                    predeclare(element.statements)
                    visitChildren(element)
                }
                // the locals of all sections share the switch block; the pattern variables of a section's labels are the section's
                is CSharpSwitchStatement -> scoped(element) {
                    element.sections.forEach { predeclare(it.statements) }
                    visitChildren(element)
                }
                is CSharpForStatement, is CSharpUsingStatement, is CSharpFixedStatement, is CSharpCatchClause, is CSharpSwitchSection, is CSharpWhileStatement,
                is CSharpDoStatement, is CSharpLockStatement, is CSharpSwitchExpressionArm, is CSharpQueryExpression, is CSharpCommonForEachStatement -> scoped(element) {
                    if (element is CSharpForEachStatement) declare(element.identifier, LocalSymbolKind.LOCAL)
                    visitChildren(element)
                }
                // an initializer of a field, a property, a parameter or an enum member: its expression variables are its own
                is CSharpEqualsValueClause -> if (element.parent is CSharpVariableDeclarator && element.parent.parent?.parent !is CSharpBaseFieldDeclaration) {
                    visitChildren(element)
                } else {
                    scoped(element) { visitChildren(element) }
                }
                is CSharpVariableDeclarator -> {
                    if (element.parent?.parent is CSharpBaseFieldDeclaration) declarations += element else declare(element.identifier, LocalSymbolKind.LOCAL)
                    visitChildren(element)
                }
                is CSharpSingleVariableDesignation -> declare(element.identifier, LocalSymbolKind.LOCAL)
                is CSharpCatchDeclaration -> {
                    declare(element.identifier, LocalSymbolKind.LOCAL)
                    visitChildren(element)
                }
                is CSharpFromClause, is CSharpLetClause, is CSharpJoinClause, is CSharpJoinIntoClause -> {
                    val identifier = when (element) {
                        is CSharpFromClause -> element.identifier
                        is CSharpLetClause -> element.identifier
                        is CSharpJoinClause -> element.identifier
                        else -> (element as CSharpJoinIntoClause).identifier
                    }
                    declare(identifier, LocalSymbolKind.LOCAL)
                    visitChildren(element)
                }
                // `into g`: a new query over `g` alone, the range variables before it are out of scope
                is CSharpQueryContinuation -> scoped(element, hidesUpTo = queryScope()) {
                    declare(element.identifier, LocalSymbolKind.LOCAL)
                    visitChildren(element)
                }
                is CSharpLabeledStatement -> {
                    declare(element.identifier, LocalSymbolKind.LABEL, into = null)?.let { labels.getOrPut(function) { HashMap() }[it.name] = it }
                    visitChildren(element)
                }
                is CSharpGotoStatement -> {
                    (element.expression as? CSharpIdentifierName)?.identifier?.let { gotos += function to it }
                    visitChildren(element)
                }
                is CSharpSimpleName -> {
                    names += element
                    val identifier = element.identifier
                    if (identifier != null && element.parent !is CSharpGotoStatement && isFreeName(element)) {
                        val symbol = lookup(identifier.text)
                        if (symbol != null && (symbol.kind == LocalSymbolKind.TYPE_PARAMETER || !NativeCSharpTypePositions.isType(element))) reference(symbol, identifier)
                    }
                    visitChildren(element)
                }
                else -> visitChildren(element)
            }
        }
    }
}

/** Where a name can only be a type (or a namespace on the left of one): a type of a declaration, `new T()`, a cast, `typeof`, a base type... */
object NativeCSharpTypePositions {
    /** [name] stands where only a type can: itself or as the rightmost part of a qualified name there. */
    fun isType(name: CSharpSimpleName): Boolean {
        var current: PsiElement = name
        while (true) {
            val parent = current.parent ?: return false
            when (parent) {
                is CSharpQualifiedName -> if (current == parent.right) current = parent else return false
                is CSharpAliasQualifiedName -> if (current == parent.nameElement) current = parent else return false
                is CSharpTypeArgumentList, is CSharpArrayType, is CSharpNullableType, is CSharpPointerType, is CSharpRefType, is CSharpScopedType -> return true
                else -> return typeOf(parent) === current
            }
        }
    }

    /** The child of [owner] that is a type by the grammar, null when it has none. */
    private fun typeOf(owner: PsiElement): PsiElement? = when (owner) {
        is CSharpVariableDeclaration -> owner.type
        is CSharpBaseParameter -> owner.type
        is CSharpBasePropertyDeclaration -> owner.type
        is CSharpMethodDeclaration -> owner.returnType
        is CSharpOperatorDeclaration -> owner.returnType
        is CSharpConversionOperatorDeclaration -> owner.type
        is CSharpDelegateDeclaration -> owner.returnType
        is CSharpLocalFunctionStatement -> owner.returnType
        is CSharpParenthesizedLambdaExpression -> owner.returnType
        is CSharpForEachStatement -> owner.type
        is CSharpDeclarationExpression -> owner.type
        is CSharpCatchDeclaration -> owner.type
        is CSharpFromClause -> owner.type
        is CSharpJoinClause -> owner.type
        is CSharpTupleElement -> owner.type
        is CSharpObjectCreationExpression -> owner.type
        is CSharpCastExpression -> owner.type
        is CSharpTypeOfExpression -> owner.type
        is CSharpSizeOfExpression -> owner.type
        is CSharpDefaultExpression -> owner.type
        is CSharpStackAllocArrayCreationExpression -> owner.type
        is CSharpBaseType -> owner.type
        is CSharpTypeConstraint -> owner.type
        is CSharpDeclarationPattern -> owner.type
        is CSharpRecursivePattern -> owner.type
        is CSharpTypePattern -> owner.type
        is CSharpExplicitInterfaceSpecifier -> owner.nameElement
        is CSharpIncompleteMember -> owner.type
        is CSharpTypeCref -> owner.type
        is CSharpCrefParameter -> owner.type
        is CSharpBinaryExpression -> if (owner.operatorToken?.text == "is" || owner.operatorToken?.text == "as") owner.right else null
        else -> null
    }
}
