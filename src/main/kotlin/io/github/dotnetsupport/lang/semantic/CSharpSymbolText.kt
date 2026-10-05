package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMember
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedParameter
import io.github.dotnetsupport.index.IndexedType
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpStubsText
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypeKind

/**
 * How a symbol of the resolver is written for people (task C3): the type and parameters of an item of the completion list, the first line
 * of quick documentation as Roslyn's Quick Info writes it (`void Console.WriteLine(string value) (+ 17 overloads)`, `(field) int
 * Program.count`, `(local variable) List<int> numbers`). Types are minimally qualified, with what is known of the type arguments where the
 * symbol is used (`Add(int item)` of a `List<int>`); what the resolver does not know is written as declared.
 */
class CSharpSymbolText(private val resolver: CSharpNameResolver) {
    /** The type of a value member, the return type of a method; null for what has none. */
    fun typeOf(symbol: CSharpSymbol): String? = when (symbol) {
        is CSharpSymbol.LibraryMember -> if (symbol.member.kind == IndexedMemberKind.CONSTRUCTOR) null else library(symbol.member, symbol.member.typeRef, symbol.declaringArguments)
        is CSharpSymbol.SourceMember -> sourceType(symbol)
        is CSharpSymbol.Local -> resolver.localType(symbol)?.minimalDisplay ?: io.github.dotnetsupport.lang.NativeCSharpLocals.typeOf(symbol.symbol)
        else -> null
    }

    /**
     * With the `?` of a nullable reference type the assembly annotates (`string? value`), as Roslyn's Quick Info and signature help write
     * it (robot, E-83); a `T?` whose `T` is known to be a value type stays without it, as in C#.
     */
    private fun library(member: IndexedMember, reference: IndexedTypeRef, arguments: List<SemanticType?>): String {
        val text = resolver.fromRef(reference, arguments)?.minimalDisplay ?: return member.display(reference, nullable = true)
        if (!reference.annotated || text.endsWith("?")) return text
        if (reference is IndexedTypeRef.TypeParameter && text != member.display(reference)) return text
        return "$text?"
    }

    private fun sourceType(symbol: CSharpSymbol.SourceMember): String? {
        if (symbol.element is CSharpEnumMemberDeclaration) return null
        runCatching { resolver.valueType(symbol) }.getOrNull()?.minimalDisplay?.let { return it }
        val written = when (val element = symbol.element) {
            is CSharpMethodDeclaration -> element.returnType
            is CSharpBasePropertyDeclaration -> element.type
            is CSharpBaseFieldDeclaration -> element.declaration?.type
            is CSharpVariableDeclarator -> (element.parent as? CSharpVariableDeclaration)?.type
            is CSharpLocalFunctionStatement -> element.returnType
            else -> (element.parent as? CSharpParameter)?.type
        }
        return written?.text?.let(CSharpStubsText::collapse)
    }

    /** The parameters of a method as written in a list (`int count, string name = ""`); [reduced]: an extension method called on a receiver. */
    fun parameters(symbol: CSharpSymbol, reduced: Boolean): List<String>? = when (symbol) {
        is CSharpSymbol.LibraryMember -> if (!symbol.member.kind.isCallable && symbol.member.kind != IndexedMemberKind.CONSTRUCTOR) null else {
            val all = resolver.session.parameters(symbol.member)
            (if (reduced && symbol.member.kind == IndexedMemberKind.EXTENSION_METHOD) all.drop(1) else all).map { parameter(symbol.member, it, symbol.declaringArguments, reduced) }
        }
        is CSharpSymbol.SourceMember -> sourceParameters(symbol.element)?.let { list ->
            val parameters = if (reduced && list.firstOrNull()?.modifiers?.any { it.text == "this" } == true) list.drop(1) else list
            parameters.map { CSharpStubsText.collapse(it.text) }
        }
        is CSharpSymbol.Local -> (symbol.symbol.declaration.parent as? CSharpLocalFunctionStatement)?.parameterList?.parameters?.map { CSharpStubsText.collapse(it.text) }
        else -> null
    }

