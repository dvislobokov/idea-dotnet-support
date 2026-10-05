package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeKind

/**
 * What the Alt+Enter actions and the checks of `using` ask of the types of expressions (C2), beyond Roslyn's display: the type as it is
 * written at a place (task A7, "Use explicit type") and whether a type implements an interface (task A8: CS1674, CS8410, the `using`
 * list). Every answer is `null` where a part is unknown — an unresolved base, a type parameter: never a guess.
 */
object CSharpTypeFacts {
    const val DISPOSABLE = "System.IDisposable"
    const val ASYNC_DISPOSABLE = "System.IAsyncDisposable"
    private const val MAX_DEPTH = 16

    /**
     * [type] as code at [at] writes it: keywords for the special types, `T?`, tuple syntax, and a named type by its simple name where that
     * name means the type there (its namespace is imported or encloses [at], nothing nearer hides it), else with its namespace.
     */
    fun written(resolver: CSharpNameResolver, type: SemanticType?, at: PsiElement): String? =
        CSharpTypeDisplay.display(type) { named -> simpleNameMeans(resolver, named, at) }

    private fun simpleNameMeans(resolver: CSharpNameResolver, type: SemanticType, at: PsiElement): Boolean = when (type) {
        is SemanticType.Source -> {
            // the outermost type is what the simple name is looked up as (`Outer.Inner`)
            var outermost: PsiElement? = type.info.parts.firstOrNull()?.element()
            while (outermost?.parent is CSharpBaseTypeDeclaration) outermost = outermost.parent
            val declaration = outermost as? CSharpBaseTypeDeclaration
            val name = declaration?.identifier?.text
            if (declaration == null || name == null) false else {
                val arity = (declaration as? CSharpTypeDeclaration)?.typeParameterList?.parameters?.size ?: 0
                val found = resolver.typeOrNamespace(at, name, arity).singleOrNull() as? CSharpSymbol.SourceType
                found != null && found.info.parts.any { it.element() == declaration }
            }
        }
        is SemanticType.Library -> {
            val first = type.type.path.substringBefore('+')
            val (name, arity) = IndexedTypeRef.segments(first).first()
            val found = resolver.typeOrNamespace(at, name, arity).singleOrNull() as? CSharpSymbol.LibraryType
            found != null && found.type.namespace == type.type.namespace && found.type.path == first
        }
        else -> false
    }

    /** Whether [type] is or derives from the interface (or class) [target] (`System.IDisposable`); null when that cannot be told. */
    fun implements(resolver: CSharpNameResolver, type: SemanticType?, target: String, depth: Int = 0): Boolean? {
        if (type == null || depth > MAX_DEPTH) return null
        return when (type) {
            is SemanticType.Library -> library(resolver, type.type, target, depth)
            is SemanticType.Source -> {
                var unknown = false
                for (part in type.info.parts) {
                    val declaration = part.element() as? CSharpBaseTypeDeclaration ?: return null
                    val owner = (declaration.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: return null
                    for (base in declaration.baseList?.types.orEmpty()) {
                        val resolved = base.type?.let(owner::resolveType) ?: return null
                        when (implements(resolver, resolved, target, depth + 1)) {
                            true -> return true
                            null -> unknown = true
                            false -> {}
                        }
                    }
                }
                if (unknown) null else false
            }
            // arrays implement the collection interfaces, never the disposable ones
            is SemanticType.ArrayOf -> if (target == DISPOSABLE || target == ASYNC_DISPOSABLE) false else null
            is SemanticType.Parameter -> null
        }
    }

    private fun library(resolver: CSharpNameResolver, type: IndexedType, target: String, depth: Int): Boolean? {
        if (type.fullName == target) return true
        if (depth > MAX_DEPTH) return null
        var unknown = false
        for (reference in type.interfaces + listOfNotNull(type.baseType)) {
            if (reference.definitionName == target) return true
            // a base the references do not have (an assembly not indexed): nothing can be said
            val resolved = resolver.assemblies.resolve(reference)
            if (resolved == null) {
                unknown = true
                continue
            }
            when (library(resolver, resolved, target, depth + 1)) {
                true -> return true
                null -> unknown = true
                false -> {}
            }
        }
        return if (unknown) null else false
    }

    /**
     * The error of Roslyn for a resource of [type] in `using` ([async]: `await using`) at [site], or null: the type is disposable as asked,
     * may be (unknown parts, a `Dispose` of a `ref struct`, a `DisposeAsync` method or extension of the pattern), or is not known.
     */
    fun usingError(resolver: CSharpNameResolver, type: SemanticType?, async: Boolean, site: PsiElement): UsingError? {
        type ?: return null
        val shown = CSharpTypeDisplay.display(type) ?: return null
        // `using (S? s = …)` of a disposable struct is legal: the question is the struct's
        if (resolver.isNullable(type)) return usingError(resolver, resolver.unwrapNullable(type), async, site)?.let { UsingError(it.code, it.message.replaceFirst("'${CSharpTypeDisplay.display(resolver.unwrapNullable(type))}'", "'$shown'")) }
        val (wanted, other) = if (async) ASYNC_DISPOSABLE to DISPOSABLE else DISPOSABLE to ASYNC_DISPOSABLE
        if (implements(resolver, type, wanted) != false) return null
        if (async) {
            // `await using` takes any type with an accessible `DisposeAsync()` (C# 8 pattern), an extension method one included
            if (resolver.membersNamed(type, "DisposeAsync", 0).isNotEmpty() || resolver.extensionMethods(type, "DisposeAsync", 0, site).isNotEmpty()) return null
        } else if (isStruct(type) && resolver.membersNamed(type, "Dispose", 0).isNotEmpty()) {
            // a `ref struct` with a `Dispose()` method is disposable by the pattern; the index does not say which structs are `ref`
            return null
        }
        val hasOther = implements(resolver, type, other) == true
        return when {
            async && hasOther -> UsingError("CS8417", "'$shown': type used in an asynchronous using statement must implement 'System.IAsyncDisposable' or implement a suitable 'DisposeAsync' method. Did you mean 'using' rather than 'await using'?")
            async -> UsingError("CS8410", "'$shown': type used in an asynchronous using statement must implement 'System.IAsyncDisposable' or implement a suitable 'DisposeAsync' method.")
            hasOther -> UsingError("CS8418", "'$shown': type used in a using statement must implement 'System.IDisposable'. Did you mean 'await using' rather than 'using'?")
            else -> UsingError("CS1674", "'$shown': type used in a using statement must implement 'System.IDisposable'.")
        }
    }

    private fun isStruct(type: SemanticType): Boolean = when (type) {
        is SemanticType.Library -> type.type.kind == IndexedTypeKind.STRUCT
        is SemanticType.Source -> type.info.kind == TypeKind.STRUCT || type.info.kind == TypeKind.RECORD_STRUCT
        else -> false
    }

    /** A semantic error of Roslyn: its code and message (the server sends the message alone). */
    class UsingError(val code: String, val message: String) {
        val text: String get() = "$code: $message"
    }
}
