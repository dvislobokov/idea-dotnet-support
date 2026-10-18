package io.github.dotnetsupport.debugger.mono

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation
import io.github.dotnetsupport.lang.CSharpHoverExpression
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/**
 * What the variables need of the runtime, asked once per session: the infos and fields of types, the names of enum values. The ids of
 * types are new in a reloaded domain, so nothing here goes stale — old ids are just no longer asked for.
 */
class MonoTypes(private val connection: () -> MonoConnection) {
    private val infos = ConcurrentHashMap<Int, MonoTypeInfo>()
    private val declared = ConcurrentHashMap<Int, List<MonoField>>()
    private val enums = ConcurrentHashMap<Int, Map<String, String>>()

    fun info(type: Int): MonoTypeInfo = infos.getOrPut(type) { connection().typeInfo(type).get(TIMEOUT_S, TimeUnit.SECONDS) }

    fun fields(type: Int): List<MonoField> = declared.getOrPut(type) { connection().typeFields(type).get(TIMEOUT_S, TimeUnit.SECONDS) }

    /** The instance fields of [type] and its bases, the type's own first, each with the type that declares it. */
    fun instanceFields(type: Int): List<Pair<Int, MonoField>> =
        generateSequence(type) { info(it).baseType.takeIf { base -> base != 0 } }
            .takeWhile { info(it).name.fullName != "System.Object" && info(it).name.fullName != "System.ValueType" }
            .flatMap { owner -> fields(owner).filter { !it.isStatic }.map { owner to it } }
            .toList()

    /** The static fields a type declares, constants left out. */
    fun staticFields(type: Int): List<MonoField> = fields(type).filter { it.isStatic && !it.isLiteral }

    /** The name of an enum value (its constants read once), null when it is none of them (a combination of flags). */
    fun enumName(type: Int, value: MonoValue): String? {
        val names = enums.getOrPut(type) {
            runCatching {
                val constants = fields(type).filter { it.isStatic && it.isLiteral }
                if (constants.isEmpty()) return@runCatching emptyMap()
                val values = connection().staticValues(type, constants.map { it.id }).get(TIMEOUT_S, TimeUnit.SECONDS)
                constants.zip(values).mapNotNull { (field, constant) -> MonoPresentation.primitiveText(constant)?.let { it to field.name } }.toMap()
            }.getOrDefault(emptyMap())
        }
        return MonoPresentation.primitiveText(value)?.let(names::get)
    }

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S
    }
}

/** How values read: the text, the C# name of a type. Pure, for the tests. */
object MonoPresentation {
    private val KEYWORDS = mapOf(
        "System.Boolean" to "bool", "System.Char" to "char", "System.SByte" to "sbyte", "System.Byte" to "byte", "System.Int16" to "short",
        "System.UInt16" to "ushort", "System.Int32" to "int", "System.UInt32" to "uint", "System.Int64" to "long", "System.UInt64" to "ulong",
        "System.Single" to "float", "System.Double" to "double", "System.Decimal" to "decimal", "System.String" to "string", "System.Object" to "object",
        "System.Void" to "void",
    )

    private val PRIMITIVES = with(MonoProtocol.ElementType) {
        mapOf(BOOLEAN to "bool", CHAR to "char", I1 to "sbyte", U1 to "byte", I2 to "short", U2 to "ushort", I4 to "int", U4 to "uint", I8 to "long",
            U8 to "ulong", R4 to "float", R8 to "double", I to "nint", U to "nuint")
    }

    /** `System.Int32` → `int`; a generic `List`1[[...]]` loses the assembly-qualified arguments the runtime writes. */
    fun typeName(fullName: String): String {
        KEYWORDS[fullName]?.let { return it }
        if (fullName.endsWith("[]")) return typeName(fullName.removeSuffix("[]")) + "[]"
        val generic = fullName.indexOf("[[")
        return if (generic > 0) fullName.substring(0, generic) else fullName
    }

    fun primitiveType(type: Int): String? = PRIMITIVES[type]

