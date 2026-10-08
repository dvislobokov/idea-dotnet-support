package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStub
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.index.AssemblyIndexSet
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpColors
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpLeaves
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.Member
import io.github.dotnetsupport.lang.NativeCSharpResolver
import io.github.dotnetsupport.lang.NativeCSharpScopes
import io.github.dotnetsupport.lang.NativeCSharpStubDeclarations
import io.github.dotnetsupport.lang.NativeCSharpTypePositions
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypePart
import io.github.dotnetsupport.msbuild.GlobalUsing
import java.util.IdentityHashMap

/**
 * Name resolution of C# (layer 11a of CSHARP_PSI_MIGRATION.md, task C1) on csharp-psi's tree, the stubs of the solution and the index of the
 * referenced assemblies, without the language server. On top of the syntactic resolver ([NativeCSharpResolver]: scopes, members of the
 * types of the solution) it follows the lookup of the C# specification (§12.8.4 simple names, §7.6 namespace and type names):
 *
 * - a simple name: a local of the scopes; a member of the enclosing types and their bases (of the solution and of assemblies); then, from
 *   the innermost namespace outwards, a type or namespace of that namespace, an alias of its `using` directives, a type of the namespaces they
 *   import (`global using` of the compilation and the `Using` items of the project at the outermost level), members of `using static` types;
 * - `A.B`: a namespace or type of the namespace `A`; a member or nested type of the type `A` (static members, enum members); a member of the
 *   type of the expression `A` — a local, parameter, field or property with a declared type, `var` of a simple initializer, `this`, `base`,
 *   `new T()`, a cast, a call whose method is known — inherited ones included, with the type arguments substituted, then extension methods
 *   of the imported namespaces; `global::`, attributes with the `Attribute` suffix, named arguments, object initializers;
 * - overloads: the ones whose parameters fit the number of arguments and, if that leaves several, the known types of the arguments; more
 *   than one left — all are candidates ([CSharpResolution.symbols]), picking is layer 11d.
 *
 * One instance per file of a [CSharpSemanticSession]; answers are cached for the life of the session.
 */
class CSharpNameResolver internal constructor(val file: CSharpFile, internal val session: CSharpSemanticSession) {
    val syntax: NativeCSharpResolver = NativeCSharpResolver(file)
    val assemblies: AssemblyIndexSet by lazy { CSharpSemanticEnvironment.assemblies(file) }
    private val names = IdentityHashMap<PsiElement, CSharpResolution?>()
    private val types = IdentityHashMap<PsiElement, SemanticType?>()
    // apart from [types]: a simple name is a type (resolveType) and an expression (typeOf) with different answers
    private val expressionTypes = IdentityHashMap<PsiElement, SemanticType?>()
    // the types of locals, parameters and range variables by their declaration (a lambda's parameter is typed through the call it is in)
    private val localTypes = IdentityHashMap<PsiElement, SemanticType?>()
    private val busy = HashSet<PsiElement>()
    // apart from [busy]: a simple name is also the expression whose type is asked, typeOf(name) resolves the same element
    private val busyTypes = HashSet<PsiElement>()
    // how many questions found their own question in progress: an answer that met a cycle is not kept when it is "nothing"
    private var cycles = 0
    internal val cycleCount: Int get() = cycles

    /** An answer asked for while it is being computed elsewhere (a range variable of a query being translated): what depends on it is not kept. */
    internal fun noteCycle() {
        cycles++
    }
    internal val expressions = CSharpExpressionTypes(this)
    internal val overloads = CSharpOverloads(this)
    private val levels = IdentityHashMap<PsiElement, List<Level>>()
    private val projectLevel: Level by lazy { compilationLevel() }

    // ---- entry points

    /** What the identifier [leaf] stands for; null when it is no name, names a declaration, or nothing is found. */
    fun resolve(leaf: PsiElement): CSharpResolution? {
        if (!CSharpLeaves.isIdentifier(leaf)) return null
        syntax.symbolAt(leaf)?.let { return if (it.declaration == leaf) null else CSharpResolution(listOf(CSharpSymbol.Local(it))) }
        val name = leaf.parent as? CSharpSimpleName ?: return null
        if (name.identifier != leaf) return null
        return resolveName(name)
    }

    fun resolveName(name: CSharpSimpleName): CSharpResolution? {
        if (names.containsKey(name)) return names[name]
        if (!busy.add(name)) {
            cycles++
            return null
        }
        var result: CSharpResolution?
        try {
            val before = cycles
            result = compute(name)?.takeIf { it.isNotEmpty() }?.distinct()?.let(::CSharpResolution)
            if (result != null || cycles == before) names[name] = result
        } finally {
            busy.remove(name)
        }
        // overloads a lambda argument tells apart: with the candidates known, the lambdas' parameters are typed, then their bodies
        if (result != null && result.symbols.size > 1) {
            val refined = expressions.refineByLambdas(name, result.symbols)
            if (refined.size < result.symbols.size) {
                result = CSharpResolution(refined)
                names[name] = result
            }
            // the lambdas' bodies typed, the better function member may tell the rest apart (task D1)
            if (result.symbols.size > 1) {
                val call = invocationOf(name)
                if (call != null && call.argumentList?.arguments.orEmpty().any { it.expression is CSharpAnonymousFunctionExpression }) {
                    val reduced = expressions.receiver(name) != null
                    overloads.resolve(result.symbols, call.argumentList?.arguments.orEmpty(), reduced, name, lambdas = true)?.let {
                        result = CSharpResolution(listOf(it))
                        names[name] = result
                    }
                }
            }
        }
        return result
    }

    private fun compute(name: CSharpSimpleName): List<CSharpSymbol>? {
        val leaf = name.identifier ?: return null
        syntax.symbolAt(leaf)?.let { return if (it.declaration == leaf) null else listOf(CSharpSymbol.Local(it)) }
        val text = leaf.text
        val arity = NativeCSharpResolver.arity(name)
        var top: PsiElement = name
        while (top.parent is CSharpQualifiedName || top.parent is CSharpAliasQualifiedName) top = top.parent
        when (val holder = top.parent) {
            is CSharpBaseNamespaceDeclaration -> if (top == holder.nameElement) return listOf(CSharpSymbol.Namespace(namespaceDeclared(holder, name)))
            is CSharpUsingDirective -> if (top == holder.namespaceOrType) {
                if (holder.alias?.nameElement == name) return null
                return usingTarget(name, text, arity)
            }
            is CSharpAttribute -> if (top == holder.nameElement && NativeCSharpResolver.isRightmost(name, top)) {
                val qualifier = qualifierOf(name)
                if (qualifier != null) return membersOf(qualifier, text + "Attribute", arity, null).ifEmpty { membersOf(qualifier, text, arity, null) }
                return typeOrNamespace(name, text + "Attribute", arity).filter { it !is CSharpSymbol.Namespace }.ifEmpty { typeOrNamespace(name, text, arity) }
            }
            is CSharpExternAliasDirective -> return null
        }
        val parent = name.parent
        when {
            parent is CSharpAliasQualifiedName && name == parent.alias -> return null
            parent is CSharpAliasQualifiedName && name == parent.nameElement -> {
                val alias = parent.alias?.identifier?.text ?: return null
                if (alias == "global") return inNamespace("", text, arity)
                // `using pb = global::Google.Protobuf;` … `pb::IMessage<T>`, as protoc writes: the alias must stand for a namespace
                val target = levels(name).firstNotNullOfOrNull { level -> if (level.hasAlias(alias)) level.alias(alias) ?: return null else null }
                return (target as? CSharpSymbol.Namespace)?.let { inNamespace(it.qualifiedName, text, arity) }
            }
            parent is CSharpQualifiedName && name == parent.right -> return qualifierOf(name)?.let { membersOf(it, text, arity, name) }
            parent is CSharpMemberAccessExpression && name == parent.nameElement -> return qualifierOf(name)?.let { member(it, text, arity, name) }
            parent is CSharpMemberBindingExpression -> return receiverOfBinding(parent)?.let { member(Qualifier.Value(it), text, arity, name) }
            parent is CSharpNameColon -> return namedArgument(parent, text)
            parent is CSharpAssignmentExpression && name == parent.left && NativeCSharpScopes.isObjectInitializer(parent.parent) -> {
                val created = createdType(parent.parent?.parent) ?: return null
                return membersOf(Qualifier.Value(created), text, 0, null)
            }
            parent is CSharpNameEquals && parent.parent is CSharpAttributeArgument -> {
                // `[Obsolete(DiagnosticId = "X")]`: a property or field of the attribute
                val attribute = PsiTreeUtil.getParentOfType(parent, CSharpAttribute::class.java) ?: return null
                val type = attributeType(attribute) ?: return null
                return membersNamed(type, text, 0)
            }
            !NativeCSharpScopes.isFreeName(name) -> return null
        }
        if (text == "var" && arity == 0) implicitType(name)?.let { return it }
        if (NativeCSharpTypePositions.isType(name) || (parent as? CSharpQualifiedName)?.left == name) return typeOrNamespace(name, text, arity)
        val found = simpleName(name, text, arity)
        colorColor(name, text, found)?.let { return listOf(it) }
        return found
    }

    internal fun attributeType(attribute: CSharpAttribute): SemanticType? {
        val rightmost = when (val n = attribute.nameElement) {
            is CSharpSimpleName -> n
            is CSharpQualifiedName -> n.right
            is CSharpAliasQualifiedName -> n.nameElement
            else -> null
        } ?: return null
        return resolveName(rightmost)?.single?.let { typeOfSymbol(it, rightmost) }
    }

    /** `Color.Red`, `Color` a property of type `Color`: when what follows the dot is static, the name is the type. */
    private fun colorColor(name: CSharpSimpleName, text: String, found: List<CSharpSymbol>): CSharpSymbol? {
        val access = name.parent as? CSharpMemberAccessExpression ?: return null
        if (access.expression != name) return null
        val value = found.singleOrNull()?.takeIf { it is CSharpSymbol.SourceMember || it is CSharpSymbol.LibraryMember || it is CSharpSymbol.Local } ?: return null
        val type = valueType(value)?.takeIf { it.name == text } ?: return null
        val right = access.nameElement ?: return null
        val members = membersNamed(type, right.identifier?.text ?: return null, NativeCSharpResolver.arity(right))
        return if (members.isNotEmpty() && members.all(::isStaticOrType)) symbolOf(type) else null
    }

    // ---- simple names