    private fun sourceParameters(element: PsiElement): List<CSharpParameter>? = when (element) {
        is CSharpBaseMethodDeclaration -> element.parameterList?.parameters
        is CSharpLocalFunctionStatement -> element.parameterList?.parameters
        is CSharpDelegateDeclaration -> element.parameterList?.parameters
        is CSharpTypeDeclaration -> element.parameterList?.parameters
        else -> null
    }

    private fun parameter(member: IndexedMember, parameter: IndexedParameter, arguments: List<SemanticType?>, reduced: Boolean): String {
        val type = library(member, parameter.typeRef, arguments)
        val modifiers = listOfNotNull(
            "this".takeIf { parameter.isThis && !reduced }, "params".takeIf { parameter.isParams }, "out".takeIf { parameter.isOut },
            "ref".takeIf { parameter.isRef }, "in".takeIf { parameter.isIn && !parameter.isOut },
        )
        val default = parameter.defaultValue?.let { " = $it" } ?: if (parameter.hasDefault || parameter.isOptional) " = default" else ""
        return (modifiers + type + parameter.name).joinToString(" ") + default
    }

    /** Whether a method returns nothing: `();` after its name when chosen. */
    fun returnsNothing(symbol: CSharpSymbol): Boolean = when (symbol) {
        is CSharpSymbol.LibraryMember -> (symbol.member.typeRef as? IndexedTypeRef.Named)?.fullName == "System.Void"
        is CSharpSymbol.SourceMember -> (symbol.element as? CSharpMethodDeclaration)?.returnType?.text == "void"
        else -> false
    }

    // ---- Quick Info

    /** The first line of quick documentation of [symbol]; [overloads]: how many other overloads the name has where it is used. */
    fun quickInfo(symbol: CSharpSymbol, overloads: Int = 0): String? {
        val text = when (symbol) {
            is CSharpSymbol.Namespace -> "namespace ${symbol.qualifiedName}"
            is CSharpSymbol.LibraryType -> libraryTypeInfo(symbol.type)
            is CSharpSymbol.SourceType -> sourceTypeInfo(symbol)
            is CSharpSymbol.LibraryMember -> libraryMemberInfo(symbol)
            is CSharpSymbol.SourceMember -> sourceMemberInfo(symbol)
            is CSharpSymbol.Local -> localInfo(symbol)
        } ?: return null
        return if (overloads > 0) "$text (+ $overloads overload${if (overloads > 1) "s" else ""})" else text
    }

    private fun libraryTypeInfo(type: IndexedType): String {
        val keyword = when (type.kind) {
            IndexedTypeKind.CLASS -> if (type.isRecord) "record" else "class"
            IndexedTypeKind.STATIC_CLASS -> "class"
            IndexedTypeKind.STRUCT -> if (type.isRecord) "record struct" else "struct"
            IndexedTypeKind.INTERFACE -> "interface"
            IndexedTypeKind.ENUM -> "enum"
            IndexedTypeKind.DELEGATE -> "delegate"
        }
        val parameters = type.typeParameters.map { it.name }
        val name = type.qualifiedName.substringBefore('`') + if (parameters.isEmpty()) "" else parameters.joinToString(", ", "<", ">")
        return "$keyword $name"
    }

    private fun sourceTypeInfo(symbol: CSharpSymbol.SourceType): String {
        val info = symbol.info
        val declaration = info.parts.firstOrNull()?.element()
        val keyword = when (info.kind) {
            TypeKind.CLASS, TypeKind.STATIC_CLASS, null -> "class"
            TypeKind.RECORD -> "record"
            TypeKind.RECORD_STRUCT -> "record struct"
            TypeKind.STRUCT -> "struct"
            TypeKind.INTERFACE -> "interface"
            TypeKind.ENUM -> "enum"
            TypeKind.DELEGATE -> "delegate"
        }
        val parameters = resolver.typeParameterNames(declaration)
        val name = info.qualifiedName + if (parameters.isEmpty()) "" else parameters.joinToString(", ", "<", ">")
        if (declaration is CSharpDelegateDeclaration) {
            val returns = declaration.returnType?.text?.let(CSharpStubsText::collapse) ?: "void"
            return "delegate $returns $name(${declaration.parameterList?.parameters.orEmpty().joinToString(", ") { CSharpStubsText.collapse(it.text) }})"
        }
        return "$keyword $name"
    }