    /** The text of a number, a bool or a char as C# writes it; null for anything else. */
    fun primitiveText(value: MonoValue): String? {
        if (value !is MonoValue.Primitive) return null
        return when (val v = value.value) {
            is Boolean -> v.toString()
            is Char -> "'" + escape(v.toString(), '\'') + "'"
            is Float -> if (v.isNaN() || v.isInfinite()) v.toString() else v.toString().removeSuffix(".0")
            is Double -> if (v.isNaN() || v.isInfinite()) v.toString() else v.toString().removeSuffix(".0")
            else -> v.toString()
        }
    }

    fun escape(text: String, quote: Char): String = buildString {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u0000' -> append("\\0")
            quote -> append('\\').append(c)
            else -> append(c)
        }
    }

    /**
     * The name a field is shown by: an auto-property's backing field by the property (`<Name>k__BackingField`), the hoisted locals of an
     * iterator, an async method or a lambda by the local (`<count>5__2`), `<>4__this` as `this`; the machinery of the compiler (`<>1__state`,
     * `<>t__builder`, `<>u__1`…) — null, not shown.
     */
    fun fieldName(name: String): String? {
        if (!name.startsWith("<")) return name
        Regex("""^<(.+)>k__BackingField$""").find(name)?.let { return it.groupValues[1] }
        Regex("""^<(.+)>5__\d+$""").find(name)?.let { return it.groupValues[1] }
        if (name == "<>4__this") return "this"
        return null
    }

    /** A compiler-made type (a state machine, a display class of a lambda): its fields are the variables of the method. */
    fun isCompilerGenerated(typeName: String): Boolean = typeName.substringAfterLast('.').substringAfterLast('/').startsWith("<")

    /** A local the compiler made (`CS$4$0000`, a nameless one): not shown. */
    fun isHiddenLocal(name: String): Boolean = name.isEmpty() || name.startsWith("CS$") || name.startsWith("<")
}