    /** An expression name: enclosing types' members, then `using static` members, then types and namespaces. */
    private fun simpleName(name: CSharpSimpleName, text: String, arity: Int): List<CSharpSymbol> {
        for (info in syntax.enclosingTypes(name)) {
            val found = membersNamed(selfType(info), text, arity)
            if (found.isNotEmpty()) return pick(found, name)
        }
        for (level in levels(name)) {
            for (static in level.statics()) {
                val found = membersNamed(static, text, arity)
                if (found.isNotEmpty()) return pick(found, name)
            }
        }
        val types = typeOrNamespace(name, text, arity)
        if (types.isNotEmpty()) return types
        // `nameof(...)` and the like: nothing here
        return emptyList()
    }

    /** A name where only a namespace or a type can stand (C# §7.6.2): nested types of the enclosing types, then the namespaces outwards. */
    fun typeOrNamespace(at: PsiElement, text: String, arity: Int): List<CSharpSymbol> {
        for (info in syntax.enclosingTypes(at)) nestedTypes(selfType(info), text, arity).takeIf { it.isNotEmpty() }?.let { return it }
        for (level in levels(at)) {
            val own = inNamespace(level.namespace, text, arity)
            if (own.isNotEmpty()) return own
            if (arity == 0) level.alias(text)?.let { return listOf(it) }
            val imported = level.imports.flatMap { typesIn(it, text, arity) }.distinct()
            if (imported.isNotEmpty()) return imported
        }
        return emptyList()
    }

    /** The type or namespace [text] of the namespace [namespace]. */
    private fun inNamespace(namespace: String, text: String, arity: Int): List<CSharpSymbol> {
        val found = typesIn(namespace, text, arity)
        if (found.isNotEmpty()) return found
        val qualified = join(namespace, text)
        if (arity == 0 && session.namespaceExists(qualified, assemblies)) return listOf(CSharpSymbol.Namespace(qualified))
        return emptyList()
    }

    /** The types [text] with [arity] declared at the top of [namespace]: of the solution, then of the assemblies (a built project is its sources). */
    fun typesIn(namespace: String, text: String, arity: Int): List<CSharpSymbol> {
        val source = syntax.typeParts(text).filter { it.namespace == namespace && it.arity == arity }.map { it.qualifiedName }.distinct()
            .mapNotNull { syntax.typeInfo(it, arity) }.map { CSharpSymbol.SourceType(it) }
        val library = session.findType(assemblies, namespace, path(text, arity))?.takeIf { type -> source.none { it.info.qualifiedName == type.qualifiedName } }
        return if (library == null) source else source + CSharpSymbol.LibraryType(library)
    }

