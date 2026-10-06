package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypeKind

/**
 * Operators, casts and statement forms of the native pass, with Roslyn's codes, texts and spans: CS0019 / CS0023 (no predefined operator
 * takes the operands), CS0030 (no explicit conversion for a cast), CS0201 (an expression that is no statement), CS0119 (a type or a method
 * group where a value is wanted), CS0428 (a method group to a non-delegate type), CS0815 / CS0818 / CS0820 (`var` without a value to type
 * it), CS0021 (indexing what has no indexer), CS0026 / CS0027 (`this` where there is no instance).
 *
 * Precision first, as [CSharpSemanticChecks]: the operator tables of C# (§12.4.7 numeric promotion, string concatenation, enum operators,
 * lifting) are only applied to operands whose every operator is known — the special types, enums, and classes and structs that neither
 * declare nor inherit any operator or conversion; anything with a user-defined operator (of the solution or of an assembly), `object`,
 * interfaces, delegates, tuples, records, type parameters, partial types, `dynamic` is left alone, and so is an operator that a C# 14
 * extension block declares anywhere.
 */
internal class CSharpOperatorChecks(
    private val resolver: CSharpNameResolver, private val checks: CSharpSemanticChecks, private val report: (String, String, TextRange) -> Unit,
) {
    private val session = resolver.session
    private val plain = HashMap<String, Boolean>()
    private val extensionOperators = HashMap<String, Boolean>()

    fun visit(element: PsiElement) {
        when (element) {
            is CSharpBinaryExpression -> checkBinary(element)
            is CSharpAssignmentExpression -> { checkCompound(element); checkMethodGroupAssignment(element) }
            is CSharpPrefixUnaryExpression -> checkUnary(element, element.operatorToken?.text, element.operand)
            // a postfix `!` is the null-forgiving operator
            is CSharpPostfixUnaryExpression -> element.operatorToken?.text?.takeIf { it != "!" }?.let { checkUnary(element, it, element.operand) }
            is CSharpCastExpression -> checkCast(element)
            is CSharpExpressionStatement -> checkStatement(element)
            is CSharpElementAccessExpression -> checkIndexing(element)
            is CSharpLocalDeclarationStatement -> checkImplicitlyTyped(element)
            is CSharpVariableDeclaration -> checkMethodGroupDeclaration(element)
            is CSharpThisExpression -> checkThis(element)
            is CSharpSimpleName, is CSharpMemberAccessExpression -> checkTypeAsValue(element as CSharpExpression)
        }
    }

    // ---- what an operand is

    private enum class Kind { NUMBER, BOOL, STRING, ENUM, STRUCT, CLASS }

    /** An operand whose operators C# defines all by itself: [core] without `Nullable<>`, [name] the full name of its definition. */
    private class Operand(val type: SemanticType, val core: SemanticType, val kind: Kind, val nullable: Boolean, val name: String)

    private fun operand(type: SemanticType?): Operand? {
        type ?: return null
        val nullable = resolver.isNullable(type)
        val core = if (nullable) (type as SemanticType.Library).arguments.singleOrNull() ?: return null else type
        val name = resolver.definitionName(core) ?: return null
        val kind = when {
            name in NUMBERS -> Kind.NUMBER
            name == BOOL -> Kind.BOOL
            name == STRING -> if (nullable) return null else Kind.STRING
            core is SemanticType.Source -> when (core.info.kind) {
                TypeKind.ENUM -> Kind.ENUM
                TypeKind.STRUCT -> Kind.STRUCT
                TypeKind.CLASS -> if (nullable) return null else Kind.CLASS
                else -> return null
            }
            core is SemanticType.Library -> when (core.type.kind) {
                IndexedTypeKind.ENUM -> Kind.ENUM
                IndexedTypeKind.STRUCT -> Kind.STRUCT
                IndexedTypeKind.CLASS -> if (nullable) return null else Kind.CLASS
                else -> return null
            }
            else -> return null
        }
        if ((kind == Kind.STRUCT || kind == Kind.CLASS) && !isPlain(core)) return null
        if (kind == Kind.ENUM && !checks.isKnown(core)) return null
        return Operand(type, core, kind, nullable, name)
    }

    /**
     * A class or struct with no operator of its own: not partial, not a record, all its bases known and none of them (interfaces included)
     * declaring an operator or a conversion; none of the types C# gives operators or conversions of its own (`object`, tuples, delegates).
     */
    private fun isPlain(type: SemanticType): Boolean {
        val name = resolver.definitionName(type) ?: return false
        if (name in SPECIAL_CLASSES || name.startsWith("System.ValueTuple") || name.startsWith("System.Linq.Expressions.")) return false
        return plain.getOrPut(name) {
            when (type) {
                is SemanticType.Source -> type.info.parts.none { "partial" in it.modifiers } && checks.isKnown(type) && !hasOperators(type, 0)
                is SemanticType.Library -> !type.type.isRecord && checks.isKnown(type) && !libraryOperators(type.type)
                else -> false
            }
        }
    }

    private fun hasOperators(type: SemanticType, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        return when (type) {
            is SemanticType.Source -> type.info.parts.any { part ->
                val declaration = part.element() as? CSharpTypeDeclaration ?: return@any true
                declaration.members.any { it is CSharpOperatorDeclaration || it is CSharpConversionOperatorDeclaration }
            } || resolver.baseTypes(type).any { (it !is SemanticType.Source || it.info.key != type.info.key) && hasOperators(it, depth + 1) }
            is SemanticType.Library -> libraryOperators(type.type)
            else -> true
        }
    }

    private fun libraryOperators(type: IndexedType): Boolean =
        (listOf(type) + session.baseTypes(resolver.assemblies, type).map { it.type }).any { t -> t.members.any { it.name.startsWith("op_") } }

    /** A C# 14 extension operator [symbol] (`operator +`) declared in the solution or an assembly: it may take any operands. */
    private fun extensionOperator(symbol: String): Boolean = extensionOperators.getOrPut(symbol) {
        val metadata = listOfNotNull(OPERATOR_NAMES[symbol], UNARY_NAMES[symbol])
        if (metadata.any { name -> resolver.assemblies.membersNamed(name).any { it.type.isStatic } }) return@getOrPut true
        var found = false
        StubIndex.getInstance().processElements(CSharpStubIndexKeys.MEMBER_NAMES, "operator $symbol", session.project,
            io.github.dotnetsupport.codeanalysis.CSharpSourceScope.of(session.project), CSharpElement::class.java) { element ->
            found = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.any { it.elementType == SyntaxKind.ExtensionBlockDeclaration }
            !found
        }
        found
    }

    private fun shown(operand: Operand): String? = minimal(operand.type)

    /** As Roslyn's messages write a type minimally: `int?`, `List<int>`, `Box.Inner` (the types around a nested one, without the namespace). */
    private fun minimal(type: SemanticType): String? {
        if (resolver.isNullable(type)) return (type as SemanticType.Library).arguments.singleOrNull()?.let(::minimal)?.plus("?")
        if (type is SemanticType.Source && type.arguments.isEmpty() && type.outer == null) {
            val declaration = type.info.parts.firstOrNull()?.element() as? CSharpBaseTypeDeclaration ?: return null
            val chain = generateSequence<PsiElement>(declaration) { it.parent }.takeWhile { it is CSharpBaseTypeDeclaration }.map { it as CSharpBaseTypeDeclaration }.toList()
            if (chain.any { (it as? CSharpTypeDeclaration)?.typeParameterList != null || it.identifier == null }) return null
            return chain.asReversed().joinToString(".") { it.identifier!!.text }
        }
        if (type is SemanticType.Library && type.type.declaringType != null) return null
        return CSharpTypeDisplay.display(type, qualified = false)
    }

    // ---- CS0019: binary operators

    private fun checkBinary(binary: CSharpBinaryExpression) {
        val operator = binary.operatorToken?.text ?: return
        if (operator !in BINARY) return
        val left = binary.left ?: return
        val right = binary.right ?: return
        reportBinary(binary, operator, operator, left, right)
    }

    private fun checkCompound(assignment: CSharpAssignmentExpression) {
        val text = assignment.operatorToken?.text ?: return
        val operator = text.removeSuffix("=")
        if (!text.endsWith("=") || operator !in BINARY || operator == "&&" || operator == "||" || operator in COMPARISONS) return
        if (extensionOperator(text)) return
        reportBinary(assignment, operator, text, assignment.left ?: return, assignment.right ?: return)
    }

    private fun reportBinary(at: CSharpExpression, operator: String, written: String, left: CSharpExpression, right: CSharpExpression) {
        val l = operand(typed(left)) ?: return
        val r = operand(typed(right)) ?: return
        if (binaryExists(operator, l, r, left, right) != false || extensionOperator(operator)) return
        report("CS0019", "Operator '$written' cannot be applied to operands of type '${shown(l) ?: return}' and '${shown(r) ?: return}'", at.textRange)
    }

    /** The type of an operand that is a value of its own: null for `null`, `default`, lambdas, method groups and the rest that take a target's. */
    private fun typed(expression: CSharpExpression): SemanticType? {
        val e = unparenthesized(expression)
        if (e.elementType == SyntaxKind.NullLiteralExpression || e.elementType == SyntaxKind.DefaultLiteralExpression || e is CSharpAnonymousFunctionExpression ||
            e is CSharpCollectionExpression || e is CSharpThrowExpression || e is CSharpSwitchExpression || e is CSharpConditionalExpression ||
            e is CSharpImplicitObjectCreationExpression || e is CSharpTupleExpression || e is CSharpInterpolatedStringExpression || e is CSharpStackAllocArrayCreationExpression) return null
        return resolver.typeOf(e)
    }

    /** Whether a predefined operator [operator] takes [l] and [r]: true, false, or null when that is not sure (constants, ambiguities). */
    private fun binaryExists(operator: String, l: Operand, r: Operand, left: CSharpExpression, right: CSharpExpression): Boolean? {
        if (operator == "+" && (l.kind == Kind.STRING || r.kind == Kind.STRING)) return true
        if (operator == "&&" || operator == "||") return l.kind == Kind.BOOL && r.kind == Kind.BOOL && !l.nullable && !r.nullable
        val a = l.kind
        val b = r.kind
        // a class or struct without operators has none of these; `==` of two classes is the reference equality when one converts to the other
        if (a == Kind.STRUCT || b == Kind.STRUCT) return false
        if (a == Kind.CLASS || b == Kind.CLASS) {
            if (operator != "==" && operator != "!=") return false
            if (a != b || l.nullable || r.nullable) return false
            return if (related(l.core, r.core)) null else false
        }
        if (a == Kind.STRING || b == Kind.STRING) return if (operator == "==" || operator == "!=") a == b else false
        if (a == Kind.BOOL || b == Kind.BOOL) return a == b && operator in BOOL_OPERATORS
        if (a == Kind.ENUM || b == Kind.ENUM) return enumExists(operator, l, r, left, right)
        // two numbers (and chars)
        if (operator in SHIFTS) {
            if (l.name !in INTEGRAL) return false
            return r.name in SHIFT_COUNTS
        }
        if (operator in BITWISE && (l.name !in INTEGRAL || r.name !in INTEGRAL)) return false
        return promotes(l.name, r.name)
    }

    /** §12.4.7.3: `decimal` with `float` / `double` has no common type; `ulong` with a signed integer is CS0034 (or a constant), not ours. */
    private fun promotes(l: String, r: String): Boolean? {
        val names = setOf(l, r)
        if (DECIMAL in names && (SINGLE in names || DOUBLE in names)) return false
        if (UINT64 in names && names.any { it in SIGNED }) return null
        return true
    }

    /** The enum operators (§12.12.6, §12.10.3, §12.13.3): `E op E` of comparison and logical operators, `E - E`, and `E ± U` with the underlying type. */
    private fun enumExists(operator: String, l: Operand, r: Operand, left: CSharpExpression, right: CSharpExpression): Boolean? {
        if (operator in SHIFTS || operator == "*" || operator == "/" || operator == "%") return false
        // there is no `E + E`; the rest takes two of the same enum
        if (l.kind == Kind.ENUM && r.kind == Kind.ENUM) return operator != "+" && l.name == r.name
        // an enum and a number: `E ± U` depends on the underlying type, a constant zero converts to any enum
        if (operator == "+" || operator == "-") return null
        val number = if (l.kind == Kind.NUMBER) left else right
        return if (maybeConstant(number)) null else false
    }

    private fun related(a: SemanticType, b: SemanticType): Boolean {
        val an = resolver.definitionName(a) ?: return true
        val bn = resolver.definitionName(b) ?: return true
        return an == bn || bn in supertypes(a, 0) || an in supertypes(b, 0)
    }

    private fun supertypes(type: SemanticType, depth: Int): Set<String> {
        if (depth > MAX_DEPTH) return emptySet()
        val found = HashSet<String>()
        when (type) {
            is SemanticType.Source -> for (base in resolver.baseTypes(type)) {
                if (base is SemanticType.Source && base.info.key == type.info.key) continue
                resolver.definitionName(base)?.let(found::add)
                found += supertypes(base, depth + 1)
            }
            is SemanticType.Library -> session.baseTypes(resolver.assemblies, type.type).forEach { found += it.type.fullName }
            else -> {}
        }
        return found
    }

    // ---- CS0023: unary operators

    private fun checkUnary(expression: CSharpExpression, operator: String?, operandExpression: CSharpExpression?) {
        operator ?: return
        operandExpression ?: return
        if (operator !in UNARY) return
        val increment = operator == "++" || operator == "--"
        if (increment && !isVariable(operandExpression)) return
        val o = operand(typed(operandExpression)) ?: return
        val exists: Boolean? = when (operator) {
            "+" -> o.kind == Kind.NUMBER
            "-" -> if (o.kind == Kind.NUMBER && o.name == UINT64) (if (maybeConstant(operandExpression)) null else false) else o.kind == Kind.NUMBER
            "!" -> o.kind == Kind.BOOL
            "~" -> o.kind == Kind.ENUM || o.kind == Kind.NUMBER && o.name in INTEGRAL
            else -> o.kind == Kind.NUMBER || o.kind == Kind.ENUM
        }
        if (exists != false || extensionOperator(operator)) return
        report("CS0023", "Operator '$operator' cannot be applied to operand of type '${shown(o) ?: return}'", expression.textRange)
    }

    /** A local, parameter, field or property, or an element of an array: what `++` / `--` may change (anything else is CS1059). */
    private fun isVariable(expression: CSharpExpression): Boolean {
        val e = unparenthesized(expression)
        if (e is CSharpElementAccessExpression) return e.expression?.let(resolver::typeOf) is SemanticType.ArrayOf
        val symbol = resolved(nameOf(e))?.single ?: return false
        return when (symbol) {
            is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.LOCAL || symbol.symbol.kind == LocalSymbolKind.PARAMETER
            is CSharpSymbol.SourceMember -> isFieldOrProperty(symbol.element)
            is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.FIELD || symbol.member.kind == IndexedMemberKind.PROPERTY
            else -> false
        }
    }

    // ---- CS0030: casts

    private fun checkCast(cast: CSharpCastExpression) {
        val written = cast.type ?: return
        val value = cast.expression ?: return
        val target = operand(resolver.resolveType(written)) ?: return
        val source = operand(typed(value)) ?: return
        if (convertsExplicitly(source, target) != false) return
        val from = CSharpTypeDisplay.display(source.type) ?: return
        val to = CSharpTypeDisplay.display(target.type) ?: return
        report("CS0030", "Cannot convert type '$from' to '$to'", cast.textRange)
    }

    /** §10.3: the explicit conversions between the operands [operand] knows; nullable ones as their underlying types (§10.6.1). */
    private fun convertsExplicitly(s: Operand, t: Operand): Boolean? {
        val a = s.kind
        val b = t.kind
        val numeric = { k: Kind -> k == Kind.NUMBER || k == Kind.ENUM }
        if (numeric(a) && numeric(b)) return true
        if (a == Kind.CLASS && b == Kind.CLASS) return if (related(s.core, t.core)) null else false
        if (a == b && (a == Kind.BOOL || a == Kind.STRING)) return true
        if (a == Kind.STRUCT && b == Kind.STRUCT) return if (s.name == t.name) null else false
        return false
    }

    // ---- CS0201: statements

    private fun checkStatement(statement: CSharpExpressionStatement) {
        val expression = statement.expression ?: return
        if (!isValue(expression)) return
        report("CS0201", "Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement", expression.textRange)
    }

    /**
     * An expression that is surely no statement and has no error of its own: a literal, `this`, `typeof`, `default(T)`, a local, field or
     * property, a method group, a valid operator of known operands, an element of an array, anything of those in parentheses (a call too).
     */
    private fun isValue(expression: CSharpExpression): Boolean {
        val e = unparenthesized(expression)
        if (e !== expression && e is CSharpInvocationExpression) return invoked(e)
        return when (e) {
            is CSharpLiteralExpression -> e.elementType != SyntaxKind.DefaultLiteralExpression
            is CSharpThisExpression -> PsiTreeUtil.getParentOfType(e, CSharpBaseMethodDeclaration::class.java, CSharpAccessorDeclaration::class.java) != null && thisContext(e) == null
            is CSharpTypeOfExpression -> e.type?.let(resolver::resolveType) != null
            is CSharpDefaultExpression -> e.type?.let(resolver::resolveType) != null
            is CSharpIdentifierName, is CSharpMemberAccessExpression -> {
                val name = nameOf(e) ?: return false
                if (name is CSharpGenericName) return false
                if (e is CSharpMemberAccessExpression && resolver.qualifier(e.expression ?: return false) !is CSharpNameResolver.Qualifier.Value &&
                    resolver.qualifier(e.expression ?: return false) !is CSharpNameResolver.Qualifier.Type) return false
                val symbols = resolved(name)?.symbols?.takeIf { it.isNotEmpty() } ?: return false
                if (symbols.all { resolver.isMethod(it) }) return e !is CSharpMemberAccessExpression || !isExtensionGroup(symbols)
                val symbol = symbols.singleOrNull() ?: return false
                val value = when (symbol) {
                    is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.LOCAL || symbol.symbol.kind == LocalSymbolKind.PARAMETER
                    is CSharpSymbol.SourceMember -> isFieldOrProperty(symbol.element)
                    is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.FIELD || symbol.member.kind == IndexedMemberKind.PROPERTY ||
                        symbol.member.kind == IndexedMemberKind.CONSTANT
                    else -> false
                }
                value && resolver.valueType(symbol) != null
            }
            is CSharpBinaryExpression -> {
                val operator = e.operatorToken?.text ?: return false
                if (operator !in BINARY) return false
                val left = e.left ?: return false
                val right = e.right ?: return false
                val l = operand(typed(left)) ?: return false
                val r = operand(typed(right)) ?: return false
                binaryExists(operator, l, r, left, right) == true && listOf(left, right).all(::operandWithoutErrors)
            }
            is CSharpElementAccessExpression -> e.expression?.let(resolver::typeOf) is SemanticType.ArrayOf && e.argumentList?.arguments?.size == 1 &&
                e.argumentList?.arguments?.single()?.expression?.let { operand(typed(it))?.name } == INT32
            else -> false
        }
    }

    /** `(M())`: a call of the one method of that name whose arguments fit. */
    private fun invoked(call: CSharpInvocationExpression): Boolean {
        val callee = when (val e = call.expression) {
            is CSharpIdentifierName -> e
            is CSharpMemberAccessExpression -> e.nameElement as? CSharpIdentifierName
            else -> null
        } ?: return false
        val symbol = resolved(callee)?.single ?: return false
        if (!resolver.isMethod(symbol) || resolver.isExtension(symbol)) return false
        val parameters = resolver.signature(symbol, false) ?: return false
        return call.argumentList?.arguments?.let { resolver.fits(parameters, it) } == true && parameters.isEmpty()
    }

    private fun isExtensionGroup(symbols: List<CSharpSymbol>): Boolean = symbols.any { resolver.isExtension(it) }

    /** An operand that is a literal or a name of a value: what surely has no error of its own. */
    private fun operandWithoutErrors(expression: CSharpExpression): Boolean = when (val e = unparenthesized(expression)) {
        is CSharpLiteralExpression -> true
        is CSharpIdentifierName -> resolved(e)?.single.let { it is CSharpSymbol.Local || it is CSharpSymbol.SourceMember && isFieldOrProperty(it.element) }
        else -> false
    }

    // ---- CS0119: a type where a value is wanted, a method group with a member

    private fun checkTypeAsValue(expression: CSharpExpression) {
        if (expression is CSharpMemberAccessExpression) checkMethodGroupMember(expression)
        if (expression is CSharpGenericName || !isValuePosition(expression)) return
        if (expression is CSharpSimpleName && expression !is CSharpIdentifierName) return
        if (expression is CSharpMemberAccessExpression && expression.nameElement !is CSharpIdentifierName) return
        if (!leftmostSure(expression)) return
        val type = (resolver.qualifier(expression) as? CSharpNameResolver.Qualifier.Type)?.type ?: return
        if (type !is SemanticType.Source && type !is SemanticType.Library) return
        if (type is SemanticType.Source && type.info.kind == TypeKind.STATIC_CLASS || type is SemanticType.Library && type.type.isStatic) return
        val shown = minimal(type) ?: return
        report("CS0119", "'$shown' is a type, which is not valid in the given context", expression.textRange)
    }

    /** The value of an argument, of a variable's initializer, of the right of `=`, of a `return`: where only a value may stand. */
    private fun isValuePosition(expression: CSharpExpression): Boolean = when (val parent = expression.parent) {
        is CSharpArgument -> parent.parent is CSharpArgumentList && parent.nameColon == null && parent.refKindKeyword == null &&
            (parent.parent?.parent as? CSharpInvocationExpression)?.let { call -> call.expression.let { it !is CSharpIdentifierName || it.identifier?.text != "nameof" } } == true
        is CSharpEqualsValueClause -> parent.parent is CSharpVariableDeclarator
        is CSharpAssignmentExpression -> parent.right == expression && parent.operatorToken?.text == "="
        is CSharpReturnStatement -> true
        else -> false
    }

    /** `M.Length` of a method `M` (CS0119 on `M`): one method of a type of the solution, not generic, its parameters of known types. */
    private fun checkMethodGroupMember(access: CSharpMemberAccessExpression) {
        val left = access.expression as? CSharpIdentifierName ?: return
        val symbol = resolved(left)?.single as? CSharpSymbol.SourceMember ?: return
        val method = symbol.element as? CSharpMethodDeclaration ?: return
        if (method.typeParameterList != null) return
        val owner = method.parent as? CSharpTypeDeclaration ?: return
        if (owner.typeParameterList != null || owner.modifiers.any { it.text == "partial" }) return
        if (owner.members.count { it is CSharpMethodDeclaration && it.identifier?.text == method.identifier?.text } != 1) return
        val names = generateSequence<PsiElement>(owner) { it.parent }.takeWhile { it is CSharpBaseTypeDeclaration }.map { (it as CSharpBaseTypeDeclaration).identifier?.text }.toList()
        if (names.any { it == null }) return
        val ownerName = names.asReversed().joinToString(".")
        val parameters = method.parameterList?.parameters.orEmpty().map { p ->
            if (p.modifiers.isNotEmpty()) return
            p.type?.let(resolver::resolveType)?.let { minimal(it) } ?: return
        }
        report("CS0119", "'$ownerName.${method.identifier?.text}(${parameters.joinToString(", ")})' is a method, which is not valid in the given context", left.textRange)
    }

    // ---- CS0428: a method group to a non-delegate type

    private fun checkMethodGroupDeclaration(declaration: CSharpVariableDeclaration) {
        val written = declaration.type ?: return
        if (resolver.isVar(written) || declaration.parent !is CSharpLocalDeclarationStatement && declaration.parent !is CSharpBaseFieldDeclaration) return
        val target by lazy { resolver.resolveType(written) }
        for (variable in declaration.variables) {
            val value = variable.initializer?.value ?: continue
            checkMethodGroup(value, target ?: return)
        }
    }

    private fun checkMethodGroupAssignment(assignment: CSharpAssignmentExpression) {
        if (assignment.operatorToken?.text != "=") return
        val left = assignment.left ?: return
        val right = assignment.right ?: return
        if (left !is CSharpIdentifierName && left !is CSharpMemberAccessExpression) return
        val symbol = resolved(nameOf(left))?.single ?: return
        val variable = when (symbol) {
            is CSharpSymbol.Local -> symbol.symbol.kind == LocalSymbolKind.LOCAL || symbol.symbol.kind == LocalSymbolKind.PARAMETER
            is CSharpSymbol.SourceMember -> isFieldOrProperty(symbol.element)
            else -> false
        }
        if (variable) checkMethodGroup(right, resolver.valueType(symbol) ?: return)
    }

    private fun checkMethodGroup(value: CSharpExpression, target: SemanticType) {
        if (value !is CSharpIdentifierName && value !is CSharpMemberAccessExpression) return
        val name = nameOf(value) as? CSharpIdentifierName ?: return
        if (value is CSharpMemberAccessExpression && resolver.qualifier(value.expression ?: return).let { it !is CSharpNameResolver.Qualifier.Value && it !is CSharpNameResolver.Qualifier.Type }) return
        val symbols = resolved(name)?.symbols?.takeIf { it.isNotEmpty() } ?: return
        if (!symbols.all { resolver.isMethod(it) }) return
        val t = operand(target) ?: return
        val shown = minimal(t.type) ?: return
        report("CS0428", "Cannot convert method group '${name.identifier?.text}' to non-delegate type '$shown'. Did you intend to invoke the method?", value.textRange)
    }

    // ---- CS0021: indexing

    private fun checkIndexing(access: CSharpElementAccessExpression) {
        val receiver = access.expression ?: return
        val arguments = access.argumentList?.arguments ?: return
        if (arguments.any { it.expression is CSharpRangeExpression || it.expression?.let(resolver::typeOf)?.let(resolver::definitionName).let { n -> n == "System.Index" || n == "System.Range" } }) return
        val shown: String
        val group = (receiver is CSharpIdentifierName || receiver is CSharpMemberAccessExpression) &&
            resolved(nameOf(receiver))?.symbols?.let { s -> s.isNotEmpty() && s.all { resolver.isMethod(it) } } == true
        if (group) {
            if (receiver is CSharpMemberAccessExpression && resolver.qualifier(receiver.expression ?: return) !is CSharpNameResolver.Qualifier.Value) return
            shown = "method group"
        } else {
            if (resolver.qualifier(unparenthesized(receiver)) !is CSharpNameResolver.Qualifier.Value && unparenthesized(receiver).let { it is CSharpSimpleName || it is CSharpMemberAccessExpression }) return
            if (!leftmostSure(unparenthesized(receiver))) return
            val o = operand(typed(receiver)) ?: return
            if (o.kind == Kind.STRING) return
            // an `[InlineArray]` struct is indexed without an indexer (C# 12): structs only of the solution, and without attributes
            if (!o.nullable && o.kind == Kind.STRUCT && (o.core !is SemanticType.Source || o.core.info.parts.any { (it.element() as? CSharpMemberDeclaration)?.attributeLists?.isNotEmpty() != false })) return
            if (!o.nullable && (o.kind == Kind.CLASS || o.kind == Kind.STRUCT) && hasIndexer(o.core, 0)) return
            shown = shown(o) ?: return
        }
        report("CS0021", "Cannot apply indexing with [] to an expression of type '$shown'", access.textRange)
    }

    private fun hasIndexer(type: SemanticType, depth: Int): Boolean {
        if (depth > MAX_DEPTH) return true
        return when (type) {
            is SemanticType.Source -> type.info.parts.any { part -> (part.element() as? CSharpTypeDeclaration)?.members?.any { it is CSharpIndexerDeclaration } != false } ||
                resolver.baseTypes(type).any { (it !is SemanticType.Source || it.info.key != type.info.key) && hasIndexer(it, depth + 1) }
            is SemanticType.Library -> session.libraryMembers(resolver.assemblies, type.type).values.any { list -> list.any { it.member.kind == IndexedMemberKind.INDEXER } } ||
                (listOf(type.type) + session.baseTypes(resolver.assemblies, type.type).map { it.type }).any { t -> t.members.any { it.kind == IndexedMemberKind.INDEXER } }
            else -> true
        }
    }

    // ---- CS0815, CS0818, CS0820: `var` without a value to type it

    private fun checkImplicitlyTyped(statement: CSharpLocalDeclarationStatement) {
        val declaration = statement.declaration ?: return
        val written = declaration.type ?: return
        if (!resolver.isVar(written) || statement.usingKeyword != null || statement.modifiers.isNotEmpty()) return
        // a type named `var` makes it no implicit typing
        if (resolver.typeOrNamespace(written, "var", 0).isNotEmpty()) return
        val variable = declaration.variables.singleOrNull() ?: return
        val at = variable.identifier?.takeIf { it.textLength > 0 }?.textRange ?: return
        if (variable.argumentList != null) return
        val initializer = variable.initializer
        if (initializer == null) return report("CS0818", "Implicitly-typed variables must be initialized", at)
        val value = initializer.value ?: return
        when {
            value is CSharpInitializerExpression -> report("CS0820", "Cannot initialize an implicitly-typed variable with an array initializer", at)
            value.elementType == SyntaxKind.NullLiteralExpression -> report("CS0815", "Cannot assign <null> to an implicitly-typed variable", at)
            isVoid(value) -> report("CS0815", "Cannot assign void to an implicitly-typed variable", at)
        }
    }

    /** A call of a `void` method (the one the name resolves to), or `await` of a `Task` / `ValueTask` that gives no value. */
    private fun isVoid(value: CSharpExpression): Boolean = when (value) {
        is CSharpAwaitExpression -> value.expression?.let(resolver::typeOf)?.let { it is SemanticType.Library && it.type.fullName in VOID_TASKS } == true
        is CSharpInvocationExpression -> {
            val callee = when (val e = value.expression) {
                is CSharpIdentifierName -> e
                is CSharpMemberAccessExpression -> e.nameElement as? CSharpIdentifierName
                else -> null
            }
            val symbol = resolved(callee)?.single
            val parameters = symbol?.let { resolver.signature(it, resolver.isExtension(it) && callee?.parent is CSharpMemberAccessExpression) }
            symbol != null && parameters != null && value.argumentList?.arguments?.let { resolver.fits(parameters, it) } == true && when (symbol) {
                is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.returnType.let { it is CSharpPredefinedType && it.text == "void" }
                is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.METHOD && symbol.member.typeRef.let { it is IndexedTypeRef.Named && it.fullName == "System.Void" }
                else -> false
            }
        }
        else -> false
    }

    // ---- CS0026, CS0027: `this` without an instance

    private fun checkThis(expression: CSharpThisExpression) {
        val code = thisContext(expression) ?: return
        val message = if (code == "CS0026") "Keyword 'this' is not valid in a static property, static method, or static field initializer"
            else "Keyword 'this' is not available in the current context"
        report(code, message, (expression.token ?: expression).textRange)
    }

    /** CS0026 in a static member, a static field or property initializer, top-level code; CS0027 in an instance initializer or a constructor initializer. */
    private fun thisContext(element: PsiElement): String? {
        var child: PsiElement = element
        var at: PsiElement? = element.parent
        while (at != null && at !is PsiFile) {
            when (at) {
                is CSharpAnonymousFunctionExpression -> if (at.modifiers.any { it.text == "static" }) return null
                is CSharpLocalFunctionStatement -> if (at.modifiers.any { it.text == "static" }) return null
                is CSharpParameter, is CSharpAttributeList, is CSharpBaseTypeDeclaration, is CSharpEnumMemberDeclaration -> return null
                is CSharpConstructorInitializer -> return "CS0027"
                is CSharpGlobalStatement -> return "CS0026"
                is CSharpBaseFieldDeclaration -> return when {
                    at.modifiers.any { it.text == "const" } || at is CSharpEventFieldDeclaration -> null
                    at.modifiers.any { it.text == "static" } -> "CS0026"
                    else -> "CS0027"
                }
                is CSharpPropertyDeclaration -> {
                    val static = at.modifiers.any { it.text == "static" }
                    return if (child == at.initializer) (if (static) "CS0026" else "CS0027") else if (static) "CS0026" else null
                }
                is CSharpOperatorDeclaration, is CSharpConversionOperatorDeclaration -> return "CS0026"
                is CSharpMethodDeclaration, is CSharpConstructorDeclaration -> return if ((at as CSharpMemberDeclaration).modifiers.any { it.text == "static" }) "CS0026" else null
                is CSharpMemberDeclaration -> return null
            }
            child = at
            at = at.parent
        }
        return null
    }

    // ----

    /** A field (not a constant, not an event) or a property of the solution: a member is resolved to its declarator or, alone, its declaration. */
    private fun isFieldOrProperty(element: PsiElement): Boolean {
        if (element is CSharpPropertyDeclaration) return true
        val field = (if (element is CSharpVariableDeclarator) element.parent?.parent else element) as? CSharpFieldDeclaration ?: return false
        return field.modifiers.none { it.text == "const" }
    }

    /** What [name] stands for, when that is sure: a local, a member reached through a qualifier, or a name every type around it knows of. */
    private fun resolved(name: CSharpSimpleName?): CSharpResolution? {
        val leaf = name?.identifier ?: return null
        val resolution = resolver.resolve(leaf) ?: return null
        if (resolution.symbols.all { it is CSharpSymbol.Local } || surely(name)) return resolution
        return null
    }

    /**
     * Whether the lookup of the free name [name] saw everything: the types around it are not partial and all their bases are known, every
     * import and `using static` resolves. A base that is not there may declare a member of that name (`Type` of a converter's base).
     */
    private fun surely(name: CSharpSimpleName): Boolean {
        val parent = name.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == name) return true
        if (!resolver.importsKnown(name)) return false
        for (info in resolver.syntax.enclosingTypes(name)) {
            if (info.parts.any { "partial" in it.modifiers } || !checks.isKnown(resolver.selfType(info))) return false
        }
        return resolver.staticTypes(name).all { checks.isKnown(it) }
    }

    /** [surely] for the first name of `A.B.C`. */
    private fun leftmostSure(expression: CSharpExpression): Boolean {
        var e = expression
        while (e is CSharpMemberAccessExpression) e = e.expression ?: return false
        return e !is CSharpSimpleName || resolved(e) != null
    }

    private fun nameOf(e: CSharpExpression): CSharpSimpleName? = when (e) {
        is CSharpSimpleName -> e
        is CSharpMemberAccessExpression -> e.nameElement
        else -> null
    }

    private fun unparenthesized(expression: CSharpExpression): CSharpExpression {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    /** A literal, an operation of literals, a name that may be a constant: what may convert as a constant (`E == 0`, `ulong + 1`). */
    private fun maybeConstant(expression: CSharpExpression): Boolean = when (val e = unparenthesized(expression)) {
        is CSharpLiteralExpression, is CSharpCheckedExpression, is CSharpDefaultExpression, is CSharpSizeOfExpression -> true
        is CSharpPrefixUnaryExpression -> e.operand?.let(::maybeConstant) ?: true
        is CSharpBinaryExpression -> listOfNotNull(e.left, e.right).all(::maybeConstant)
        is CSharpCastExpression -> e.expression?.let(::maybeConstant) ?: true
        is CSharpConditionalExpression -> true
        is CSharpIdentifierName, is CSharpMemberAccessExpression -> when (val symbol = resolved(nameOf(e))?.single) {
            is CSharpSymbol.Local -> symbol.symbol.kind != LocalSymbolKind.PARAMETER &&
                PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpLocalDeclarationStatement::class.java)?.modifiers?.any { it.text == "const" } != false
            is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
            is CSharpSymbol.SourceMember -> (PsiTreeUtil.getParentOfType(symbol.element, CSharpBaseFieldDeclaration::class.java, false) ?: symbol.element as? CSharpMemberDeclaration)
                ?.modifiers?.any { it.text == "const" } != false
            else -> true
        }
        else -> false
    }

    companion object {
        private const val MAX_DEPTH = 16
        private const val BOOL = "System.Boolean"
        private const val STRING = "System.String"
        private const val INT32 = "System.Int32"
        private const val UINT64 = "System.UInt64"
        private const val DECIMAL = "System.Decimal"
        private const val SINGLE = "System.Single"
        private const val DOUBLE = "System.Double"

        private val SIGNED = setOf("System.SByte", "System.Int16", "System.Int32", "System.Int64")
        private val INTEGRAL = SIGNED + setOf("System.Byte", "System.UInt16", "System.UInt32", "System.UInt64", "System.Char")
        private val NUMBERS = INTEGRAL + setOf(SINGLE, DOUBLE, DECIMAL)
        /** What converts to `int` implicitly: the count of a shift. */
        private val SHIFT_COUNTS = setOf("System.SByte", "System.Byte", "System.Int16", "System.UInt16", "System.Char", "System.Int32")
        private val SPECIAL_CLASSES = setOf("System.Object", "System.ValueType", "System.Enum", "System.Delegate", "System.MulticastDelegate", "System.Array",
            "System.Nullable`1", "System.IntPtr", "System.UIntPtr", "System.String", "System.Decimal", "System.Void", "System.TypedReference")
        private val VOID_TASKS = setOf("System.Threading.Tasks.Task", "System.Threading.Tasks.ValueTask")

        private val SHIFTS = setOf("<<", ">>", ">>>")
        private val BITWISE = setOf("&", "|", "^")
        private val COMPARISONS = setOf("==", "!=", "<", ">", "<=", ">=")
        private val BOOL_OPERATORS = setOf("==", "!=", "&", "|", "^")
        private val BINARY = setOf("+", "-", "*", "/", "%", "&&", "||") + SHIFTS + BITWISE + COMPARISONS
        private val UNARY = setOf("+", "-", "!", "~", "++", "--")

        /** The metadata names of the operators, as an assembly declares a C# 14 extension operator. */
        private val OPERATOR_NAMES = mapOf(
            "+" to "op_Addition", "-" to "op_Subtraction", "*" to "op_Multiply", "/" to "op_Division", "%" to "op_Modulus", "&" to "op_BitwiseAnd",
            "|" to "op_BitwiseOr", "^" to "op_ExclusiveOr", "<<" to "op_LeftShift", ">>" to "op_RightShift", ">>>" to "op_UnsignedRightShift",
            "==" to "op_Equality", "!=" to "op_Inequality", "<" to "op_LessThan", ">" to "op_GreaterThan", "<=" to "op_LessThanOrEqual", ">=" to "op_GreaterThanOrEqual",
            "!" to "op_LogicalNot", "~" to "op_OnesComplement", "++" to "op_Increment", "--" to "op_Decrement",
            "+=" to "op_AdditionAssignment", "-=" to "op_SubtractionAssignment", "*=" to "op_MultiplyAssignment", "/=" to "op_DivisionAssignment",
        )
        private val UNARY_NAMES = mapOf("+" to "op_UnaryPlus", "-" to "op_UnaryNegation")
    }
}