/** A variable, a field, an element: a value of the runtime and how it is shown; its children are read when it is opened. */
class MonoValueNode(
    private val process: MonoDebugProcess, name: String, val value: MonoValue, private val icon: Icon = AllIcons.Nodes.Variable,
    private val declaredType: Int? = null,
) : XNamedValue(name) {
    private val connection get() = process.connection
    private val types get() = process.types

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        when (value) {
            is MonoValue.Primitive -> node.setPresentation(icon, XRegularValuePresentation(MonoPresentation.primitiveText(value).orEmpty(), MonoPresentation.primitiveType(value.type)), false)
            is MonoValue.Null -> node.setPresentation(icon, XRegularValuePresentation("null", declaredType?.let { runCatching { MonoPresentation.typeName(types.info(it).name.fullName) }.getOrNull() }), false)
            is MonoValue.Pointer -> node.setPresentation(icon, XRegularValuePresentation("0x" + value.address.toString(16), MonoPresentation.primitiveType(value.type)), false)
            else -> background(node) { presentation(node) }
        }
    }

    private fun presentation(node: XValueNode) {
        when (value) {
            is MonoValue.Ref -> when (value.type) {
                MonoProtocol.ElementType.STRING -> {
                    val text = connection.stringValue(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                    node.setPresentation(icon, object : XStringValuePresentation(text) {
                        override fun getType(): String = "string"
                    }, false)
                }
                else -> {
                    val type = types.info(connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS))
                    val name = MonoPresentation.typeName(type.name.fullName)
                    if (type.rank > 0) {
                        val lengths = connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                        val shown = name.substringBefore('[') + "[" + lengths.joinToString(",") + "]"
                        node.setPresentation(icon, XRegularValuePresentation("{$shown}", name), lengths.any { it > 0 })
                    } else {
                        node.setPresentation(icon, XRegularValuePresentation("{$name}", name), true)
                    }
                }
            }
            is MonoValue.Struct -> {
                val type = types.info(value.type)
                val name = MonoPresentation.typeName(type.name.fullName)
                if (value.isEnum) {
                    val raw = value.fields.firstOrNull() ?: MonoValue.Void
                    val text = types.enumName(value.type, raw)?.let { "$name.$it" } ?: MonoPresentation.primitiveText(raw).orEmpty()
                    return node.setPresentation(icon, XRegularValuePresentation(text, name), false)
                }
                // IntPtr / UIntPtr: a struct of one pointer, shown as the address
                (value.fields.singleOrNull() as? MonoValue.Pointer)?.let { return node.setPresentation(icon, XRegularValuePresentation("0x" + it.address.toString(16), name), false) }
                node.setPresentation(icon, XRegularValuePresentation(structText(value, name), name), value.fields.isNotEmpty())
            }
            is MonoValue.TypeRef -> node.setPresentation(icon, XRegularValuePresentation("{${MonoPresentation.typeName(types.info(value.id).name.fullName)}}", "Type"), false)
            else -> node.setPresentation(icon, XRegularValuePresentation(value.toString(), null), false)
        }
    }

    /** A small struct of numbers shows its fields in the line (`{x = 1, y = 2, z = 0}` for a Vector3), a bigger one its type. */
    private fun structText(value: MonoValue.Struct, name: String): String {
        val fields = types.fields(value.type).filter { !it.isStatic }
        if (fields.size != value.fields.size || fields.size > 4 || value.fields.any { it !is MonoValue.Primitive }) return "{$name}"
        return fields.zip(value.fields).joinToString(", ", "{", "}") { (field, v) -> "${MonoPresentation.fieldName(field.name) ?: field.name} = ${MonoPresentation.primitiveText(v)}" }
    }

    override fun computeChildren(node: XCompositeNode) = background(node) {
        when (value) {
            is MonoValue.Ref -> {
                val type = types.info(connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS))
                if (type.rank > 0) elements(node, connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).fold(1) { a, b -> a * b }, 0)
                else node.addChildren(objectChildren(process, value.id, connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)), true)
            }
            is MonoValue.Struct -> {
                val fields = types.fields(value.type).filter { !it.isStatic }
                val list = XValueChildrenList()
                fields.zip(value.fields).forEach { (field, v) -> MonoPresentation.fieldName(field.name)?.let { list.add(MonoValueNode(process, it, v, AllIcons.Nodes.Field, field.type)) } }
                node.addChildren(list, true)
            }
            else -> node.addChildren(XValueChildrenList.EMPTY, true)
        }
    }

    private fun elements(node: XCompositeNode, length: Int, from: Int) {
        val count = minOf(PAGE, length - from)
        val values = if (count > 0) connection.arrayValues((value as MonoValue.Ref).id, from, count).get(TIMEOUT_S, TimeUnit.SECONDS) else emptyList()
        val list = XValueChildrenList()
        values.forEachIndexed { i, v -> list.add(MonoValueNode(process, "[${from + i}]", v, AllIcons.Debugger.Value)) }
        val more = from + count < length
        node.addChildren(list, !more)
        if (more) node.tooManyChildren(length - from - count) { background(node) { elements(node, length, from + count) } }
    }

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S
        private const val PAGE = 100

        /** The fields of an object (its type's and its bases'), then a group of the static members of its type when there are some. */
        fun objectChildren(process: MonoDebugProcess, obj: Int, type: Int): XValueChildrenList {
            val fields = process.types.instanceFields(type)
            val values = if (fields.isEmpty()) emptyList() else process.connection.objectValues(obj, fields.map { it.second.id }).get(TIMEOUT_S, TimeUnit.SECONDS)
            val list = XValueChildrenList()
            fields.zip(values).forEach { (field, value) ->
                MonoPresentation.fieldName(field.second.name)?.let { list.add(MonoValueNode(process, it, value, AllIcons.Nodes.Field, field.second.type)) }
            }
            if (process.types.staticFields(type).isNotEmpty()) list.addBottomGroup(StaticMembers(process, type))
            return list
        }

        fun background(node: XCompositeNode, work: () -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    if (!node.isObsolete) work()
                } catch (e: Exception) {
                    if (!node.isObsolete) node.setErrorMessage(MonoDebugProcess.errorText(e))
                }
            }
        }

        private fun background(node: XValueNode, work: () -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    if (!node.isObsolete) work()
                } catch (e: Exception) {
                    if (!node.isObsolete) node.setPresentation(AllIcons.General.Error, XRegularValuePresentation(MonoDebugProcess.errorText(e), null), false)
                }
            }
        }
    }
}

