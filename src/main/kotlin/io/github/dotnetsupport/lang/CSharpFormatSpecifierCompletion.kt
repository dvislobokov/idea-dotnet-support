package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.SemanticType

/**
 * COMPLETION of format strings (task 2.6 of docs/COMPLETION_GAPS.md), as Rider lists them (dump 20 of docs/rider-analysis): after `{x:` in
 * an interpolation, `{0:` in the format of `string.Format` / `Console.WriteLine` / `AppendFormat`, in `x.ToString("…")` and the format of
 * `DateTime.ParseExact` — the standard specifiers and common custom ones of the type of the value (numbers, dates and times, `TimeSpan`,
 * `Guid`, enums), each with an example of what it gives. The type comes from the plugin's semantics; nothing where it is unknown.
 */
class CSharpFormatSpecifierCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val file = parameters.position.containingFile as? CSharpFile ?: return
        val place = CSharpFormatPlaces.at(parameters.position, parameters.offset, parameters.editor.document.charsSequence) ?: return
        val resolver = CSharpSemanticSession(file.project).resolver(file)
        val kind = place.kind ?: place.value?.let { CSharpFormatSpecifiers.kindOf(resolver.typeOf(it)) }
        if (kind == null) {
            // inside a format nothing else is offered: no names, no words
            result.stopHere()
            return
        }
        val set = result.withPrefixMatcher(place.prefix)
        val specifiers = CSharpFormatSpecifiers.of(kind)
        for ((index, specifier) in specifiers.withIndex()) {
            val text = if (place.escapesBackslash) specifier.code.replace("\\", "\\\\") else specifier.code
            val element = LookupElementBuilder.create(specifier, text).withPresentableText("${specifier.code} - ${specifier.title}")
                .withTypeText(specifier.example).withIcon(AllIcons.Nodes.Constant).withLookupString(specifier.code)
            set.addElement(PrioritizedLookupElement.withPriority(element, (specifiers.size - index).toDouble()))
        }
        result.stopHere()
    }
}

/** Where a format string is typed: the value it formats (or its kind, known by the call) and what is typed of it before the caret. */
class CSharpFormatPlace(val value: CSharpExpression?, val kind: CSharpFormatSpecifiers.Kind?, val prefix: String, val escapesBackslash: Boolean)

object CSharpFormatPlaces {
    private val COMPOSITE = setOf("Format", "AppendFormat", "Write", "WriteLine", "TraceInformation", "TraceWarning", "TraceError")
    private val PARSE_EXACT = setOf("ParseExact", "TryParseExact")
    private val ITEM = Regex("""\{\s*(\d+)\s*(?:,\s*-?\d+\s*)?:([^{}"]*)$""")

