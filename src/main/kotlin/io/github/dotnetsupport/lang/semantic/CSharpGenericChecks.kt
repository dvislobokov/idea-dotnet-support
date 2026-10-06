package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpTypePositions
import io.github.dotnetsupport.lang.TypeKind

/**
 * Type arguments the compiler rejects: CS0305 / CS0308 (a generic type or method given another number of type arguments, a non-generic one
 * given some; in a type position and left of a member access in code), and the constraints of the type parameter an argument stands for —
 * CS0452 (`class`), CS0453 (`struct`), CS0311 / CS0315 (a constraint type it does not convert to by reference / boxing), CS0310 (`new()`) —
 * declared in the sources or kept by the index of an assembly; for written type arguments and for the ones a call to the only method of its
 * name surely infers (`Make(5)`).
 *
 * Precision first, as [CSharpSemanticChecks]: an argument that mentions a type parameter, is not fully known, or is a partial type a
 * generator may add to says nothing; neither does a constraint that does not resolve (`unmanaged`, `notnull`), nor a method call with
 * overloads the arguments do not tell apart. Spans as Roslyn's: in code the type argument (a call: its name with the type arguments);
 * in the signature of a member the name of the member or parameter, as Roslyn checks those after binding the declaration. A type nested
 * in a generic one and a method of a generic type are named with the arguments the reference gives the type around (`Outer<int>.Inner<U>`).
 */
internal class CSharpGenericChecks(private val checks: CSharpSemanticChecks, private val r: CSharpNameResolver, private val report: (CSharpSemanticProblem) -> Unit) {

    /** What a type parameter demands; [types] null when a constraint is not known (then nothing is said of it). */
    private class Bound(val name: String, val isClass: Boolean, val isStruct: Boolean, val hasNew: Boolean, val types: List<SemanticType>?)

    fun check(name: CSharpSimpleName) {
        val leaf = name.identifier ?: return
        if (leaf.textLength == 0 || r.syntax.symbolAt(leaf) != null || PsiTreeUtil.getParentOfType(name, CSharpUsingDirective::class.java) != null) return
        val written = (name as? CSharpGenericName)?.typeArgumentList?.arguments
        if (written != null && (written.isEmpty() || written.any { it is CSharpOmittedTypeArgument })) return
        val call = invocationOf(name)
        if (call != null) {
            if (written != null) checkMethod(name, leaf, written, call) else checkInferred(name, leaf, call)
            return
        }
        // `Box<int, int>.Count`, `Box.Count`: a type left of a member access in code
        val inCode = typeInExpression(name)
        if (written == null) {
            if ((NativeCSharpTypePositions.isType(name) || inCode) && r.resolve(leaf) == null) checkTypeArity(name, leaf.text, 0)
            return
        }
        val found = r.resolve(leaf)?.symbols.orEmpty()
        if (found.isEmpty()) {
            if (NativeCSharpTypePositions.isType(name) || (name.parent as? CSharpQualifiedName)?.left == name || inCode) checkTypeArity(name, leaf.text, written.size)
            return
        }
        val type = found.singleOrNull() ?: return
        if (type !is CSharpSymbol.SourceType && type !is CSharpSymbol.LibraryType) return
        val ranges = argumentRanges(name as CSharpGenericName, written) ?: return
        val outer = if (type is CSharpSymbol.SourceType && nestedInGeneric(type.info)) outerOf(name) ?: return else null
        val bounds = typeBounds(type, written.map(r::resolveType), outer) ?: return
        val shown = typeDisplay(type, outer) ?: return
        checkArguments(written.map(r::resolveType), written.map { it is CSharpNullableType }, bounds, shown, ranges)
    }

    /** `Box<int>` of `Box<int>.Count`, `N.Box<int>` of `N.Box<int>.Count`: a name that can only be a type (or a namespace) in code. */
    private fun typeInExpression(name: CSharpSimpleName): Boolean {
        val parent = name.parent as? CSharpMemberAccessExpression ?: return false
        if (parent.expression == name) return true
        return parent.nameElement == name && (parent.parent as? CSharpMemberAccessExpression)?.expression == parent
    }

