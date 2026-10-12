package io.github.dotnetsupport.lang.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedMemberKind
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.index.IndexedTypeRef
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.LocalSymbolKind
import io.github.dotnetsupport.lang.TypeKind
import java.math.BigInteger
import java.util.IdentityHashMap

/**
 * The values of the constant expressions a `case` label or a `goto case` is written with (C# §12.23, the part switch statements use):
 * literals of `int`, `long`, `char`, `string` and `null`; enum members (an implicit value is the one before plus one, the first one 0,
 * an initializer may name the other members); `const` fields and locals of the solution, constants and enum members of assemblies;
 * `+ - * / % << >> | & ^ ~` and casts of integral constants, `+` of strings. Anything else, an overflow (an error of its own) or a type
 * not listed (`uint`, `byte`, floating point) has no value here: no answer is better than a wrong one. One per file run, cached.
 */
internal class CSharpConstantValues(private val resolver: CSharpNameResolver) {
    enum class Kind { INT, LONG, CHAR, STRING, NULL, ENUM }

    /** [number] of an integral value (a char's code, an enum member's), [text] of a string, [enum] the identity of an enum type. */
    class Value(val kind: Kind, val number: BigInteger? = null, val text: String? = null, val enum: Any? = null) {
        /** The key two equal labels share. */
        val key: String get() = when (kind) {
            Kind.STRING -> "s:$text"
            Kind.NULL -> "null"
            else -> "n:$number"
        }
    }

    private val enumValues = IdentityHashMap<CSharpEnumDeclaration, MutableMap<CSharpEnumMemberDeclaration, BigInteger?>>()
    private val evaluating = HashSet<PsiElement>()
    private val others = HashMap<CSharpFile, CSharpConstantValues?>()

    /** The values of [file]'s constants, read with its own resolver (another file of the solution: one per file it is asked of). */
    private fun ownerOf(file: CSharpFile): CSharpConstantValues? =
        if (file == resolver.file) this else others.getOrPut(file) { resolver.session.reachable(file)?.let(::CSharpConstantValues) }

    fun of(expression: CSharpExpression?, depth: Int = 0): Value? {
        if (expression == null || depth > MAX_DEPTH) return null
        val e = unparenthesized(expression)
        return when {
            e.elementType == SyntaxKind.NumericLiteralExpression -> number(e.text, negative = false)
            e.elementType == SyntaxKind.CharacterLiteralExpression -> character(e.text)
            e.elementType == SyntaxKind.StringLiteralExpression -> string(e.text)
            e.elementType == SyntaxKind.NullLiteralExpression -> Value(Kind.NULL)
            e is CSharpPrefixUnaryExpression -> unary(e, depth)
            e is CSharpCastExpression -> cast(e, depth)
            e is CSharpBinaryExpression -> binary(e, depth)
            e is CSharpIdentifierName -> named(e, depth)
            e is CSharpMemberAccessExpression -> (e.nameElement as? CSharpIdentifierName)?.let { named(it, depth) }
            else -> null
        }
    }

    /** The integral types a value of [kind] converts to implicitly in a `case` of a switch on that type (C# §10.2.3, §10.2.11 for literals). */
    fun forSwitch(value: Value, governing: SemanticType): Value? = when {
        enumKey(governing) != null -> value.takeIf { it.kind == Kind.ENUM && it.enum == enumKey(governing) }
        governing !is SemanticType.Library -> null
        else -> when (governing.type.fullName) {
            INT -> if (value.kind == Kind.INT || value.kind == Kind.CHAR) Value(Kind.INT, value.number) else null
            LONG -> if (value.kind == Kind.INT || value.kind == Kind.LONG || value.kind == Kind.CHAR) Value(Kind.LONG, value.number) else null
            CHAR -> value.takeIf { it.kind == Kind.CHAR }
            STRING -> value.takeIf { it.kind == Kind.STRING || it.kind == Kind.NULL }
            else -> null
        }
    }

    fun enumKey(type: SemanticType): Any? = when {
        type is SemanticType.Source && type.info.kind == TypeKind.ENUM -> type.info.key
        type is SemanticType.Library && type.type.kind == IndexedTypeKind.ENUM -> type.type.fullName
        else -> null
    }

    // ---- literals