    private fun libraryMemberInfo(symbol: CSharpSymbol.LibraryMember): String {
        val member = symbol.member
        val owner = libraryOwner(member.type, symbol.declaringArguments)
        val type = typeOf(symbol)
        val typeParameters = if (member.arity > 0) member.typeParameters.joinToString(", ", "<", ">") { it.name } else ""
        return when (member.kind) {
            IndexedMemberKind.METHOD, IndexedMemberKind.EXTENSION_METHOD, IndexedMemberKind.OPERATOR -> {
                val prefix = if (member.kind == IndexedMemberKind.EXTENSION_METHOD) "(extension) " else ""
                "$prefix$type $owner.${member.name}$typeParameters(${parameters(symbol, false).orEmpty().joinToString(", ")})"
            }
            IndexedMemberKind.CONSTRUCTOR -> "$owner(${parameters(symbol, false).orEmpty().joinToString(", ")})"
            IndexedMemberKind.PROPERTY, IndexedMemberKind.INDEXER -> "$type $owner.${member.name} ${accessors(member)}"
            IndexedMemberKind.FIELD -> "(field) $type $owner.${member.name}"
            IndexedMemberKind.CONSTANT -> "(constant) $type $owner.${member.name}" + (member.constantValue?.let { " = $it" } ?: "")
            IndexedMemberKind.ENUM_MEMBER -> "$owner.${member.name}" + (member.constantValue?.let { " = $it" } ?: "")
            IndexedMemberKind.EVENT -> "event $type $owner.${member.name}"
        }
    }

    private fun accessors(member: IndexedMember): String = listOfNotNull(
        "get;".takeIf { member.hasGetter }, (if (member.isInitOnly) "init;" else "set;").takeIf { member.hasSetter },
    ).joinToString(" ", "{ ", " }")

    /** `List<int>` where the arguments are known, `List<T>` where not. */
    private fun libraryOwner(type: IndexedType, arguments: List<SemanticType?>): String {
        // `string.Substring`, as Roslyn writes the special types
        CSharpTypeDisplay.KEYWORDS[type.fullName]?.let { return it }
        if (type.arity == 0) return type.name
        val known = SemanticType.Library(type, arguments).takeIf { arguments.size == type.arity }?.minimalDisplay
        return known ?: (type.name + type.typeParameters.joinToString(", ", "<", ">") { it.name })
    }

