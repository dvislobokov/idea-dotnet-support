package io.github.dotnetsupport.lang.semantic

import io.github.dotnetsupport.csharp.lang.psi.CSharpConstructorDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpTypeDeclaration
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind

// The `new()` constraint, for the compiler error CS0310 (CSharpGenericChecks) and for the applicability of a candidate (CSharpOverloads).

/** Whether [type] satisfies `new()`: a value type, or a non-abstract class with a public constructor without parameters; null when not known. */
internal fun CSharpNameResolver.newable(type: SemanticType, isPartial: (TypeInfo) -> Boolean): Boolean? = when (type) {
    // an array has no parameterless constructor
    is SemanticType.ArrayOf -> false
    is SemanticType.Library -> when (type.type.kind) {
        IndexedTypeKind.STRUCT, IndexedTypeKind.ENUM -> true
        IndexedTypeKind.INTERFACE, IndexedTypeKind.DELEGATE -> false
        IndexedTypeKind.CLASS -> if (type.type.isStatic) null else if (type.type.isAbstract) false else {
            val constructors = type.type.members.filter { it.kind == IndexedMemberKind.CONSTRUCTOR && !it.isStatic }
            if (constructors.isEmpty()) null else constructors.any { !it.isProtected && session.parameters(it).isEmpty() }
        }
        else -> null
    }
    is SemanticType.Source -> when (type.info.kind) {
        TypeKind.STRUCT, TypeKind.RECORD_STRUCT, TypeKind.ENUM -> true
        TypeKind.INTERFACE, TypeKind.DELEGATE -> false
        TypeKind.CLASS, TypeKind.RECORD -> sourceNewable(type, isPartial)
        else -> null
    }
    else -> null
}

private fun CSharpNameResolver.sourceNewable(type: SemanticType.Source, isPartial: (TypeInfo) -> Boolean): Boolean? {
    if (isPartial(type.info)) return null
    val declarations = type.info.parts.map { part ->
        val declaration = part.element() as? CSharpTypeDeclaration ?: return null
        (declaration.containingFile as? CSharpFile)?.let(session::reachable) ?: return null
        declaration
    }
    if (declarations.any { d -> d.modifiers.any { it.text == "abstract" } }) return false
    val constructors = declarations.flatMap { d -> d.members.filterIsInstance<CSharpConstructorDeclaration>().filter { c -> c.modifiers.none { it.text == "static" } } }
    val primary = declarations.mapNotNull { it.parameterList }
    if (constructors.isEmpty() && primary.isEmpty()) return true
    return primary.any { it.parameters.isEmpty() } || constructors.any { c -> c.modifiers.any { it.text == "public" } && c.parameterList?.parameters.isNullOrEmpty() }
}
