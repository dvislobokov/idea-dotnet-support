package io.github.dotnetsupport.razor.lang.syntax

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentMetadata
import io.github.dotnetsupport.razor.lang.codegen.mvc.ViewComponentMetadata

/**
 * Tag helper descriptors as JSON: the format razordump writes (`tools/razor/razordump`, `tag-helpers` and `cases-codegen --tag-helpers-out`,
 * TagHelperJson.cs) of the descriptors the compiler discovers through a Roslyn compilation, which the port has not — everything a
 * `TagHelperDescriptor` holds, so the model is rebuilt exactly. The code generation gate reads its fixtures so
 * (`testData/codegen-tagHelpers`); it is also how the plugin's own index will hand descriptors to the engine (RAZOR_PLAN.md, R5).
 *
 * One array of descriptors; keys in a fixed order, two spaces of indentation, `[]` / `{}` when empty, only `"`, `\` and the control
 * characters escaped, a final line break: [write] gives razordump's text back byte for byte (`write(read(x)) == x`).
 */
object TagHelperDescriptorJson {
    fun read(text: String): TagHelperCollection {
        val root = Parser(text).parseDocument() as? List<*> ?: throw IllegalArgumentException("tag helper JSON: not an array")
        return TagHelperCollection(root.map { descriptor(it.obj()) })
    }

    fun write(descriptors: TagHelperCollection): String {
        val output = StringBuilder()
        writeValue(output, descriptors.map(::descriptorJson), 0)
        return output.append('\n').toString()
    }

    // ---- reading

    private fun descriptor(o: Map<String, Any?>): TagHelperDescriptor {
        var flags = 0
        if (o.bool("caseSensitive")) flags = flags or TagHelperFlags.CASE_SENSITIVE
        if (o.bool("isFullyQualifiedNameMatch")) flags = flags or TagHelperFlags.IS_FULLY_QUALIFIED_NAME_MATCH
        if (o.bool("classifyAttributesOnly")) flags = flags or TagHelperFlags.CLASSIFY_ATTRIBUTES_ONLY
        return TagHelperDescriptor(
            flags, TagHelperKind.valueOf(o.str("kind")!!), RuntimeKind.valueOf(o.str("runtimeKind")!!), o.str("name")!!, o.str("assemblyName")!!,
            o.str("displayName")!!, TypeNameObject.from(o.str("typeName")!!, o.str("typeNamespace"), o.str("typeNameIdentifier")),
            o.str("documentation"), o.str("tagOutputHint"),
            o.list("tagMatchingRules").map { rule(it.obj()) }, o.list("boundAttributes").map { attribute(it.obj()) },
            o.list("allowedChildTags").map { it.obj().let { t -> AllowedChildTagDescriptor(t.str("name")!!, t.str("displayName")!!, diagnostics(t)) } },
            metadata(o["metadata"].obj()), diagnostics(o),
        )
    }

    private fun rule(o: Map<String, Any?>) = TagMatchingRuleDescriptor(
        o.str("tagName")!!, o.str("parentTag"), TagStructure.valueOf(o.str("tagStructure")!!), o.bool("caseSensitive"),
        o.list("attributes").map { requiredAttribute(it.obj()) }, diagnostics(o),
    )

    private fun requiredAttribute(o: Map<String, Any?>): RequiredAttributeDescriptor {
        var flags = RequiredAttributeDescriptorFlags.NONE
        if (o.bool("caseSensitive")) flags = flags or RequiredAttributeDescriptorFlags.CASE_SENSITIVE
        if (o.bool("isDirectiveAttribute")) flags = flags or RequiredAttributeDescriptorFlags.IS_DIRECTIVE_ATTRIBUTE
        return RequiredAttributeDescriptor(
            flags, o.str("name")!!, RequiredAttributeNameComparison.valueOf(o.str("nameComparison")!!), o.str("value"),
            RequiredAttributeValueComparison.valueOf(o.str("valueComparison")!!), diagnostics(o),
        )
    }

