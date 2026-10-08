package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbol
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.NativeCSharpGenerate
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind

/**
 * The rules of `ref` as the compiler-messages pages state them (0.1.146), without the escape analysis of C# §16.4 beyond its plainest
 * case:
 * - declarations: a `ref` field outside a `ref struct` (CS9059); a property returning by reference without `get` (CS8146) or with a
 *   `set` / `init` (CS8147); a class member that implements an interface member of the solution with another kind of return (CS8152);
 * - returns: `return ref` where the function returns by value (CS8149) and `return x` where it returns by reference (CS8150, an
 *   expression body too); of a `return ref x`: the type must be the declared one (CS8151), `x` must be a variable (CS8156), not a range
 *   variable (CS8159), not a plain local (CS8168) or parameter (CS8166) or a member of one (CS8169, CS8167), not a `ref` local
 *   initialized from such (CS8157, CS8158), not an instance member of a struct (CS8170), not a `readonly` field by writable reference
 *   (CS8160, CS8161) nor a member of one (CS8162);
 * - `ref` locals: no initializer (CS8174), a value for a `ref` local (CS8172), a `ref` for a value local (CS8171), the type of a
 *   `= ref x` or `y = ref x` must be the declared one (CS8173);
 * - a call whose result is a `ref struct` of the solution returned from a function, with `ref local` for a parameter that is not
 *   `scoped`: the result may keep that reference (CS8347 and CS8168), the one escape rule the pages show.
 * A library member says `ref` by its type (`IndexedTypeRef.ByRef`) and `ref readonly` by `IndexedMember.isRefReadOnly` (index format 5):
 * a `ref readonly` result returned or taken by writable reference is CS8333 / CS8329, as an `in` parameter. A lambda whose delegate is
 * of a library is left alone.
 */