    /** The name `using A.B;` / `using static A.B.T;` / `using X = A.B.T;` gives: looked up from the global namespace, then the enclosing ones. */
    private fun usingTarget(name: CSharpSimpleName, text: String, arity: Int): List<CSharpSymbol> {
        val parent = name.parent
        if (parent is CSharpQualifiedName && name == parent.right) return qualifierOf(name)?.let { membersOf(it, text, arity, null) }.orEmpty()
        if (parent is CSharpAliasQualifiedName) return inNamespace("", text, arity)
        inNamespace("", text, arity).takeIf { it.isNotEmpty() }?.let { return it }
        for (level in levels(name)) if (level.namespace.isNotEmpty()) inNamespace(level.namespace, text, arity).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    /** `namespace A.B.C`: the namespace a part of the name stands for, the namespaces around the declaration in front. */
    private fun namespaceDeclared(declaration: CSharpBaseNamespaceDeclaration, name: CSharpSimpleName): String {
        val outer = generateSequence(declaration.parent) { if (it is CSharpFile) null else it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>()
            .map { NativeCSharpResolver.compact(it.nameElement) }.toList().asReversed()
        val top = declaration.nameElement ?: return name.text
        val own = top.text.substring(0, name.textRange.endOffset - top.textRange.startOffset).filterNot(Char::isWhitespace)
        return (outer + own).joinToString(".").removePrefix("global::")
    }

    // ---- qualifiers and members

    /** What the left side of `A.B` is. */
    sealed class Qualifier {
        class Namespace(val name: String) : Qualifier()
        class Type(val type: SemanticType) : Qualifier()
        class Value(val type: SemanticType) : Qualifier()
        /** `Color Color`: a value whose type has the name of the value (C# §12.8.7.2): both readings. */
        class ValueOrType(val value: SemanticType, val type: SemanticType) : Qualifier()
    }

    internal fun qualifierOf(name: CSharpSimpleName): Qualifier? {
        val left: PsiElement? = when (val parent = name.parent) {
            is CSharpQualifiedName -> parent.left
            is CSharpMemberAccessExpression -> parent.expression
            else -> null
        }
        return left?.let(::qualifier)
    }

    internal fun qualifier(element: PsiElement): Qualifier? {
        val name = when (element) {
            is CSharpSimpleName -> element
            is CSharpMemberAccessExpression -> element.nameElement
            is CSharpQualifiedName -> element.right
            is CSharpAliasQualifiedName -> element.nameElement
            is CSharpPredefinedType -> return resolveType(element)?.let { Qualifier.Type(it) }
            is CSharpThisExpression, is CSharpBaseExpression -> return typeOf(element)?.let { Qualifier.Value(it) }
            is CSharpExpression -> return typeOf(element)?.let { Qualifier.Value(it) }
            else -> null
        } ?: return null
        val symbol = resolveName(name)?.single ?: run {
            // `nint.MaxValue`: the contextual keywords of the native integers
            if (element is CSharpIdentifierName && (name.identifier?.text == "nint" || name.identifier?.text == "nuint")) return resolveType(element)?.let { Qualifier.Type(it) }
            return (element as? CSharpExpression)?.let(::typeOf)?.let { Qualifier.Value(it) }
        }
        return when {
            symbol is CSharpSymbol.Namespace -> Qualifier.Namespace(symbol.qualifiedName)
            symbol is CSharpSymbol.SourceType || symbol is CSharpSymbol.LibraryType -> typeOfSymbol(symbol, name)?.let { Qualifier.Type(it) }
            // `T.Zero` of `where T : INumber<T>`: static members of the constraints
            symbol is CSharpSymbol.Local && symbol.symbol.kind == LocalSymbolKind.TYPE_PARAMETER -> (name as? CSharpType)?.let(::resolveType)?.let { Qualifier.Type(it) }
            else -> {
                val value = valueType(symbol) ?: return null
                // `Color Color`: the name also finds the type of the value
                if (value.name == name.identifier?.text) Qualifier.ValueOrType(value, value) else Qualifier.Value(value)
            }
        }
    }

    /** `A.B` in an expression: a member of what `A` is; extension methods when `A` is a value with no member `B`. */
    private fun member(qualifier: Qualifier, text: String, arity: Int, site: CSharpSimpleName): List<CSharpSymbol> {
        val found = membersOf(qualifier, text, arity, site)
        val receiver = when (qualifier) {
            is Qualifier.Value -> qualifier.type
            is Qualifier.ValueOrType -> qualifier.value
            else -> return found
        }
        if (found.isNotEmpty() && callable(found, site)) return found
        // C# §12.8.10.3: extension methods when no instance method is applicable, `span.Equals(other, comparison)`
        return pick(extensionMethods(receiver, text, arity, site), site, reduced = true).ifEmpty { found }
    }

    /** Whether some of [found] can take the arguments of the call at [site] (not a call: yes). */
    private fun callable(found: List<CSharpSymbol>, site: CSharpSimpleName): Boolean {
        if (found.any { !isMethod(it) }) return true
        val arguments = invocationOf(site)?.argumentList?.arguments ?: return true
        return found.any { symbol -> signature(symbol, false)?.let { fits(it, arguments) } ?: true }
    }

    private fun membersOf(qualifier: Qualifier, text: String, arity: Int, site: CSharpSimpleName?): List<CSharpSymbol> = when (qualifier) {
        is Qualifier.Namespace -> inNamespace(qualifier.name, text, arity)
        is Qualifier.Type -> pick(byReceiver(membersNamed(qualifier.type, text, arity), static = true), site)
        is Qualifier.Value -> pick(byReceiver(membersNamed(qualifier.type, text, arity), static = false), site)
        is Qualifier.ValueOrType -> pick(membersNamed(qualifier.value, text, arity), site)
    }

    /**
     * C# 7.3, the improved candidates of §12.6.4.1: a method group reached through a value leaves its static methods out, one reached
     * through a type its instance ones — when some are left and the group is all methods.
     */
    private fun byReceiver(found: List<CSharpSymbol>, static: Boolean): List<CSharpSymbol> {
        if (found.size < 2 || found.any { !isMethod(it) }) return found
        val kept = found.filter { overloads.isStatic(it) != !static }
        return kept.ifEmpty { found }
    }

    /** The type [info] seen from inside: its type parameters as arguments. */
    fun selfType(info: TypeInfo): SemanticType.Source {
        val owner = info.parts.firstOrNull { it.arity > 0 }?.element()
        val parameters = typeParameterNames(owner)
        return SemanticType.Source(info, List(info.arity) { i -> SemanticType.Parameter(parameters.getOrElse(i) { "T$i" }, owner, i, false) }, outerTypeOf(info)?.let(::selfType))
    }

    internal fun typeParameterNames(owner: PsiElement?): List<String> = when (owner) {
        is CSharpTypeDeclaration -> owner.typeParameterList?.parameters.orEmpty().map { it.identifier?.text.orEmpty() }
        is CSharpDelegateDeclaration -> owner.typeParameterList?.parameters.orEmpty().map { it.identifier?.text.orEmpty() }
        is CSharpMethodDeclaration -> owner.typeParameterList?.parameters.orEmpty().map { it.identifier?.text.orEmpty() }
        is CSharpLocalFunctionStatement -> owner.typeParameterList?.parameters.orEmpty().map { it.identifier?.text.orEmpty() }
        else -> emptyList()
    }

    /**
     * The members named [text] of [type], the inherited ones included (the nearest type that has the name wins), and its nested types of
     * that name; for an [arity] above 0 the generic methods and types with as many type parameters.
     */
    fun membersNamed(type: SemanticType, text: String, arity: Int, depth: Int = 0): List<CSharpSymbol> {
        if (depth > MAX_DEPTH) return emptyList()
        return when (type) {
            is SemanticType.Source -> {
                val map = syntax.membersOf(type.info)
                val member = (if (arity > 0) map["$text<$arity>"] ?: map["$text`$arity"] else map[text])
                if (member != null) {
                    val nested = syntax.nestedTypeOf(member)
                    if (nested != null) return listOf(CSharpSymbol.SourceType(nested))
                    // an explicit implementation (`IEnumerator IEnumerable.GetEnumerator()`) is not found by its name
                    val found = member.targets().filter { !isExplicitImplementation(it) }.map { CSharpSymbol.SourceMember(it, member, declaringInstance(type, it)) }
                    if (found.isNotEmpty()) return withObjectMethods(type, found, text, arity, depth)
                    // only explicit implementations here (the map keeps the nearest declarations): the base class has the member
                    val bases = baseTypes(type).sortedBy { overloads.isInterface(it) }
                    for (base in bases) membersNamed(base, text, arity, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
                }
                // the bases as their declarations resolve them, those of assemblies included: the map walks the bases of the solution by
                // simple name as this file sees them, which may be another type of that name (a DTO imported here, E-190)
                for (base in baseTypes(type).sortedBy { overloads.isInterface(it) }) {
                    if (base is SemanticType.Source && base.info.key == type.info.key) continue
                    membersNamed(base, text, arity, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
                }
                emptyList()
            }
            is SemanticType.Library -> {
                val found = session.libraryMembers(assemblies, type.type)[text].orEmpty().filter { inherited ->
                    val m = inherited.member
                    m.name == text && m.kind != IndexedMemberKind.CONSTRUCTOR && (arity == 0 || m.arity == arity)
                }.map { CSharpSymbol.LibraryMember(it.member, declaringArguments(type, it.from)) }
                if (found.isNotEmpty()) return withObjectMethods(type, found, text, arity, depth)
                val nested = nestedTypes(type, text, arity)
                if (nested.isNotEmpty()) return nested
                if (type.type.kind == IndexedTypeKind.INTERFACE && type.type.fullName != OBJECT) libraryType(OBJECT)?.let { return membersNamed(it, text, arity, depth + 1) }
                emptyList()
            }
            is SemanticType.ArrayOf -> libraryType("System.Array")?.let { membersNamed(it, text, arity, depth + 1) }.orEmpty()
            is SemanticType.Parameter -> {
                // C# §12.5: the effective base class and the interface constraints together; methods of object overload those of an interface
                val found = constraintsOf(type).flatMap { membersNamed(it, text, arity, depth + 1) }
                if (found.isNotEmpty() && found.any { !isMethod(it) }) found
                else (found + libraryType(OBJECT)?.let { membersNamed(it, text, arity, depth + 1) }.orEmpty()).distinctBy { it.id ?: it }
            }
        }
    }

    private fun isExplicitImplementation(element: PsiElement): Boolean = when {
        // the stubs do not keep the interface of the name: a file that is not parsed is not parsed for it (taken as an ordinary member)
        (element as? com.intellij.extapi.psi.StubBasedPsiElementBase<*>)?.stub != null -> false
        element is CSharpMethodDeclaration -> element.explicitInterfaceSpecifier != null
        element is CSharpBasePropertyDeclaration -> element.explicitInterfaceSpecifier != null
        else -> false
    }

    /** The methods of an interface overload those of `object` of the same name (C# §12.5: an interface's lookup includes object's members). */
    private fun withObjectMethods(type: SemanticType, found: List<CSharpSymbol>, text: String, arity: Int, depth: Int): List<CSharpSymbol> {
        val isInterface = type is SemanticType.Library && type.type.kind == IndexedTypeKind.INTERFACE && type.type.fullName != OBJECT ||
            type is SemanticType.Source && type.info.kind == io.github.dotnetsupport.lang.TypeKind.INTERFACE
        if (!isInterface || found.any { !isMethod(it) }) return found
        val objects = libraryType(OBJECT)?.let { membersNamed(it, text, arity, depth + 1) }.orEmpty().filter(::isMethod)
        return if (objects.isEmpty()) found else (found + objects).distinctBy { it.id ?: it }
    }

    /**
     * The type of the solution [element] is declared in, as [type] sees it: [type] itself, or the base of it (`Channel<T, T>` of
     * `class Channel<T> : Channel<T, T>`) whose type arguments the signature of an inherited member is substituted with.
     */
    private fun declaringInstance(type: SemanticType.Source, element: PsiElement, depth: Int = 0): SemanticType.Source {
        val container = PsiTreeUtil.getParentOfType(element, CSharpBaseTypeDeclaration::class.java, false) ?: return type
        if (type.info.parts.any { it.element() == container } || depth > MAX_DEPTH) return type
        for (base in baseTypes(type)) {
            if (base !is SemanticType.Source || base.info.key == type.info.key) continue
            val found = declaringInstance(base, element, depth + 1)
            if (found.info.parts.any { it.element() == container }) return found
        }
        return type
    }

    /** What the type parameters of the type [from] declares a member in stand for, seen from [receiver]. */
    internal fun declaringArguments(receiver: SemanticType.Library, from: AssemblyIndexSet.Supertype?): List<SemanticType?> =
        from?.arguments?.map { fromRef(it, receiver.arguments) } ?: receiver.arguments

    /** Nested types named [text] of [type] and of its bases. */
    fun nestedTypes(type: SemanticType, text: String, arity: Int, depth: Int = 0): List<CSharpSymbol> {
        if (depth > MAX_DEPTH) return emptyList()
        when (type) {
            is SemanticType.Source -> {
                syntax.membersOf(type.info)["$text`$arity"]?.let(syntax::nestedTypeOf)?.let { return listOf(CSharpSymbol.SourceType(it)) }
                for (base in libraryBases(type, depth)) nestedTypes(base, text, arity, depth + 1).takeIf { it.isNotEmpty() }?.let { return it }
            }
            is SemanticType.Library -> {
                type.type.nestedTypes.firstOrNull { it.simpleName == text && it.ownArity == arity }?.let { return listOf(CSharpSymbol.LibraryType(it)) }
                for (base in session.baseTypes(assemblies, type.type)) base.type.nestedTypes.firstOrNull { it.simpleName == text && it.ownArity == arity }?.let { return listOf(CSharpSymbol.LibraryType(it)) }
            }
            else -> {}
        }
        return emptyList()
    }

    /**
     * The base types of a type of the solution that are not of the solution — and those of its bases of the solution, which the syntactic
     * resolver walks by name: the members it cannot see. Substituted with the type arguments of [type].
     */
    fun libraryBases(type: SemanticType.Source, depth: Int = 0): List<SemanticType> {
        if (depth > MAX_DEPTH) return emptyList()
        val found = ArrayList<SemanticType>()
        for (base in baseTypes(type)) when (base) {
            is SemanticType.Library -> found += base
            is SemanticType.Source -> if (base.info.key != type.info.key) found += libraryBases(base, depth + 1)
            else -> {}
        }
        return found
    }

    /** The base class and interfaces of a type of the solution as its parts write them, resolved where they are written, substituted. */
    fun baseTypes(type: SemanticType.Source): List<SemanticType> {
        val found = ArrayList<SemanticType>()
        for (part in type.info.parts) {
            val declaration = part.element() as? CSharpBaseTypeDeclaration ?: continue
            val owner = declaration.containingFile as? CSharpFile ?: continue
            val resolver = session.reachable(owner) ?: continue
            for (base in declaration.baseList?.types.orEmpty()) {
                val resolved = base.type?.let(resolver::resolveType) ?: continue
                substitute(resolved, type)?.let { found += it }
            }
        }
        if (found.none { it is SemanticType.Library && it.type.kind != IndexedTypeKind.INTERFACE || it is SemanticType.Source }) {
            // a class without a base class derives from `object`, a struct from `ValueType`, an enum from `Enum`
            val implicit = when (type.info.kind) {
                io.github.dotnetsupport.lang.TypeKind.STRUCT, io.github.dotnetsupport.lang.TypeKind.RECORD_STRUCT -> "System.ValueType"
                io.github.dotnetsupport.lang.TypeKind.ENUM -> "System.Enum"
                io.github.dotnetsupport.lang.TypeKind.INTERFACE -> null
                io.github.dotnetsupport.lang.TypeKind.DELEGATE -> "System.MulticastDelegate"
                else -> OBJECT
            }
            implicit?.let(::libraryType)?.let { found += it }
        }
        return found
    }

    internal fun constraintsOf(parameter: SemanticType.Parameter): List<SemanticType> {
        val owner = parameter.owner ?: return emptyList()
        val clauses = when (owner) {
            is CSharpTypeDeclaration -> owner.constraintClauses
            is CSharpMethodDeclaration -> owner.constraintClauses
            is CSharpLocalFunctionStatement -> owner.constraintClauses
            is CSharpDelegateDeclaration -> owner.constraintClauses
            else -> return emptyList()
        }
        val clause = clauses.firstOrNull { it.nameElement?.identifier?.text == parameter.name } ?: return emptyList()
        val resolver = (owner.containingFile as? CSharpFile)?.let(session::reachable) ?: return emptyList()
        return clause.constraints.filterIsInstance<CSharpTypeConstraint>().mapNotNull { it.type?.let(resolver::resolveType) }
    }

    /** [type] with the type parameters of [receiver]'s type replaced by [receiver]'s arguments. */
    fun substitute(type: SemanticType?, receiver: SemanticType.Source): SemanticType? {
        if (type == null) return null
        // a member of a type nested in a generic one uses the type parameters of the types around it too
        val outer = receiver.outer?.let { substitute(type, it) } ?: type
        if (receiver.arguments.isEmpty()) return outer
        val names = selfType(receiver.info).arguments.map { (it as SemanticType.Parameter).name }
        return replace(outer) { p -> if (!p.ofMethod && p.owner.isTypeDeclaration()) names.indexOf(p.name).takeIf { it >= 0 }?.let { receiver.arguments[it] } ?: p else p }
    }

    private fun PsiElement?.isTypeDeclaration(): Boolean = this is CSharpBaseTypeDeclaration || this is CSharpDelegateDeclaration

    internal fun replace(type: SemanticType?, map: (SemanticType.Parameter) -> SemanticType?): SemanticType? = when (type) {
        null -> null
        is SemanticType.Parameter -> map(type)
        is SemanticType.Source -> if (type.arguments.isEmpty() && type.outer == null) type else SemanticType.Source(type.info, type.arguments.map { replace(it, map) }, type.outer?.let { replace(it, map) as? SemanticType.Source })
        is SemanticType.Library -> if (type.arguments.isEmpty()) type else SemanticType.Library(type.type, type.arguments.map { replace(it, map) }, type.tupleNames)
        is SemanticType.ArrayOf -> SemanticType.ArrayOf(replace(type.element, map), type.rank)
    }

    // ---- extension methods

    /**
     * Extension methods named [text] for a receiver of [receiver]'s type, of the static classes of the namespaces imported where [site] is:
     * of the assemblies by what they extend (the type, its bases and interfaces, arrays, `this T`), of the solution by the type their
     * `this` parameter names.
     */
    internal fun extensionMethods(receiver: SemanticType, text: String, arity: Int, site: PsiElement): List<CSharpSymbol> {
        val imported = importedNamespaces(site)
        val found = ArrayList<CSharpSymbol>()
        val keys = LinkedHashSet<String>()
        val names = HashSet<String>()
        collectSupertypes(receiver, keys, names, 0)
        keys += AssemblyIndexSet.GENERIC_RECEIVER
        for (key in keys) for (member in session.extensions(assemblies, key)) {
            if (member.name == text && (arity == 0 || member.arity == arity) && member.type.namespace in imported) {
                // `where TBuilder : IEndpointConventionBuilder`: not a method of a value that does not satisfy it
                val symbol = CSharpSymbol.LibraryMember(member)
                if (key != AssemblyIndexSet.GENERIC_RECEIVER || expressions.receiverFits(symbol, receiver)) found += symbol
            }
        }
        for (element in session.sourceExtensions(text)) sourceExtension(element, arity, imported::contains, names)?.let { found += it }
        return found
    }

    /** An extension method of the solution found by name in the stub index, if it is imported where the lookup is and may take a receiver of [names]. */
    private fun sourceExtension(element: PsiElement, arity: Int, imported: (String) -> Boolean, names: Set<String>): CSharpSymbol? {
        val stub = NativeCSharpStubDeclarations.stub(element)
        val method = element as? CSharpMethodDeclaration ?: return null
        if (arity != 0 && (stub?.arity ?: method.typeParameterList?.parameters?.size ?: 0) != arity) return null
        val namespace = stub?.let { s -> s.parentStub?.let { it as? CSharpStub }?.let { namespaceOfStub(it) } } ?: namespaceOfPsi(method)
        val written = stub?.parameters?.let(::firstParameterType) ?: method.parameterList?.parameters?.firstOrNull()?.type?.let { TypePart.simpleName(it)?.first ?: it.text }
        // `this string text`: the keyword is the type `String`
        val receiverName = written?.let { KEYWORD_TYPES[it]?.substringAfterLast('.') ?: it }
        val generic = stub?.arity?.let { it > 0 } ?: (method.typeParameterList != null)
        if (namespace == null || !imported(namespace) || receiverName == null) return null
        if (!(receiverName in names || generic && receiverName.length <= 2 || generic && isMethodTypeParameter(method, receiverName))) return null
        return CSharpSymbol.SourceMember(method, Member.method(listOf("static"), true).at { method }, null)
    }

    /**
     * Every extension method a value of [receiver] can call where [site] is (completion after a dot, task C3): those of the assemblies by
     * what they extend, those of the solution by the type of their `this` parameter, imported where [site] is and taking the receiver
     * ([CSharpExpressionTypes.receiverFits]). [wanted]: the names worth looking at (the prefix typed), every name when null. [namespaces]:
     * the namespaces of the static classes to look in instead of the imported ones (the unimported ones, for import completion).
     */
    fun extensionMethodsFor(receiver: SemanticType, site: PsiElement, wanted: ((String) -> Boolean)? = null, namespaces: ((String) -> Boolean)? = null): List<CSharpSymbol> {
        val imported = namespaces ?: importedNamespaces(site)::contains
        val found = ArrayList<CSharpSymbol>()
        val keys = LinkedHashSet<String>()
        val names = HashSet<String>()
        collectSupertypes(receiver, keys, names, 0)
        keys += AssemblyIndexSet.GENERIC_RECEIVER
        for (key in keys) for (member in session.extensions(assemblies, key)) {
            if (imported(member.type.namespace) && (wanted == null || wanted(member.name))) found += CSharpSymbol.LibraryMember(member)
        }
        val index = StubIndex.getInstance()
        for (name in index.getAllKeys(CSharpStubIndexKeys.EXTENSION_METHODS, session.project)) {
            if (wanted != null && !wanted(name)) continue
            for (element in session.sourceExtensions(name)) sourceExtension(element, 0, imported, names)?.let { found += it }
        }
        return found.filter { expressions.receiverFits(it, receiver) }
    }

    private fun isMethodTypeParameter(method: CSharpMethodDeclaration, name: String): Boolean = method.typeParameterList?.parameters?.any { it.identifier?.text == name } == true

    /** The keys of extension methods ([AssemblyIndexSet.extensions]) and the simple names of [type] and of everything it derives from. */
    internal fun collectSupertypes(type: SemanticType?, keys: MutableSet<String>, names: MutableSet<String>, depth: Int) {
        if (type == null || depth > MAX_DEPTH) return
        when (type) {
            is SemanticType.Library -> {
                keys += type.type.fullName
                names += type.type.simpleName
                session.baseTypes(assemblies, type.type).forEach { keys += it.type.fullName; names += it.type.simpleName }
                session.interfaces(assemblies, type.type).forEach { keys += it.type.fullName; names += it.type.simpleName }
                if (type.type.kind == IndexedTypeKind.INTERFACE) { keys += OBJECT; names += "Object" }
            }
            is SemanticType.Source -> {
                names += type.name
                for (base in baseTypes(type)) collectSupertypes(base, keys, names, depth + 1)
            }
            is SemanticType.ArrayOf -> {
                keys += AssemblyIndexSet.ARRAY_RECEIVER
                names += "Array"
                for (name in ARRAY_INTERFACES) libraryType(name)?.let { collectSupertypes(it, keys, names, depth + 1) }
                libraryType("System.Array")?.let { collectSupertypes(it, keys, names, depth + 1) }
            }
            is SemanticType.Parameter -> {
                constraintsOf(type).forEach { collectSupertypes(it, keys, names, depth + 1) }
                keys += OBJECT
                names += "Object"
            }
        }
    }

    /** The namespace of the static class a stub of an extension method is in, null when that class is nested or broken. */
    private fun namespaceOfStub(classStub: CSharpStub): String? {
        val containers = NativeCSharpStubDeclarations.containers(classStub)
        if (containers.any { it.second.isType }) return null
        return containers.joinToString(".") { it.first }
    }

    private fun namespaceOfPsi(method: PsiElement): String? {
        val type = method.parent as? CSharpBaseTypeDeclaration ?: return null
        if (type.parent is CSharpBaseTypeDeclaration) return null
        return generateSequence(type.parent) { if (it is CSharpFile) null else it.parent }.filterIsInstance<CSharpBaseNamespaceDeclaration>()
            .map { NativeCSharpResolver.compact(it.nameElement) }.toList().asReversed().joinToString(".")
    }

    /** `(this IEnumerable<T> source, int n)` -> `IEnumerable`. */
    private fun firstParameterType(parameters: String): String? {
        val first = parameters.trim().removePrefix("(").trim().removePrefix("this ").trim()
        val end = first.indexOfFirst { it == '<' || it == ' ' || it == '[' || it == '?' || it == ',' || it == ')' }
        return (if (end < 0) first else first.substring(0, end)).substringAfterLast('.').takeIf { it.isNotEmpty() }
    }

    /** The namespaces whose types and extension methods [site] sees: those around it and those its `using` directives import. */
    private fun importedNamespaces(site: PsiElement): Set<String> {
        val found = HashSet<String>()
        for (level in levels(site)) {
            found += level.namespace
            found += level.imports
        }
        return found
    }

    // ---- overloads

    /**
     * Of [candidates], the ones a call at [site] can mean: by the number of arguments, then by the types of the arguments that are known.
     * Not a call, or no way to tell: all of them. [reduced]: extension methods called on a receiver (the receiver is their first argument).
     */
    private fun pick(candidates: List<CSharpSymbol>, site: CSharpSimpleName?, reduced: Boolean = false): List<CSharpSymbol> {
        if (candidates.size <= 1 || site == null) return candidates
        if (candidates.any { !isMethod(it) }) return candidates.filterNot(::isMethod).ifEmpty { candidates }
        val call = invocationOf(site) ?: return candidates
        val arguments = call.argumentList?.arguments.orEmpty()
        val receiver = if (reduced) expressions.receiver(site) else null
        // `doubles.Average()` of `IEnumerable<double?>`: the receiver takes the overload for its own element type
        val receiving = if (receiver == null) candidates else candidates.filter { expressions.receiverFits(it, receiver) }.ifEmpty { candidates }
        return pickByArguments(receiving, arguments, reduced, candidates, site)
    }

    /** The constructor `new T(...)` with [arguments] calls, of [constructors]; null when the arguments do not tell one (parameter info). */
    internal fun pickConstructor(constructors: List<CSharpSymbol>, arguments: List<CSharpArgument>): CSharpSymbol? =
        if (constructors.size == 1) constructors.single() else pickByArguments(constructors, arguments, false, constructors, null).singleOrNull()

    private fun pickByArguments(receiving: List<CSharpSymbol>, arguments: List<CSharpArgument>, reduced: Boolean, candidates: List<CSharpSymbol>, site: CSharpSimpleName?): List<CSharpSymbol> {
        // C# §12.6.4: the applicable candidates and the better function member, when everything it needs is known (task D1)
        overloads.resolve(receiving, arguments, reduced, site)?.let { return listOf(it) }
        val signatures = receiving.map { it to signature(it, reduced && isExtension(it)) }
        val fitting = signatures.filter { (_, s) -> s != null && fits(s, arguments) }
        if (fitting.size == 1) return listOf(fitting.single().first)
        val pool = fitting.ifEmpty { return candidates }
        val argumentTypes = arguments.map { argument -> argument.expression?.let(::typeOfArgument) }
        var best = -1
        val scored = ArrayList<Triple<CSharpSymbol, List<Parameter>, Int>>()
        for ((symbol, signature) in pool) {
            val score = score(signature!!, arguments, argumentTypes, (symbol as? CSharpSymbol.SourceMember)?.element) ?: continue
            scored += Triple(symbol, signature, score)
            if (score > best) best = score
        }
        // `[OverloadResolutionPriority(n)]` removes the candidates of lower priority before anything else. The index does not keep n; the
        // libraries use it to step aside (-1: `Debug.Assert(bool)` for the overload with the caller's expression, `Span` overloads of
        // `MemoryExtensions` for the `ReadOnlySpan` ones), so a candidate with it loses to any other
        if (scored.size > 1 && scored.any { (symbol, _, _) -> hasPriority(symbol) } && scored.any { (symbol, _, _) -> !hasPriority(symbol) }) {
            scored.removeAll { (symbol, _, _) -> hasPriority(symbol) }
            best = scored.maxOf { it.third }
        }
        var top = scored.filter { it.third == best }
        // C# §12.6.4.3, the tie-breaks of the better function member: the normal form over the expanded one (no `params`, no default left
        // out), a method that is not generic over a generic one — when the types of all arguments are known, else the tie may be none
        if (top.size > 1 && argumentTypes.any { it == null }) return scored.map { it.first }
        if (top.size > 1) top.filter { (_, s, _) -> s.size == arguments.size && s.lastOrNull()?.isParams != true }.takeIf { it.isNotEmpty() }?.let { top = it }
        if (top.size > 1) top.filter { (symbol, _, _) -> !isGeneric(symbol) }.takeIf { it.isNotEmpty() }?.let { top = it }
        if (top.size > 1) top.filter { (_, s, _) -> s.size == arguments.size }.takeIf { it.isNotEmpty() }?.let { top = it }
        return if (top.size == 1) listOf(top.single().first) else if (scored.isNotEmpty()) scored.map { it.first } else pool.map { it.first }
    }

    private fun hasPriority(symbol: CSharpSymbol): Boolean =
        symbol is CSharpSymbol.LibraryMember && symbol.member.attributes.any { it == "System.Runtime.CompilerServices.OverloadResolutionPriorityAttribute" }

    internal fun isGeneric(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.LibraryMember -> symbol.member.arity > 0
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.typeParameterList != null
        else -> false
    }

    internal fun isMethod(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> symbol.element is CSharpMethodDeclaration || symbol.element is CSharpLocalFunctionStatement
        is CSharpSymbol.LibraryMember -> symbol.member.kind.isCallable
        else -> false
    }

    fun isExtension(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.let(TypePart::isExtension) == true
        is CSharpSymbol.LibraryMember -> symbol.member.kind == IndexedMemberKind.EXTENSION_METHOD
        else -> false
    }

    /** A parameter as overload resolution sees it; [type] is computed when asked. */
    internal class Parameter(val name: String, val optional: Boolean, val isParams: Boolean, val byRef: Boolean, val type: () -> SemanticType?)

    internal fun signature(symbol: CSharpSymbol, reduced: Boolean): List<Parameter>? {
        val all = when (symbol) {
            is CSharpSymbol.SourceMember -> {
                val resolver = (symbol.element.containingFile as? CSharpFile)?.let(session::reachable) ?: return null
                val list = when (val element = symbol.element) {
                    is CSharpMethodDeclaration -> element.parameterList
                    is CSharpLocalFunctionStatement -> element.parameterList
                    is CSharpConstructorDeclaration -> element.parameterList
                    // a primary constructor
                    is CSharpTypeDeclaration -> element.parameterList
                    else -> return null
                }
                list?.parameters.orEmpty().map { p ->
                    val modifiers = p.modifiers.map { it.text }
                    Parameter(p.identifier?.text.orEmpty(), p.default != null, "params" in modifiers, "ref" in modifiers || "out" in modifiers) {
                        val type = p.type?.let(resolver::resolveType)
                        val owner = symbol.owner
                        if (owner is SemanticType.Source) substitute(type, owner) else type
                    }
                }
            }
            is CSharpSymbol.LibraryMember -> {
                val methodParameters = if (symbol.member.arity > 0) symbol.member.typeParameters.mapIndexed { i, t -> SemanticType.Parameter(t.name, null, i, true) } else emptyList()
                session.parameters(symbol.member).map { p ->
                    Parameter(p.name, p.isOptional || p.hasDefault, p.isParams, p.isRef || p.isOut) { fromRef(p.typeRef, symbol.declaringArguments, methodParameters) }
                }
            }
            else -> return null
        }
        return if (reduced) all.drop(1) else all
    }

    /** The names of the parameters of a method (without the receiver of an extension called on it), each with whether it is `params`. */
    fun parameterNames(symbol: CSharpSymbol, reduced: Boolean): List<Pair<String, Boolean>>? = signature(symbol, reduced)?.map { it.name to it.isParams }

    internal fun fits(parameters: List<Parameter>, arguments: List<CSharpArgument>): Boolean {
        val named = arguments.mapNotNull { it.nameColon?.nameElement?.identifier?.text }
        if (named.any { n -> parameters.none { it.name == n } }) return false
        val count = arguments.size
        val required = parameters.count { !it.optional && !it.isParams }
        val variadic = parameters.lastOrNull()?.isParams == true
        return count >= required && (count <= parameters.size || variadic)
    }

    /** How well the known argument types fit: 2 per identical type, 1 per a conversion that is surely there; null when one surely does not fit. */
    private fun score(parameters: List<Parameter>, arguments: List<CSharpArgument>, types: List<SemanticType?>, owner: PsiElement?): Int? {
        var score = 0
        for ((i, argument) in arguments.withIndex()) {
            val name = argument.nameColon?.nameElement?.identifier?.text
            val parameter = (if (name != null) parameters.firstOrNull { it.name == name } else parameters.getOrNull(i) ?: parameters.lastOrNull()) ?: return null
            val argumentType = types[i]
            if (argument.expression?.elementType == SyntaxKind.NullLiteralExpression) {
                val p = parameter.type() ?: continue
                if (isValueType(p) && !isNullable(p)) return null
                continue
            }
            val lambda = argument.expression as? CSharpAnonymousFunctionExpression
            if (lambda != null) {
                // `Select(x => ...)` or `Select((x, i) => ...)`: the number of the lambda's parameters picks the delegate
                val p = parameter.type() ?: continue
                if (!expressions.lambdaFits(lambda, p, typeBody = expressions.lambdaParameterCount(lambda) == 0)) return null
                continue
            }
            if (argumentType == null) continue
            var p = parameter.type() ?: continue
            if (parameter.isParams && i >= parameters.size - 1 && p is SemanticType.ArrayOf && !(argumentType is SemanticType.ArrayOf)) p = p.element ?: continue
            // a type parameter of the method is inferred from the argument: identity
            // (only the candidate's own: a type parameter of the method the call is in is a type like any other)
            val conversion = if (p is SemanticType.Parameter && p.ofMethod && (p.owner == null || p.owner == owner)) Conversion.IDENTITY else simpleConversion(argumentType, p)
            // `ref` / `out` take a variable of the very type
            if (parameter.byRef && conversion == Conversion.IMPLICIT) return null
            score += when (conversion) {
                Conversion.IDENTITY -> 2
                Conversion.IMPLICIT -> 1
                Conversion.UNKNOWN -> 0
                Conversion.NONE -> return null
            }
        }
        return score
    }

    internal enum class Conversion { IDENTITY, IMPLICIT, UNKNOWN, NONE }

    internal fun conversion(from: SemanticType, to: SemanticType): Conversion = overloads.classify(from, to)

    /** The conversion of layer 11a/11b before task D1: what the scoring of [pickByArguments] was tuned on. */
    private fun simpleConversion(from: SemanticType, to: SemanticType): Conversion {
        if (to is SemanticType.Parameter || from is SemanticType.Parameter) return Conversion.UNKNOWN
        val fromName = definitionName(from)
        val toName = definitionName(to)
        if (fromName != null && fromName == toName) return Conversion.IDENTITY
        if (toName == OBJECT) return Conversion.IMPLICIT
        if (fromName != null && toName != null) {
            NUMERIC_CONVERSIONS[fromName]?.let { targets -> if (toName in targets) return Conversion.IMPLICIT }
            if (toName == "System.Nullable`1") return (to as? SemanticType.Library)?.arguments?.firstOrNull()?.let { simpleConversion(from, it) }?.let { if (it == Conversion.IDENTITY) Conversion.IMPLICIT else it } ?: Conversion.UNKNOWN
            val keys = HashSet<String>()
            collectSupertypes(from, keys, HashSet(), 0)
            if (toName in keys) return Conversion.IMPLICIT
            // what converts from a primitive or a string to what it does not derive from: nothing, unless a user conversion (rare in the libraries)
            if (fromName in PRIMITIVES && (toName in PRIMITIVES || (to is SemanticType.Library && !hasImplicitConversions(to.type)))) return Conversion.NONE
        }
        return Conversion.UNKNOWN
    }

    private fun hasImplicitConversions(type: IndexedType): Boolean = type.members.any { it.name == "op_Implicit" }

    internal fun definitionName(type: SemanticType): String? = when (type) {
        is SemanticType.Library -> type.type.fullName
        is SemanticType.Source -> type.info.qualifiedName + if (type.info.arity > 0) "`${type.info.arity}" else ""
        is SemanticType.ArrayOf -> "[]"
        is SemanticType.Parameter -> null
    }

    internal fun isValueType(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.STRUCT || type.type.kind == IndexedTypeKind.ENUM
        is SemanticType.Source -> type.info.kind.let { it == io.github.dotnetsupport.lang.TypeKind.STRUCT || it == io.github.dotnetsupport.lang.TypeKind.RECORD_STRUCT || it == io.github.dotnetsupport.lang.TypeKind.ENUM }
        is SemanticType.Parameter -> constraintClause(type)?.constraints.orEmpty().any { c ->
            // `where T : struct` / `unmanaged`: `T?` is `Nullable<T>`
            (c is CSharpClassOrStructConstraint && c.text.startsWith("struct")) || (c is CSharpTypeConstraint && c.text == "unmanaged")
        }
        else -> false
    }

    private fun constraintClause(parameter: SemanticType.Parameter): CSharpTypeParameterConstraintClause? {
        val clauses = when (val owner = parameter.owner) {
            is CSharpTypeDeclaration -> owner.constraintClauses
            is CSharpMethodDeclaration -> owner.constraintClauses
            is CSharpLocalFunctionStatement -> owner.constraintClauses
            is CSharpDelegateDeclaration -> owner.constraintClauses
            else -> return null
        }
        return clauses.firstOrNull { it.nameElement?.identifier?.text == parameter.name }
    }

    internal fun isNullable(type: SemanticType): Boolean = type is SemanticType.Library && type.type.fullName == "System.Nullable`1"

    /** The type of an argument for overload resolution: a lambda or a method group says nothing here. */
    private fun typeOfArgument(expression: CSharpExpression): SemanticType? = when {
        expression is CSharpAnonymousFunctionExpression -> null
        // `out var x` is typed by the parameter the overload resolution is choosing
        expression is CSharpDeclarationExpression && expression.type?.let(::isVar) != false -> null
        else -> typeOf(expression)
    }

    internal fun invocationOf(site: CSharpSimpleName): CSharpInvocationExpression? {
        var expression: PsiElement = site
        val parent = site.parent
        if (parent is CSharpMemberAccessExpression && parent.nameElement == site) expression = parent
        if (parent is CSharpMemberBindingExpression) expression = parent
        val call = expression.parent as? CSharpInvocationExpression ?: return null
        return if (call.expression == expression) call else null
    }

    // ---- named arguments, initializers

    /** `M(name: value)`: the parameter [text] of the method the call resolves to (one of the solution). */
    private fun namedArgument(colon: CSharpNameColon, text: String): List<CSharpSymbol>? {
        val argument = colon.parent as? CSharpArgument ?: return null
        val list = argument.parent as? CSharpArgumentList ?: return null
        val targets: List<PsiElement> = when (val owner = list.parent) {
            is CSharpInvocationExpression -> {
                val callee = when (val e = owner.expression) {
                    is CSharpSimpleName -> e
                    is CSharpMemberAccessExpression -> e.nameElement
                    is CSharpMemberBindingExpression -> e.nameElement
                    else -> null
                } ?: return null
                val resolution = resolveName(callee) ?: return null
                resolution.symbols.mapNotNull { symbol ->
                    when (symbol) {
                        is CSharpSymbol.SourceMember -> symbol.element
                        is CSharpSymbol.Local -> symbol.symbol.declaration.parent
                        else -> null
                    }
                }
            }
            is CSharpObjectCreationExpression -> constructorsOf(owner.type?.let(::resolveType))
            else -> return null
        }
        val parameters = targets.mapNotNull { target ->
            val parameterList = when (target) {
                is CSharpBaseMethodDeclaration -> target.parameterList
                is CSharpLocalFunctionStatement -> target.parameterList
                is CSharpTypeDeclaration -> target.parameterList
                is CSharpDelegateDeclaration -> target.parameterList
                else -> null
            }
            parameterList?.parameters?.firstOrNull { it.identifier?.text == text }?.identifier
        }.distinct()
        return if (parameters.size == 1) listOf(CSharpSymbol.Local(syntaxOf(parameters.single())?.symbolAt(parameters.single()) ?: return null)) else null
    }

    private fun syntaxOf(element: PsiElement): NativeCSharpResolver? = (element.containingFile as? CSharpFile)?.let { session.reachable(it)?.syntax }

    /** The constructors of a type of the solution and its primary constructor (the type declaration itself). */
    private fun constructorsOf(type: SemanticType?): List<PsiElement> {
        val info = (type as? SemanticType.Source)?.info ?: return emptyList()
        return info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.flatMap { declaration ->
            declaration.members.filterIsInstance<CSharpConstructorDeclaration>() + listOfNotNull(declaration.takeIf { it.parameterList != null })
        }
    }

    /** The type an object or `with` initializer sets the members of. */
    private fun createdType(owner: PsiElement?): SemanticType? = when (owner) {
        is CSharpExpression -> if (owner is CSharpWithExpression) owner.expression?.let(::typeOf) else typeOf(owner)
        else -> null
    }

    internal fun receiverOfBinding(binding: CSharpExpression): SemanticType? {
        var current: PsiElement = binding
        while (true) {
            val parent = current.parent ?: return null
            if (parent is CSharpConditionalAccessExpression && parent.whenNotNull == current) return parent.expression?.let(::typeOf)?.let(::unwrapNullable)
            if (parent is CSharpStatement || parent is CSharpMemberDeclaration) return null
            current = parent
        }
    }

    internal fun unwrapNullable(type: SemanticType): SemanticType =
        if (type is SemanticType.Library && type.type.fullName == "System.Nullable`1") type.arguments.firstOrNull() ?: type else type

    // ---- `var`

    /** `var` of a declaration with an initializer: the type of the initializer (Roslyn binds `var` to it). */
    private fun implicitType(name: CSharpSimpleName): List<CSharpSymbol>? {
        if (typeOrNamespace(name, "var", 0).isNotEmpty()) return null
        val type = implicitlyTyped(name) ?: return null
        return listOfNotNull(symbolOf(type))
    }

    internal fun implicitlyTyped(name: CSharpSimpleName): SemanticType? = when (val parent = name.parent) {
        is CSharpVariableDeclaration -> parent.variables.singleOrNull()?.initializer?.value?.let(::typeOf)
        is CSharpForEachStatement -> parent.expression?.let(::typeOf)?.let(::elementType)
        is CSharpDeclarationExpression -> (parent.designation as? CSharpSingleVariableDesignation)?.let { expressions.outVariableType(parent) ?: expressions.deconstructedType(it) } ?: typeOf(parent)
        else -> null
    }

    private fun symbolOf(type: SemanticType): CSharpSymbol? = when (type) {
        is SemanticType.Source -> CSharpSymbol.SourceType(type.info)
        is SemanticType.Library -> CSharpSymbol.LibraryType(type.type)
        is SemanticType.Parameter -> {
            val owner = type.owner?.takeIf { o -> (o.containingFile as? CSharpFile)?.let(session::reachable) != null }
            val list = when (owner) {
                is CSharpTypeDeclaration -> owner.typeParameterList
                is CSharpDelegateDeclaration -> owner.typeParameterList
                is CSharpMethodDeclaration -> owner.typeParameterList
                is CSharpLocalFunctionStatement -> owner.typeParameterList
                else -> null
            }
            val identifier = list?.parameters?.getOrNull(type.index)?.identifier
            identifier?.let { syntaxOf(it)?.symbolAt(it) }?.let { CSharpSymbol.Local(it) }
        }
        else -> null
    }

    /** Statics are reached through a type: `Color.Red` where `Color` is also a property of type `Color` (C# §12.8.7.2). */
    private fun isStaticOrType(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.SourceType, is CSharpSymbol.LibraryType -> true
        is CSharpSymbol.LibraryMember -> symbol.member.isStatic || symbol.member.kind == IndexedMemberKind.CONSTANT || symbol.member.kind == IndexedMemberKind.ENUM_MEMBER
        // by the color of the member: it comes from the stub, not from the AST of another file
        is CSharpSymbol.SourceMember -> symbol.member.referenceKey in STATIC_KEYS
        else -> false
    }

    // ---- types of symbols and expressions

    /** The type a type symbol is, with the type arguments [at] writes. */
    internal fun typeOfSymbol(symbol: CSharpSymbol, at: CSharpSimpleName?): SemanticType? {
        val arguments = (at as? CSharpGenericName)?.typeArgumentList?.arguments.orEmpty().map(::resolveType)
        return when (symbol) {
            is CSharpSymbol.SourceType -> SemanticType.Source(symbol.info, if (arguments.size == symbol.info.arity) arguments else List(symbol.info.arity) { null }, sourceOuter(symbol.info, at))
            is CSharpSymbol.LibraryType -> SemanticType.Library(symbol.type, outerArguments(symbol.type, at) + if (arguments.size == symbol.type.ownArity) arguments else List(symbol.type.ownArity) { null })
            else -> null
        }
    }

    /** The type around a type of the solution nested in a generic type (a type around it has type parameters); null for any other. */
    private fun outerTypeOf(info: TypeInfo): TypeInfo? {
        val parent = info.parts.firstOrNull()?.element()?.parent as? CSharpBaseTypeDeclaration ?: return null
        var at: PsiElement? = parent
        var generic = false
        while (at is CSharpBaseTypeDeclaration) {
            if ((at as? CSharpTypeDeclaration)?.typeParameterList != null) generic = true
            at = at.parent
        }
        return if (generic) syntax.declaredType(parent) else null
    }

    /** The generic type around the nested type [info] where [at] names it: `Outer<int>.Inner` as written, or the type around [at]. */
    private fun sourceOuter(info: TypeInfo, at: CSharpSimpleName?): SemanticType.Source? {
        val outer = outerTypeOf(info) ?: return null
        if (at == null) return null
        val qualifier: SemanticType? = when (val parent = at.parent) {
            is CSharpQualifiedName -> if (parent.right == at) (parent.left as? CSharpType)?.let(::resolveType) else null
            is CSharpMemberAccessExpression -> if (parent.nameElement == at) (parent.expression?.let(::qualifier) as? Qualifier.Type)?.type else null
            else -> null
        }
        (qualifier as? SemanticType.Source)?.takeIf { it.info.key == outer.key }?.let { return it }
        if (qualifier != null) return null
        // inside the outer type (or one derived from it), `Inner` is the one of the outer's own type parameters
        return syntax.enclosingTypes(at).firstOrNull { it.key == outer.key }?.let(::selfType)
    }

    /** The arguments of the types around a nested type of an assembly, as `Outer<int>.Inner` writes them. */
    private fun outerArguments(type: IndexedType, at: CSharpSimpleName?): List<SemanticType?> {
        val outer = type.declaringType ?: return emptyList()
        val qualifier = (at?.parent as? CSharpQualifiedName)?.takeIf { it.right == at }?.left ?: (at?.parent as? CSharpMemberAccessExpression)?.takeIf { it.nameElement == at }?.expression
        val written = (qualifier as? CSharpGenericName)?.typeArgumentList?.arguments?.map(::resolveType)
        return written?.takeIf { it.size == outer.arity } ?: List(outer.arity) { null }
    }

    /** The type of the value [symbol] is: a local, a parameter, a field, a property, an event, an enum member; what a method returns. */
    fun valueType(symbol: CSharpSymbol): SemanticType? = when (symbol) {
        is CSharpSymbol.Local -> localType(symbol)
        is CSharpSymbol.SourceMember -> memberType(symbol)
        is CSharpSymbol.LibraryMember -> fromRef(symbol.member.typeRef, symbol.declaringArguments)
        else -> null
    }

    internal fun localType(local: CSharpSymbol.Local): SemanticType? {
        val declaration = local.symbol.declaration
        if (localTypes.containsKey(declaration)) return localTypes[declaration]
        val before = cycles
        val result = computeLocalType(local)
        if (result != null || cycles == before) localTypes[declaration] = result
        return result
    }

    private fun computeLocalType(local: CSharpSymbol.Local): SemanticType? {
        val declaration = local.symbol.declaration
        if (!busy.add(declaration)) {
            cycles++
            return null
        }
        try {
            val resolver = (declaration.containingFile as? CSharpFile)?.let(session::reachable) ?: return null
            val owner = declaration.parent
            return when (local.symbol.kind) {
                LocalSymbolKind.LOCAL -> when (owner) {
                    is CSharpVariableDeclarator -> {
                        val declared = (owner.parent as? CSharpVariableDeclaration)?.type
                        if (declared == null || isVar(declared)) owner.initializer?.value?.let(resolver::typeOf) else resolver.resolveType(declared)
                    }
                    is CSharpForEachStatement -> owner.type?.takeIf { !isVar(it) }?.let(resolver::resolveType) ?: owner.expression?.let(resolver::typeOf)?.let(resolver::elementType)
                    is CSharpSingleVariableDesignation -> when (val holder = owner.parent) {
                        is CSharpDeclarationExpression -> holder.type?.takeIf { !isVar(it) }?.let(resolver::resolveType) ?: resolver.expressions.outVariableType(holder) ?: resolver.expressions.deconstructedType(owner)
                        is CSharpParenthesizedVariableDesignation -> resolver.expressions.deconstructedType(owner)
                        is CSharpDeclarationPattern -> holder.type?.let(resolver::resolveType)
                        is CSharpRecursivePattern -> holder.type?.let(resolver::resolveType) ?: resolver.expressions.patternInputType(holder)
                        is CSharpVarPattern -> resolver.expressions.patternInputType(holder)
                        else -> null
                    }
                    is CSharpCatchDeclaration -> owner.type?.let(resolver::resolveType)
                    // a range variable: the parameter of the lambda its query is translated to (§12.20.3, CSharpQueryTranslation)
                    is CSharpFromClause, is CSharpLetClause, is CSharpJoinClause, is CSharpJoinIntoClause, is CSharpQueryContinuation -> resolver.expressions.rangeVariableType(owner)
                    else -> null
                }
                LocalSymbolKind.PARAMETER -> (owner as? CSharpParameter)?.let { p -> p.type?.let(resolver::resolveType) ?: resolver.expressions.lambdaParameterType(p) }
                LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> (owner as? CSharpParameter)?.type?.let(resolver::resolveType)
                LocalSymbolKind.LOCAL_FUNCTION -> (owner as? CSharpLocalFunctionStatement)?.returnType?.let(resolver::resolveType)
                else -> null
            }
        } finally {
            busy.remove(declaration)
        }
    }

    internal fun isVar(type: CSharpType): Boolean = type is CSharpIdentifierName && type.identifier?.text == "var"

    private fun memberType(symbol: CSharpSymbol.SourceMember): SemanticType? {
        val element = symbol.element
        if (!busy.add(element)) {
            cycles++
            return null
        }
        try {
            val resolver = (element.containingFile as? CSharpFile)?.let(session::reachable) ?: return null
            val type: SemanticType? = when (element) {
                is CSharpBasePropertyDeclaration -> element.type?.let(resolver::resolveType)
                is CSharpBaseFieldDeclaration -> element.declaration?.type?.let(resolver::resolveType)
                is CSharpVariableDeclarator -> (element.parent as? CSharpVariableDeclaration)?.type?.let(resolver::resolveType)
                is CSharpMethodDeclaration -> element.returnType?.let(resolver::resolveType)
                is CSharpEnumMemberDeclaration -> (element.parent as? CSharpEnumDeclaration)?.let { resolver.syntax.declaredType(it) }?.let { SemanticType.Source(it, emptyList()) }
                is CSharpDelegateDeclaration -> null
                else -> (element.parent as? CSharpParameter)?.type?.let(resolver::resolveType) // a positional parameter of a record, by its name
            }
            val owner = symbol.owner
            return if (owner is SemanticType.Source) substitute(type, owner) else type
        } finally {
            busy.remove(element)
        }
    }

    /** The type of the elements `foreach` gets from a value of [type]. */
    fun elementType(type: SemanticType): SemanticType? = when (type) {
        is SemanticType.ArrayOf -> type.element
        is SemanticType.Library -> if (type.type.fullName == "System.String") libraryType("System.Char") else enumeratorCurrent(type) ?: enumerableArgument(type)
        is SemanticType.Source -> enumeratorCurrent(type) ?: libraryBases(type).firstNotNullOfOrNull { (it as? SemanticType.Library)?.let(::enumerableArgument) }
        is SemanticType.Parameter -> expressions.instanceOf(type, ENUMERABLE)?.arguments?.firstOrNull()
    }

    /** C# §13.9.5, the pattern: `GetEnumerator()` (`GetAsyncEnumerator()` of `await foreach`) and the type of its `Current`. */
    private fun enumeratorCurrent(type: SemanticType): SemanticType? {
        val method = (membersNamed(type, "GetEnumerator", 0).ifEmpty { membersNamed(type, "GetAsyncEnumerator", 0) })
            .filter { isMethod(it) && signature(it, false)?.none { p -> !p.optional } != false }.singleOrNull() ?: return null
        val enumerator = returnType(method, emptyList()) ?: return null
        return membersNamed(enumerator, "Current", 0).singleOrNull()?.let(::valueType)
    }

    private fun enumerableArgument(type: SemanticType.Library): SemanticType? {
        if (type.type.fullName == ENUMERABLE) return type.arguments.firstOrNull()
        val supertype = session.interfaces(assemblies, type.type).firstOrNull { it.type.fullName == ENUMERABLE } ?: return null
        return supertype.arguments.firstOrNull()?.let { fromRef(it, type.arguments) }
    }

    /** [reference] of an assembly's signature as a type, [typeArguments] for the type parameters of its type, [methodArguments] of its method. */
    fun fromRef(reference: IndexedTypeRef, typeArguments: List<SemanticType?>, methodArguments: List<SemanticType?> = emptyList()): SemanticType? = when (reference) {
        is IndexedTypeRef.Named -> session.findType(assemblies, null, reference.fullName)?.let { SemanticType.Library(it, emptyList()) }
        is IndexedTypeRef.Generic -> session.findType(assemblies, null, reference.definition.fullName)?.let { definition ->
            SemanticType.Library(definition, reference.arguments.map { fromRef(it, typeArguments, methodArguments) }, reference.tupleNames)
        }
        is IndexedTypeRef.TypeParameter -> (if (reference.ofMethod) methodArguments else typeArguments).getOrNull(reference.index)
        is IndexedTypeRef.ArrayOf -> SemanticType.ArrayOf(fromRef(reference.element, typeArguments, methodArguments), reference.rank)
        is IndexedTypeRef.ByRef -> fromRef(reference.element, typeArguments, methodArguments)
        else -> null
    }

    fun libraryType(fullName: String): SemanticType.Library? = session.findType(assemblies, null, fullName)?.let { SemanticType.Library(it, emptyList()) }

    /** What a type written in this file stands for. */
    fun resolveType(type: CSharpType): SemanticType? {
        if (types.containsKey(type)) return types[type]
        val result = computeType(type)
        types[type] = result
        return result
    }

    private fun computeType(type: CSharpType): SemanticType? = when (type) {
        is CSharpPredefinedType -> type.keyword?.text?.let { KEYWORD_TYPES[it] }?.let(::libraryType)
        is CSharpSimpleName -> {
            val leaf = type.identifier
            val local = leaf?.let(syntax::symbolAt)
            if (local != null && local.kind == LocalSymbolKind.TYPE_PARAMETER) {
                val owner = local.scope
                SemanticType.Parameter(local.name, owner, typeParameterNames(owner).indexOf(local.name), owner !is CSharpBaseTypeDeclaration && owner !is CSharpDelegateDeclaration)
            } else if (leaf?.text == "var" && type !is CSharpGenericName && (type.parent is CSharpVariableDeclaration || type.parent is CSharpForEachStatement ||
                    type.parent is CSharpDeclarationExpression) && typeOrNamespace(type, "var", 0).isEmpty()) {
                // `var`: the type it infers, with its type arguments (the symbol alone would lose them)
                implicitlyTyped(type)
            } else if ((leaf?.text == "nint" || leaf?.text == "nuint") && type !is CSharpGenericName && typeOrNamespace(type, leaf.text, 0).isEmpty()) {
                libraryType(if (leaf.text == "nint") "System.IntPtr" else "System.UIntPtr")
            } else {
                resolveName(type)?.single?.let { typeOfSymbol(it, type) }
            }
        }
        is CSharpQualifiedName -> type.right?.let { right -> resolveName(right)?.single?.let { typeOfSymbol(it, right) } }
        is CSharpAliasQualifiedName -> type.nameElement?.let { right -> resolveName(right)?.single?.let { typeOfSymbol(it, right) } }
        is CSharpArrayType -> {
            var result = type.elementType?.let(::resolveType)
            for (rank in type.rankSpecifiers.asReversed()) result = SemanticType.ArrayOf(result, rank.sizes.size.coerceAtLeast(1))
            result
        }
        is CSharpNullableType -> type.elementType?.let(::resolveType)?.let { element ->
            if (isValueType(element)) session.findType(assemblies, null, "System.Nullable`1")?.let { SemanticType.Library(it, listOf(element)) } ?: element else element
        }
        is CSharpTupleType -> session.findType(assemblies, null, "System.ValueTuple`${type.elements.size}")?.let { tuple -> SemanticType.Library(tuple, type.elements.map { it.type?.let(::resolveType) }, type.elements.map { it.identifier?.text }.takeIf { names -> names.any { it != null } }) }
        is CSharpRefType -> type.type?.let(::resolveType)
        is CSharpScopedType -> type.type?.let(::resolveType)
        else -> null
    }

    /** The natural type of the expression [expression] (layer 11b, [CSharpExpressionTypes]); a name that stands for a type has none here. */
    fun typeOf(expression: CSharpExpression): SemanticType? {
        if (expressionTypes.containsKey(expression)) return expressionTypes[expression]
        if (!busyTypes.add(expression)) {
            cycles++
            return null
        }
        try {
            val before = cycles
            val result = expressions.compute(expression)
            if (result != null || cycles == before) expressionTypes[expression] = result
            return result
        } finally {
            busyTypes.remove(expression)
        }
    }

    /**
     * The type of any expression node as Roslyn's `TypeInfo.Type` gives it: [typeOf] for values, and type syntax too — a name of a type
     * (`Console` of `Console.WriteLine`, `List<int>` of a declaration) is that type, `var` is the type it infers.
     */
    fun expressionType(expression: CSharpExpression): SemanticType? = expressions.ofNode(expression)

    /** The parameter types of a lambda without written types, from the delegate it converts to; null where that is not known. */
    fun lambdaParameterType(parameter: CSharpParameter): SemanticType? = expressions.lambdaParameterType(parameter)

    internal fun returnType(method: CSharpSymbol, typeArguments: List<SemanticType?>): SemanticType? = when (method) {
        is CSharpSymbol.LibraryMember -> fromRef(method.member.typeRef, method.declaringArguments, typeArguments)
        is CSharpSymbol.Local -> localType(method)
        is CSharpSymbol.SourceMember -> {
            val resolver = (method.element.containingFile as? CSharpFile)?.let(session::reachable)
            val element = (method.element as? CSharpMethodDeclaration)?.takeIf { resolver != null }
            val raw = element?.returnType?.let { resolver?.resolveType(it) }
            val names = typeParameterNames(element)
            val typed = if (typeArguments.isNotEmpty()) replace(raw) { p -> if (p.ofMethod && p.owner == element) typeArguments.getOrNull(names.indexOf(p.name)) else p } else raw
            val owner = method.owner
            if (owner is SemanticType.Source) substitute(typed, owner) else typed
        }
        else -> null
    }

    internal fun delegateReturnType(type: SemanticType): SemanticType? {
        val library = type as? SemanticType.Library ?: return null
        if (library.type.kind != IndexedTypeKind.DELEGATE) return null
        val invoke = library.type.members.firstOrNull { it.name == "Invoke" } ?: return null
        return fromRef(invoke.typeRef, library.arguments)
    }

    // ---- the namespaces a position sees

    /** One namespace on the way out from a position: its name, and what the `using` directives of its declaration (or of the file) bring. */
    private inner class Level(val namespace: String, private val usings: List<CSharpUsingDirective>, private val global: List<GlobalUsing> = emptyList()) {
        val imports: List<String> by lazy {
            usings.filter { it.alias == null && it.staticKeyword == null }.map { NativeCSharpResolver.compact(it.namespaceOrType).removePrefix("global::") } +
                global.filter { it.alias == null && !it.isStatic }.map { it.namespace.removePrefix("global::") }
        }

        private val aliases: Map<String, () -> CSharpSymbol?> by lazy {
            val map = HashMap<String, () -> CSharpSymbol?>()
            for (using in usings) {
                val alias = using.alias?.nameElement?.identifier?.text ?: continue
                val target = using.namespaceOrType ?: continue
                map[alias] = { aliasTarget(target) }
            }
            for (using in global) if (using.alias != null) map.putIfAbsent(using.alias, { resolveText(using.namespace) })
            map
        }

        fun alias(name: String): CSharpSymbol? = aliases[name]?.invoke()

        fun hasAlias(name: String): Boolean = name in aliases

        /**
         * Whether all this level brings is known (task C4c): every alias and `using static` type resolves. When not, an assembly or a
         * generated file is missing from the view, and a name the level does not find may well be there. A `using` of a namespace that is
         * nowhere does not count (0.1.142): the compiler reports it on the directive (CS0246 / CS0234) and goes on, nothing comes from it.
         */
        val known: Boolean by lazy {
            aliases.values.all { it() != null } &&
                statics().size == usings.count { it.alias == null && it.staticKeyword != null } + global.count { it.isStatic }
        }

        private var staticTypes: List<SemanticType>? = null

        fun statics(): List<SemanticType> = staticTypes ?: run {
            staticTypes = emptyList()
            val found = usings.filter { it.alias == null && it.staticKeyword != null }.mapNotNull { it.namespaceOrType?.let(::resolveType) } +
                global.filter { it.isStatic }.mapNotNull { resolveText(it.namespace)?.let { symbol -> typeOfSymbol(symbol, null) } }
            staticTypes = found
            found
        }
    }

    private fun aliasTarget(target: CSharpType): CSharpSymbol? {
        val rightmost = when (target) {
            is CSharpSimpleName -> target
            is CSharpQualifiedName -> target.right
            is CSharpAliasQualifiedName -> target.nameElement
            else -> return null
        } ?: return null
        return resolveName(rightmost)?.single
    }

    /** `A.B.C` written as text (a `Using` item, a `global using` of another file): from the global namespace. */
    private fun resolveText(text: String): CSharpSymbol? {
        if ('<' in text) return null
        var current: CSharpSymbol? = null
        for (part in text.removePrefix("global::").split('.')) {
            val found = when (val c = current) {
                null -> inNamespace("", part, 0)
                is CSharpSymbol.Namespace -> inNamespace(c.qualifiedName, part, 0)
                is CSharpSymbol.SourceType, is CSharpSymbol.LibraryType -> typeOfSymbol(c, null)?.let { nestedTypes(it, part, 0) }.orEmpty()
                else -> emptyList()
            }
            current = found.singleOrNull() ?: return null
        }
        return current
    }

    // ---- what the diagnostics ask (task C4c)

    /** Whether every level [at] sees is [Level.known]. */
    internal fun importsKnown(at: PsiElement): Boolean = levels(at).all { it.known }

    /** Whether a `using` alias named [name] is declared on the way out from [at] (resolving or not). */
    internal fun aliasNamed(at: PsiElement, name: String): Boolean = levels(at).any { it.hasAlias(name) }

    /** The types of the `using static` directives [at] sees. */
    internal fun staticTypes(at: PsiElement): List<SemanticType> = levels(at).flatMap { it.statics() }

    /** The namespaces [at] sees types and extension methods of: the enclosing ones and the imported ones. */
    internal fun visibleNamespaces(at: PsiElement): Set<String> = importedNamespaces(at)

    /** The namespaces the `using` directives (and global usings) import on the way out from [at], without the enclosing namespaces. */
    internal fun importedOnly(at: PsiElement): Set<String> = levels(at).flatMapTo(HashSet()) { it.imports }

    /** Whether the extension method [symbol] may take a receiver of [receiver] (its `this` parameter). */
    internal fun receiverFits(symbol: CSharpSymbol, receiver: SemanticType): Boolean = expressions.receiverFits(symbol, receiver)

    /** The scopes extension methods are looked up in from [at], innermost first (C# §12.8.10.3): a namespace and what its `using`s import. */
    internal fun extensionScopes(at: PsiElement): List<Pair<String, List<String>>> = levels(at).map { it.namespace to it.imports }

    /** The namespace of the static class an extension method of the solution is declared in; null when nested or broken. */
    internal fun namespaceOfMethod(method: PsiElement): String? = namespaceOfPsi(method)

    /** The levels from [at] outwards: each enclosing namespace (`namespace A.B` is `A.B`, then `A`), then the compilation unit. */
    private fun levels(at: PsiElement): List<Level> {
        val declaration = generateSequence(at.parent) { if (it is CSharpFile) null else it.parent }.firstOrNull { it is CSharpBaseNamespaceDeclaration }
            ?: return listOf(projectLevel)
        return levels.getOrPut(declaration) {
            val namespace = declaration as CSharpBaseNamespaceDeclaration
            val outer = levels(namespace)
            val full = (outer.firstOrNull()?.namespace?.takeIf { it.isNotEmpty() }?.let { "$it." } ?: "") + NativeCSharpResolver.compact(namespace.nameElement).removePrefix("global::")
            val own = ArrayList<Level>()
            // `namespace A.B { using X; }`: the usings are of `A.B`, `A` is a level of its own
            own += Level(full, namespace.usings)
            var prefix = full
            val outerName = outer.firstOrNull()?.namespace.orEmpty()
            while (true) {
                val dot = prefix.lastIndexOf('.')
                if (dot < 0) break
                prefix = prefix.substring(0, dot)
                if (prefix.length <= outerName.length) break
                own += Level(prefix, emptyList())
            }
            own + outer
        }
    }

    private fun compilationLevel(): Level {
        val own = file.compilationUnit?.usings.orEmpty()
        val global = session.globalUsings(file)
        return Level("", own, global)
    }

    companion object {
        private val STATIC_KEYS = setOf(
            CSharpColors.STATIC_FIELD, CSharpColors.STATIC_PROPERTY, CSharpColors.STATIC_METHOD_CALL, CSharpColors.CONSTANT,
        )

        private const val MAX_DEPTH = 16
        private const val OBJECT = "System.Object"
        private const val ENUMERABLE = "System.Collections.Generic.IEnumerable`1"

        private val ARRAY_INTERFACES = listOf("System.Collections.Generic.IList`1", "System.Collections.Generic.IReadOnlyList`1")

        /** `int` -> `System.Int32`: the types the keywords of C# stand for. */
        val KEYWORD_TYPES: Map<String, String> = mapOf(
            "bool" to "System.Boolean", "byte" to "System.Byte", "sbyte" to "System.SByte", "char" to "System.Char", "short" to "System.Int16",
            "ushort" to "System.UInt16", "int" to "System.Int32", "uint" to "System.UInt32", "long" to "System.Int64", "ulong" to "System.UInt64",
            "float" to "System.Single", "double" to "System.Double", "decimal" to "System.Decimal", "string" to "System.String", "object" to OBJECT,
            "void" to "System.Void", "nint" to "System.IntPtr", "nuint" to "System.UIntPtr",
        )

        private val PRIMITIVES = setOf(
            "System.Boolean", "System.Byte", "System.SByte", "System.Char", "System.Int16", "System.UInt16", "System.Int32", "System.UInt32", "System.Int64",
            "System.UInt64", "System.Single", "System.Double", "System.Decimal", "System.String",
        )

        /** C# §10.2.3: the implicit numeric conversions. */
        private val NUMERIC_CONVERSIONS: Map<String, Set<String>> = run {
            fun s(vararg names: String) = names.map { "System.$it" }.toSet()
            mapOf(
                "System.SByte" to s("Int16", "Int32", "Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.Byte" to s("Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal", "IntPtr", "UIntPtr"),
                "System.Int16" to s("Int32", "Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.UInt16" to s("Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal", "IntPtr", "UIntPtr"),
                "System.Int32" to s("Int64", "Single", "Double", "Decimal", "IntPtr"),
                "System.UInt32" to s("Int64", "UInt64", "Single", "Double", "Decimal", "UIntPtr"),
                "System.Int64" to s("Single", "Double", "Decimal"),
                "System.UInt64" to s("Single", "Double", "Decimal"),
                "System.Char" to s("UInt16", "Int32", "UInt32", "Int64", "UInt64", "Single", "Double", "Decimal"),
                "System.Single" to s("Double"),
            )
        }

        fun path(name: String, arity: Int): String = if (arity > 0) "$name`$arity" else name

        fun join(namespace: String, name: String): String = if (namespace.isEmpty()) name else "$namespace.$name"

        /** C# §6.4.5.3: the type of a numeric literal by its suffix and value. */
        fun numericKeyword(text: String): String? {
            val t = text.replace("_", "").lowercase()
            val hex = t.startsWith("0x")
            val binary = t.startsWith("0b")
            if (!hex && !binary) {
                if (t.endsWith("m")) return "decimal"
                if (t.endsWith("f")) return "float"
                if (t.endsWith("d") || '.' in t || 'e' in t) return "double"
            }
            val suffix = t.takeLastWhile { it == 'u' || it == 'l' }
            val digits = t.dropLast(suffix.length).let { if (hex || binary) it.drop(2) else it }
            val value = digits.toBigIntegerOrNull(if (hex) 16 else if (binary) 2 else 10) ?: return null
            val fitsInt = value <= Int.MAX_VALUE.toBigInteger()
            val fitsUInt = value <= 0xFFFFFFFFL.toBigInteger()
            val fitsLong = value <= Long.MAX_VALUE.toBigInteger()
            return when (suffix) {
                "" -> if (fitsInt) "int" else if (fitsUInt) "uint" else if (fitsLong) "long" else "ulong"
                "u" -> if (fitsUInt) "uint" else "ulong"
                "l" -> if (fitsLong) "long" else "ulong"
                else -> "ulong"
            }
        }
    }
}