    private fun attribute(o: Map<String, Any?>): BoundAttributeDescriptor {
        var flags = 0
        if (o.bool("caseSensitive")) flags = flags or BoundAttributeFlags.CASE_SENSITIVE
        if (o.bool("hasIndexer")) flags = flags or BoundAttributeFlags.HAS_INDEXER
        if (o.bool("isEnum")) flags = flags or BoundAttributeFlags.IS_ENUM
        if (o.bool("isEditorRequired")) flags = flags or BoundAttributeFlags.IS_EDITOR_REQUIRED
        if (o.bool("isDirectiveAttribute")) flags = flags or BoundAttributeFlags.IS_DIRECTIVE_ATTRIBUTE
        if (o.bool("isWeaklyTyped")) flags = flags or BoundAttributeFlags.IS_WEAKLY_TYPED
        return BoundAttributeDescriptor(
            flags, o.str("name")!!, o.str("propertyName")!!, TypeNameObject.from(o.str("typeName")), o.str("indexerNamePrefix"),
            TypeNameObject.from(o.str("indexerTypeName")), o.str("documentation"), o.str("displayName")!!, o.str("containingType"),
            o.list("parameters").map { parameter(it.obj()) }, metadata(o["metadata"].obj()), diagnostics(o),
        )
    }

    private fun parameter(o: Map<String, Any?>): BoundAttributeParameterDescriptor {
        var flags = 0
        if (o.bool("caseSensitive")) flags = flags or BoundAttributeParameterFlags.CASE_SENSITIVE
        if (o.bool("isEnum")) flags = flags or BoundAttributeParameterFlags.IS_ENUM
        if (o.bool("bindAttributeGetSet")) flags = flags or BoundAttributeParameterFlags.BIND_ATTRIBUTE_GET_SET
        return BoundAttributeParameterDescriptor(flags, o.str("name")!!, o.str("propertyName")!!, TypeNameObject.from(o.str("typeName")), o.str("documentation"), diagnostics(o))
    }

    private fun metadata(o: Map<String, Any?>): MetadataObject = when (MetadataKind.valueOf(o.str("kind")!!)) {
        MetadataKind.None -> MetadataObject.NONE
        MetadataKind.TypeParameter -> TypeParameterMetadata(o.bool("isCascading"), o.str("constraints"), o.str("nameWithAttributes"))
        MetadataKind.Property -> PropertyMetadata(
            o.str("globallyQualifiedTypeName"), o.bool("isChildContent"), o.bool("isEventCallback"), o.bool("isDelegateSignature"),
            o.bool("isDelegateWithAwaitableResult"), o.bool("isGenericTyped"), o.bool("isInitOnlyProperty"), o.bool("acceptsStringLiteral"), o.bool("acceptsAssetPath"),
        )
        MetadataKind.ChildContentParameter -> ChildContentParameterMetadata.DEFAULT
        MetadataKind.Bind -> BindMetadata(
            o.bool("isFallback"), o.str("valueAttribute"), o.str("changeAttribute"), o.str("expressionAttribute"), o.str("typeAttribute"),
            o.bool("isInvariantCulture"), o.str("format"),
        )
        MetadataKind.Component -> ComponentMetadata(o.bool("isGeneric"), o.bool("hasRenderModeDirective"), o.bool("acceptsUnmatchedAttributes"))
        MetadataKind.EventHandler -> EventHandlerMetadata(o.str("eventArgsType")!!)
        MetadataKind.ViewComponent -> ViewComponentMetadata(
            o.str("name")!!,
            o.str("originalTypeName")?.let { TypeNameObject.from(it, o.str("originalTypeNamespace"), o.str("originalTypeNameIdentifier")) } ?: TypeNameObject.NULL,
        )
        MetadataKind.AssetPath -> AssetPathMetadata(o.str("element")!!, o.str("attribute")!!)
    }

