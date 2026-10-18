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

    private val ownProperties = ConcurrentHashMap<Int, List<MonoProperty>>()
    private val chainProperties = ConcurrentHashMap<Int, List<MonoProperty>>()
    private val methods = ConcurrentHashMap<String, Int>()
    private val toStrings = ConcurrentHashMap<Int, Int>()
    private val parameters = ConcurrentHashMap<Int, MonoParamInfo>()

    /** The instance properties of [type] and its bases: what an object lists (the static ones, `Vector3.zero`, are its type's). */
    fun instanceProperties(type: Int): List<MonoProperty> = properties(type).filter { !isStatic(it.getter) }

    /** The static properties a type declares (`Vector3.zero`, `Time.deltaTime`). */
    fun staticProperties(type: Int): List<MonoProperty> =
        ownProperties.getOrPut(type) { connection().typeProperties(type).get(TIMEOUT_S, TimeUnit.SECONDS) }.filter { it.getter != 0 && it.name != "Item" && isStatic(it.getter) }

    /** The properties with a getter of [type] and its bases (an override once, the most derived), indexers left out. */
    fun properties(type: Int): List<MonoProperty> = chainProperties.getOrPut(type) {
        val seen = HashSet<String>()
        generateSequence(type) { info(it).baseType.takeIf { base -> base != 0 } }
            .takeWhile { info(it).name.fullName != "System.Object" && info(it).name.fullName != "System.ValueType" }
            .flatMap { owner -> ownProperties.getOrPut(owner) { connection().typeProperties(owner).get(TIMEOUT_S, TimeUnit.SECONDS) } }
            .filter { it.getter != 0 && it.name != "Item" && seen.add(it.name) }
            .toList()
    }

    /** A method of [type] or its bases named [name] with [argumentCount] parameters (the first such overload); null when none. */
    fun method(type: Int, name: String, argumentCount: Int): Int? {
        val key = "$type:$name:$argumentCount"
        methods[key]?.let { return it.takeIf { id -> id != 0 } }
        val found = generateSequence(type) { info(it).baseType.takeIf { base -> base != 0 } }.firstNotNullOfOrNull { owner ->
            connection().methodsByName(owner, name, MonoProtocol.Binding.ALL or MonoProtocol.Binding.DECLARED_ONLY).get(TIMEOUT_S, TimeUnit.SECONDS)
                .firstOrNull { parameterCount(it) == argumentCount }
        }
        methods[key] = found ?: 0
        return found
    }

    /** The parameters of a method, asked once. */
    fun parameters(method: Int): MonoParamInfo = parameters.getOrPut(method) { connection().paramInfo(method).get(TIMEOUT_S, TimeUnit.SECONDS) }

    private fun parameterCount(method: Int): Int = parameters(method).names.size

    private val statics = ConcurrentHashMap<Int, Boolean>()
    private val candidates = ConcurrentHashMap<String, List<Int>>()

    fun isStatic(method: Int): Boolean = statics.getOrPut(method) {
        connection().methodAttributes(method).get(TIMEOUT_S, TimeUnit.SECONDS) and MonoProtocol.METHOD_STATIC != 0
    }

    /**
     * The overload of [name] for [args] (the receiver's type and its bases, the most derived first): the count of parameters, static or not
     * as [static] says (null: either), then the one whose parameters take the arguments best — a number for a numeric parameter, a string
     * for `string` / `object`, null for a reference — as C# would pick among simple overloads.
     */
    fun overload(type: Int, name: String, args: List<MonoValue>, static: Boolean?): Int? {
        val all = candidates.getOrPut("$type:$name") {
            generateSequence(type) { info(it).baseType.takeIf { base -> base != 0 } }.flatMap { owner ->
                connection().methodsByName(owner, name, MonoProtocol.Binding.ALL or MonoProtocol.Binding.DECLARED_ONLY).get(TIMEOUT_S, TimeUnit.SECONDS)
            }.toList()
        }
        return all.asSequence()
            .filter { parameterCount(it) == args.size && (static == null || isStatic(it) == static) }
            .map { it to score(it, args) }
            .filter { it.second >= 0 }
            .maxByOrNull { it.second }?.first
    }

    /** How well [args] fit the parameters of [method]: -1 when one cannot be passed at all. */
    private fun score(method: Int, args: List<MonoValue>): Int {
        val params = parameters(method).types
        var total = 0
        args.forEachIndexed { i, arg ->
            val type = info(params[i]).name.fullName
            val numeric = type in NUMERIC
            total += when (arg) {
                is MonoValue.Primitive -> when {
                    arg.value is Boolean -> if (type == "System.Boolean") 3 else if (type == "System.Object") 1 else return -1
                    numeric -> if (MonoPresentation.primitiveType(arg.type) == MonoPresentation.typeName(type)) 3 else 2
                    type == "System.Object" -> 1
                    else -> return -1
                }
                is MonoValue.Text -> if (type == "System.String") 3 else if (type == "System.Object") 1 else return -1
                is MonoValue.Ref -> when {
                    numeric || type == "System.Boolean" -> return -1
                    arg.type == MonoProtocol.ElementType.STRING -> if (type == "System.String") 3 else if (type == "System.Object") 1 else return -1
                    else -> if (type == "System.String") return -1 else 1
                }
                is MonoValue.Null -> if (numeric || type == "System.Boolean" || info(params[i]).isValueType) return -1 else 1
                is MonoValue.Struct -> if (arg.type == params[i]) 3 else if (numeric || type == "System.String") return -1 else 1
                else -> 0
            }
        }
        return total
    }

    /**
     * `ToString()` of [type] when a type of it overrides the one of `System.Object` / `ValueType` / `Enum` (as Rider shows it in the line of a
     * value: `GameBootstrap (Game.Boot.GameBootstrap)` of a component, `(0.00, 0.00, 0.00)` of a Vector3); null when it does not.
     */
    fun toStringOverride(type: Int): Int? {
        toStrings[type]?.let { return it.takeIf { id -> id != 0 } }
        val found = generateSequence(type) { info(it).baseType.takeIf { base -> base != 0 } }
            .takeWhile { info(it).name.fullName !in setOf("System.Object", "System.ValueType", "System.Enum") }
            .firstNotNullOfOrNull { owner ->
                connection().methodsByName(owner, "ToString", MonoProtocol.Binding.INSTANCE or MonoProtocol.Binding.PUBLIC or MonoProtocol.Binding.DECLARED_ONLY)
                    .get(TIMEOUT_S, TimeUnit.SECONDS).firstOrNull { parameterCount(it) == 0 }
            }
        toStrings[type] = found ?: 0
        return found
    }

    /** The static fields a type declares, constants left out. */
    fun staticFields(type: Int): List<MonoField> = fields(type).filter { it.isStatic && !it.isLiteral }

    /** The name of an enum value (its constants read once), null when it is none of them (a combination of flags). */
    fun enumName(type: Int, value: MonoValue): String? {
        val names = enums.getOrPut(type) {
            runCatching {
                val constants = fields(type).filter { it.isStatic && it.isLiteral }
                if (constants.isEmpty()) return@runCatching emptyMap()
                val values = connection().staticValues(type, constants.map { it.id }).get(TIMEOUT_S, TimeUnit.SECONDS)
                // a constant of an enum comes as the enum itself (a struct of one field), its number inside
                constants.zip(values).mapNotNull { (field, constant) ->
                    val number = (constant as? MonoValue.Struct)?.fields?.singleOrNull() ?: constant
                    MonoPresentation.primitiveText(number)?.let { it to field.name }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
        return MonoPresentation.primitiveText(value)?.let(names::get)
    }

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S
        private val NUMERIC = setOf("System.Single", "System.Double", "System.Int32", "System.Int64", "System.Int16", "System.SByte", "System.Byte",
            "System.UInt16", "System.UInt32", "System.UInt64", "System.Char", "System.Decimal")
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

    /**
     * `System.Int32` → `int`; a generic type as C# writes it — the runtime writes `List`1[[System.String, mscorlib, Version=…]]`, which is
     * `System.Collections.Generic.List<string>`; a nested type's `+` is a `.`.
     */
    fun typeName(fullName: String): String {
        KEYWORDS[fullName]?.let { return it }
        if (fullName.endsWith("[]")) return typeName(fullName.removeSuffix("[]")) + "[]"
        val tick = fullName.indexOf('`')
        val open = fullName.indexOf("[[")
        if (tick < 0 || open < 0) return fullName.replace('+', '.')
        val args = genericArguments(fullName.substring(open + 1, fullName.lastIndexOf(']')))
        return fullName.substring(0, tick).replace('+', '.') + args.joinToString(", ", "<", ">") { typeName(it) }
    }

    /** `[System.String, mscorlib, …],[System.Int32, …]` → the full names of the arguments, brackets inside counted. */
    private fun genericArguments(text: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = -1
        for ((i, c) in text.withIndex()) {
            when (c) {
                '[' -> { if (depth == 0) start = i + 1; depth++ }
                ']' -> { depth--; if (depth == 0 && start >= 0) { result += text.substring(start, i); start = -1 } }
            }
        }
        // each argument is assembly-qualified: the name is what comes before the first comma outside brackets
        return result.map { arg ->
            var d = 0
            val cut = arg.indexOfFirst { c -> if (c == '[') d++ else if (c == ']') d--; c == ',' && d == 0 }
            (if (cut < 0) arg else arg.substring(0, cut)).trim()
        }
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

/**
 * A variable, a field, an element: a value of the runtime and how it is shown; its children are read when it is opened. With [frame] (the
 * stop it was read at) and implicit evaluation on, an object shows its `ToString()` when its type overrides it, and lists its properties.
 */
class MonoValueNode(
    private val process: MonoDebugProcess, name: String, val value: MonoValue, private val icon: Icon = AllIcons.Nodes.Variable,
    private val declaredType: Int? = null, private val frame: MonoFrameRef? = null,
) : XNamedValue(name) {
    private val connection get() = process.connection
    private val types get() = process.types
    private val calls: Boolean get() = frame != null && process.implicitEvaluation

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        when (value) {
            is MonoValue.Primitive -> node.setPresentation(icon, XRegularValuePresentation(MonoPresentation.primitiveText(value).orEmpty(), MonoPresentation.primitiveType(value.type)), false)
            is MonoValue.Null -> node.setPresentation(icon, XRegularValuePresentation("null", declaredType?.let { runCatching { MonoPresentation.typeName(types.info(it).name.fullName) }.getOrNull() }), false)
            is MonoValue.Pointer -> node.setPresentation(icon, XRegularValuePresentation("0x" + value.address.toString(16), MonoPresentation.primitiveType(value.type)), false)
            is MonoValue.Text -> node.setPresentation(icon, stringPresentation(value.text), false)
            else -> background(node) { presentation(node) }
        }
    }

    private fun stringPresentation(text: String) = object : XStringValuePresentation(text) {
        override fun getType(): String = "string"
    }

    private fun presentation(node: XValueNode) {
        when (value) {
            is MonoValue.Ref -> when (value.type) {
                MonoProtocol.ElementType.STRING -> node.setPresentation(icon, stringPresentation(connection.stringValue(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)), false)
                else -> {
                    val typeId = connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                    val type = types.info(typeId)
                    val name = MonoPresentation.typeName(type.name.fullName)
                    if (type.rank > 0) {
                        val lengths = connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                        val shown = name.substringBefore('[') + "[" + lengths.joinToString(",") + "]"
                        node.setPresentation(icon, XRegularValuePresentation("{$shown}", name), lengths.any { it > 0 })
                    } else {
                        val text = toStringText(typeId)
                        node.setPresentation(icon, XRegularValuePresentation("{${shortName(name)}}" + (text?.let { " $it" } ?: ""), name), true)
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
                val text = toStringText(value.type) ?: structText(value, name)
                node.setPresentation(icon, XRegularValuePresentation(text, name), value.fields.isNotEmpty())
            }
            is MonoValue.TypeRef -> node.setPresentation(icon, XRegularValuePresentation("{${MonoPresentation.typeName(types.info(value.id).name.fullName)}}", "Type"), true)
            else -> node.setPresentation(icon, XRegularValuePresentation(value.toString(), null), false)
        }
    }

    /** `List<string>` of `System.Collections.Generic.List<string>`: the name without the namespace, the arguments kept. */
    private fun shortName(name: String): String {
        val generic = name.indexOf('<')
        return if (generic < 0) name.substringAfterLast('.') else name.substring(0, generic).substringAfterLast('.') + name.substring(generic)
    }

    /** `ToString()` of the value when its type overrides it and calls are allowed; null otherwise or when the call fails. */
    private fun toStringText(type: Int): String? {
        val at = frame ?: return null
        if (!calls) return null
        val method = runCatching { types.toStringOverride(type) }.getOrNull() ?: return null
        val result = runCatching { process.invoke(at, method, value, emptyList()) }.getOrNull() as? MonoValue.Ref ?: return null
        return runCatching { connection.stringValue(result.id).get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrNull()
    }

    /** A small struct of numbers shows its fields in the line (`{x = 1, y = 2, z = 0}`), a bigger one its type. */
    private fun structText(value: MonoValue.Struct, name: String): String {
        val fields = types.fields(value.type).filter { !it.isStatic }
        if (fields.size != value.fields.size || fields.size > 4 || value.fields.any { it !is MonoValue.Primitive }) return "{$name}"
        return fields.zip(value.fields).joinToString(", ", "{", "}") { (field, v) -> "${MonoPresentation.fieldName(field.name) ?: field.name} = ${MonoPresentation.primitiveText(v)}" }
    }

    override fun computeChildren(node: XCompositeNode) = background(node) {
        when (value) {
            is MonoValue.Ref -> {
                val typeId = connection.objectType(value.id).get(TIMEOUT_S, TimeUnit.SECONDS)
                if (types.info(typeId).rank > 0) elements(node, connection.arrayLengths(value.id).get(TIMEOUT_S, TimeUnit.SECONDS).fold(1) { a, b -> a * b }, 0)
                else node.addChildren(objectChildren(process, value, typeId, frame), true)
            }
            is MonoValue.Struct -> node.addChildren(objectChildren(process, value, value.type, frame), true)
            is MonoValue.TypeRef -> node.addChildren(XValueChildrenList().apply { addBottomGroup(StaticMembers(process, value.id, frame)) }, true)
            else -> node.addChildren(XValueChildrenList.EMPTY, true)
        }
    }

    private fun elements(node: XCompositeNode, length: Int, from: Int) {
        val count = minOf(PAGE, length - from)
        val values = if (count > 0) connection.arrayValues((value as MonoValue.Ref).id, from, count).get(TIMEOUT_S, TimeUnit.SECONDS) else emptyList()
        val list = XValueChildrenList()
        values.forEachIndexed { i, v -> list.add(MonoValueNode(process, "[${from + i}]", v, AllIcons.Debugger.Value, null, frame)) }
        val more = from + count < length
        node.addChildren(list, !more)
        if (more) node.tooManyChildren(length - from - count) { background(node) { elements(node, length, from + count) } }
    }

    companion object {
        private const val TIMEOUT_S = MonoDebugProcess.TIMEOUT_S
        private const val PAGE = 100

        /**
         * Properties Unity's own types have that are not read by themselves: they make a copy of what they return (`Renderer.material`,
         * `MeshFilter.mesh`, `Collider.material` — in edit mode the copy leaks, as Rider warns) or are the removed shortcuts that only throw.
         */
        private val UNSAFE = setOf(
            "material", "materials", "mesh", "rigidbody", "rigidbody2D", "camera", "light", "animation", "constantForce", "renderer", "audio",
            "networkView", "collider", "collider2D", "hingeJoint", "particleSystem", "guiText", "guiTexture", "guiElement",
        )

        /**
         * The fields of an object or a struct (its type's and its bases'), then its properties (read when shown; with calls allowed), then a
         * group of the static members of its type when there are some.
         */
        fun objectChildren(process: MonoDebugProcess, receiver: MonoValue, type: Int, frame: MonoFrameRef?): XValueChildrenList {
            val list = XValueChildrenList()
            when (receiver) {
                is MonoValue.Ref -> {
                    val fields = process.types.instanceFields(type)
                    val values = if (fields.isEmpty()) emptyList() else process.connection.objectValues(receiver.id, fields.map { it.second.id }).get(TIMEOUT_S, TimeUnit.SECONDS)
                    fields.zip(values).forEach { (field, value) ->
                        MonoPresentation.fieldName(field.second.name)?.let { list.add(MonoValueNode(process, it, value, AllIcons.Nodes.Field, field.second.type, frame)) }
                    }
                }
                is MonoValue.Struct -> {
                    val fields = process.types.fields(type).filter { !it.isStatic }
                    fields.zip(receiver.fields).forEach { (field, v) -> MonoPresentation.fieldName(field.name)?.let { list.add(MonoValueNode(process, it, v, AllIcons.Nodes.Field, field.type, frame)) } }
                }
                else -> Unit
            }
            if (frame != null && process.implicitEvaluation) {
                val shown = HashSet<String>()
                for (i in 0 until list.size()) shown += list.getName(i)
                runCatching { process.types.instanceProperties(type) }.getOrDefault(emptyList())
                    .filter { it.name !in shown && it.name !in UNSAFE }
                    .forEach { list.add(MonoPropertyNode(process, it, receiver, frame)) }
            }
            val statics = process.types.staticFields(type).isNotEmpty() ||
                frame != null && process.implicitEvaluation && runCatching { process.types.staticProperties(type).isNotEmpty() }.getOrDefault(false)
            if (statics) list.addBottomGroup(StaticMembers(process, type, frame))
            return list
        }

        fun background(node: XCompositeNode, work: () -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    if (!node.isObsolete) work()
                } catch (e: Exception) {
                    if (!node.isObsolete) node.setErrorMessage(e.message ?: MonoDebugProcess.errorText(e))
                }
            }
        }

        fun background(node: XValueNode, work: () -> Unit) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    if (!node.isObsolete) work()
                } catch (e: Exception) {
                    if (!node.isObsolete) node.setPresentation(AllIcons.General.Error, XRegularValuePresentation(e.message ?: MonoDebugProcess.errorText(e), null), false)
                }
            }
        }
    }
}

/**
 * A property of an object: its getter is invoked when the row is shown (not before — a getter may be slow or throw, and an object has
 * many), then it shows as the value it returned; a getter that throws shows the exception in the row.
 */
class MonoPropertyNode(
    private val process: MonoDebugProcess, private val property: MonoProperty, private val receiver: MonoValue, private val frame: MonoFrameRef,
) : XNamedValue(property.name) {
    @Volatile private var evaluated: MonoValueNode? = null

    private fun evaluate(): MonoValueNode {
        evaluated?.let { return it }
        val type = runCatching { process.types.parameters(property.getter).returnType.takeIf { it != 0 } }.getOrNull()
        return MonoValueNode(process, property.name, process.invoke(frame, property.getter, receiver, emptyList()), AllIcons.Nodes.Property, type, frame)
            .also { evaluated = it }
    }

    override fun computePresentation(node: XValueNode, place: XValuePlace) = MonoValueNode.background(node) {
        evaluate().computePresentation(node, place)
    }

    override fun computeChildren(node: XCompositeNode) = MonoValueNode.background(node) { evaluate().computeChildren(node) }
}

/** The static fields and properties of a type, as Rider groups them under an object. */
class StaticMembers(private val process: MonoDebugProcess, private val type: Int, private val frame: MonoFrameRef?) : XValueGroup("Static members") {
    override fun computeChildren(node: XCompositeNode) = MonoValueNode.background(node) {
        val fields = process.types.staticFields(type)
        val values = if (fields.isEmpty()) emptyList() else process.connection.staticValues(type, fields.map { it.id }).get(MonoDebugProcess.TIMEOUT_S, TimeUnit.SECONDS)
        val list = XValueChildrenList()
        fields.zip(values).forEach { (field, value) -> MonoPresentation.fieldName(field.name)?.let { list.add(MonoValueNode(process, it, value, AllIcons.Nodes.Field, field.type, frame)) } }
        if (frame != null && process.implicitEvaluation) {
            runCatching { process.types.staticProperties(type) }.getOrDefault(emptyList()).forEach { list.add(MonoPropertyNode(process, it, MonoValue.Null, frame)) }
        }
        node.addChildren(list, true)
    }
}

/** A variable of a frame: `this`, a parameter, a local (or a local hoisted into a field of a state machine). */
data class MonoVariable(val name: String, val value: MonoValue, val icon: Icon, val type: Int? = null)

/**
 * Evaluate, watches, the hover in the editor, the conditions to come: the expression parsed by [MonoExpressionParser] and evaluated by
 * [MonoInterpreter] in the frame — fields and variables read, getters and methods invoked in the runtime on the thread of the stop.
 */
class MonoEvaluator(
    private val process: MonoDebugProcess, private val frame: MonoFrameRef, private val ownType: () -> Int?, private val namespaces: () -> List<String>,
    private val variables: () -> List<MonoVariable>,
) : XDebuggerEvaluator() {
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val result = evaluate(expression)
                callback.evaluated(MonoValueNode(process, expression.trim(), result.value, AllIcons.Debugger.Value, result.type, frame))
            } catch (e: MonoEvaluationException) {
                callback.errorOccurred(e.message ?: "Cannot evaluate")
            } catch (e: Exception) {
                callback.errorOccurred(MonoDebugProcess.errorText(e))
            }
        }
    }

    fun evaluate(expression: String): MonoInterpreter.Result =
        MonoInterpreter(process, frame, variables(), ownType(), namespaces()).evaluate(MonoExpressionParser(expression).parse())

    override fun getExpressionRangeAtOffset(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean): TextRange? =
        CSharpHoverExpression.rangeAt(document.immutableCharSequence, offset)
}