    private fun number(written: String, negative: Boolean): Value? {
        var text = written.replace("_", "").lowercase()
        var long = false
        if (text.endsWith("l")) { long = true; text = text.dropLast(1) }
        if (text.endsWith("u") || text.endsWith("l")) return null
        val n = when {
            text.startsWith("0x") -> text.substring(2).toBigIntegerOrNull(16)
            text.startsWith("0b") -> text.substring(2).toBigIntegerOrNull(2)
            text.isNotEmpty() && text.all(Char::isDigit) -> text.toBigIntegerOrNull()
            else -> null
        } ?: return null
        val value = if (negative) n.negate() else n
        // without a suffix the literal is `int` when it fits, else `uint` (not modeled), else `long`
        return when {
            !long && value in INT_MIN..INT_MAX -> Value(Kind.INT, value)
            value in LONG_MIN..LONG_MAX && (long || n > UINT_MAX) -> Value(Kind.LONG, value)
            else -> null
        }
    }

    /** `'x'`: a printable character without an escape (Roslyn writes the character itself in its messages). */
    private fun character(text: String): Value? {
        if (text.length != 3 || text[0] != '\'' || text[2] != '\'' || text[1] == '\\' || text[1] == '\'' || text[1].code !in 32..126) return null
        return Value(Kind.CHAR, BigInteger.valueOf(text[1].code.toLong()))
    }

    /** `"text"` without escapes (a verbatim, raw or interpolated string is not modeled). */
    private fun string(text: String): Value? {
        if (!text.startsWith("\"") || text.startsWith("\"\"\"") || text.length < 2 || !text.endsWith("\"") || '\\' in text) return null
        val content = text.substring(1, text.length - 1)
        return if ('"' in content || content.any { it.code !in 32..126 }) null else Value(Kind.STRING, text = content)
    }

    // ---- operators

    private fun unary(e: CSharpPrefixUnaryExpression, depth: Int): Value? {
        val op = e.operatorToken?.text
        val operandSyntax = e.operand?.let(::unparenthesized) ?: return null
        // `-2147483648`: the literal is `uint` alone, `int` negated
        if (op == "-" && operandSyntax.elementType == SyntaxKind.NumericLiteralExpression && e.operand == operandSyntax) return number(operandSyntax.text, negative = true)
        val operand = promoted(of(operandSyntax, depth + 1) ?: return null) ?: return null
        val n = operand.number ?: return null
        return when (op) {
            "+" -> operand
            "-" -> checked(operand.kind, n.negate())
            "~" -> Value(operand.kind, n.not())
            else -> null
        }
    }

    private fun cast(e: CSharpCastExpression, depth: Int): Value? {
        val type = e.type?.let(resolver::resolveType) ?: return null
        val value = of(e.expression, depth + 1) ?: return null
        val n = value.number ?: return null
        enumKey(type)?.let { key -> return Value(Kind.ENUM, n, enum = key) }
        return when ((type as? SemanticType.Library)?.type?.fullName) {
            INT -> checked(Kind.INT, n)
            LONG -> checked(Kind.LONG, n)
            CHAR -> if (n in PRINTABLE && value.kind != Kind.ENUM) Value(Kind.CHAR, n) else null
            else -> null
        }
    }