    private fun sourceMemberInfo(symbol: CSharpSymbol.SourceMember): String? {
        val element = symbol.element
        val container = generateSequence(element.parent) { it.parent }.firstOrNull { it is CSharpBaseTypeDeclaration }
        val owner = (container as? CSharpBaseTypeDeclaration)?.let(resolver.syntax::declaredType)?.let { info ->
            val parameters = resolver.typeParameterNames(container)
            info.qualifiedName.substringAfterLast('.') + if (parameters.isEmpty()) "" else parameters.joinToString(", ", "<", ">")
        } ?: (container as? CSharpBaseTypeDeclaration)?.identifier?.text.orEmpty()
        val type = typeOf(symbol)
        val modifiers = (element as? CSharpMemberDeclaration)?.modifiers?.map { it.text }
            ?: (element.parent?.parent?.parent as? CSharpMemberDeclaration)?.modifiers?.map { it.text }.orEmpty()
        return when (element) {
            is CSharpMethodDeclaration -> {
                val typeParameters = element.typeParameterList?.parameters?.joinToString(", ", "<", ">") { it.identifier?.text.orEmpty() }.orEmpty()
                val prefix = if (element.parameterList?.parameters?.firstOrNull()?.modifiers?.any { it.text == "this" } == true) "(extension) " else ""
                "$prefix$type $owner.${element.identifier?.text}$typeParameters(${parameters(symbol, false).orEmpty().joinToString(", ")})"
            }
            is CSharpConstructorDeclaration -> "$owner(${parameters(symbol, false).orEmpty().joinToString(", ")})"
            is CSharpEventDeclaration -> "event $type $owner.${element.identifier?.text}"
            is CSharpPropertyDeclaration -> "$type $owner.${element.identifier?.text} ${propertyAccessors(element)}"
            is CSharpIndexerDeclaration -> "$type $owner[${element.parameterList?.parameters.orEmpty().joinToString(", ") { CSharpStubsText.collapse(it.text) }}]"
            is CSharpEnumMemberDeclaration -> "$owner.${element.identifier?.text}" + (element.equalsValue?.value?.text?.let { " = ${CSharpStubsText.collapse(it)}" } ?: "")
            is CSharpBaseFieldDeclaration, is CSharpVariableDeclarator -> {
                val declarator = element as? CSharpVariableDeclarator ?: (element as CSharpBaseFieldDeclaration).declaration?.variables?.firstOrNull()
                val name = declarator?.identifier?.text ?: return null
                when {
                    element is CSharpEventFieldDeclaration || element.parent?.parent is CSharpEventFieldDeclaration -> "event $type $owner.$name"
                    "const" in modifiers -> "(constant) $type $owner.$name" + (declarator.initializer?.value?.text?.let { " = ${CSharpStubsText.collapse(it)}" } ?: "")
                    else -> "(field) $type $owner.$name"
                }
            }
            else -> {
                // a positional parameter of a record: its property
                val parameter = element.parent as? CSharpParameter ?: return null
                "$type $owner.${parameter.identifier?.text} { get; init; }"
            }
        }
    }

    private fun propertyAccessors(property: CSharpPropertyDeclaration): String {
        if (property.expressionBody != null) return "{ get; }"
        val accessors = property.accessorList?.accessors.orEmpty().mapNotNull { it.keyword?.text }
        return accessors.joinToString(" ", "{ ", " }") { "$it;" }
    }

    private fun localInfo(symbol: CSharpSymbol.Local): String {
        val local = symbol.symbol
        val type = typeOf(symbol)
        return when (local.kind) {
            LocalSymbolKind.LOCAL -> {
                val constant = (local.declaration.parent?.parent?.parent as? CSharpLocalDeclarationStatement)?.modifiers?.any { it.text == "const" } == true
                // as Roslyn's Quick Info: a variable of a query is a range variable
                val range = when (local.declaration.parent) {
                    is CSharpFromClause, is CSharpLetClause, is CSharpJoinClause, is CSharpJoinIntoClause, is CSharpQueryContinuation -> true
                    else -> false
                }
                (if (range) "(range variable) " else if (constant) "(local constant) " else "(local variable) ") + listOfNotNull(type, local.name).joinToString(" ")
            }
            LocalSymbolKind.PARAMETER, LocalSymbolKind.PRIMARY_CONSTRUCTOR_PARAMETER -> "(parameter) " + listOfNotNull(type, local.name).joinToString(" ")
            LocalSymbolKind.LOCAL_FUNCTION -> "(local function) " + listOfNotNull(type, local.name).joinToString(" ") + "(${parameters(symbol, false).orEmpty().joinToString(", ")})"
            LocalSymbolKind.TYPE_PARAMETER -> {
                val owner = generateSequence(local.declaration.parent) { it.parent }.firstOrNull { it is CSharpBaseTypeDeclaration || it is CSharpMethodDeclaration || it is CSharpDelegateDeclaration || it is CSharpLocalFunctionStatement }
                val ownerName = when (owner) {
                    is CSharpBaseTypeDeclaration -> owner.identifier?.text
                    is CSharpMethodDeclaration -> owner.identifier?.text
                    is CSharpDelegateDeclaration -> owner.identifier?.text
                    is CSharpLocalFunctionStatement -> owner.identifier?.text
                    else -> null
                }
                if (ownerName != null) "${local.name} in $ownerName" else local.name
            }
            LocalSymbolKind.LABEL -> "(label) ${local.name}"
        }
    }
}