/** The static fields of a type, as Rider groups them under an object. */
class StaticMembers(private val process: MonoDebugProcess, private val type: Int) : XValueGroup("Static members") {
    override fun computeChildren(node: XCompositeNode) = MonoValueNode.background(node) {
        val fields = process.types.staticFields(type)
        val values = process.connection.staticValues(type, fields.map { it.id }).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
        val list = XValueChildrenList()
        fields.zip(values).forEach { (field, value) -> MonoPresentation.fieldName(field.name)?.let { list.add(MonoValueNode(process, it, value, AllIcons.Nodes.Field, field.type)) } }
        node.addChildren(list, true)
    }
}

/** A variable of a frame: `this`, a parameter, a local (or a local hoisted into a field of a state machine). */
data class MonoVariable(val name: String, val value: MonoValue, val icon: Icon, val type: Int? = null)

/**
 * Evaluate, watches and the hover in the editor, without evaluation in the runtime yet: a variable of the frame, a field of `this` by its
 * name, and fields of those (`_session.Name`, `this.speed`); `Length` of an array or a string. Anything else says it cannot yet.
 */
class MonoEvaluator(private val process: MonoDebugProcess, private val variables: () -> List<MonoVariable>) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val (value, type) = evaluate(expression.trim())
                callback.evaluated(MonoValueNode(process, expression.trim(), value, AllIcons.Debugger.Value, type))
            } catch (e: Exception) {
                callback.errorOccurred(e.message ?: MonoDebugProcess.errorText(e))
            }
        }
    }

    private fun evaluate(expression: String): Pair<MonoValue, Int?> {
        val path = expression.split('.').map { it.trim() }
        if (path.any { !it.matches(Regex("""@?[\p{L}_][\p{L}\p{N}_]*""")) }) throw IllegalArgumentException(CANNOT)
        val names = path.map { it.removePrefix("@") }
        val frame = variables()
        val first = frame.firstOrNull { it.name == names[0] }
        var current: Pair<MonoValue, Int?> = when {
            first != null -> first.value to first.type
            else -> {
                // a field of `this` without `this.`
                val self = frame.firstOrNull { it.name == "this" } ?: throw IllegalArgumentException("Cannot find '${names[0]}' in the frame")
                member(self.value, names[0]) ?: throw IllegalArgumentException("Cannot find '${names[0]}' in the frame")
            }
        }
        for (name in names.drop(1)) current = member(current.first, name) ?: throw IllegalArgumentException("Cannot find '$name' in ${expression.substringBefore(".$name")}")
        return current
    }

    /** A field (or an auto-property's backing field) of an object or a struct; `Length` of a string or an array. */
    private fun member(value: MonoValue, name: String): Pair<MonoValue, Int?>? {
        val connection = process.connection
        val types = process.types
        return when (value) {
            is MonoValue.Ref -> {
                if (name == "Length" && value.type == MonoProtocol.ElementType.STRING) {
                    return MonoValue.Primitive(MonoProtocol.ElementType.I4, connection.stringValue(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).length) to null
                }
                val type = connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                if (types.info(type).rank > 0 && name == "Length") {
                    return MonoValue.Primitive(MonoProtocol.ElementType.I4, connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).fold(1) { a, b -> a * b }) to null
                }
                val field = types.instanceFields(type).firstOrNull { MonoPresentation.fieldName(it.second.name) == name }?.second ?: return null
                connection.objectValues(value.id, listOf(field.id)).get(TIMEOUT_S, TimeUnit.SECONDS).single() to field.type
            }
            is MonoValue.Struct -> {
                val fields = types.fields(value.type).filter { !it.isStatic }
                val index = fields.indexOfFirst { MonoPresentation.fieldName(it.name) == name }
                if (index < 0 || index >= value.fields.size) null else value.fields[index] to fields[index].type
            }
            else -> null
        }
    }

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        CSharpHoverExpression.rangeAt(document.immutableCharSequence, offset)

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S
        const val CANNOT = "The Unity debugger evaluates names of variables and fields only, for now"
    }
}
