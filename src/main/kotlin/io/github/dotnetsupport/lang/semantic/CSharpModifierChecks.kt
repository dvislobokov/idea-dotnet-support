package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.TypeInfo
import io.github.dotnetsupport.lang.TypeKind

/**
 * Modifiers and layout, as the compiler-messages pages of Roslyn say them (0.1.143):
 * - CS0106 «The modifier 'x' is not valid for this item»: a modifier the kind of declaration never takes (`virtual` on a field, `static`
 *   on an indexer, `public` on a local function, `abstract` on a struct, `readonly` on a member of a class); CS1527 (`private` /
 *   `protected` on a type of a namespace) and CS1530 (`new` there) are its cousins. Modifiers whose rules depend on more than the kind
 *   (`async` with a return type, `extern` with a body, `partial` on a non-partial type, operators, interface members, accessors) are left alone;
 * - CS0504 «The constant 'C.x' cannot be marked static»;
 * - CS0523 «Struct member 'S.f' of type 'S' causes a cycle in the struct layout»: an instance field or auto-property of a struct whose type
 *   is a struct of the solution that holds, through such members, the first one again (`S?` counts, as `Nullable<S>` holds an `S`); only
 *   structs whose parts are all seen, without generic arguments.
 */
internal class CSharpModifierChecks(
    private val resolver: CSharpNameResolver,
    private val quiet: (PsiElement) -> Boolean,
    private val report: (code: String, message: String, range: TextRange) -> Unit,
) {
    private val file: CSharpFile = resolver.file

    fun run(unit: CSharpCompilationUnit) {
        PsiTreeUtil.processElements(unit) { element ->
            when (element) {
                is CSharpBaseTypeDeclaration -> if (!quiet(element)) { checkTypeModifiers(element); if (element is CSharpTypeDeclaration) checkMembers(element) }
                is CSharpDelegateDeclaration -> if (!quiet(element)) checkDelegateModifiers(element)
                is CSharpLocalFunctionStatement -> if (!quiet(element)) invalid(element.modifiers, LOCAL_FUNCTION, accessOk = false)
            }
            true
        }
        for (declaration in resolver.syntax.scopes.declarations) {
            if (declaration is CSharpTypeDeclaration && !quiet(declaration)) checkLayout(declaration)
        }
    }

    // ---- CS0106, CS1527, CS1530

    private fun checkTypeModifiers(type: CSharpBaseTypeDeclaration) {
        val topLevel = PsiTreeUtil.getParentOfType(type, CSharpBaseTypeDeclaration::class.java) == null
        if (topLevel) {
            for (modifier in type.modifiers) when (modifier.text) {
                "private", "protected" -> report("CS1527", "Elements defined in a namespace cannot be explicitly declared as private, protected, protected internal, or private protected", modifier.textRange)
                "new" -> report("CS1530", "Keyword 'new' is not allowed on elements defined in a namespace", modifier.textRange)
            }
        }
        val kind = resolver.syntax.declaredType(type)?.kind
        val allowed = when (kind) {
            TypeKind.CLASS, TypeKind.STATIC_CLASS, TypeKind.RECORD -> CLASS
            TypeKind.STRUCT, TypeKind.RECORD_STRUCT -> STRUCT
            TypeKind.INTERFACE -> INTERFACE
            TypeKind.ENUM -> ENUM
            else -> return
        }
        if (type.node.elementType == SyntaxKind.ExtensionBlockDeclaration) return
        invalid(type.modifiers, allowed)
    }

    private fun checkDelegateModifiers(delegate: CSharpDelegateDeclaration) {
        val topLevel = PsiTreeUtil.getParentOfType(delegate, CSharpBaseTypeDeclaration::class.java) == null
        if (topLevel) {
            for (modifier in delegate.modifiers) when (modifier.text) {
                "private", "protected" -> report("CS1527", "Elements defined in a namespace cannot be explicitly declared as private, protected, protected internal, or private protected", modifier.textRange)
                "new" -> report("CS1530", "Keyword 'new' is not allowed on elements defined in a namespace", modifier.textRange)
            }
        }
        invalid(delegate.modifiers, DELEGATE)
    }

    private fun checkMembers(type: CSharpTypeDeclaration) {
        val kind = resolver.syntax.declaredType(type)?.kind ?: return
        if (kind == TypeKind.INTERFACE || type.node.elementType == SyntaxKind.ExtensionBlockDeclaration) return
        val struct = kind == TypeKind.STRUCT || kind == TypeKind.RECORD_STRUCT
        for (member in type.members) {
            if (quiet(member)) continue
            val modifiers = member.modifiers
            val allowed = when (member) {
                is CSharpFieldDeclaration -> {
                    if (modifiers.any { it.text == "const" }) {
                        for (variable in member.declaration?.variables.orEmpty()) {
                            if (modifiers.any { it.text == "static" }) variable.identifier?.let { report("CS0504", "The constant '${shown(type)}.${it.text}' cannot be marked static", it.textRange) }
                        }
                        CONST
                    } else FIELD
                }
                is CSharpEventFieldDeclaration -> if (struct) EVENT + "readonly" else EVENT
                is CSharpMethodDeclaration -> if (struct) METHOD + "readonly" else METHOD
                is CSharpPropertyDeclaration -> if (struct) PROPERTY + "readonly" else PROPERTY
                is CSharpEventDeclaration -> if (struct) EVENT + "readonly" else EVENT
                is CSharpIndexerDeclaration -> if (struct) INDEXER + "readonly" else INDEXER
                is CSharpConstructorDeclaration -> if (modifiers.any { it.text == "static" }) STATIC_CONSTRUCTOR else CONSTRUCTOR
                is CSharpDestructorDeclaration -> DESTRUCTOR
                else -> continue   // operators, nested types (their own visit), incomplete members
            }
            invalid(modifiers, allowed, accessOk = member !is CSharpDestructorDeclaration)
        }
    }

    /** [accessOk]: accessibility modifiers are the item's business (members, types); a local function or a destructor takes none. */
    private fun invalid(modifiers: List<PsiElement>, allowed: Set<String>, accessOk: Boolean = true) {
        for (modifier in modifiers) {
            val text = modifier.text
            if (accessOk && text in ACCESS || text in allowed) continue
            if (text !in KNOWN) continue
            report("CS0106", "The modifier '$text' is not valid for this item", modifier.textRange)
        }
    }

    // ---- CS0523

    private fun checkLayout(type: CSharpTypeDeclaration) {
        val info = resolver.syntax.declaredType(type) ?: return
        if (info.kind != TypeKind.STRUCT && info.kind != TypeKind.RECORD_STRUCT) return
        if (info.parts.firstOrNull()?.element() != type) return   // once per struct, at its first part
        if (!partsSeen(info)) return
        val shown = shown(type)
        for ((member, at, target) in instanceMembers(info)) {
            if (reaches(target, info.key, HashSet())) {
                report("CS0523", "Struct member '$shown.${at.text}' of type '${CSharpTypeDisplay.display(member, qualified = false) ?: continue}' causes a cycle in the struct layout", at.textRange)
            }
        }
    }

    private fun partsSeen(info: TypeInfo): Boolean = info.parts.all { part -> (part.element()?.containingFile as? CSharpFile)?.let(resolver.session::reachable) != null }

    /** Whether the struct [info] holds, through the instance members of struct type of itself and of what they hold, the struct [key]. */
    private fun reaches(info: TypeInfo, key: String, seen: MutableSet<String>): Boolean {
        if (info.key == key) return true
        if (!seen.add(info.key) || !partsSeen(info)) return false
        return instanceMembers(info).any { (_, _, target) -> reaches(target, key, seen) }
    }

    /** The instance fields and auto-properties of the parts of [info] whose type is a struct of the solution: (the type, the name, its info). */
    private fun instanceMembers(info: TypeInfo): List<Triple<SemanticType, PsiElement, TypeInfo>> {
        val found = ArrayList<Triple<SemanticType, PsiElement, TypeInfo>>()
        for (part in info.parts) {
            val declaration = part.element() as? CSharpTypeDeclaration ?: continue
            val owner = (declaration.containingFile as? CSharpFile)?.let(resolver.session::reachable) ?: continue
            for (member in declaration.members) {
                val modifiers = member.modifiers.map { it.text }
                if ("static" in modifiers || "const" in modifiers || "fixed" in modifiers) continue
                val (syntax, names) = when (member) {
                    is CSharpFieldDeclaration -> (member.declaration?.type ?: continue) to member.declaration!!.variables.mapNotNull { it.identifier }
                    is CSharpPropertyDeclaration -> {
                        val accessors = member.accessorList?.accessors ?: continue
                        if (accessors.isEmpty() || accessors.any { it.body != null || it.expressionBody != null }) continue
                        (member.type ?: continue) to listOfNotNull(member.identifier)
                    }
                    else -> continue
                }
                val type = owner.resolveType(syntax) ?: continue
                val target = structOf(type) ?: continue
                for (name in names) found += Triple(type, name, target)
            }
        }
        return found
    }

    /** The struct of the solution a member of [type] holds inline: the type itself, or the `T` of a `Nullable<T>`; a type with arguments is not followed. */
    private fun structOf(type: SemanticType): TypeInfo? = when (type) {
        is SemanticType.Source -> type.info.takeIf { (it.kind == TypeKind.STRUCT || it.kind == TypeKind.RECORD_STRUCT) && type.arguments.isEmpty() }
        is SemanticType.Library -> if (type.fullName == "System.Nullable`1" || type.fullName == "System.Nullable") type.arguments.singleOrNull()?.let(::structOf) else null
        else -> null
    }

    private fun shown(type: CSharpBaseTypeDeclaration): String {
        val names = ArrayList<String>()
        var at: PsiElement? = type
        while (at is CSharpBaseTypeDeclaration) { names += at.identifier?.text ?: "?"; at = at.parent }
        return names.asReversed().joinToString(".")
    }

    private companion object {
        val ACCESS = setOf("public", "private", "protected", "internal", "file")
        /** The modifiers this check knows the rules of; anything else (`scoped`, `required`, `ref`, contextual words) is left alone. */
        val KNOWN = setOf("new", "static", "virtual", "sealed", "override", "abstract", "extern", "async", "unsafe", "partial", "readonly", "volatile", "const", "public", "private", "protected", "internal")
        val CLASS = setOf("new", "abstract", "sealed", "static", "partial", "unsafe")
        val STRUCT = setOf("new", "partial", "unsafe", "readonly")
        val INTERFACE = setOf("new", "partial", "unsafe")
        val ENUM = setOf("new")
        val DELEGATE = setOf("new", "unsafe")
        val FIELD = setOf("new", "static", "readonly", "volatile", "unsafe")
        val CONST = setOf("new", "const", "static")   // `static const` is CS0504, not CS0106
        val METHOD = setOf("new", "static", "virtual", "sealed", "override", "abstract", "extern", "async", "unsafe", "partial")
        val PROPERTY = setOf("new", "static", "virtual", "sealed", "override", "abstract", "extern", "unsafe", "partial")
        val EVENT = setOf("new", "static", "virtual", "sealed", "override", "abstract", "extern", "unsafe", "partial")
        val INDEXER = setOf("new", "virtual", "sealed", "override", "abstract", "extern", "unsafe", "partial")
        val CONSTRUCTOR = setOf("extern", "unsafe", "partial")
        val STATIC_CONSTRUCTOR = setOf("static", "extern", "unsafe")
        val DESTRUCTOR = setOf("extern", "unsafe")
        val LOCAL_FUNCTION = setOf("static", "async", "unsafe", "extern")
    }
}
