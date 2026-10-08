package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeKind

/**
 * «Inconsistent accessibility» (compiler-messages, 0.1.143): a type named in the signature of a member or in the base list of a type must
 * be at least as visible as what names it — CS0050 / CS0051 (the return and parameter types of a method or a constructor), CS0052 (a
 * field), CS0053 (a property), CS7025 (an event), CS0054 / CS0055 (an indexer), CS0056 / CS0057 (an operator), CS0058 / CS0059 (a delegate),
 * CS0060 / CS0061 (a base class or interface). The effective accessibility of a symbol is its declared one met with those of the types
 * around it (Roslyn's `IsAtLeastAsVisibleAs`): `protected` met with `internal` is `private protected`, anything met with `private` is private
 * to that containing type. Silent where a type does not resolve or is a type parameter, for explicit interface implementations and for
 * members of interfaces and of extension blocks (their accessibility is not theirs to say).
 */
internal class CSharpAccessibilityChecks(
    private val resolver: CSharpNameResolver,
    private val quiet: (PsiElement) -> Boolean,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val file: CSharpFile = resolver.file

    private enum class Acc { PRIVATE, PRIVATE_PROTECTED, PROTECTED, INTERNAL, PROTECTED_INTERNAL, PUBLIC }

    /** An effective accessibility: [scope] is the type declaration a private one is private to. */
    private class Effective(val acc: Acc, val scope: PsiElement?)

    fun run() {
        for (declaration in resolver.syntax.scopes.declarations) {
            when (declaration) {
                is CSharpTypeDeclaration -> if (declaration.node.elementType != SyntaxKind.ExtensionBlockDeclaration && !quiet(declaration)) checkType(declaration)
                is CSharpDelegateDeclaration -> if (!quiet(declaration)) checkDelegate(declaration)
                else -> {}
            }
        }
    }

    // ---- what is checked

    private fun checkType(type: CSharpTypeDeclaration) {
        val kind = type.keyword?.text
        val own = effectiveOfType(type) ?: return
        val shown = shownType(type) ?: return
        if (kind == "interface") {
            for (base in type.baseList?.types.orEmpty()) base.type?.let { check(it, own, "CS0061", "base interface", "interface", shown, type.identifier) }
            return   // interface members: public by default, their accessibility is the interface's
        }
        for (base in type.baseList?.types.orEmpty()) {
            val baseType = base.type ?: continue
            val resolved = resolver.resolveType(baseType) ?: continue
            val baseKind = kindOf(resolved) ?: continue
            if (baseKind == TypeKind.INTERFACE) continue   // a class implementing a less visible interface is fine: only its base class counts
            if (kind == "class" || kind == "record") check(baseType, own, "CS0060", "base class", kind, shown, type.identifier)
        }
        for (member in type.members) {
            if (quiet(member)) continue
            when (member) {
                is CSharpMethodDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val label = methodShown(member, shown) ?: continue
                    member.returnType?.let { check(it, eff, "CS0050", "return type", "method", label, member.identifier) }
                    for (p in member.parameterList?.parameters.orEmpty()) p.type?.let { check(it, eff, "CS0051", "parameter type", "method", label, member.identifier) }
                }
                is CSharpConstructorDeclaration -> {
                    if (member.modifiers.any { it.text == "static" }) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val label = constructorShown(member, shown) ?: continue
                    for (p in member.parameterList?.parameters.orEmpty()) p.type?.let { check(it, eff, "CS0051", "parameter type", "method", label, member.identifier) }
                }
                is CSharpOperatorDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val label = operatorShown(member, shown) ?: continue
                    member.returnType?.let { check(it, eff, "CS0056", "return type", "operator", label, member.operatorToken) }
                    for (p in member.parameterList?.parameters.orEmpty()) p.type?.let { check(it, eff, "CS0057", "parameter type", "operator", label, member.operatorToken) }
                }
                is CSharpConversionOperatorDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val label = conversionShown(member, shown) ?: continue
                    member.type?.let { check(it, eff, "CS0056", "return type", "operator", label, member.type) }
                    for (p in member.parameterList?.parameters.orEmpty()) p.type?.let { check(it, eff, "CS0057", "parameter type", "operator", label, member.type) }
                }
                is CSharpPropertyDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val name = member.identifier ?: continue
                    member.type?.let { check(it, eff, "CS0053", "property type", "property", "$shown.${name.text}", name) }
                }
                is CSharpEventDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val name = member.identifier ?: continue
                    member.type?.let { check(it, eff, "CS7025", "event type", "event", "$shown.${name.text}", name) }
                }
                is CSharpIndexerDeclaration -> {
                    if (member.explicitInterfaceSpecifier != null) continue
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val label = indexerShown(member, shown) ?: continue
                    member.type?.let { check(it, eff, "CS0054", "indexer return type", "indexer", label, member.thisKeyword) }
                    for (p in member.parameterList?.parameters.orEmpty()) p.type?.let { check(it, eff, "CS0055", "parameter type", "indexer", label, member.thisKeyword) }
                }
                is CSharpBaseFieldDeclaration -> {
                    val eff = effectiveOfMember(member.modifiers, own, type) ?: continue
                    val declaration = member.declaration ?: continue
                    val fieldType = declaration.type ?: continue
                    val what = if (member is CSharpEventFieldDeclaration) "event" else "field"
                    val code = if (member is CSharpEventFieldDeclaration) "CS7025" else "CS0052"
                    for (v in declaration.variables) v.identifier?.let { check(fieldType, eff, code, "$what type", what, "$shown.${it.text}", it) }
                }
                is CSharpDelegateDeclaration -> checkDelegate(member)
                else -> {}
            }
        }
    }

    private fun checkDelegate(delegate: CSharpDelegateDeclaration) {
        val own = effectiveOfType(delegate) ?: return
        val shown = shownType(delegate) ?: return
        delegate.returnType?.let { check(it, own, "CS0058", "return type", "delegate", shown, delegate.identifier) }
        for (p in delegate.parameterList?.parameters.orEmpty()) p.type?.let { check(it, own, "CS0059", "parameter type", "delegate", shown, delegate.identifier) }
    }

    /** Reports [code] at [at] when the type [syntax] names is less visible than [owner] (the effective accessibility of what names it). */
    private fun check(syntax: CSharpType, owner: Effective, code: String, what: String, kind: String, shown: String, at: PsiElement?) {
        val range = at?.textRange ?: return
        val type = resolver.resolveType(syntax) ?: return
        val typeAcc = effectiveOfSemantic(type, 0) ?: return
        if (atLeastAsVisible(typeAcc, owner)) return
        val typeShown = CSharpTypeDisplay.display(type, qualified = false) ?: return
        report(code, "Inconsistent accessibility: $what '$typeShown' is less accessible than $kind '$shown'", range)
    }

    // ---- accessibility

    /** The effective accessibility of a type declaration: its own met with its containers'. Null when the parts of a partial type disagree. */
    private fun effectiveOfType(declaration: CSharpMemberDeclaration): Effective? {
        val outer = PsiTreeUtil.getParentOfType(declaration, CSharpBaseTypeDeclaration::class.java)
        val outerAcc = outer?.let { effectiveOfType(it) ?: return null }
        var modifiers: Collection<String> = declaration.modifiers.map { it.text }
        val info = (declaration as? CSharpBaseTypeDeclaration)?.let { resolver.syntax.declaredType(it) }
        if (info != null && info.parts.size > 1) {
            // any part may say the accessibility; two parts saying different ones is CS0262, not said here
            val said = info.parts.map { part -> part.modifiers.filter { it in ACCESS_MODIFIERS }.toSet() }.filter { it.isNotEmpty() }.toSet()
            if (said.size > 1) return null
            said.firstOrNull()?.let { modifiers = it }
        }
        return meet(declaredAccessibility(modifiers, nested = outer != null), declaration, outerAcc)
    }

    /** The effective accessibility of a member declared with [modifiers] in [owner] (whose effective accessibility is [ownerAcc]). */
    private fun effectiveOfMember(modifiers: List<PsiElement>, ownerAcc: Effective, owner: CSharpTypeDeclaration): Effective? =
        meet(declaredAccessibility(modifiers.map { it.text }, nested = true), owner, ownerAcc)

    /** The accessibility a symbol declares: [nested] members and nested types default to private, top-level types to internal. */
    private fun declaredAccessibility(modifiers: Collection<String>, nested: Boolean): Acc {
        val protected = "protected" in modifiers
        val internal = "internal" in modifiers
        val private = "private" in modifiers
        return when {
            "public" in modifiers -> Acc.PUBLIC
            protected && internal -> Acc.PROTECTED_INTERNAL
            private && protected -> Acc.PRIVATE_PROTECTED
            protected -> Acc.PROTECTED
            internal -> Acc.INTERNAL
            private -> Acc.PRIVATE
            nested -> Acc.PRIVATE
            else -> Acc.INTERNAL
        }
    }

    /** [declared] of a symbol declared in [container] (a type declaration, or a member of one), met with [containerAcc]. */
    private fun meet(declared: Acc, container: PsiElement, containerAcc: Effective?): Effective {
        // a private member or nested type is seen in the type that contains it (not in the nested type itself: the ref probe of 2026-10-08)
        val scope = if (declared == Acc.PRIVATE) PsiTreeUtil.getParentOfType(container, CSharpBaseTypeDeclaration::class.java, true) else null
        val own = Effective(declared, scope)
        if (containerAcc == null || containerAcc.acc == Acc.PUBLIC) return own
        if (declared == Acc.PUBLIC) return containerAcc
        if (declared == Acc.PRIVATE) return own
        if (containerAcc.acc == Acc.PRIVATE) return containerAcc
        val acc = when {
            declared == containerAcc.acc -> declared
            declared == Acc.PROTECTED_INTERNAL -> containerAcc.acc
            containerAcc.acc == Acc.PROTECTED_INTERNAL -> declared
            // internal with protected, either with private protected: what both allow
            else -> Acc.PRIVATE_PROTECTED
        }
        return Effective(acc, null)
    }

    /** The effective accessibility of a type a signature names; null when not known (an unresolved type, a type whose parts disagree). */
    private fun effectiveOfSemantic(type: SemanticType, depth: Int): Effective? {
        if (depth > 8) return null
        return when (type) {
            is SemanticType.Parameter -> Effective(Acc.PUBLIC, null)
            is SemanticType.ArrayOf -> type.element?.let { effectiveOfSemantic(it, depth + 1) }
            is SemanticType.Source -> {
                val declaration = type.info.parts.firstNotNullOfOrNull { it.element() as? CSharpMemberDeclaration } ?: return null
                meetAll(effectiveOfType(declaration) ?: return null, type.arguments, depth)
            }
            is SemanticType.Library -> {
                val t = type.type
                val own = when {
                    t.isPrivate -> return null
                    t.isProtected && t.isInternal -> Effective(Acc.PROTECTED_INTERNAL, null)
                    t.isProtected -> Effective(Acc.PROTECTED, null)
                    t.isInternal -> Effective(Acc.INTERNAL, null)
                    else -> Effective(Acc.PUBLIC, null)
                }
                meetAll(own, type.arguments, depth)
            }
        }
    }

    /** A constructed type is as visible as the least visible of its definition and its arguments. */
    private fun meetAll(own: Effective, arguments: List<SemanticType?>, depth: Int): Effective? {
        var result = own
        for (argument in arguments) {
            val acc = effectiveOfSemantic(argument ?: return null, depth + 1) ?: return null
            if (!atLeastAsVisible(acc, result)) result = acc
            else if (!atLeastAsVisible(result, acc)) return null   // incomparable (internal against protected): not said
        }
        return result
    }

    /** Whether [type] is visible wherever [member] is. */
    private fun atLeastAsVisible(type: Effective, member: Effective): Boolean {
        if (type.acc == Acc.PUBLIC) return true
        if (member.acc == Acc.PRIVATE) {
            if (type.acc != Acc.PRIVATE) return true
            val typeScope = type.scope ?: return true
            val memberScope = member.scope ?: return true
            return PsiTreeUtil.isAncestor(typeScope, memberScope, false)
        }
        if (type.acc == Acc.PRIVATE) return false
        return when (type.acc) {
            Acc.PROTECTED_INTERNAL -> member.acc != Acc.PUBLIC
            Acc.INTERNAL -> member.acc == Acc.INTERNAL || member.acc == Acc.PRIVATE_PROTECTED
            Acc.PROTECTED -> member.acc == Acc.PROTECTED || member.acc == Acc.PRIVATE_PROTECTED
            Acc.PRIVATE_PROTECTED -> member.acc == Acc.PRIVATE_PROTECTED
            else -> false
        }
    }

    private fun kindOf(type: SemanticType): TypeKind? = when (type) {
        is SemanticType.Source -> type.info.kind
        is SemanticType.Library -> when (type.type.kind) {
            IndexedTypeKind.INTERFACE -> TypeKind.INTERFACE
            IndexedTypeKind.CLASS -> TypeKind.CLASS
            IndexedTypeKind.STRUCT -> TypeKind.STRUCT
            IndexedTypeKind.ENUM -> TypeKind.ENUM
            IndexedTypeKind.DELEGATE -> TypeKind.DELEGATE
            else -> null
        }
        else -> null
    }

    // ---- how the messages name things

    /** `Outer.Inner<T>`: the type as the messages name it. */
    private fun shownType(declaration: CSharpMemberDeclaration): String? {
        val names = ArrayList<String>()
        var at: PsiElement? = declaration
        while (at is CSharpMemberDeclaration && at !is CSharpBaseNamespaceDeclaration) {
            val identifier = (at as? CSharpBaseTypeDeclaration)?.identifier ?: (at as? CSharpDelegateDeclaration)?.identifier ?: return null
            val parameters = ((at as? CSharpTypeDeclaration)?.typeParameterList ?: (at as? CSharpDelegateDeclaration)?.typeParameterList)?.parameters?.map { it.identifier?.text ?: return null }
            names += if (parameters.isNullOrEmpty()) identifier.text else identifier.text + parameters.joinToString(", ", "<", ">")
            at = at.parent
        }
        return names.asReversed().joinToString(".")
    }

    private fun parametersShown(parameters: List<CSharpParameter>?): String? {
        val shown = parameters.orEmpty().map { p ->
            val type = p.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
            (p.modifiers.map { it.text }.filter { it == "ref" || it == "out" || it == "in" || it == "params" } + type).joinToString(" ")
        }
        return shown.joinToString(", ")
    }

    private fun methodShown(method: CSharpMethodDeclaration, type: String): String? {
        val name = method.identifier?.text ?: return null
        val typeParameters = method.typeParameterList?.parameters?.map { it.identifier?.text ?: return null }?.joinToString(", ", "<", ">").orEmpty()
        return "$type.$name$typeParameters(${parametersShown(method.parameterList?.parameters) ?: return null})"
    }

    private fun constructorShown(constructor: CSharpConstructorDeclaration, type: String): String? =
        "$type.${constructor.identifier?.text ?: return null}(${parametersShown(constructor.parameterList?.parameters) ?: return null})"

    private fun indexerShown(indexer: CSharpIndexerDeclaration, type: String): String? =
        "$type.this[${parametersShown(indexer.parameterList?.parameters) ?: return null}]"

    private fun operatorShown(operator: CSharpOperatorDeclaration, type: String): String? =
        "$type.operator ${operator.operatorToken?.text ?: return null}(${parametersShown(operator.parameterList?.parameters) ?: return null})"

    private fun conversionShown(conversion: CSharpConversionOperatorDeclaration, type: String): String? {
        val target = conversion.type?.let(resolver::resolveType)?.let { CSharpTypeDisplay.display(it, qualified = false) } ?: return null
        return "$type.${conversion.implicitOrExplicitKeyword?.text ?: return null} operator $target(${parametersShown(conversion.parameterList?.parameters) ?: return null})"
    }

    private companion object {
        val ACCESS_MODIFIERS = setOf("public", "private", "protected", "internal")
    }
}