    /** The place of [leaf] (in the copy the platform completes in) at [offset] of [text] (the original document). */
    fun at(leaf: PsiElement, offset: Int, text: CharSequence): CSharpFormatPlace? {
        if (leaf.elementType == SyntaxKind.InterpolatedStringTextToken) {
            val clause = leaf.parent as? CSharpInterpolationFormatClause ?: return null
            val interpolation = clause.parent as? CSharpInterpolation ?: return null
            val start = clause.colonToken?.textRange?.endOffset ?: return null
            if (start > offset) return null
            val prefix = text.subSequence(start, offset).toString()
            val opening = (interpolation.parent as? CSharpInterpolatedStringExpression)?.stringStartToken?.text.orEmpty()
            return CSharpFormatPlace(interpolation.expression, null, prefix, '@' !in opening && "\"\"\"" !in opening)
        }
        if (leaf.elementType != SyntaxKind.StringLiteralToken) return null
        val literal = leaf.parent as? CSharpLiteralExpression ?: return null
        val argument = literal.parent as? CSharpArgument ?: return null
        val list = argument.parent as? CSharpArgumentList ?: return null
        val call = list.parent as? CSharpInvocationExpression ?: return null
        val callee = call.expression as? CSharpMemberAccessExpression ?: return null
        val name = callee.nameElement?.identifier?.text ?: return null
        val index = list.arguments.indexOf(argument)
        val shape = CSharpStringLiterals.shape(leaf.text) ?: return null
        val contentStart = leaf.textRange.startOffset + shape.contentStart
        if (contentStart > offset) return null
        val typed = text.subSequence(contentStart, offset).toString()
        val escapes = shape.kind == CSharpStringLiterals.Kind.REGULAR
        return when {
            name == "ToString" && index == 0 && '"' !in typed -> CSharpFormatPlace(callee.expression, null, typed, escapes)
            name in PARSE_EXACT && index == 1 && '"' !in typed -> {
                val type = callee.expression?.text?.substringAfterLast('.')?.trim() ?: return null
                val kind = CSharpFormatSpecifiers.kindOfName(type) ?: return null
                CSharpFormatPlace(null, kind, typed, escapes)
            }
            name in COMPOSITE -> {
                val match = ITEM.find(typed) ?: return null
                // `{{0:` is text, not an item
                var braces = 0
                while (match.range.first - braces >= 0 && typed[match.range.first - braces] == '{') braces++
                if (braces % 2 == 0) return null
                val item = match.groupValues[1].toIntOrNull() ?: return null
                val arguments = list.arguments
                val value = arguments.getOrNull(index + 1 + item)?.expression ?: return null
                CSharpFormatPlace(value, null, match.groupValues[2], escapes)
            }
            else -> null
        }
    }
}

/** The format specifiers by the kind of the value, with Rider's titles and examples. */
object CSharpFormatSpecifiers {
    enum class Kind { INTEGER, FLOATING, DECIMAL, DATE_TIME, DATE_ONLY, TIME_ONLY, TIME_SPAN, GUID, ENUM }

    class Specifier(val code: String, val title: String, val example: String)

