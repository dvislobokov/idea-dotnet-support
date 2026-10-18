package io.github.dotnetsupport.debugger.mono

import java.util.concurrent.TimeUnit

/** An expression of Evaluate, a watch, the hover: the part of C# the Unity debugger evaluates. */
sealed class MonoExpr {
    data class Literal(val value: Any?) : MonoExpr()
    data class Name(val name: String) : MonoExpr()
    data object This : MonoExpr()
    data class Member(val target: MonoExpr, val name: String) : MonoExpr()
    /** A call: [target] null — of the frame's own type (`Describe(1)`). */
    data class Call(val target: MonoExpr?, val name: String, val args: List<MonoExpr>) : MonoExpr()
    data class Index(val target: MonoExpr, val args: List<MonoExpr>) : MonoExpr()
    data class Unary(val op: String, val operand: MonoExpr) : MonoExpr()
    data class Binary(val op: String, val left: MonoExpr, val right: MonoExpr) : MonoExpr()
    data class Conditional(val condition: MonoExpr, val then: MonoExpr, val otherwise: MonoExpr) : MonoExpr()
}

class MonoEvaluationException(message: String) : Exception(message)

/**
 * A parser of that part of C#, by precedence as C# has it: `?:`, `||`, `&&`, `==` `!=`, `<` `>` `<=` `>=`, `+` `-`, `*` `/` `%`, unary
 * `!` `-` `+`, then member access, calls and indexers on literals, names, `this` and parentheses. No casts, lambdas, `new`, generics.
 */
class MonoExpressionParser(private val text: String) {
    private var at = 0

    fun parse(): MonoExpr {
        val expr = conditional()
        skip()
        if (at < text.length) fail("Unexpected '${text.substring(at).take(10)}'")
        return expr
    }

    private fun conditional(): MonoExpr {
        val condition = binary(0)
        if (!eat("?")) return condition
        val then = conditional()
        expect(":")
        return MonoExpr.Conditional(condition, then, conditional())
    }

    private val levels = listOf(listOf("||"), listOf("&&"), listOf("==", "!="), listOf("<=", ">=", "<", ">"), listOf("+", "-"), listOf("*", "/", "%"))

    private fun binary(level: Int): MonoExpr {
        if (level == levels.size) return unary()
        var left = binary(level + 1)
        while (true) {
            skip()
            // the longer operators come first in their level (`<=` before `<`); `=` alone (an assignment) is in none and ends the parse
            val op = levels[level].firstOrNull { text.startsWith(it, at) } ?: return left
            at += op.length
            left = MonoExpr.Binary(op, left, binary(level + 1))
        }
    }

    private fun unary(): MonoExpr {
        skip()
        for (op in listOf("!", "-", "+")) if (text.startsWith(op, at) && !text.startsWith("!=", at)) {
            at += 1
            return MonoExpr.Unary(op, unary())
        }
        return postfix(primary())
    }

    private fun postfix(start: MonoExpr): MonoExpr {
        var expr = start
        while (true) {
            skip()
            expr = when {
                eat(".") -> {
                    val name = identifier() ?: fail("A name is expected after '.'")
                    if (peek("(")) MonoExpr.Call(expr, name, arguments("(", ")")) else MonoExpr.Member(expr, name)
                }
                peek("[") -> MonoExpr.Index(expr, arguments("[", "]"))
                else -> return expr
            }
        }
    }

    private fun primary(): MonoExpr {
        skip()
        if (at >= text.length) fail("An expression is expected")
        val c = text[at]
        return when {
            eat("(") -> conditional().also { expect(")") }
            c == '"' -> MonoExpr.Literal(string())
            c == '\'' -> MonoExpr.Literal(char())
            c.isDigit() || c == '.' && at + 1 < text.length && text[at + 1].isDigit() -> MonoExpr.Literal(number())
            else -> {
                val name = identifier() ?: fail("Unexpected '$c'")
                when (name) {
                    "true" -> MonoExpr.Literal(true)
                    "false" -> MonoExpr.Literal(false)
                    "null" -> MonoExpr.Literal(null)
                    "this" -> MonoExpr.This
                    else -> if (peek("(")) MonoExpr.Call(null, name, arguments("(", ")")) else MonoExpr.Name(name)
                }
            }
        }
    }