    /** `Holder<int>` of `Holder<int>.Inner<string>`: the type around a type nested in a generic one, as the reference gives its arguments. */
    private fun outerOf(name: CSharpSimpleName): SemanticType.Source? {
        val parent = name.parent
        val whole = when {
            parent is CSharpQualifiedName && parent.right == name -> r.resolveType(parent)
            parent is CSharpMemberAccessExpression && parent.nameElement == name -> (r.qualifier(parent) as? CSharpNameResolver.Qualifier.Type)?.type
            name is CSharpType -> r.resolveType(name)
            else -> null
        }
        return (whole as? SemanticType.Source)?.outer?.takeIf(::complete)
    }

    private fun nestedInGeneric(info: io.github.dotnetsupport.lang.TypeInfo): Boolean {
        var at = info.parts.firstOrNull()?.element()?.parent
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) return true
            at = at.parent
        }
        return false
    }

    /** The call [name] is the callee of: `M<T>(...)`, `x.M<T>(...)`, `x?.M<T>(...)`. */
    private fun invocationOf(name: CSharpSimpleName): CSharpInvocationExpression? {
        val parent = name.parent
        val callee: PsiElement = when {
            parent is CSharpInvocationExpression -> name
            parent is CSharpMemberAccessExpression && parent.nameElement == name -> parent
            parent is CSharpMemberBindingExpression && parent.nameElement == name -> parent
            else -> return null
        }
        return (callee.parent as? CSharpInvocationExpression)?.takeIf { it.expression == callee }
    }

    // ---- CS0305, CS0308

    /** A type name [written] type arguments fit no type of: CS0305 / CS0308 when exactly one type of that name and another arity is there. */
    private fun checkTypeArity(name: CSharpSimpleName, text: String, written: Int) {
        if (checks.generated || !r.importsKnown(name) || r.aliasNamed(name, text)) return
        val parent = name.parent
        val qualifier = when {
            parent is CSharpQualifiedName && parent.right == name -> parent.left?.let(r::qualifier) ?: return
            parent is CSharpMemberAccessExpression && parent.nameElement == name -> parent.expression?.let(r::qualifier) ?: return
            parent is CSharpAliasQualifiedName -> return
            else -> null
        }
        val lookup: (Int) -> List<CSharpSymbol> = when (qualifier) {
            null -> {
                // a nested type of that arity may hide in a base that is not known
                for (info in r.syntax.enclosingTypes(name)) if (!checks.isKnown(r.selfType(info))) return
                { arity -> r.typeOrNamespace(name, text, arity) }
            }
            is CSharpNameResolver.Qualifier.Namespace -> { arity -> r.typesIn(qualifier.name, text, arity) }
            is CSharpNameResolver.Qualifier.Type -> {
                val type = qualifier.type
                if (!checks.isKnown(type) || type is SemanticType.Source && checks.isPartial(type.info)) return
                { arity -> r.nestedTypes(type, text, arity) }
            }
            else -> return
        }
        if (lookup(written).isNotEmpty()) return
        val others = (0..MAX_ARITY).filter { it != written }.flatMap(lookup).distinct()
        val type = others.singleOrNull() ?: return
        val arity = when (type) {
            is CSharpSymbol.SourceType -> type.info.arity
            is CSharpSymbol.LibraryType -> type.type.ownArity
            else -> return
        }
        val shown = typeDisplay(type) ?: return
        if (arity == 0) report("CS0308", "The non-generic type '$shown' cannot be used with type arguments", name.textRange)
        else report("CS0305", "Using the generic type '$shown' requires $arity type arguments", name.textRange)
    }

    // ---- calls

    /**
     * `M<A>(...)`: CS0305 / CS0308 when no method of the name has as many type parameters (one generic method of the name, or one
     * non-generic one, to name in the message); else the constraints of the one method of that arity the arguments can call.
     */
    private fun checkMethod(name: CSharpSimpleName, leaf: PsiElement, written: List<CSharpType>, call: CSharpInvocationExpression) {
        val all = checks.overloads(name, leaf.text, null) ?: return
        if (all.isEmpty()) return
        val arity = written.size
        val same = all.filter { r.expressions.methodArity(it) == arity }
        if (same.isEmpty()) {
            // no method of that arity: extension methods of the name are looked for, even through a type (`Tools.Count<int>()`: CS1929)
            if (extensionInScope(leaf.text, name)) return
            val generic = all.filter { r.expressions.methodArity(it) > 0 }
            val method = (if (generic.isEmpty()) all else generic).singleOrNull() ?: return
            val shown = methodDisplay(method) ?: return
            val wanted = r.expressions.methodArity(method)
            if (wanted == 0) report("CS0308", "The non-generic method '$shown' cannot be used with type arguments", name.textRange)
            else report("CS0305", "Using the generic method '$shown' requires $wanted type arguments", name.textRange)
            return
        }
        val arguments = call.argumentList?.arguments ?: return
        // the constraints are checked of the candidates the arguments fit; Roslyn names the failure only when there is one
        val fitting = same.filter { symbol -> r.signature(symbol, false)?.let { r.fits(it, arguments) } ?: return }
        val method = fitting.singleOrNull() ?: return
        if (r.isExtension(method)) return
        val bounds = methodBounds(method, written.map(r::resolveType)) ?: return
        val shown = methodDisplay(method) ?: return
        checkArguments(written.map(r::resolveType), written.map { it is CSharpNullableType }, bounds, shown, List(written.size) { listOf(name.textRange) })
    }

    /**
     * `Make(5)` of `Make<T>(T value) where T : class`: the type arguments inferred, checked as written ones (Roslyn names the method on its
     * name, the type without a nullable annotation). Only for the one method of the name, and only where inference is surely what is
     * computed here: every parameter that mentions a type parameter of the method gets an argument of a known type that is exactly that
     * parameter's type with the inferred arguments put in (no conversion, no lambda, no `null`, no `ref`, no `params` of them).
     */
    private fun checkInferred(name: CSharpSimpleName, leaf: PsiElement, call: CSharpInvocationExpression) {
        val method = r.resolve(leaf)?.symbols?.singleOrNull() ?: return
        if (method !is CSharpSymbol.SourceMember && method !is CSharpSymbol.LibraryMember || r.isExtension(method)) return
        val arity = r.expressions.methodArity(method)
        if (arity == 0 || method is CSharpSymbol.SourceMember && method.element !is CSharpMethodDeclaration) return
        // the constraints first: a method without any says nothing, and LINQ's have none
        val open = methodBounds(method, List(arity) { null }) ?: return
        if (open.none { it.isClass || it.isStruct || it.hasNew || it.types == null || it.types.isNotEmpty() }) return
        val all = checks.overloads(name, leaf.text, null) ?: return
        if (all.size != 1 || extensionInScope(leaf.text, name)) return
        val parameters = r.signature(method, false) ?: return
        val arguments = call.argumentList?.arguments ?: return
        if (!r.fits(parameters, arguments) || arguments.size > parameters.size || arguments.any { it.nameColon != null || it.refKindKeyword != null }) return
        val inferred = r.expressions.typeArguments(method, call, name)
        if (inferred.size != arity || inferred.any { !complete(it) }) return
        val mentioned = HashSet<Int>()
        for ((i, parameter) in parameters.withIndex()) {
            val type = parameter.type() ?: return
            if (!mentionsMethodParameter(type, mentioned)) continue
            if (parameter.isParams || parameter.byRef) return
            val expression = arguments.getOrNull(i)?.expression ?: return
            if (expression is CSharpAnonymousFunctionExpression || expression.elementType == SyntaxKind.NullLiteralExpression) return
            val argument = r.typeOf(expression)?.let(CSharpTypeDisplay::display) ?: return
            val substituted = r.replace(type) { p -> if (p.ofMethod) inferred.getOrNull(p.index) else p }?.let(CSharpTypeDisplay::display) ?: return
            if (argument != substituted) return
        }
        if (mentioned.size != arity) return
        val bounds = methodBounds(method, inferred) ?: return
        val shown = methodDisplay(method) ?: return
        checkArguments(inferred, List(arity) { false }, bounds, shown, List(arity) { listOf(leaf.textRange) })
    }

    /**
     * An extension method named [text] a call at [site] may find: one of the solution, or one of a static class in a namespace [site] imports
     * (or of a `using static` type). Roslyn looks nowhere else: `Vector.Store` of System.Numerics, `EF.Functions.Like` of EF Core in a file
     * without their `using` change nothing of `Make.Store<int, int>(1)`. Imports that do not resolve: anything may be there.
     */
    private fun extensionInScope(text: String, site: PsiElement): Boolean {
        if (r.session.sourceExtensions(text).isNotEmpty() || !r.importsKnown(site)) return true
        val members = r.assemblies.membersNamed(text).filter { it.kind == IndexedMemberKind.EXTENSION_METHOD }
        if (members.isEmpty()) return false
        val visible = r.visibleNamespaces(site)
        val statics = r.staticTypes(site).mapNotNullTo(HashSet()) { (it as? SemanticType.Library)?.type?.fullName }
        return members.any { it.type.namespace in visible || it.type.fullName in statics }
    }

    /** Whether [type] mentions a type parameter of a method; their indexes go into [into]. */
    private fun mentionsMethodParameter(type: SemanticType?, into: MutableSet<Int>): Boolean = when (type) {
        null -> false
        is SemanticType.Parameter -> type.ofMethod.also { if (it) into += type.index }
        is SemanticType.ArrayOf -> mentionsMethodParameter(type.element, into)
        is SemanticType.Library -> type.arguments.map { mentionsMethodParameter(it, into) }.any { it }
        is SemanticType.Source -> (type.arguments.map { mentionsMethodParameter(it, into) } + listOf(mentionsMethodParameter(type.outer, into))).any { it }
    }

    // ---- constraints

    /** [annotated]: the argument is written with `?` (Roslyn writes the annotation of `string?` too: not modeled, nothing is said). */
    private fun checkArguments(arguments: List<SemanticType?>, annotated: List<Boolean>, bounds: List<Bound>, shown: String, ranges: List<List<TextRange>>) {
        if (bounds.size != arguments.size) return
        for ((i, argument) in arguments.withIndex()) {
            if (argument == null || !complete(argument)) continue
            if (annotated[i] && !r.isNullable(argument)) continue
            for (range in ranges[i]) checkArgument(argument, bounds[i], shown, range)
        }
    }

    /** Roslyn's order (ConstraintsHelper): `class`, `struct` (each ends the check), the constraint types, `new()`. */
    private fun checkArgument(argument: SemanticType, bound: Bound, shown: String, range: TextRange) {
        if (argument is SemanticType.Source && checks.isPartial(argument.info) || argument !is SemanticType.ArrayOf && !checks.isKnown(argument)) return
        val reference = r.overloads.isReference(argument) ?: return
        val nullable = r.isNullable(argument)
        val short = CSharpTypeDisplay.display(argument, qualified = false) ?: return
        val full = CSharpTypeDisplay.display(argument) ?: return
        val where = "in order to use it as parameter '${bound.name}' in the generic type or method '$shown'"
        if (bound.isClass && !reference) return report("CS0452", "The type '$short' must be a reference type $where", range)
        if (bound.isStruct && (reference || nullable)) return report("CS0453", "The type '$short' must be a non-nullable value type $where", range)
        val types = bound.types ?: return
        for (constraint in types) {
            // `int?` against an interface is CS0313, of its own
            if (nullable) return
            if (r.overloads.classify(argument, constraint, userDefined = false) != CSharpNameResolver.Conversion.NONE) continue
            val to = CSharpTypeDisplay.display(constraint) ?: continue
            val head = "The type '$full' cannot be used as type parameter '${bound.name}' in the generic type or method '$shown'."
            if (reference) report("CS0311", "$head There is no implicit reference conversion from '$full' to '$to'.", range)
            else report("CS0315", "$head There is no boxing conversion from '$full' to '$to'.", range)
        }
        if (bound.hasNew && !bound.isStruct && newable(argument) == false) {
            report("CS0310", "'$short' must be a non-abstract type with a public parameterless constructor $where", range)
        }
    }

    /** A known type without type parameters or unknown parts in it. */
    private fun complete(type: SemanticType?): Boolean = when (type) {
        null, is SemanticType.Parameter -> false
        is SemanticType.ArrayOf -> complete(type.element)
        is SemanticType.Library -> type.arguments.all(::complete)
        is SemanticType.Source -> type.arguments.all(::complete) && (type.outer == null || complete(type.outer))
    }

    /** Whether [type] satisfies `new()`: a value type, or a non-abstract class with a public constructor without parameters; null when not known. */
    private fun newable(type: SemanticType): Boolean? = when (type) {
        // an array has no parameterless constructor
        is SemanticType.ArrayOf -> false
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.STRUCT, IndexedTypeKind.ENUM -> true
            IndexedTypeKind.INTERFACE, IndexedTypeKind.DELEGATE -> false
            IndexedTypeKind.CLASS -> if (type.type.isStatic) null else if (type.type.isAbstract) false else {
                val constructors = type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic }
                if (constructors.isEmpty()) null else constructors.any { !it.isProtected && r.session.parameters(it).isEmpty() }
            }
            else -> null
        }
        is SemanticType.Source -> when (type.info.kind) {
            TypeKind.STRUCT, TypeKind.RECORD_STRUCT, TypeKind.ENUM -> true
            TypeKind.INTERFACE, TypeKind.DELEGATE -> false
            TypeKind.CLASS, TypeKind.RECORD -> sourceNewable(type)
            else -> null
        }
        else -> null
    }

    private fun sourceNewable(type: SemanticType.Source): Boolean? {
        if (checks.isPartial(type.info)) return null
        val declarations = type.info.parts.map { part ->
            val declaration = part.element() as? CSharpTypeDeclaration ?: return null
            (declaration.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
            declaration
        }
        if (declarations.any { d -> d.modifiers.any { it.text == "abstract" } }) return false
        val constructors = declarations.flatMap { d -> d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } } }
        val primary = declarations.mapNotNull { it.parameterList }
        if (constructors.isEmpty() && primary.isEmpty()) return true
        return primary.any { it.parameters.isEmpty() } || constructors.any { c -> c.modifiers.any { it.text == "public" } && c.parameterList?.parameters.isNullOrEmpty() }
    }

    /** The type parameters of the generic type [symbol] with their constraints, [arguments] put for the type parameters in them. */
    private fun typeBounds(symbol: CSharpSymbol, arguments: List<SemanticType?>, outer: SemanticType.Source? = null): List<Bound>? = when (symbol) {
        is CSharpSymbol.LibraryType -> {
            val type = symbol.type
            if (type.declaringType?.let { it.arity > 0 } == true) null
            else type.typeParameters.map { p -> libraryBound(p, p.constraints.map { r.fromRef(it, arguments) }) }
        }
        is CSharpSymbol.SourceType -> {
            // the clauses of every part, each read in its own file (a file the pass may not load says nothing)
            val declarations = symbol.info.parts.map { part -> part.element()?.takeIf { (it.containingFile as? CSharpFile)?.let(r.session::reachable) != null } ?: return null }
            val first = declarations.firstOrNull() ?: return null
            val names = r.typeParameterNames(first)
            if (names.size != symbol.info.arity) null
            else sourceBounds(names, declarations.flatMap { clausesOf(it) ?: return null }) { p ->
                when {
                    !p.ofMethod && p.owner in declarations -> arguments.getOrNull(names.indexOf(p.name)) ?: p
                    // `where U : T` of a type nested in `Outer<T>`: the arguments of the outer type as the reference gives them
                    !p.ofMethod && outer != null -> r.substitute(p, outer) ?: p
                    else -> p
                }
            }
        }
        else -> null
    }

    private fun methodBounds(symbol: CSharpSymbol, arguments: List<SemanticType?>): List<Bound>? = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.typeParameters.map { p -> libraryBound(p, p.constraints.map { r.fromRef(it, symbol.declaringArguments, arguments) }) }
        is CSharpSymbol.SourceMember -> {
            val method = (symbol.element as? CSharpMethodDeclaration)?.takeIf { (it.containingFile as? CSharpFile)?.let(r.session::reachable) != null }
            val owner = symbol.owner as? SemanticType.Source
            if (method == null) null
            else sourceBounds(r.typeParameterNames(method), method.constraintClauses) { p ->
                when {
                    p.ofMethod && p.owner == method -> arguments.getOrNull(r.typeParameterNames(method).indexOf(p.name)) ?: p
                    !p.ofMethod && owner != null -> r.substitute(p, owner) ?: p
                    else -> p
                }
            }
        }
        else -> null
    }

    private fun libraryBound(p: io.github.dotnetsupport.index.IndexedTypeParameter, constraints: List<SemanticType?>): Bound {
        val kept = p.constraints.zip(constraints).filter { (ref, _) -> !(ref is IndexedTypeRef.Named && (ref.fullName == OBJECT || ref.fullName == VALUE_TYPE)) }.map { it.second }
        val types = if (p.isUnmanaged || kept.any { !complete(it) }) null else kept.map { it!! }
        return Bound(p.name, p.isClass, p.isStruct, p.hasNew, types)
    }

    /** The bounds of [names] as [clauses] write them, each resolved in its file and substituted by [substitute]. */
    private fun sourceBounds(names: List<String>, clauses: List<CSharpTypeParameterConstraintClause>, substitute: (SemanticType.Parameter) -> SemanticType?): List<Bound>? {
        return names.map { name ->
            var isClass = false
            var isStruct = false
            var hasNew = false
            var types: MutableList<SemanticType>? = ArrayList()
            for (clause in clauses.filter { it.nameElement?.identifier?.text == name }) for (constraint in clause.constraints) when (constraint) {
                is CSharpClassOrStructConstraint -> if (constraint.classOrStructKeyword?.text == "struct") isStruct = true else isClass = true
                is CSharpConstructorConstraint -> hasNew = true
                is CSharpDefaultConstraint -> {}
                is CSharpTypeConstraint -> {
                    val resolver = (constraint.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
                    val type = constraint.type?.let(resolver::resolveType)?.let { r.replace(it, substitute) }
                    if (type == null || !complete(type)) types = null else types?.add(type)
                }
                else -> types = null
            }
            Bound(name, isClass, isStruct, hasNew, types?.filter { r.definitionName(it) != OBJECT })
        }
    }

    private fun clausesOf(declaration: PsiElement): List<CSharpTypeParameterConstraintClause>? = when (declaration) {
        is CSharpTypeDeclaration -> declaration.constraintClauses
        is CSharpDelegateDeclaration -> declaration.constraintClauses
        else -> null
    }

    // ---- spans and texts

    /**
     * Where Roslyn reports a constraint of the type argument [i] of [name]: the argument itself in code (and in the signature of a
     * delegate, a local function, a lambda); the name of a field, property, method, parameter or type whose signature or base list has
     * it (each declarator of a field, `this` of an indexer, the operator token, the type of a conversion operator; a record's parameter also
     * on the record's name), the type parameter a constraint clause constrains. Null where that is not known.
     */
    private fun argumentRanges(name: CSharpGenericName, written: List<CSharpType>): List<List<TextRange>>? {
        var top: PsiElement = name
        while (true) {
            val parent = top.parent
            if (parent is CSharpTypeArgumentList || parent is CSharpQualifiedName || parent is CSharpAliasQualifiedName || parent is CSharpArrayType ||
                parent is CSharpNullableType || parent is CSharpGenericName || parent is CSharpTupleElement || parent is CSharpTupleType || parent is CSharpRefType ||
                parent is CSharpPointerType || parent is CSharpScopedType) top = parent else break
        }
        val inCode = written.map { listOf(it.textRange) }
        val at: List<PsiElement?> = when (val owner = top.parent) {
            is CSharpVariableDeclaration -> if (owner.parent is CSharpBaseFieldDeclaration) owner.variables.map { it.identifier } else return inCode
            is CSharpPropertyDeclaration -> listOf(if (owner.type == top) owner.identifier else null)
            is CSharpEventDeclaration -> listOf(if (owner.type == top) owner.identifier else null)
            is CSharpIndexerDeclaration -> listOf(if (owner.type == top) owner.thisKeyword else null)
            is CSharpMethodDeclaration -> listOf(if (owner.returnType == top) owner.identifier else null)
            is CSharpOperatorDeclaration -> listOf(if (owner.returnType == top) owner.operatorToken else null)
            is CSharpConversionOperatorDeclaration -> listOf(if (owner.type == top) top else null)
            is CSharpParameter -> if (owner.type != top) listOf(null) else when (val function = owner.parent?.parent) {
                is CSharpMethodDeclaration, is CSharpConstructorDeclaration, is CSharpIndexerDeclaration, is CSharpOperatorDeclaration,
                is CSharpConversionOperatorDeclaration -> listOf(owner.identifier)
                // a record's parameter is also checked on the record's name (its synthesized members), an own property of the name or not
                is CSharpRecordDeclaration -> listOf(function.identifier, owner.identifier)
                is CSharpTypeDeclaration -> listOf(owner.identifier)
                is CSharpLocalFunctionStatement, is CSharpAnonymousFunctionExpression, is CSharpDelegateDeclaration -> return inCode
                else -> listOf(null)
            }
            is CSharpBaseType -> listOf((owner.parent?.parent as? CSharpTypeDeclaration)?.identifier)
            is CSharpDelegateDeclaration, is CSharpLocalFunctionStatement, is CSharpAttribute -> return inCode
            // `class Gen<T> where T : Kennel<string>`: on the `T` of the type parameter list
            is CSharpTypeConstraint -> listOf((owner.parent as? CSharpTypeParameterConstraintClause)?.let { clause ->
                val list = when (val declaration = clause.parent) {
                    is CSharpTypeDeclaration -> declaration.typeParameterList
                    is CSharpMethodDeclaration -> declaration.typeParameterList
                    else -> null
                }
                list?.parameters?.firstOrNull { it.identifier?.text == clause.nameElement?.identifier?.text }?.identifier
            })
            is CSharpExplicitInterfaceSpecifier -> listOf(null)
            else -> return if (PsiTreeUtil.getParentOfType(top, CSharpBlock::class.java, CSharpArrowExpressionClause::class.java, CSharpEqualsValueClause::class.java,
                    CSharpAnonymousFunctionExpression::class.java, CSharpGlobalStatement::class.java, CSharpAttributeArgumentList::class.java) != null) inCode else null
        }
        val ranges = at.map { it?.textRange ?: return null }
        return List(written.size) { ranges }
    }

    /** `Box<T>`, `Outer.Inner<T>`, `Dictionary<TKey, TValue>`: a generic type as Roslyn's messages name it. */
    private fun typeDisplay(symbol: CSharpSymbol, outer: SemanticType.Source? = null): String? = when (symbol) {
        // `Holder<int>.Inner<U>`: the type around with the arguments the reference gives it
        is CSharpSymbol.SourceType -> if (outer == null) sourceTypeDisplay(symbol.info) else {
            val declaration = symbol.info.parts.firstOrNull()?.element()
            val own = r.typeParameterNames(declaration)
            val name = ((declaration as? CSharpBaseTypeDeclaration)?.identifier ?: (declaration as? CSharpDelegateDeclaration)?.identifier)?.text
            CSharpTypeDisplay.display(outer, qualified = false)?.let { o -> name?.let { "$o.$it" + if (own.isEmpty()) "" else own.joinToString(", ", "<", ">") } }
        }
        is CSharpSymbol.LibraryType -> libraryTypeDisplay(symbol.type)
        else -> null
    }

    /** The names of the declaration and of the types around it; null inside a generic type (Roslyn writes its type parameters there). */
    private fun sourceTypeDisplay(info: io.github.dotnetsupport.lang.TypeInfo): String? {
        val declaration = info.parts.firstOrNull()?.element() ?: return null
        val own = r.typeParameterNames(declaration)
        var at: PsiElement? = declaration.parent
        val names = arrayListOf(((declaration as? CSharpBaseTypeDeclaration)?.identifier ?: (declaration as? CSharpDelegateDeclaration)?.identifier)?.text ?: return null)
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) return null
            names += at.identifier?.text ?: return null
            at = at.parent
        }
        return names.asReversed().joinToString(".") + if (own.isEmpty()) "" else own.joinToString(", ", "<", ">")
    }

    private fun libraryTypeDisplay(type: IndexedType): String? {
        if (type.declaringType?.let { it.arity > 0 } == true) return null
        val own = type.typeParameters.takeLast(type.ownArity).map { it.name }
        return type.name + if (own.isEmpty()) "" else own.joinToString(", ", "<", ">")
    }

    /** `F.Make<T>()`, `Enum.GetValues<TEnum>()`, `F.Take<T>(T, int)`: a method as Roslyn's messages name it. */
    private fun methodDisplay(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.SourceMember -> checks.methodDisplay(symbol) ?: genericOwnerMethodDisplay(symbol)
        is CSharpSymbol.LibraryMember -> {
            val m = symbol.member
            // `List<int>.ConvertAll<TOutput>(Converter<int, TOutput>)`: a method of a generic type as the receiver gives its arguments
            val declaring = if (m.type.arity == 0) emptyList() else symbol.declaringArguments.takeIf { it.size == m.type.arity && it.all(::complete) }
            val owner = if (declaring == null) null else if (m.type.arity == 0) libraryTypeDisplay(m.type) else CSharpTypeDisplay.display(SemanticType.Library(m.type, declaring), qualified = false)
            val own = m.typeParameters.map { it.name }
            val methodArguments = own.mapIndexed { i, n -> SemanticType.Parameter(n, null, i, true) }
            val parameters = r.session.parameters(m).map { p ->
                if (p.isByReference || p.isParams || p.isOptional || p.hasDefault || p.isThis) return null
                CSharpTypeDisplay.display(r.fromRef(p.typeRef, declaring.orEmpty(), methodArguments), qualified = false) ?: return null
            }
            owner?.let { "$it.${m.name}${if (own.isEmpty()) "" else own.joinToString(", ", "<", ">")}(${parameters.joinToString(", ")})" }
        }
        else -> null
    }

    /** `Holder<string>.Put<U>(string, U)`: a method of a generic type of the solution, its type's parameters as the receiver gives them. */
    private fun genericOwnerMethodDisplay(symbol: CSharpSymbol.SourceMember): String? {
        val method = symbol.element as? CSharpMethodDeclaration ?: return null
        val owner = (symbol.owner as? SemanticType.Source)?.takeIf(::complete) ?: return null
        val resolver = (method.containingFile as? CSharpFile)?.let(r.session::reachable) ?: return null
        val shown = CSharpTypeDisplay.display(owner, qualified = false) ?: return null
        val parameters = method.parameterList?.parameters.orEmpty().map { p ->
            if (p.default != null || p.modifiers.any { it.text == "this" }) return null
            val type = p.type?.let(resolver::resolveType)?.let { r.substitute(it, owner) }?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
            (p.modifiers.map { it.text } + type).joinToString(" ")
        }
        val typeParameters = r.typeParameterNames(method).takeIf { it.isNotEmpty() }?.joinToString(", ", "<", ">").orEmpty()
        return "$shown.${method.identifier?.text ?: return null}$typeParameters(${parameters.joinToString(", ")})"
    }

    private fun report(code: String, message: String, range: TextRange) = report(CSharpSemanticProblem(code, message, range))

    private companion object {
        const val MAX_ARITY = 8
        const val OBJECT = "System.Object"
        const val VALUE_TYPE = "System.ValueType"
    }
}