    /** A diagnostic of the compiler: its message becomes the format (`{0}` of a message kept as the single argument). */
    private fun diagnostics(o: Map<String, Any?>): List<RazorDiagnostic> = o.list("diagnostics").map { item ->
        val d = item.obj()
        val message = d.str("message") ?: ""
        val literal = Regex("\\{\\d+}").containsMatchIn(message)
        val severity = RazorDiagnosticSeverity.valueOf(d.str("severity")!!.uppercase())
        val descriptor = RazorDiagnosticDescriptor(d.str("id")!!, if (literal) "{0}" else message, severity, d.int("warningLevel"))
        val s = d["span"].obj()
        val span = SourceSpan(s.str("filePath"), s.int("absoluteIndex"), s.int("lineIndex"), s.int("characterIndex"), s.int("length"), s.int("lineCount"), s.int("endCharacterIndex"))
        RazorDiagnostic(descriptor, span, if (literal) listOf(message) else emptyList())
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(): Map<String, Any?> = this as? Map<String, Any?> ?: throw IllegalArgumentException("tag helper JSON: an object expected, got $this")
    private fun Map<String, Any?>.str(key: String): String? = this[key] as String?
    private fun Map<String, Any?>.bool(key: String): Boolean = this[key] as Boolean? ?: false
    private fun Map<String, Any?>.int(key: String): Int = (this[key] as Number?)?.toInt() ?: 0
    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as List<Any?>? ?: emptyList()

    // ---- writing: the keys in razordump's order

    private fun descriptorJson(d: TagHelperDescriptor): Map<String, Any?> = linkedMapOf(
        "kind" to d.kind.name, "runtimeKind" to d.runtimeKind.name, "name" to d.name, "assemblyName" to d.assemblyName, "displayName" to d.displayName,
        "typeName" to d.typeNameObject.fullName, "typeNamespace" to d.typeNamespace, "typeNameIdentifier" to d.typeNameIdentifier,
        "documentation" to d.documentation, "tagOutputHint" to d.tagOutputHint, "caseSensitive" to d.caseSensitive,
        "isFullyQualifiedNameMatch" to d.isFullyQualifiedNameMatch, "classifyAttributesOnly" to d.classifyAttributesOnly,
        "tagMatchingRules" to d.tagMatchingRules.map(::ruleJson), "boundAttributes" to d.boundAttributes.map(::attributeJson),
        "allowedChildTags" to d.allowedChildTags.map { linkedMapOf("name" to it.name, "displayName" to it.displayName, "diagnostics" to diagnosticsJson(it.diagnostics)) },
        "metadata" to metadataJson(d.metadata), "diagnostics" to diagnosticsJson(d.diagnostics),
    )

    private fun ruleJson(r: TagMatchingRuleDescriptor): Map<String, Any?> = linkedMapOf(
        "tagName" to r.tagName, "parentTag" to r.parentTag, "tagStructure" to r.tagStructure.name, "caseSensitive" to r.caseSensitive,
        "attributes" to r.attributes.map {
            linkedMapOf(
                "name" to it.name, "nameComparison" to it.nameComparison.name, "value" to it.value, "valueComparison" to it.valueComparison.name,
                "caseSensitive" to it.caseSensitive, "isDirectiveAttribute" to it.isDirectiveAttribute, "diagnostics" to diagnosticsJson(it.diagnostics),
            )
        },
        "diagnostics" to diagnosticsJson(r.diagnostics),
    )

    private fun attributeJson(a: BoundAttributeDescriptor): Map<String, Any?> = linkedMapOf(
        "name" to a.name, "propertyName" to a.propertyName, "typeName" to a.typeNameObject.fullName, "indexerNamePrefix" to a.indexerNamePrefix,
        "indexerTypeName" to a.indexerTypeName, "documentation" to a.documentation, "displayName" to a.displayName, "containingType" to a.containingType,
        "caseSensitive" to a.caseSensitive, "hasIndexer" to a.hasIndexer, "isEnum" to a.isEnum, "isEditorRequired" to a.isEditorRequired,
        "isDirectiveAttribute" to a.isDirectiveAttribute, "isWeaklyTyped" to a.isWeaklyTyped,
        "parameters" to a.parameters.map {
            linkedMapOf(
                "name" to it.name, "propertyName" to it.propertyName, "typeName" to it.typeNameObject.fullName, "documentation" to it.documentation,
                "caseSensitive" to it.caseSensitive, "isEnum" to it.isEnum, "bindAttributeGetSet" to it.bindAttributeGetSet, "diagnostics" to diagnosticsJson(it.diagnostics),
            )
        },
        "metadata" to metadataJson(a.metadata), "diagnostics" to diagnosticsJson(a.diagnostics),
    )

    private fun metadataJson(m: MetadataObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("kind" to m.kind.name)
        when (m) {
            is TypeParameterMetadata -> result += listOf("isCascading" to m.isCascading, "constraints" to m.constraints, "nameWithAttributes" to m.nameWithAttributes)
            is PropertyMetadata -> result += listOf(
                "globallyQualifiedTypeName" to m.globallyQualifiedTypeName, "isChildContent" to m.isChildContent, "isEventCallback" to m.isEventCallback,
                "isDelegateSignature" to m.isDelegateSignature, "isDelegateWithAwaitableResult" to m.isDelegateWithAwaitableResult, "isGenericTyped" to m.isGenericTyped,
                "isInitOnlyProperty" to m.isInitOnlyProperty, "acceptsStringLiteral" to m.acceptsStringLiteral, "acceptsAssetPath" to m.acceptsAssetPath,
            )
            is BindMetadata -> result += listOf(
                "isFallback" to m.isFallback, "valueAttribute" to m.valueAttribute, "changeAttribute" to m.changeAttribute, "expressionAttribute" to m.expressionAttribute,
                "typeAttribute" to m.typeAttribute, "isInvariantCulture" to m.isInvariantCulture, "format" to m.format,
            )
            is ComponentMetadata -> result += listOf("isGeneric" to m.isGeneric, "hasRenderModeDirective" to m.hasRenderModeDirective, "acceptsUnmatchedAttributes" to m.acceptsUnmatchedAttributes)
            is EventHandlerMetadata -> result["eventArgsType"] = m.eventArgsType
            is ViewComponentMetadata -> result += listOf(
                "name" to m.name, "originalTypeName" to m.originalTypeNameObject.fullName, "originalTypeNamespace" to m.originalTypeNameObject.namespace,
                "originalTypeNameIdentifier" to m.originalTypeNameObject.name,
            )
            is AssetPathMetadata -> result += listOf("element" to m.element, "attribute" to m.attribute)
        }
        return result
    }

    private fun diagnosticsJson(diagnostics: List<RazorDiagnostic>): List<Any?> = diagnostics.map { d ->
        val s = d.span
        linkedMapOf(
            "id" to d.id, "severity" to d.severity.name.lowercase().replaceFirstChar { it.uppercaseChar() }, "warningLevel" to d.warningLevel, "message" to d.message,
            "span" to linkedMapOf(
                "filePath" to s.filePath, "absoluteIndex" to s.absoluteIndex, "lineIndex" to s.lineIndex, "characterIndex" to s.characterIndex,
                "length" to s.length, "lineCount" to s.lineCount, "endCharacterIndex" to s.endCharacterIndex,
            ),
        )
    }

    // ---- the JSON itself: razordump's printer, a small parser (objects as LinkedHashMap, numbers as Long)

    private fun writeValue(output: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> output.append("null")
            is Boolean, is Int, is Long -> output.append(value.toString())
            is String -> writeString(output, value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    output.append("{}")
                    return
                }
                output.append("{\n")
                var first = true
                for ((key, item) in value) {
                    if (!first) output.append(",\n")
                    first = false
                    repeat(indent + 2) { output.append(' ') }
                    writeString(output, key as String)
                    output.append(": ")
                    writeValue(output, item, indent + 2)
                }
                output.append('\n')
                repeat(indent) { output.append(' ') }
                output.append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    output.append("[]")
                    return
                }
                output.append("[\n")
                value.forEachIndexed { k, item ->
                    if (k > 0) output.append(",\n")
                    repeat(indent + 2) { output.append(' ') }
                    writeValue(output, item, indent + 2)
                }
                output.append('\n')
                repeat(indent) { output.append(' ') }
                output.append(']')
            }
            else -> throw IllegalArgumentException("JSON value of type ${value.javaClass}")
        }
    }

    private fun writeString(output: StringBuilder, s: String) {
        output.append('"')
        for (c in s) {
            when (c) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                else -> if (c < ' ') output.append("\\u").append(String.format("%04x", c.code)) else output.append(c)
            }
        }
        output.append('"')
    }

    internal class Parser(private val text: String) {
        private var i = 0

        fun parseDocument(): Any? {
            if (text.startsWith('﻿')) i = 1
            val value = parseValue()
            skipWhitespace()
            if (i != text.length) fail("trailing text")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (i >= text.length) fail("unexpected end")
            return when (val c = text[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("unexpected '$c'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (peek() == '}') return result.also { i++ }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("a key expected")
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (peek() == ',') i++ else return result.also { expect('}') }
            }
        }

        private fun parseArray(): List<Any?> {
            val result = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (peek() == ']') return result.also { i++ }
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (peek() == ',') i++ else return result.also { expect(']') }
            }
        }

        private fun parseString(): String {
            i++
            val builder = StringBuilder()
            while (true) {
                if (i >= text.length) fail("unterminated string")
                val c = text[i++]
                when (c) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        val e = text[i++]
                        when (e) {
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'u' -> builder.append(text.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                            else -> builder.append(e)
                        }
                    }
                    else -> builder.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < text.length && (text[i].isDigit() || text[i] in ".eE+-")) i++
            val token = text.substring(start, i)
            return token.toLongOrNull() ?: token.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, i)) fail("unexpected token")
            i += word.length
            return value
        }

        private fun skipWhitespace() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        private fun peek(): Char? = text.getOrNull(i)

        private fun expect(c: Char) {
            if (peek() != c) fail("'$c' expected")
            i++
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("tag helper JSON at $i: $message")
    }
}