    private fun arguments(open: String, close: String): List<MonoExpr> {
        expect(open)
        val args = ArrayList<MonoExpr>()
        if (eat(close)) return args
        do args += conditional() while (eat(","))
        expect(close)
        return args
    }

    private fun identifier(): String? {
        skip()
        val start = at
        if (at < text.length && text[at] == '@') at++
        if (at >= text.length || !(text[at].isLetter() || text[at] == '_')) { at = start; return null }
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        return text.substring(start, at).removePrefix("@")
    }

    private fun number(): Any {
        val start = at
        if (text.startsWith("0x", at) || text.startsWith("0X", at)) {
            at += 2
            while (at < text.length && (text[at].isLetterOrDigit())) at++
            val digits = text.substring(start + 2, at).trimEnd('L', 'l', 'U', 'u')
            return digits.toLong(16).let { if (it in Int.MIN_VALUE..Int.MAX_VALUE && !text.substring(start, at).endsWith("L", true)) it.toInt() else it }
        }
        while (at < text.length && (text[at].isDigit() || text[at] == '.' || text[at] == '_')) at++
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            at++
            if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
            while (at < text.length && text[at].isDigit()) at++
        }
        val digits = text.substring(start, at).replace("_", "")
        val suffix = if (at < text.length && text[at].lowercaseChar() in "fdlm") text[at++].lowercaseChar() else null
        return when {
            suffix == 'f' -> digits.toFloat()
            suffix == 'd' || suffix == 'm' -> digits.toDouble()
            suffix == 'l' -> digits.toLong()
            '.' in digits || 'e' in digits || 'E' in digits -> digits.toDouble()
            else -> digits.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: fail("A number too big: $digits")
        }
    }

    private fun string(): String {
        at++ // the opening quote
        val out = StringBuilder()
        while (at < text.length && text[at] != '"') out.append(escaped())
        expect("\"")
        return out.toString()
    }

    private fun char(): Char {
        at++
        val c = escaped()
        expect("'")
        return c
    }

    private fun escaped(): Char {
        val c = text[at++]
        if (c != '\\' || at >= text.length) return c
        return when (val e = text[at++]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            '0' -> '\u0000'
            'u' -> text.substring(at, at + 4).toInt(16).toChar().also { at += 4 }
            else -> e
        }
    }

    private fun skip() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(token: String): Boolean {
        skip()
        return text.startsWith(token, at)
    }

    private fun eat(token: String): Boolean {
        if (!peek(token)) return false
        at += token.length
        return true
    }

    private fun expect(token: String) {
        if (!eat(token)) fail("'$token' is expected")
    }

    private fun fail(message: String): Nothing = throw MonoEvaluationException(message)
}

/** The frame an evaluation runs in: the thread it was read from (invocations run on it) and the frame (its domain, for strings). */
data class MonoFrameRef(val thread: Int, val frame: Int)

/**
 * Evaluates a [MonoExpr] in a frame: variables, fields, properties (their getters invoked in the runtime), methods (by name and the count
 * of arguments; the first such overload), static members of a type by its name (the frame's namespace, the `using`s of its file, `UnityEngine`,
 * `System`), elements of arrays, `[…]` of anything else as its indexer. Arithmetic, comparisons and `+` of strings are done by the IDE.
 */