    private fun binary(e: CSharpBinaryExpression, depth: Int): Value? {
        val op = e.operatorToken?.text ?: return null
        val left = of(e.left, depth + 1) ?: return null
        val right = of(e.right, depth + 1) ?: return null
        if (left.kind == Kind.STRING || right.kind == Kind.STRING || left.kind == Kind.NULL || right.kind == Kind.NULL) {
            // string concatenation of constants: `null` is the empty string there
            if (op != "+" || left.kind !in TEXT || right.kind !in TEXT || left.kind == Kind.NULL && right.kind == Kind.NULL) return null
            return Value(Kind.STRING, text = left.text.orEmpty() + right.text.orEmpty())
        }
        // enums: `E + n`, `E - n`, `E - E`, `E | E` and the like of one enum type
        if (left.kind == Kind.ENUM || right.kind == Kind.ENUM) {
            val l = left.number ?: return null
            val r = right.number ?: return null
            val enum = left.enum ?: right.enum
            val both = left.kind == Kind.ENUM && right.kind == Kind.ENUM
            if (both && left.enum != right.enum) return null
            return when (op) {
                "|" -> if (both) Value(Kind.ENUM, l.or(r), enum = enum) else null
                "&" -> if (both) Value(Kind.ENUM, l.and(r), enum = enum) else null
                "^" -> if (both) Value(Kind.ENUM, l.xor(r), enum = enum) else null
                "+" -> if (!both && (right.kind == Kind.INT || left.kind == Kind.INT)) Value(Kind.ENUM, l + r, enum = enum) else null
                "-" -> if (both) null else if (left.kind == Kind.ENUM && right.kind == Kind.INT) Value(Kind.ENUM, l - r, enum = enum) else null
                else -> null
            }
        }
        val l = promoted(left) ?: return null
        val r = promoted(right) ?: return null
        val a = l.number ?: return null
        val b = r.number ?: return null
        if (op == "<<" || op == ">>") {
            if (r.kind != Kind.INT) return null
            // the count is masked, the result wraps: shifts do not overflow
            val count = b.toInt() and (if (l.kind == Kind.INT) 31 else 63)
            return if (l.kind == Kind.INT) {
                val v = a.toInt()
                Value(Kind.INT, BigInteger.valueOf((if (op == "<<") v shl count else v shr count).toLong()))
            } else {
                val v = a.toLong()
                Value(Kind.LONG, BigInteger.valueOf(if (op == "<<") v shl count else v shr count))
            }
        }
        val kind = if (l.kind == Kind.LONG || r.kind == Kind.LONG) Kind.LONG else Kind.INT
        return when (op) {
            "+" -> checked(kind, a + b)
            "-" -> checked(kind, a - b)
            "*" -> checked(kind, a * b)
            "/" -> if (b.signum() == 0) null else checked(kind, a.divide(b))
            "%" -> if (b.signum() == 0) null else checked(kind, a.rem(b))
            "|" -> Value(kind, a.or(b))
            "&" -> Value(kind, a.and(b))
            "^" -> Value(kind, a.xor(b))
            else -> null
        }
    }

    /** A `char` takes part in arithmetic as an `int`. */
    private fun promoted(value: Value): Value? = when (value.kind) {
        Kind.CHAR -> Value(Kind.INT, value.number)
        Kind.INT, Kind.LONG -> value
        else -> null
    }

    /** Out of range is CS0220 (or a value the cast does not keep): no value. */
    private fun checked(kind: Kind, n: BigInteger): Value? = when (kind) {
        Kind.INT -> if (n in INT_MIN..INT_MAX) Value(Kind.INT, n) else null
        Kind.LONG -> if (n in LONG_MIN..LONG_MAX) Value(Kind.LONG, n) else null
        else -> null
    }

    // ---- names