internal class CSharpRefChecks(
    private val resolver: CSharpNameResolver,
    private val quiet: (PsiElement) -> Boolean,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val overloads = CSharpOverloads(resolver)

    fun run(unit: CSharpCompilationUnit) {
        PsiTreeUtil.processElements(unit) { element ->
            if (element is CSharpElement && !quiet(element)) when (element) {
                is CSharpFieldDeclaration -> checkRefField(element)
                is CSharpPropertyDeclaration -> checkRefProperty(element, element.identifier)
                is CSharpIndexerDeclaration -> checkRefProperty(element, element.thisKeyword)
                is CSharpTypeDeclaration -> checkInterfaceReturns(element)
                is CSharpReturnStatement -> element.expression?.let { e -> contextOf(element)?.let { checkReturn(e, it) } }
                is CSharpArrowExpressionClause -> element.expression?.let { e -> contextOf(element)?.let { checkReturn(e, it) } }
                is CSharpAnonymousFunctionExpression -> element.expressionBody?.let { e -> contextOf(e)?.let { checkReturn(e, it) } }
                is CSharpVariableDeclaration -> checkRefLocals(element)
                is CSharpAssignmentExpression -> checkRefAssignment(element)
            }
            true
        }
    }

    // ---- declarations

    private fun checkRefField(field: CSharpFieldDeclaration) {
        val type = field.declaration?.type as? CSharpRefType ?: return
        val owner = field.parent as? CSharpTypeDeclaration ?: return
        if ((owner is CSharpStructDeclaration || owner is CSharpRecordDeclaration) && owner.modifiers.any { it.text == "ref" }) return
        report("CS9059", "A ref field can only be declared in a ref struct", (type.refKeyword ?: type).textRange)
    }

    private fun checkRefProperty(property: CSharpBasePropertyDeclaration, at: PsiElement?) {
        if (property.type !is CSharpRefType) return
        val accessors = property.accessorList?.accessors ?: return
        // without a getter only CS8146 is said, as Roslyn
        if (accessors.none { it.keyword?.text == "get" }) return report("CS8146", "Properties which return by reference must have a get accessor", (at ?: property).textRange)
        for (accessor in accessors) {
            val keyword = accessor.keyword ?: continue
            if (keyword.text == "set" || keyword.text == "init") report("CS8147", "Properties which return by reference cannot have set accessors", keyword.textRange)
        }
    }

    /** A class or struct whose own member implements a member of an interface of the solution with another kind of return: CS8152 on the type's name. */
    private fun checkInterfaceReturns(type: CSharpTypeDeclaration) {
        if (type !is CSharpClassDeclaration && type !is CSharpStructDeclaration && type !is CSharpRecordDeclaration) return
        val name = type.identifier?.takeIf { it.textLength > 0 } ?: return
        val own = type.members
        for (base in type.baseList?.types.orEmpty()) {
            val face = base.type?.let(resolver::resolveType) as? SemanticType.Source ?: continue
            if (!NativeCSharpGenerate.isInterface(face)) continue
            val faceShown = CSharpTypeDisplay.display(face, qualified = false) ?: continue
            val typeShown = resolver.syntax.declaredType(type)?.let(resolver::selfType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: continue
            for (part in face.info.parts) {
                val declaration = part.element() as? CSharpTypeDeclaration ?: continue
                val faceResolver = (declaration.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: continue
                for (member in declaration.members) {
                    val (returns, signature) = signatureOf(member, faceResolver) ?: continue
                    val mine = own.firstNotNullOfOrNull { m -> signatureOf(m, resolver)?.takeIf { it.second.key == signature.key }?.first } ?: continue
                    if (mine == returns) continue
                    val shown = signature.display
                    report("CS8152", "'$typeShown' does not implement interface member '$faceShown.$shown'. '$typeShown.$shown' cannot implement '$faceShown.$shown' because it does not have matching return by reference.", name.textRange)
                }
            }
        }
    }

    /** A method or property as an interface member is matched: [key] name and parameter kinds and types; an explicit implementation is none. */
    private class Signature(val key: String, val display: String)

    private fun signatureOf(member: CSharpMemberDeclaration, owner: CSharpNameResolver): Pair<Returns, Signature>? = when (member) {
        is CSharpMethodDeclaration -> {
            if (member.explicitInterfaceSpecifier != null || member.typeParameterList != null) null else {
                val name = member.identifier?.text ?: return null
                val parameters = member.parameterList?.parameters.orEmpty()
                val key = parameters.map { p ->
                    val type = p.type?.let(owner::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = true) } ?: return null
                    (p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" } + type).joinToString(" ")
                }
                val shown = parameters.map { p ->
                    val type = p.type?.let(owner::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
                    (p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" } + type).joinToString(" ")
                }
                returnsOf(member.returnType) to Signature("$name(${key.joinToString(",")})", "$name(${shown.joinToString(", ")})")
            }
        }
        is CSharpPropertyDeclaration -> {
            if (member.explicitInterfaceSpecifier != null) null
            else member.identifier?.text?.let { returnsOf(member.type) to Signature(it, it) }
        }
        else -> null
    }

    // ---- the function a return belongs to

    private enum class Returns { VALUE, REF, REF_READONLY }

    private fun returnsOf(type: CSharpType?): Returns = when {
        type !is CSharpRefType -> Returns.VALUE
        type.readOnlyKeyword != null -> Returns.REF_READONLY
        else -> Returns.REF
    }

    /**
     * [function] and how it returns; [returnType] the declared type (the `ref` stripped), resolved where it is written; [unscoped]: a
     * `[UnscopedRef]` on the function or its property lets a struct return its members by reference.
     */
    private class Context(val function: PsiElement, val returns: Returns, val returnType: SemanticType?, val unscoped: Boolean)

    private fun context(function: PsiElement, syntax: CSharpType?, owner: CSharpNameResolver = resolver): Context {
        val attributed = listOfNotNull(function, (function as? CSharpAccessorDeclaration)?.let { PsiTreeUtil.getParentOfType(it, CSharpBasePropertyDeclaration::class.java) })
        val unscoped = attributed.any { f -> f.children.filterIsInstance<CSharpAttributeList>().any { it.text.contains("UnscopedRef") } }
        return Context(function, returnsOf(syntax), syntax?.let { (if (it is CSharpRefType) it.type else it)?.let(owner::resolveType) }, unscoped)
    }

    private fun contextOf(at: PsiElement): Context? {
        var current: PsiElement? = at.parent
        while (current != null && current !is CSharpFile) {
            when (current) {
                is CSharpAnonymousFunctionExpression -> {
                    (current as? CSharpParenthesizedLambdaExpression)?.returnType?.let { return context(current, it) }
                    val (type, owner) = delegateReturnOf(current) ?: return null
                    return context(current, type, owner)
                }
                is CSharpLocalFunctionStatement -> return context(current, current.returnType)
                is CSharpAccessorDeclaration -> return context(current, PsiTreeUtil.getParentOfType(current, CSharpBasePropertyDeclaration::class.java)?.type ?: return null)
                is CSharpMethodDeclaration -> return context(current, current.returnType)
                is CSharpBaseMethodDeclaration -> return context(current, null)
                is CSharpBasePropertyDeclaration -> return context(current, current.type)
                is CSharpBaseTypeDeclaration -> return null
            }
            current = current.parent
        }
        return null
    }

    /** The declared return type of the delegate of the solution [lambda] is converted to (`D d = …`, `new D(…)`, `(D)…`), with the resolver of its file. */
    private fun delegateReturnOf(lambda: CSharpAnonymousFunctionExpression): Pair<CSharpType?, CSharpNameResolver>? {
        var e: PsiElement = lambda
        while (e.parent is CSharpParenthesizedExpression) e = e.parent
        val parent = e.parent
        val syntax: CSharpType? = when (parent) {
            is CSharpEqualsValueClause -> (parent.parent?.parent as? CSharpVariableDeclaration)?.type?.takeUnless { resolver.isVar(it) }
            is CSharpCastExpression -> parent.type
            is CSharpArgument -> {
                val list = parent.parent as? CSharpArgumentList
                (list?.parent as? CSharpObjectCreationExpression)?.takeIf { list.arguments.size == 1 }?.type
            }
            else -> null
        }
        val type = syntax?.let(resolver::resolveType) as? SemanticType.Source ?: return null
        if (type.info.kind != TypeKind.DELEGATE) return null
        val declaration = type.info.parts.singleOrNull()?.element() as? CSharpDelegateDeclaration ?: return null
        val owner = (declaration.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: return null
        return declaration.returnType to owner
    }

    // ---- returns

    private fun checkReturn(e: CSharpExpression, context: Context) {
        val ref = e as? CSharpRefExpression
        if (context.returns == Returns.VALUE) {
            if (ref != null) report("CS8149", "By-reference returns may only be used in methods that return by reference", e.textRange)
            else checkEscapingResult(e)
            return
        }
        if (ref == null) return report("CS8150", "By-value returns may only be used in methods that return by value", e.textRange)
        val x = ref.expression ?: return
        if (context.returnType != null && overloads.same(context.returnType, resolver.typeOf(x)) == false) {
            val shown = CSharpTypeDisplay.display(context.returnType, qualified = false) ?: return
            return report("CS8151", "The return expression must be of type '$shown' because this method returns by reference", x.textRange)
        }
        val writable = context.returns == Returns.REF
        val range = x.textRange
        when (val place = placeOf(x)) {
            is Place.Value -> report("CS8156", "An expression cannot be used in this context because it may not be passed or returned by reference", range)
            is Place.Range -> report("CS8159", "Cannot return the range variable '${place.name}' by reference", range)
            is Place.Local -> when {
                !place.byRef -> report("CS8168", "Cannot return local '${place.name}' by reference because it is not a ref local", range)
                place.readOnly && writable -> report("CS8156", "An expression cannot be used in this context because it may not be passed or returned by reference", range)
                !returnable(place.init) -> report("CS8157", "Cannot return '${place.name}' by reference because it was initialized to a value that cannot be returned by reference", range)
            }
            is Place.ReadOnlyRef -> if (writable) report("CS8333", "Cannot return variable '${x.text}' by writable reference because it is a readonly variable", range)
            is Place.Parameter -> when {
                place.kind == "" -> report("CS8166", "Cannot return a parameter by reference '${place.name}' because it is not a ref or out parameter", range)
                place.kind == "in" && writable -> report("CS8333", "Cannot return variable '${place.name}' by writable reference because it is a readonly variable", range)
            }
            is Place.This -> if (place.struct && !context.unscoped) report("CS8170", "Struct members cannot return 'this' or other instance members by reference", range)
            is Place.Field -> {
                val through = place.through
                when {
                    place.self && place.structOwner -> if (!context.unscoped) report("CS8170", "Struct members cannot return 'this' or other instance members by reference", range)
                    through is Place.Local && !through.byRef -> report("CS8169", "Cannot return a member of local '${through.name}' by reference because it is not a ref local", range)
                    through is Place.Local && !returnable(through.init) -> report("CS8158", "Cannot return by reference a member of '${through.name}' because it was initialized to a value that cannot be returned by reference", range)
                    through is Place.Parameter && through.kind == "" -> report("CS8167", "Cannot return by reference a member of parameter '${through.name}' because it is not a ref or out parameter", range)
                    through is Place.Parameter && through.kind == "in" && writable -> report("CS8334", "Members of variable '${through.name}' cannot be returned by writable reference because it is a readonly variable", range)
                    through is Place.Local && through.readOnly && writable -> report("CS8334", "Members of variable '${through.name}' cannot be returned by writable reference because it is a readonly variable", range)
                    through is Place.ReadOnlyRef && writable -> report("CS8334", "Members of variable '${receiverText(x)}' cannot be returned by writable reference because it is a readonly variable", range)
                    through is Place.This && through.struct -> if (!context.unscoped) report("CS8170", "Struct members cannot return 'this' or other instance members by reference", range)
                    through is Place.Field && through.readonly && writable -> report("CS8162", "Members of readonly field '${through.owner}.${through.name}' cannot be returned by writable reference", range)
                    place.readonly && writable && place.isStatic -> report("CS8161", "A static readonly field cannot be returned by writable reference", range)
                    place.readonly && writable -> report("CS8160", "A readonly field cannot be returned by writable reference", range)
                }
            }
            is Place.Fine, is Place.Unknown -> {}
        }
    }

    /** The text before the last dot of a member access, for a message about the members of it. */
    private fun receiverText(x: CSharpExpression): String {
        var e: CSharpExpression = x
        while (e is CSharpParenthesizedExpression) e = e.expression ?: break
        return (e as? CSharpMemberAccessExpression)?.expression?.text ?: x.text
    }

    /** Whether what a `ref` local was initialized from may be returned by reference (not known: yes). */
    private fun returnable(init: Place?): Boolean = when (init) {
        null, is Place.Fine, is Place.Unknown, is Place.This, is Place.ReadOnlyRef -> true
        is Place.Value, is Place.Range -> false
        is Place.Local -> init.byRef && returnable(init.init)
        is Place.Parameter -> init.kind != ""
        is Place.Field -> init.through?.let(::returnable) ?: true
    }

    /**
     * `return M(ref local)` where `M` is of the solution and returns a `ref struct` of the solution: the result may keep the reference to
     * the local, which does not outlive the function — CS8347 on the call and CS8168 on the local (C# 11, `ref` fields), unless the
     * parameter is `scoped`.
     */
    private fun checkEscapingResult(e: CSharpExpression) {
        var x: CSharpExpression? = e
        while (x is CSharpParenthesizedExpression) x = x.expression
        val call = x as? CSharpInvocationExpression ?: return
        val result = resolver.typeOf(call) as? SemanticType.Source ?: return
        if (!isRefStruct(result.info)) return
        val leaf = calleeLeaf(call.expression) ?: return
        if (resolver.syntax.symbolAt(leaf) != null) return
        val method = resolver.resolve(leaf)?.single as? CSharpSymbol.SourceMember ?: return
        val declaration = method.element as? CSharpMethodDeclaration ?: return
        val parameters = declaration.parameterList?.parameters.orEmpty()
        val arguments = call.argumentList?.arguments.orEmpty()
        if (arguments.size > parameters.size) return
        for ((i, argument) in arguments.withIndex()) {
            val kind = argument.refKindKeyword?.text ?: continue
            if (kind != "ref" && kind != "in") continue
            val named = argument.nameColon?.nameElement?.identifier?.text
            val parameter = (if (named != null) parameters.firstOrNull { it.identifier?.text == named } else parameters.getOrNull(i)) ?: return
            if (parameter.modifiers.any { it.text == "scoped" }) continue
            val expression = argument.expression ?: continue
            val place = placeOf(expression) as? Place.Local ?: continue
            if (place.byRef) continue
            val shown = methodShown(declaration, method) ?: return
            report("CS8347", "Cannot use a result of '$shown' in this context because it may expose variables referenced by parameter '${parameter.identifier?.text}' outside of their declaration scope", call.textRange)
            report("CS8168", "Cannot return local '${place.name}' by reference because it is not a ref local", expression.textRange)
        }
    }

    private fun isRefStruct(info: TypeInfo): Boolean = (info.kind == TypeKind.STRUCT || info.kind == TypeKind.RECORD_STRUCT) && info.parts.any { "ref" in it.modifiers }

    private fun calleeLeaf(callee: CSharpExpression?): PsiElement? = when (callee) {
        is CSharpSimpleName -> callee.identifier
        is CSharpMemberAccessExpression -> callee.nameElement?.identifier
        else -> null
    }

    /** `Program.CaptureArgument(ref int)`, as Roslyn names a method. */
    private fun methodShown(method: CSharpMethodDeclaration, symbol: CSharpSymbol.SourceMember): String? {
        val owner = symbol.owner?.let { CSharpTypeDisplay.display(it, qualified = false) }
            ?: PsiTreeUtil.getParentOfType(method, CSharpBaseTypeDeclaration::class.java)?.let { resolver.syntax.declaredType(it) }?.let(resolver::selfType)?.let { CSharpTypeDisplay.display(it, qualified = false) }
            ?: return null
        val at = (method.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: return null
        val parameters = method.parameterList?.parameters.orEmpty().map { p ->
            val type = p.type?.let(at::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
            (p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" || it == "params" } + type).joinToString(" ")
        }
        return "$owner.${method.identifier?.text}(${parameters.joinToString(", ")})"
    }

    // ---- ref locals and ref assignments

    private fun checkRefLocals(declaration: CSharpVariableDeclaration) {
        val parent = declaration.parent
        if (parent !is CSharpLocalDeclarationStatement && parent !is CSharpForStatement) return
        val type = declaration.type
        val declared = (type as? CSharpRefType)?.type?.let(resolver::resolveType)
        for (variable in declaration.variables) {
            val value = variable.initializer?.value
            if (type is CSharpRefType) {
                if (variable.initializer == null) {
                    if (variable.argumentList == null) variable.identifier?.let { report("CS8174", "A declaration of a by-reference variable must have an initializer", it.textRange) }
                    continue
                }
                value ?: continue
                if (value !is CSharpRefExpression) {
                    report("CS8172", "Cannot initialize a by-reference variable with a value", value.textRange)
                    continue
                }
                value.expression?.let { checkReferenced(it, declared, writable = type.readOnlyKeyword == null) }
            } else if (value is CSharpRefExpression) {
                report("CS8171", "Cannot initialize a by-value variable with a reference", value.textRange)
            }
        }
    }

    /** `x = ref y` of a `ref` local or a `ref` / `out` / `in` parameter: `y` must be a variable of the type of `x`. */
    private fun checkRefAssignment(assignment: CSharpAssignmentExpression) {
        if (assignment.operatorToken?.text != "=") return
        val right = assignment.right as? CSharpRefExpression ?: return
        val y = right.expression ?: return
        val left = assignment.left ?: return
        val place = placeOf(left)
        val writable = when {
            place is Place.Local && place.byRef -> !place.readOnly
            place is Place.Parameter && place.kind != "" -> place.kind != "in"
            else -> return
        }
        checkReferenced(y, resolver.typeOf(left), writable)
    }

    /**
     * `= ref y` of a by-reference variable of [declared] type: `y` must be a variable (CS1510, as for a `ref` argument), not a readonly one
     * when the variable is [writable] (CS8329), of the same type (CS8173).
     */
    private fun checkReferenced(y: CSharpExpression, declared: SemanticType?, writable: Boolean) {
        var inner: CSharpExpression = y
        while (inner is CSharpParenthesizedExpression) inner = inner.expression ?: break
        val place = placeOf(y)
        if (place is Place.Value) return report("CS1510", "A ref or out value must be an assignable variable", inner.textRange)
        // a `ref readonly` local taken by writable reference is CS1510 in Roslyn, an `in` parameter or a `ref readonly` result CS8329
        if (writable && place is Place.Local && place.readOnly) return report("CS1510", "A ref or out value must be an assignable variable", inner.textRange)
        val readOnly = place is Place.ReadOnlyRef || place is Place.Parameter && place.kind == "in"
        if (readOnly && writable) return report("CS8329", "Cannot use variable '${inner.text}' as a ref or out value because it is a readonly variable", inner.textRange)
        if (declared != null && overloads.same(declared, resolver.typeOf(y)) == false) {
            val shown = CSharpTypeDisplay.display(declared, qualified = false) ?: return
            report("CS8173", "The expression must be of type '$shown' because it is being assigned by reference", y.textRange)
        }
    }

    // ---- what `ref x` refers to

    private sealed class Place {
        /** Not a variable: a literal, an operator, `new`, a call or property that returns by value. */
        object Value : Place()
        object Unknown : Place()
        /** A variable with nothing to say: an array element, a `ref` return, a dereference. */
        object Fine : Place()
        /** A `ref readonly` return of a method, property or indexer: a variable that may not be taken by writable reference. */
        object ReadOnlyRef : Place()
        class Range(val name: String) : Place()
        class Local(val name: String, val byRef: Boolean, val init: Place?, val readOnly: Boolean = false) : Place()
        /** [kind]: "", `ref`, `out`, `in` (`ref readonly` is `in` here: the same rules). */
        class Parameter(val name: String, val kind: String) : Place()
        class This(val struct: Boolean) : Place()
        /** [self]: through `this`, written or not; [through]: the struct value it is a member of (`r.x`), null for a static field or a class instance. */
        class Field(val name: String, val owner: String?, val isStatic: Boolean, val readonly: Boolean, val self: Boolean, val structOwner: Boolean, val through: Place?) : Place()
    }

    private fun placeOf(e: CSharpExpression?): Place = when (e) {
        null -> Place.Unknown
        is CSharpParenthesizedExpression -> placeOf(e.expression)
        is CSharpLiteralExpression, is CSharpBinaryExpression, is CSharpConditionalExpression, is CSharpCastExpression, is CSharpBaseObjectCreationExpression,
        is CSharpAnonymousFunctionExpression, is CSharpInterpolatedStringExpression, is CSharpTupleExpression, is CSharpDefaultExpression, is CSharpTypeOfExpression,
        is CSharpAwaitExpression, is CSharpCheckedExpression, is CSharpSwitchExpression, is CSharpQueryExpression, is CSharpWithExpression, is CSharpConditionalAccessExpression,
        is CSharpPostfixUnaryExpression, is CSharpArrayCreationExpression, is CSharpImplicitArrayCreationExpression, is CSharpCollectionExpression,
        is CSharpAnonymousObjectCreationExpression, is CSharpIsPatternExpression -> Place.Value
        is CSharpPrefixUnaryExpression -> if (e.operatorToken?.text == "*") Place.Fine else Place.Value
        // `rx = ref y` is the variable `rx` refers to from now on; `x = y` is a value
        is CSharpAssignmentExpression -> if (e.operatorToken?.text == "=" && e.right is CSharpRefExpression) Place.Unknown else Place.Value
        is CSharpInstanceExpression -> if (e.text == "this") Place.This(enclosingIsStruct(e)) else Place.Value
        is CSharpIdentifierName -> e.identifier?.let { leaf -> resolver.syntax.symbolAt(leaf)?.let(::placeOfLocal) ?: placeOfMember(leaf, receiver = null, self = true) } ?: Place.Unknown
        is CSharpMemberAccessExpression -> {
            val leaf = e.nameElement?.identifier
            val receiver = e.expression
            if (leaf == null || receiver == null) Place.Unknown
            else if (receiver is CSharpInstanceExpression && receiver.text == "this") placeOfMember(leaf, receiver = null, self = true)
            else placeOfMember(leaf, receiver, self = false)
        }
        is CSharpInvocationExpression -> calleeLeaf(e.expression)?.let { leaf ->
            val local = resolver.syntax.symbolAt(leaf)
            if (local != null) {
                if (local.kind == LocalSymbolKind.LOCAL_FUNCTION) (local.declaration.parent as? CSharpLocalFunctionStatement)?.let { byReturn(it.returnType) } ?: Place.Unknown else Place.Unknown
            } else placeOfCall(leaf)
        } ?: Place.Unknown
        is CSharpElementAccessExpression -> placeOfIndexer(e)
        else -> Place.Unknown
    }

    private fun enclosingIsStruct(at: PsiElement): Boolean {
        val type = PsiTreeUtil.getParentOfType(at, CSharpBaseTypeDeclaration::class.java) ?: return false
        return resolver.syntax.declaredType(type)?.kind.let { it == TypeKind.STRUCT || it == TypeKind.RECORD_STRUCT }
    }

    private fun placeOfLocal(symbol: LocalSymbol): Place {
        val declaration = symbol.declaration
        return when (symbol.kind) {
            LocalSymbolKind.PARAMETER -> {
                val parameter = declaration.parent as? CSharpParameter ?: return Place.Unknown
                val modifiers = parameter.modifiers.map { it.text }
                val kind = modifiers.firstOrNull { it == "ref" || it == "out" || it == "in" } ?: ""
                Place.Parameter(symbol.name, if (kind == "ref" && "readonly" in modifiers) "in" else kind)
            }
            LocalSymbolKind.LOCAL -> {
                val parent = declaration.parent
                when {
                    parent is CSharpQueryClause || parent is CSharpQueryContinuation || parent is CSharpJoinIntoClause -> Place.Range(symbol.name)
                    parent is CSharpVariableDeclarator -> {
                        val type = (parent.parent as? CSharpVariableDeclaration)?.type
                        if (type !is CSharpRefType) Place.Local(symbol.name, false, null)
                        // a ref local reassigned (`r = ref y`) refers to what the flow says: not followed
                        else if (symbol.references.any { r -> (r.parent?.parent as? CSharpAssignmentExpression)?.let { it.left == r.parent && it.right is CSharpRefExpression } == true }) Place.Local(symbol.name, true, Place.Unknown, type.readOnlyKeyword != null)
                        else Place.Local(symbol.name, true, (parent.initializer?.value as? CSharpRefExpression)?.expression?.let(::placeOf), type.readOnlyKeyword != null)
                    }
                    parent is CSharpForEachStatement -> Place.Local(symbol.name, parent.type is CSharpRefType, Place.Fine, (parent.type as? CSharpRefType)?.readOnlyKeyword != null)
                    else -> Place.Local(symbol.name, false, null)
                }
            }
            else -> Place.Unknown
        }
    }

    /** A member named by [leaf]: a field, or a property / method by how it returns; [receiver] the value before the dot (null: `this` or none). */
    private fun placeOfMember(leaf: PsiElement, receiver: CSharpExpression?, self: Boolean): Place {
        val symbol = resolver.resolve(leaf)?.single ?: return tupleElement(leaf, receiver)
        return when (symbol) {
            is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
                is CSharpVariableDeclarator, is CSharpFieldDeclaration -> {
                    val field = element as? CSharpFieldDeclaration ?: element.parent?.parent as? CSharpFieldDeclaration ?: return Place.Unknown
                    val modifiers = field.modifiers.map { it.text }
                    if ("const" in modifiers) return Place.Value
                    if (field.declaration?.type is CSharpRefType) return Place.Fine
                    val isStatic = "static" in modifiers
                    val ownerStruct = (symbol.owner as? SemanticType.Source)?.info?.kind.let { it == TypeKind.STRUCT || it == TypeKind.RECORD_STRUCT }
                    Place.Field(leaf.text, symbol.owner?.let { CSharpTypeDisplay.display(it, qualified = false) }, isStatic, "readonly" in modifiers, self && !isStatic, ownerStruct, through(receiver, isStatic))
                }
                is CSharpPropertyDeclaration -> byReturn(element.type)
                is CSharpIndexerDeclaration -> byReturn(element.type)
                else -> Place.Unknown
            }
            is CSharpSymbol.LibraryMember -> {
                val member = symbol.member
                when (member.kind) {
                    IndexedMemberKind.FIELD -> Place.Field(member.name, member.type.simpleName, member.isStatic, member.isReadOnly, self && !member.isStatic, member.type.kind == IndexedTypeKind.STRUCT, through(receiver, member.isStatic))
                    IndexedMemberKind.CONSTANT -> Place.Value
                    IndexedMemberKind.PROPERTY -> byReturn(member)
                    else -> Place.Unknown   // a method group, an event
                }
            }
            else -> Place.Unknown
        }
    }

    /** `t.Alice` of a `(int Alice, int Bob) t`: a field of the `ValueTuple` struct, which has no symbol of its own. */
    private fun tupleElement(leaf: PsiElement, receiver: CSharpExpression?): Place {
        val type = receiver?.let(resolver::typeOf) as? SemanticType.Library ?: return Place.Unknown
        if (type.tupleNames?.contains(leaf.text) != true) return Place.Unknown
        return Place.Field(leaf.text, "ValueTuple", isStatic = false, readonly = false, self = false, structOwner = true, through = placeOf(receiver))
    }

    /** The struct value a field is read through (`r.x`): [receiver] when its type is a struct; null for a static field or a class instance. */
    private fun through(receiver: CSharpExpression?, isStatic: Boolean): Place? {
        if (receiver == null || isStatic) return null
        val struct = when (val type = resolver.typeOf(receiver)) {
            is SemanticType.Source -> type.info.kind == TypeKind.STRUCT || type.info.kind == TypeKind.RECORD_STRUCT
            is SemanticType.Library -> type.type.kind == IndexedTypeKind.STRUCT
            else -> return Place.Unknown
        }
        return if (struct) placeOf(receiver) else null
    }

    private fun byReturn(type: CSharpType?): Place = when {
        type !is CSharpRefType -> Place.Value
        type.readOnlyKeyword != null -> Place.ReadOnlyRef
        else -> Place.Fine
    }

    private fun byReturn(member: IndexedMember): Place = when {
        member.typeRef !is IndexedTypeRef.ByRef -> Place.Value
        member.isRefReadOnly -> Place.ReadOnlyRef
        else -> Place.Fine
    }

    /** A call of a method: by how its overloads return, when they all agree. */
    private fun placeOfCall(leaf: PsiElement): Place {
        val symbols = resolver.resolve(leaf)?.symbols ?: return Place.Unknown
        val places = symbols.map { symbol ->
            when (symbol) {
                is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.let { byReturn(it.returnType) } ?: return Place.Unknown
                is CSharpSymbol.LibraryMember -> if (symbol.member.kind.isCallable) byReturn(symbol.member) else return Place.Unknown
                else -> return Place.Unknown
            }
        }.distinct()
        return places.singleOrNull() ?: Place.Unknown
    }

    /** `x[i]`: an array element is a variable; the indexers of the type of `x`, when they all return alike, say the rest. */
    private fun placeOfIndexer(e: CSharpElementAccessExpression): Place {
        val receiver = e.expression ?: return Place.Unknown
        val places = when (val type = resolver.typeOf(receiver)) {
            is SemanticType.ArrayOf -> return Place.Fine
            is SemanticType.Source -> type.info.parts.flatMap { part -> (part.element() as? CSharpTypeDeclaration)?.members.orEmpty().filterIsInstance<CSharpIndexerDeclaration>().map { byReturn(it.type) } }
            is SemanticType.Library -> resolver.session.libraryMembers(resolver.assemblies, type.type).values.flatten().filter { it.member.kind == IndexedMemberKind.INDEXER }.map { byReturn(it.member) }
            else -> return Place.Unknown
        }.distinct()
        return places.singleOrNull() ?: Place.Unknown
    }
}