class MonoInterpreter(
    private val process: MonoDebugProcess,
    private val frame: MonoFrameRef,
    private val variables: List<MonoVariable>,
    /** The type the frame's method belongs to: what a bare name or call falls back to. */
    private val ownType: Int?,
    /** Namespaces a bare type name is looked for in. */
    private val namespaces: List<String>,
) {
    private val connection get() = process.connection
    private val types get() = process.types

    /** A value and its declared type, when the evaluation knows it. */
    data class Result(val value: MonoValue, val type: Int? = null)

    fun evaluate(expr: MonoExpr): Result = when (expr) {
        is MonoExpr.Literal -> Result(literal(expr.value))
        is MonoExpr.This -> variables.firstOrNull { it.name == "this" }?.let { Result(it.value, it.type) } ?: fail("There is no 'this' in a static method")
        is MonoExpr.Name -> name(expr.name)
        is MonoExpr.Member -> member(expr)
        is MonoExpr.Call -> call(expr)
        is MonoExpr.Index -> index(evaluate(expr.target).value, expr.args.map { evaluate(it).value })
        is MonoExpr.Unary -> unary(expr.op, evaluate(expr.operand).value)
        is MonoExpr.Binary -> binary(expr)
        is MonoExpr.Conditional -> if (truth(evaluate(expr.condition).value)) evaluate(expr.then) else evaluate(expr.otherwise)
    }

    private fun literal(value: Any?): MonoValue {
        val e = MonoProtocol.ElementType
        return when (value) {
            null -> MonoValue.Null
            is Boolean -> MonoValue.Primitive(e.BOOLEAN, value)
            is Char -> MonoValue.Primitive(e.CHAR, value)
            is Int -> MonoValue.Primitive(e.I4, value)
            is Long -> MonoValue.Primitive(e.I8, value)
            is Float -> MonoValue.Primitive(e.R4, value)
            is Double -> MonoValue.Primitive(e.R8, value)
            is String -> MonoValue.Text(value)
            else -> fail("A literal of ${value.javaClass.simpleName}")
        }
    }

    private fun name(name: String): Result {
        variables.firstOrNull { it.name == name }?.let { return Result(it.value, it.type) }
        val self = variables.firstOrNull { it.name == "this" }
        if (self != null) memberOf(self.value, name)?.let { return it }
        ownType?.let { type -> staticMember(type, name)?.let { return it } }
        resolveType(name)?.let { return Result(MonoValue.TypeRef(it)) }
        fail("Cannot find '$name' in the frame")
    }

    private fun member(expr: MonoExpr.Member): Result {
        val target = try {
            evaluate(expr.target)
        } catch (e: MonoEvaluationException) {
            // `UnityEngine.Time` — a namespace, then a type
            val dotted = dotted(expr) ?: throw e
            resolveType(dotted)?.let { return Result(MonoValue.TypeRef(it)) }
            throw e
        }
        return memberOf(target.value, expr.name) ?: fail("'${describe(expr.target)}' has no member '${expr.name}'")
    }

    /** A field, a property or `Length` of [value]; static ones of a type. */
    private fun memberOf(value: MonoValue, name: String): Result? = when (value) {
        is MonoValue.TypeRef -> staticMember(value.id, name)
        is MonoValue.Text -> if (name == "Length") Result(int(value.text.length)) else memberOf(runtimeString(value.text), name)
        is MonoValue.Ref -> {
            if (value.type == MonoProtocol.ElementType.STRING && name == "Length") Result(int(connection.stringValue(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).length))
            else {
                val type = connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                if (types.info(type).rank > 0 && name == "Length") Result(int(connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).fold(1) { a, b -> a * b }))
                else instanceMember(value, type, name)
            }
        }
        is MonoValue.Struct -> instanceMember(value, value.type, name)
        is MonoValue.Null -> fail("Cannot read '$name' of null")
        else -> null
    }

    private fun instanceMember(receiver: MonoValue, type: Int, name: String): Result? {
        val field = types.instanceFields(type).firstOrNull { MonoPresentation.fieldName(it.second.name) == name }?.second
        if (field != null) {
            return when (receiver) {
                is MonoValue.Ref -> Result(connection.objectValues(receiver.id, listOf(field.id)).get(TIMEOUT_S, TimeUnit.SECONDS).single(), field.type)
                is MonoValue.Struct -> {
                    val own = types.fields(type).filter { !it.isStatic }
                    val index = own.indexOfFirst { it.id == field.id }
                    if (index in receiver.fields.indices) Result(receiver.fields[index], field.type) else null
                }
                else -> null
            }
        }
        val property = types.properties(type).firstOrNull { it.name == name && it.getter != 0 } ?: return null
        return Result(process.invoke(frame, property.getter, receiver, emptyList()), returnType(property.getter))
    }

    private fun returnType(method: Int): Int? = runCatching { types.parameters(method).returnType.takeIf { it != 0 } }.getOrNull()

    private fun staticMember(type: Int, name: String): Result? {
        generateSequence(type) { types.info(it).baseType.takeIf { base -> base != 0 } }.forEach { owner ->
            types.fields(owner).firstOrNull { it.isStatic && MonoPresentation.fieldName(it.name) == name }?.let { field ->
                return Result(connection.staticValues(owner, listOf(field.id)).get(TIMEOUT_S, TimeUnit.SECONDS).single(), field.type)
            }
        }
        val property = types.properties(type).firstOrNull { it.name == name && it.getter != 0 } ?: return null
        return Result(process.invoke(frame, property.getter, MonoValue.Null, emptyList()), returnType(property.getter))
    }

    private fun call(expr: MonoExpr.Call): Result {
        val args = expr.args.map { argument(evaluate(it).value) }
        val receiver: MonoValue
        val type: Int
        if (expr.target == null) {
            val self = variables.firstOrNull { it.name == "this" }?.value
            val selfType = self?.let { typeOf(it) }
            val own = selfType?.let { types.overload(it, expr.name, args, static = null) }
            if (own != null) return Result(process.invoke(frame, own, if (types.isStatic(own)) MonoValue.Null else self, coerce(own, args)), returnType(own))
            type = ownType ?: fail("Cannot find '${expr.name}' in the frame")
            receiver = MonoValue.Null
        } else {
            val target = evaluate(expr.target).value
            if (target is MonoValue.Primitive && expr.name == "ToString" && args.isEmpty()) return Result(MonoValue.Text(MonoPresentation.primitiveText(target).orEmpty().trim('\'')))
            if (target is MonoValue.Text && expr.name == "ToString" && args.isEmpty()) return Result(target)
            if (target is MonoValue.Null) fail("Cannot call '${expr.name}' on null")
            type = typeOf(target) ?: fail("Cannot call '${expr.name}' on ${describe(expr.target)}")
            // a static method through its type (`Mathf.Max(2, 7)`) has no receiver
            receiver = when (target) {
                is MonoValue.TypeRef -> MonoValue.Null
                is MonoValue.Text -> runtimeString(target.text)
                else -> target
            }
        }
        val method = types.overload(type, expr.name, args, static = receiver is MonoValue.Null)
            ?: fail("${MonoPresentation.typeName(types.info(type).name.fullName)} has no method '${expr.name}' of ${args.size} argument" + if (args.size == 1) "" else "s")
        return Result(process.invoke(frame, method, receiver, coerce(method, args)), returnType(method))
    }

    /**
     * The arguments converted to the types of the parameters where C# would convert them implicitly (`2` to a `float` parameter): the agent
     * takes a value only of the parameter's own element type.
     */
    private fun coerce(method: Int, args: List<MonoValue>): List<MonoValue> {
        val params = runCatching { types.parameters(method).types }.getOrNull() ?: return args
        return args.mapIndexed { i, arg ->
            val target = params.getOrNull(i)?.let { runCatching { PRIMITIVE_TYPES[types.info(it).name.fullName] }.getOrNull() } ?: return@mapIndexed arg
            if (arg !is MonoValue.Primitive || arg.type == target || arg.value is Boolean) arg else convert(arg, target)
        }
    }

    private fun convert(value: MonoValue.Primitive, type: Int): MonoValue {
        val e = MonoProtocol.ElementType
        val n = number(value)
        return MonoValue.Primitive(type, when (type) {
            e.R4 -> n.toFloat()
            e.R8 -> n.toDouble()
            e.I8 -> n.toLong()
            e.I4 -> n.toInt()
            e.I2 -> n.toShort()
            e.I1 -> n.toByte()
            e.U1, e.U2 -> n.toInt()
            e.U4 -> n.toLong()
            e.CHAR -> n.toInt().toChar()
            else -> return value
        })
    }

    private fun index(target: MonoValue, args: List<MonoValue>): Result {
        if (target is MonoValue.Text) return Result(MonoValue.Primitive(MonoProtocol.ElementType.CHAR, target.text[intOf(args.single())]))
        if (target is MonoValue.Ref) {
            val type = connection.objectType(target.id).get(TIMEOUT_S, TimeUnit.SECONDS)
            if (types.info(type).rank > 0) {
                val lengths = connection.arrayLengths(target.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                if (args.size != lengths.size) fail("The array has ${lengths.size} dimension" + if (lengths.size == 1) "" else "s")
                var flat = 0
                args.forEachIndexed { i, arg ->
                    val k = intOf(arg)
                    if (k < 0 || k >= lengths[i]) fail("Index $k is out of the bounds of the array (${lengths[i]})")
                    flat = flat * lengths[i] + k
                }
                return Result(connection.arrayValues(target.id, flat, 1).get(TIMEOUT_S, TimeUnit.SECONDS).single())
            }
        }
        val type = typeOf(target) ?: fail("Cannot index ${target.javaClass.simpleName}")
        val getter = types.overload(type, "get_Item", args, static = false) ?: fail("${MonoPresentation.typeName(types.info(type).name.fullName)} has no indexer")
        return Result(process.invoke(frame, getter, target, coerce(getter, args.map(::argument))), returnType(getter))
    }

    private fun unary(op: String, value: MonoValue): Result = when (op) {
        "!" -> Result(bool(!truth(value)))
        "-" -> Result(arithmetic("-", int(0), value))
        else -> Result(value)
    }

    private fun binary(expr: MonoExpr.Binary): Result {
        if (expr.op == "&&") return Result(bool(truth(evaluate(expr.left).value) && truth(evaluate(expr.right).value)))
        if (expr.op == "||") return Result(bool(truth(evaluate(expr.left).value) || truth(evaluate(expr.right).value)))
        val left = evaluate(expr.left).value
        val right = evaluate(expr.right).value
        return Result(when (expr.op) {
            "==" -> bool(same(left, right))
            "!=" -> bool(!same(left, right))
            "+" -> if (isText(left) || isText(right)) MonoValue.Text(text(left) + text(right)) else operator("+", left, right) ?: arithmetic("+", left, right)
            "<", ">", "<=", ">=" -> {
                val c = compare(left, right)
                bool(when (expr.op) { "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 })
            }
            else -> operator(expr.op, left, right) ?: arithmetic(expr.op, left, right)
        })
    }

    /**
     * A user-defined operator of a struct or a class operand (`Vector3.one * 2` is `Vector3.op_Multiply(Vector3, float)`), invoked as the static
     * method it is; null when both operands are numbers or no such operator has two parameters.
     */
    private fun operator(op: String, left: MonoValue, right: MonoValue): MonoValue? {
        if (left is MonoValue.Primitive && right is MonoValue.Primitive) return null
        val name = OPERATORS[op] ?: return null
        val method = listOfNotNull(typeOf(left), typeOf(right)).distinct().firstNotNullOfOrNull { types.overload(it, name, listOf(left, right), static = true) } ?: return null
        return process.invoke(frame, method, MonoValue.Null, coerce(method, listOf(argument(left), argument(right))))
    }

    /** Numbers as C# promotes them: double if either is floating (float if both fit), long if either is long, else int. */
    private fun arithmetic(op: String, left: MonoValue, right: MonoValue): MonoValue {
        val a = number(left)
        val b = number(right)
        val e = MonoProtocol.ElementType
        return when {
            a is Double || b is Double -> MonoValue.Primitive(e.R8, apply(op, a.toDouble(), b.toDouble()))
            a is Float || b is Float -> MonoValue.Primitive(e.R4, apply(op, a.toDouble(), b.toDouble()).toFloat())
            a is Long || b is Long -> MonoValue.Primitive(e.I8, applyLong(op, a.toLong(), b.toLong()))
            else -> MonoValue.Primitive(e.I4, applyLong(op, a.toLong(), b.toLong()).toInt())
        }
    }

    private fun apply(op: String, a: Double, b: Double): Double = when (op) {
        "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b
        else -> fail("'$op' of numbers")
    }

    private fun applyLong(op: String, a: Long, b: Long): Long = when (op) {
        "+" -> a + b; "-" -> a - b; "*" -> a * b
        "/" -> if (b == 0L) fail("Division by zero") else a / b
        "%" -> if (b == 0L) fail("Division by zero") else a % b
        else -> fail("'$op' of numbers")
    }

    private fun number(value: MonoValue): Number {
        val v = (value as? MonoValue.Primitive)?.value ?: fail("Not a number: ${presentable(value)}")
        return when (v) {
            is Char -> v.code
            is Boolean -> fail("Not a number: $v")
            is String -> v.toBigInteger().toDouble() // ulong
            is Number -> v
            else -> fail("Not a number: $v")
        }
    }

    private fun compare(left: MonoValue, right: MonoValue): Int {
        if (isText(left) && isText(right)) return text(left).compareTo(text(right))
        return number(left).toDouble().compareTo(number(right).toDouble())
    }

    private fun same(left: MonoValue, right: MonoValue): Boolean = when {
        left is MonoValue.Null || right is MonoValue.Null -> left is MonoValue.Null && right is MonoValue.Null
        isText(left) && isText(right) -> text(left) == text(right)
        left is MonoValue.Primitive && right is MonoValue.Primitive ->
            if (left.value is Boolean || right.value is Boolean) left.value == right.value else number(left).toDouble() == number(right).toDouble()
        left is MonoValue.Ref && right is MonoValue.Ref -> left.id == right.id
        else -> left == right
    }

    private fun truth(value: MonoValue): Boolean = ((value as? MonoValue.Primitive)?.value as? Boolean) ?: fail("Not a bool: ${presentable(value)}")

    private fun isText(value: MonoValue): Boolean = value is MonoValue.Text || value is MonoValue.Ref && value.type == MonoProtocol.ElementType.STRING

    /** The text of a value in a concatenation, as C# would make it (`ToString()` of an object, invoked). */
    private fun text(value: MonoValue): String = when (value) {
        is MonoValue.Text -> value.text
        is MonoValue.Null -> ""
        is MonoValue.Primitive -> MonoPresentation.primitiveText(value).orEmpty().let { if (value.value is Char) it.trim('\'') else it }
        is MonoValue.Ref -> if (value.type == MonoProtocol.ElementType.STRING) connection.stringValue(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
            else toStringOf(value)
        is MonoValue.Struct -> toStringOf(value)
        else -> value.toString()
    }

    private fun toStringOf(value: MonoValue): String {
        val type = typeOf(value) ?: return value.toString()
        val method = types.method(type, "ToString", 0) ?: return MonoPresentation.typeName(types.info(type).name.fullName)
        val result = process.invoke(frame, method, value, emptyList())
        return (result as? MonoValue.Ref)?.let { connection.stringValue(it.id).get(TIMEOUT_S, TimeUnit.SECONDS) } ?: ""
    }

    /** An argument for the runtime: a string of the IDE made a string of the runtime. */
    private fun argument(value: MonoValue): MonoValue = if (value is MonoValue.Text) runtimeString(value.text) else value

    private fun runtimeString(text: String): MonoValue.Ref {
        val domain = connection.frameDomain(frame.thread, frame.frame).get(TIMEOUT_S, TimeUnit.SECONDS)
        return connection.createString(domain, text).get(TIMEOUT_S, TimeUnit.SECONDS)
    }

    private fun typeOf(value: MonoValue): Int? = when (value) {
        is MonoValue.Ref -> connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
        is MonoValue.Struct -> value.type
        is MonoValue.TypeRef -> value.id
        else -> null
    }

    /** A type by its name as written: a keyword of C# (`string`), full, or in one of the namespaces of the frame. */
    private fun resolveType(name: String): Int? {
        val candidates = KEYWORD_TYPES[name]?.let { listOf(it) } ?: (listOf(name) + namespaces.map { "$it.$name" })
        for (candidate in candidates.distinct()) {
            val found = runCatching { connection.typesByName(candidate).get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull().orEmpty()
            if (found.isNotEmpty()) return ofFrameDomain(found)
        }
        return null
    }

    /**
     * Of the same type in several domains, the one of the frame's domain: the agent refuses an object of another domain as an argument
     * (INVALID_ARGUMENT — `string.Equals(args[1], name)` with the `System.String` of the root domain, seen on Unity 6000.6).
     */
    private fun ofFrameDomain(found: List<Int>): Int {
        if (found.size == 1) return found.single()
        val assemblies = runCatching { process.domainAssemblies(connection.frameDomain(frame.thread, frame.frame).get(TIMEOUT_S, TimeUnit.SECONDS)) }.getOrNull()
            ?: return found.last()
        return found.firstOrNull { runCatching { types.info(it).assembly in assemblies }.getOrDefault(false) } ?: found.last()
    }

    private fun dotted(expr: MonoExpr): String? = when (expr) {
        is MonoExpr.Name -> expr.name
        is MonoExpr.Member -> dotted(expr.target)?.let { "$it.${expr.name}" }
        else -> null
    }

    private fun describe(expr: MonoExpr): String = dotted(expr) ?: "the expression"

    private fun presentable(value: MonoValue): String = when (value) {
        is MonoValue.Text -> "\"${value.text}\""
        is MonoValue.Primitive -> MonoPresentation.primitiveText(value).orEmpty()
        else -> value.javaClass.simpleName
    }

    private fun intOf(value: MonoValue): Int = (number(value) as? Int) ?: number(value).toInt()

    private fun int(value: Int) = MonoValue.Primitive(MonoProtocol.ElementType.I4, value)

    private fun bool(value: Boolean) = MonoValue.Primitive(MonoProtocol.ElementType.BOOLEAN, value)

    private fun fail(message: String): Nothing = throw MonoEvaluationException(message)

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S

        private val KEYWORD_TYPES = mapOf(
            "string" to "System.String", "object" to "System.Object", "bool" to "System.Boolean", "char" to "System.Char", "int" to "System.Int32",
            "uint" to "System.UInt32", "long" to "System.Int64", "ulong" to "System.UInt64", "short" to "System.Int16", "ushort" to "System.UInt16",
            "byte" to "System.Byte", "sbyte" to "System.SByte", "float" to "System.Single", "double" to "System.Double", "decimal" to "System.Decimal",
        )

        private val OPERATORS = mapOf(
            "+" to "op_Addition", "-" to "op_Subtraction", "*" to "op_Multiply", "/" to "op_Division", "%" to "op_Modulus",
            "<" to "op_LessThan", ">" to "op_GreaterThan", "<=" to "op_LessThanOrEqual", ">=" to "op_GreaterThanOrEqual",
        )

        private val PRIMITIVE_TYPES = with(MonoProtocol.ElementType) {
            mapOf("System.Single" to R4, "System.Double" to R8, "System.Int32" to I4, "System.Int64" to I8, "System.Int16" to I2, "System.SByte" to I1,
                "System.Byte" to U1, "System.UInt16" to U2, "System.UInt32" to U4, "System.Char" to CHAR)
        }

        /** The namespaces of `using` directives in [source] (a frame's file), for bare type names. */
        fun usings(source: CharSequence): List<String> =
            Regex("""(?m)^\s*(?:global\s+)?using\s+(?!static\b)([\w.]+)\s*;""").findAll(source).map { it.groupValues[1] }.toList()
    }
}