    private fun named(name: CSharpIdentifierName, depth: Int): Value? {
        val symbol = name.identifier?.let(resolver::resolve)?.single ?: return null
        return when (symbol) {
            is CSharpSymbol.SourceMember -> when (val element = symbol.element) {
                is CSharpEnumMemberDeclaration -> enumMember(element, depth)
                else -> {
                    // the symbol is the field or its declarator
                    val field = PsiTreeUtil.getParentOfType(element, CSharpBaseFieldDeclaration::class.java, false) ?: return null
                    if (field.modifiers.none { it.text == "const" }) return null
                    val declarator = PsiTreeUtil.findChildrenOfType(field, CSharpVariableDeclarator::class.java)
                        .singleOrNull { it.identifier?.text == name.identifier?.text } ?: return null
                    constant(declarator, depth)
                }
            }
            is CSharpSymbol.Local -> {
                if (symbol.symbol.kind != LocalSymbolKind.LOCAL) return null
                val statement = PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpLocalDeclarationStatement::class.java) ?: return null
                if (statement.modifiers.none { it.text == "const" }) return null
                val declarator = PsiTreeUtil.getParentOfType(symbol.symbol.declaration, CSharpVariableDeclarator::class.java, false) ?: return null
                constant(declarator, depth)
            }
            is CSharpSymbol.LibraryMember -> library(symbol)
            else -> null
        }
    }

    /** A `const` field or local: its initializer, in its own file, converted to its declared type. */
    private fun constant(declarator: CSharpVariableDeclarator, depth: Int): Value? {
        val type = (declarator.parent as? CSharpVariableDeclaration)?.type ?: return null
        val file = declarator.containingFile as? CSharpFile ?: return null
        val owner = ownerOf(file) ?: return null
        if (!evaluating.add(declarator)) return null
        try {
            val value = owner.of(declarator.initializer?.value, depth + 1) ?: return null
            val declared = owner.resolver.resolveType(type) ?: return null
            owner.enumKey(declared)?.let { key -> return value.takeIf { it.kind == Kind.ENUM && it.enum == key } }
            return when ((declared as? SemanticType.Library)?.type?.fullName) {
                INT -> if (value.kind == Kind.INT || value.kind == Kind.CHAR) Value(Kind.INT, value.number) else null
                LONG -> if (value.kind == Kind.INT || value.kind == Kind.LONG || value.kind == Kind.CHAR) Value(Kind.LONG, value.number) else null
                CHAR -> value.takeIf { it.kind == Kind.CHAR }
                STRING -> value.takeIf { it.kind == Kind.STRING || it.kind == Kind.NULL }
                else -> null
            }
        } finally {
            evaluating.remove(declarator)
        }
    }

    /** An enum member of the solution: its initializer (the other members are numbers there), else the one before plus one, the first 0. */
    private fun enumMember(member: CSharpEnumMemberDeclaration, depth: Int): Value? {
        val declaration = member.parent as? CSharpEnumDeclaration ?: return null
        val file = declaration.containingFile as? CSharpFile ?: return null
        val owner = ownerOf(file) ?: return null
        val info = owner.resolver.syntax.declaredType(declaration) ?: return null
        val n = owner.memberValue(declaration, declaration.members.indexOf(member), depth) ?: return null
        return Value(Kind.ENUM, n, enum = info.key)
    }

    /** The value of the member [index] of [declaration]; a member that names itself through the others (CS0110) has none. */
    private fun memberValue(declaration: CSharpEnumDeclaration, index: Int, depth: Int): BigInteger? {
        if (index < 0 || depth > MAX_DEPTH) return null
        val values = enumValues.getOrPut(declaration) { HashMap() }
        val member = declaration.members[index]
        if (values.containsKey(member)) return values[member]
        if (!evaluating.add(member)) return null
        try {
            val initializer = member.equalsValue?.value
            val value = when {
                initializer != null -> of(initializer, depth + 1)?.takeIf { it.kind != Kind.STRING && it.kind != Kind.NULL }?.number
                index == 0 -> BigInteger.ZERO
                else -> memberValue(declaration, index - 1, depth + 1)?.add(BigInteger.ONE)
            }
            values[member] = value
            return value
        } finally {
            evaluating.remove(member)
        }
    }

    /** A constant or an enum member of an assembly, as its index writes the value. */
    private fun library(symbol: CSharpSymbol.LibraryMember): Value? {
        val m = symbol.member
        val text = m.constantValue ?: return null
        return when (m.kind) {
            IndexedMemberKind.ENUM_MEMBER -> text.toBigIntegerOrNull()?.let { Value(Kind.ENUM, it, enum = m.type.fullName) }
            IndexedMemberKind.CONSTANT -> when ((m.typeRef as? IndexedTypeRef.Named)?.fullName) {
                INT -> text.toBigIntegerOrNull()?.let { checked(Kind.INT, it) }
                LONG -> text.toBigIntegerOrNull()?.let { checked(Kind.LONG, it) }
                CHAR -> character(text)
                STRING -> if (text == "null") Value(Kind.NULL) else string(text)
                else -> null
            }
            else -> null
        }
    }

    private fun unparenthesized(expression: CSharpExpression): CSharpExpression {
        var e = expression
        while (e is CSharpParenthesizedExpression) e = e.expression ?: return e
        return e
    }

    companion object {
        const val INT = "System.Int32"
        const val LONG = "System.Int64"
        const val CHAR = "System.Char"
        const val STRING = "System.String"
        private const val MAX_DEPTH = 64
        private val TEXT = setOf(Kind.STRING, Kind.NULL)
        val INT_MIN: BigInteger = BigInteger.valueOf(Int.MIN_VALUE.toLong())
        val INT_MAX: BigInteger = BigInteger.valueOf(Int.MAX_VALUE.toLong())
        val UINT_MAX: BigInteger = BigInteger.valueOf(0xFFFFFFFFL)
        val LONG_MIN: BigInteger = BigInteger.valueOf(Long.MIN_VALUE)
        val LONG_MAX: BigInteger = BigInteger.valueOf(Long.MAX_VALUE)
        // a character Roslyn writes as itself in a message
        val PRINTABLE = BigInteger.valueOf(32)..BigInteger.valueOf(126)
    }
}