    private val INTEGERS = setOf("Byte", "SByte", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64", "Int128", "UInt128", "IntPtr", "UIntPtr", "BigInteger")
    private val FLOATING = setOf("Double", "Single", "Half")

    fun kindOf(type: SemanticType?): Kind? = when (type) {
        is SemanticType.Library -> when {
            type.type.namespace == "System" && type.type.simpleName == "Nullable" -> kindOf(type.arguments.firstOrNull())
            type.type.kind == IndexedTypeKind.ENUM -> Kind.ENUM
            type.type.namespace == "System" || type.type.namespace == "System.Numerics" -> kindOfName(type.type.simpleName)
            else -> null
        }
        is SemanticType.Source -> if (type.info.kind == TypeKind.ENUM) Kind.ENUM else null
        else -> null
    }

    fun kindOfName(name: String): Kind? = when (name) {
        in INTEGERS -> Kind.INTEGER
        in FLOATING -> Kind.FLOATING
        "Decimal" -> Kind.DECIMAL
        "DateTime", "DateTimeOffset" -> Kind.DATE_TIME
        "DateOnly" -> Kind.DATE_ONLY
        "TimeOnly" -> Kind.TIME_ONLY
        "TimeSpan" -> Kind.TIME_SPAN
        "Guid" -> Kind.GUID
        else -> null
    }

    fun of(kind: Kind): List<Specifier> = when (kind) {
        Kind.INTEGER -> NUMBER + INTEGER
        Kind.FLOATING -> NUMBER + ROUND_TRIP
        Kind.DECIMAL -> NUMBER
        Kind.DATE_TIME -> DATE + TIME + DATE_TIME
        Kind.DATE_ONLY -> DATE
        Kind.TIME_ONLY -> TIME
        Kind.TIME_SPAN -> TIME_SPAN
        Kind.GUID -> GUID
        Kind.ENUM -> ENUM
    }

    private fun s(code: String, title: String, example: String) = Specifier(code, title, example)

    /** Rider's list for a number (dump 20, `{order.Total:`), in its order. */
    private val NUMBER = listOf(
        s("0000", "custom", "0123"), s("C", "currency", "¤1,234.45"), s("C0", "currency", "¤1,234"), s("E", "exponential", "1.234000E+004"),
        s("e2", "exponential", "1.23e+004"), s("E2", "exponential", "1.23E+004"), s("F", "fixed-point", "123.45"), s("F1", "fixed-point", "123.4"),
        s("G", "general", "1.234E+56"), s("g2", "general", "1.2e+45"), s("N", "number", "12,345.68"), s("N0", "integer", "12,345"),
        s("N1", "number", "12,345.6"), s("N2", "number", "12,345.68"), s("P", "percent", "123.45 %"), s("P1", "percent", "1,230.0 %"),
        s("#,##0.00", "custom", "12,345.68"), s("0.##", "custom", "123.4"),
    )
    private val INTEGER = listOf(s("D", "decimal", "1234"), s("D4", "decimal", "0042"), s("X", "hexadecimal", "2A"), s("x", "hexadecimal", "2a"), s("X8", "hexadecimal", "0000002A"))
    private val ROUND_TRIP = listOf(s("R", "round-trip", "123.456789012345"))

    private val DATE = listOf(
        s("d", "short date", "6/15/2009"), s("D", "long date", "Monday, June 15, 2009"), s("M", "month/day", "June 15"), s("Y", "year/month", "June 2009"),
        s("yyyy-MM-dd", "custom", "2009-06-15"), s("dd.MM.yyyy", "custom", "15.06.2009"), s("MM/dd/yyyy", "custom", "06/15/2009"), s("yyyyMMdd", "custom", "20090615"),
        s("dddd", "day of week", "Monday"), s("MMMM", "month name", "June"), s("yyyy", "year", "2009"), s("o", "round-trip", "2009-06-15"),
    )
    private val TIME = listOf(
        s("t", "short time", "1:45 PM"), s("T", "long time", "1:45:30 PM"), s("HH:mm:ss", "custom", "13:45:30"), s("HH:mm", "custom", "13:45"),
        s("hh:mm tt", "custom", "01:45 PM"), s("HH:mm:ss.fff", "custom", "13:45:30.618"),
    )
    private val DATE_TIME = listOf(
        s("f", "full date/time (short time)", "Monday, June 15, 2009 1:45 PM"), s("F", "full date/time (long time)", "Monday, June 15, 2009 1:45:30 PM"),
        s("g", "general date/time (short time)", "6/15/2009 1:45 PM"), s("G", "general date/time (long time)", "6/15/2009 1:45:30 PM"),
        s("O", "round-trip", "2009-06-15T13:45:30.0000000-07:00"), s("R", "RFC1123", "Mon, 15 Jun 2009 20:45:30 GMT"), s("s", "sortable", "2009-06-15T13:45:30"),
        s("u", "universal sortable", "2009-06-15 13:45:30Z"), s("U", "universal full", "Monday, June 15, 2009 8:45:30 PM"),
        s("yyyy-MM-dd HH:mm:ss", "custom", "2009-06-15 13:45:30"), s("yyyy-MM-ddTHH:mm:ss", "custom", "2009-06-15T13:45:30"),
    )
    private val TIME_SPAN = listOf(
        s("c", "constant", "1.03:14:56.1667"), s("g", "general short", "1:3:14:56.17"), s("G", "general long", "1:03:14:56.1666670"),
        s("hh\\:mm\\:ss", "custom", "03:14:56"), s("mm\\:ss", "custom", "14:56"), s("d\\.hh\\:mm\\:ss", "custom", "1.03:14:56"),
    )
    private val GUID = listOf(
        s("D", "hyphens", "00000000-0000-0000-0000-000000000000"), s("N", "digits", "00000000000000000000000000000000"),
        s("B", "braces", "{00000000-0000-0000-0000-000000000000}"), s("P", "parentheses", "(00000000-0000-0000-0000-000000000000)"),
        s("X", "hexadecimal", "{0x00000000,0x0000,0x0000,{0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00}}"),
    )
    private val ENUM = listOf(s("G", "name", "Red, Blue"), s("F", "flags", "Red, Blue"), s("D", "decimal", "5"), s("X", "hexadecimal", "00000005"))
}
