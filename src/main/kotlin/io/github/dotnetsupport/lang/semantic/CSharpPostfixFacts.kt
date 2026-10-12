package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.csharp.lang.psi.CSharpExpression
import io.github.dotnetsupport.lang.CSharpFile

/**
 * What the postfix templates ask of the type of the expression before the key (COMPLETION_GAPS 2.1, as Rider filters its list: `.await` of a
 * task, `.foreach` of a collection, `.if` of a `bool`). Every answer is null where it cannot be told — an unresolved type, a type parameter:
 * the template is offered then, as before the types were asked.
 */
class CSharpPostfixFacts(
    /** `List<Order>`, without namespaces: what names are made of. */
    val type: String?,
    /** The type as code at the expression writes it, for a field or a property of it. */
    val written: String?,
    val isBool: Boolean?,
    val isAwaitable: Boolean?,
    val isEnumerable: Boolean?,
    /** The element `foreach` gets, without namespaces. */
    val elementType: String?,
    /** `Count` or `Length`, what `.for` compares the index with; null when the type has neither (or is not known). */
    val countMember: String?,
    val isInteger: Boolean?,
    /** False for a value type that is not `Nullable<T>`. */
    val canBeNull: Boolean?,
    val isDisposable: Boolean?,
    val isAsyncDisposable: Boolean?,
    val isReference: Boolean?,
    val isString: Boolean?,
    val isException: Boolean?,
) {
    companion object {
        private val TASKS = setOf(
            "System.Threading.Tasks.Task", "System.Threading.Tasks.Task`1", "System.Threading.Tasks.ValueTask", "System.Threading.Tasks.ValueTask`1",
            "System.Runtime.CompilerServices.ConfiguredTaskAwaitable", "System.Runtime.CompilerServices.ConfiguredTaskAwaitable`1",
            "System.Runtime.CompilerServices.ConfiguredValueTaskAwaitable", "System.Runtime.CompilerServices.ConfiguredValueTaskAwaitable`1",
            "System.Runtime.CompilerServices.YieldAwaitable",
        )
        private val INTEGERS = setOf(
            "System.Int32", "System.Int64", "System.Int16", "System.Byte", "System.SByte", "System.UInt16", "System.UInt32", "System.UInt64",
            "System.IntPtr", "System.UIntPtr",
        )

        /** The facts of the expression spanning exactly [range] of [file] (a copy of completion included); null when its type is not known. */
        fun of(file: PsiFile, range: TextRange): CSharpPostfixFacts? {
            val csharp = file as? CSharpFile ?: return null
            if (csharp.compilationUnit == null || DumbService.isDumb(file.project)) return null
            val expression = expressionAt(file, range) ?: return null
            return runCatching { compute(CSharpSemanticSession(file.project).resolver(csharp), expression) }.getOrNull()
        }

        /**
         * Whether [range] names a type rather than a value (`OrderStatus` of `OrderStatus.`, DEV_JOURNEY 4.7): [NamedType.ENUM] for an enum,
         * [NamedType.TYPE] for another type, null for a value or what is not known.
         */
        fun namedType(file: PsiFile, range: TextRange): NamedType? {
            val csharp = file as? CSharpFile ?: return null
            if (csharp.compilationUnit == null || DumbService.isDumb(file.project)) return null
            val expression = expressionAt(file, range) ?: return null
            val qualifier = runCatching { CSharpSemanticSession(file.project).resolver(csharp).qualifier(expression) }.getOrNull()
            val type = (qualifier as? CSharpNameResolver.Qualifier.Type)?.type ?: return null
            val enum = type is SemanticType.Source && type.info.kind == io.github.dotnetsupport.lang.TypeKind.ENUM ||
                type is SemanticType.Library && type.type.kind == io.github.dotnetsupport.index.IndexedTypeKind.ENUM
            return if (enum) NamedType.ENUM else NamedType.TYPE
        }

        private fun expressionAt(file: PsiFile, range: TextRange): CSharpExpression? {
            var element: PsiElement? = file.findElementAt(range.startOffset)
            var found: CSharpExpression? = null
            while (element != null && element !is PsiFile && element.textRange.startOffset == range.startOffset) {
                if (element.textRange == range && element is CSharpExpression) found = element
                if (element.textRange.endOffset > range.endOffset) break
                element = element.parent
            }
            return found
        }

        private fun compute(r: CSharpNameResolver, expression: CSharpExpression): CSharpPostfixFacts? {
            val type = r.typeOf(expression) ?: return null
            val library = type as? SemanticType.Library
            val fullName = library?.fullName
            val parameter = type is SemanticType.Parameter
            val valueType = r.isValueType(type)
            val element = r.elementType(type)
            val string = fullName == "System.String"
            val enumerable = when {
                type is SemanticType.ArrayOf || string || element != null -> true
                else -> CSharpTypeFacts.implements(r, type, "System.Collections.IEnumerable")
            }
            val count = when {
                type is SemanticType.ArrayOf || string -> "Length"
                parameter -> null
                else -> listOf("Count", "Length").firstOrNull { name -> r.membersNamed(type, name, 0).any { !r.isMethod(it) } }
            }
            val awaitable = when {
                fullName in TASKS -> true
                r.membersNamed(type, "GetAwaiter", 0).any { r.isMethod(it) } -> true
                parameter -> null
                else -> false
            }
            val disposable = CSharpTypeFacts.implements(r, type, CSharpTypeFacts.DISPOSABLE)
                .let { if (it == false && valueType && r.membersNamed(type, "Dispose", 0).isNotEmpty()) true else it }
            val asyncDisposable = CSharpTypeFacts.implements(r, type, CSharpTypeFacts.ASYNC_DISPOSABLE)
                .let { if (it == false && r.membersNamed(type, "DisposeAsync", 0).isNotEmpty()) true else it }
            return CSharpPostfixFacts(
                type = type.minimalDisplay,
                written = CSharpTypeFacts.written(r, type, expression),
                isBool = if (parameter) null else fullName == "System.Boolean",
                isAwaitable = awaitable,
                isEnumerable = if (parameter && enumerable != true) null else enumerable,
                elementType = element?.minimalDisplay,
                countMember = count,
                isInteger = if (parameter) null else fullName in INTEGERS,
                canBeNull = when {
                    valueType -> r.isNullable(type)
                    parameter -> null
                    else -> true
                },
                isDisposable = if (parameter && disposable != true) null else disposable,
                isAsyncDisposable = if (parameter && asyncDisposable != true) null else asyncDisposable,
                isReference = when {
                    valueType -> false
                    parameter -> null
                    else -> true
                },
                isString = if (parameter) null else string,
                isException = if (parameter) null else CSharpTypeFacts.implements(r, type, "System.Exception"),
            )
        }
    }
}

/** What the expression before a postfix key names when it is a type: [CSharpPostfixFacts.namedType]. */
enum class NamedType { TYPE, ENUM }
