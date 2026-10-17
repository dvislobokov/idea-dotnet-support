package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.lang.syntax.AllowedChildTagDescriptor
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeFlags
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeParameterDescriptor
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeParameterFlags
import io.github.dotnetsupport.razor.lang.syntax.MetadataObject
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticSeverity
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeDescriptorFlags
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeNameComparison
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeValueComparison
import io.github.dotnetsupport.razor.lang.syntax.RuntimeKind
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperFlags
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions
import io.github.dotnetsupport.razor.lang.syntax.TagMatchingRuleDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagStructure
import io.github.dotnetsupport.razor.lang.syntax.TypeNameObject

/*
 * The builders of the tag helper descriptors (`TagHelperDescriptorBuilder` and the ones under it): what the producers fill, with the
 * checks of the names a builder makes on `Build()` (RZ3000–RZ3017) and its default display names. A list of builders builds into the
 * distinct objects in order (`TagHelperObjectBuilderCollection.ToImmutable`).
 */

/** The objects of [builders] without repeats (`ToImmutable` puts them through a set: equal descriptors once). */
private fun <B, T : Any> buildDistinct(builders: List<B>, build: (B) -> T, key: (T) -> String): List<T> {
    if (builders.isEmpty()) return emptyList()
    if (builders.size == 1) return listOf(build(builders[0]))
    val seen = HashSet<String>()
    return builders.map(build).filter { seen.add(key(it)) }
}

private fun diagnosticsKey(diagnostics: List<RazorDiagnostic>): String = diagnostics.joinToString("|") { it.id + ":" + it.message }

private fun requiredAttributeKey(a: RequiredAttributeDescriptor) = "${a.flags}/${a.name}/${a.nameComparison}/${a.value}/${a.valueComparison}/${diagnosticsKey(a.diagnostics)}"
private fun ruleKey(r: TagMatchingRuleDescriptor) = "${r.tagName}/${r.parentTag}/${r.tagStructure}/${r.caseSensitive}/${r.attributes.joinToString(";", transform = ::requiredAttributeKey)}/${diagnosticsKey(r.diagnostics)}"
private fun parameterKey(p: BoundAttributeParameterDescriptor) = "${p.flags}/${p.name}/${p.propertyName}/${p.typeNameObject}/${p.documentation}/${diagnosticsKey(p.diagnostics)}"
private fun attributeKey(a: BoundAttributeDescriptor) = "${a.flags}/${a.name}/${a.propertyName}/${a.typeNameObject}/${a.indexerNamePrefix}/${a.indexerTypeNameObject}/${a.documentation}/" +
    "${a.displayName}/${a.containingType}/${a.parameters.joinToString(";", transform = ::parameterKey)}/${a.metadata}/${diagnosticsKey(a.diagnostics)}"

/** The identity of a descriptor (`TagHelperDescriptor.Equals`: all of it): the discovery keeps the first of equal ones. */
fun tagHelperKey(d: TagHelperDescriptor): String = "${d.flags}/${d.kind}/${d.runtimeKind}/${d.name}/${d.assemblyName}/${d.displayName}/${d.typeNameObject}/${d.typeNamespace}/" +
    "${d.typeNameIdentifier}/${d.documentation}/${d.tagOutputHint}/${d.tagMatchingRules.joinToString(";", transform = ::ruleKey)}/" +
    "${d.boundAttributes.joinToString(";", transform = ::attributeKey)}/${d.allowedChildTags.joinToString(";") { it.name + "=" + it.displayName }}/${d.metadata}/" +
    diagnosticsKey(d.diagnostics)

class TagHelperDescriptorBuilder(val kind: TagHelperKind, val name: String, val assemblyName: String) {
    var runtimeKind: RuntimeKind = RuntimeKind.None
    var displayName: String? = null
    var tagOutputHint: String? = null
    var typeNameObject: TypeNameObject = TypeNameObject.NULL
    var caseSensitive = false
    var isFullyQualifiedNameMatch = false
    var classifyAttributesOnly = false
    var documentation: String? = null
    var metadata: MetadataObject = MetadataObject.NONE
    val diagnostics = ArrayList<RazorDiagnostic>()
    val boundAttributes = ArrayList<BoundAttributeDescriptorBuilder>()
    val tagMatchingRules = ArrayList<TagMatchingRuleDescriptorBuilder>()
    val allowedChildTags = ArrayList<AllowedChildTagDescriptorBuilder>()

    val typeName: String? get() = typeNameObject.fullName

    fun setTypeName(fullName: String?) {
        typeNameObject = TypeNameObject.from(fullName)
    }

    fun setTypeName(fullName: String, typeNamespace: String?, typeNameIdentifier: String?) {
        typeNameObject = TypeNameObject.from(fullName, typeNamespace, typeNameIdentifier)
    }

    fun setDocumentation(documentation: DocumentationDescriptor) {
        this.documentation = documentation.text
    }

    fun bindAttribute(configure: BoundAttributeDescriptorBuilder.() -> Unit) {
        boundAttributes += BoundAttributeDescriptorBuilder(this).apply(configure)
    }

    fun tagMatchingRule(configure: TagMatchingRuleDescriptorBuilder.() -> Unit) {
        tagMatchingRules += TagMatchingRuleDescriptorBuilder(this).apply(configure)
    }

    fun allowChildTag(configure: AllowedChildTagDescriptorBuilder.() -> Unit) {
        allowedChildTags += AllowedChildTagDescriptorBuilder(this).apply(configure)
    }

    fun resolvedDisplayName(): String = displayName ?: typeName ?: name

    fun build(): TagHelperDescriptor {
        var flags = 0
        if (caseSensitive) flags = flags or TagHelperFlags.CASE_SENSITIVE
        if (isFullyQualifiedNameMatch) flags = flags or TagHelperFlags.IS_FULLY_QUALIFIED_NAME_MATCH
        if (classifyAttributesOnly) flags = flags or TagHelperFlags.CLASSIFY_ATTRIBUTES_ONLY
        return TagHelperDescriptor(
            flags, kind, runtimeKind, name, assemblyName, resolvedDisplayName(), typeNameObject, documentation, tagOutputHint,
            buildDistinct(tagMatchingRules, { it.build() }, ::ruleKey), buildDistinct(boundAttributes, { it.build() }, ::attributeKey),
            buildDistinct(allowedChildTags, { it.build() }, { it.name + "=" + it.displayName + diagnosticsKey(it.diagnostics) }),
            metadata, distinct(diagnostics),
        )
    }
}

private fun distinct(diagnostics: List<RazorDiagnostic>): List<RazorDiagnostic> {
    val seen = HashSet<String>()
    return diagnostics.filter { seen.add(it.id + "\u0001" + it.message) }
}

class BoundAttributeDescriptorBuilder(private val parent: TagHelperDescriptorBuilder) {
    var name: String? = null
    var propertyName: String? = null
    var typeNameObject: TypeNameObject = TypeNameObject.NULL
    var isEnum = false
    var isDictionary = false
    var indexerAttributeNamePrefix: String? = null
    var indexerTypeNameObject: TypeNameObject = TypeNameObject.NULL
    var isEditorRequired = false
    var isDirectiveAttribute = false
    var isWeaklyTyped = false
    var documentation: String? = null
    var displayName: String? = null
    var containingType: String? = null
    var metadata: MetadataObject? = null
    private var caseSensitiveValue: Boolean? = null
    val diagnostics = ArrayList<RazorDiagnostic>()
    private val parameters = ArrayList<BoundAttributeParameterDescriptorBuilder>()

    var typeName: String?
        get() = typeNameObject.fullName
        set(value) {
            typeNameObject = TypeNameObject.from(value)
        }

    /** Set or the descriptor's (`CaseSensitive` of the builder falls back to its parent's). */
    var caseSensitive: Boolean
        get() = caseSensitiveValue ?: parent.caseSensitive
        set(value) {
            caseSensitiveValue = value
        }

    fun setDocumentation(documentation: DocumentationDescriptor) {
        this.documentation = documentation.text
    }

    /** `AsDictionary`: the attribute also takes `prefix<key>` attributes of [valueTypeName]. */
    fun asDictionary(attributeNamePrefix: String?, valueTypeName: String?) {
        isDictionary = true
        indexerAttributeNamePrefix = attributeNamePrefix
        indexerTypeNameObject = TypeNameObject.from(valueTypeName)
    }

    fun bindAttributeParameter(configure: BoundAttributeParameterDescriptorBuilder.() -> Unit) {
        parameters += BoundAttributeParameterDescriptorBuilder(this).apply(configure)
    }

    private fun resolvedDisplayName(): String {
        displayName?.let { return it }
        val parentTypeName = parent.typeName
        val typeName = typeName
        val propertyName = propertyName
        if (typeName != null && propertyName != null && parentTypeName != null) {
            return "${PRIMITIVE_DISPLAY_NAMES[typeName] ?: typeName} $parentTypeName.$propertyName"
        }
        return name ?: ""
    }

    fun build(): BoundAttributeDescriptor {
        var flags = 0
        // the flag only when set (BuildCore passes the flags; the getter's fallback to the parent serves the parameters)
        if (caseSensitiveValue == true) flags = flags or BoundAttributeFlags.CASE_SENSITIVE
        if (isDictionary) flags = flags or BoundAttributeFlags.HAS_INDEXER
        if (isEnum) flags = flags or BoundAttributeFlags.IS_ENUM
        if (isEditorRequired) flags = flags or BoundAttributeFlags.IS_EDITOR_REQUIRED
        if (isDirectiveAttribute) flags = flags or BoundAttributeFlags.IS_DIRECTIVE_ATTRIBUTE
        if (isWeaklyTyped) flags = flags or BoundAttributeFlags.IS_WEAKLY_TYPED
        return BoundAttributeDescriptor(
            flags, name ?: "", propertyName ?: "", typeNameObject, indexerAttributeNamePrefix, indexerTypeNameObject, documentation, resolvedDisplayName(),
            containingType, buildDistinct(parameters, { it.build() }, ::parameterKey), metadata ?: MetadataObject.NONE, distinct(collectDiagnostics() + diagnostics),
        )
    }

    private fun collectDiagnostics(): List<RazorDiagnostic> {
        val result = ArrayList<RazorDiagnostic>()
        val parentName = parent.resolvedDisplayName()
        val name = name
        if (name.isNullOrBlank()) {
            if (indexerAttributeNamePrefix == null) result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeNullOrWhitespace(parentName, resolvedDisplayName())
        } else {
            if (name.startsWith(DATA_DASH, ignoreCase = true)) result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeNameStartsWith(parentName, resolvedDisplayName(), name)
            var checked = name
            if (isDirectiveAttribute && name[0] == '@') checked = name.substring(1)
            else if (isDirectiveAttribute) result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundDirectiveAttributeName(parentName, resolvedDisplayName(), name)
            for (ch in checked) {
                if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) {
                    result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeName(parentName, resolvedDisplayName(), checked, ch)
                }
            }
        }
        val prefix = indexerAttributeNamePrefix
        if (prefix != null) {
            if (prefix.startsWith(DATA_DASH, ignoreCase = true)) {
                result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributePrefixStartsWith(parentName, resolvedDisplayName(), prefix)
            } else if (prefix.isNotEmpty() && prefix.isBlank()) {
                result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeNullOrWhitespace(parentName, resolvedDisplayName())
            } else {
                var checked = prefix
                if (isDirectiveAttribute && prefix.isNotEmpty() && prefix[0] == '@') checked = prefix.substring(1)
                else if (isDirectiveAttribute) result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundDirectiveAttributePrefix(parentName, resolvedDisplayName(), checked)
                for (ch in checked) {
                    if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) {
                        result += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributePrefix(parentName, resolvedDisplayName(), checked, ch)
                    }
                }
            }
        }
        return result
    }

    companion object {
        private const val DATA_DASH = "data-"

        val PRIMITIVE_DISPLAY_NAMES = mapOf(
            "System.Byte" to "byte", "System.SByte" to "sbyte", "System.Int32" to "int", "System.UInt32" to "uint", "System.Int16" to "short",
            "System.UInt16" to "ushort", "System.Int64" to "long", "System.UInt64" to "ulong", "System.Single" to "float", "System.Double" to "double",
            "System.Char" to "char", "System.Boolean" to "bool", "System.Object" to "object", "System.String" to "string", "System.Decimal" to "decimal",
        )
    }
}

class BoundAttributeParameterDescriptorBuilder(private val parent: BoundAttributeDescriptorBuilder) {
    var name: String? = null
    var propertyName: String? = null
    var typeNameObject: TypeNameObject = TypeNameObject.NULL
    var isEnum = false
    var bindAttributeGetSet = false
    var documentation: String? = null
    val diagnostics = ArrayList<RazorDiagnostic>()

    var typeName: String?
        get() = typeNameObject.fullName
        set(value) {
            typeNameObject = TypeNameObject.from(value)
        }

    fun setDocumentation(documentation: DocumentationDescriptor) {
        this.documentation = documentation.text
    }

    fun build(): BoundAttributeParameterDescriptor {
        var flags = 0
        if (isEnum) flags = flags or BoundAttributeParameterFlags.IS_ENUM
        if (bindAttributeGetSet) flags = flags or BoundAttributeParameterFlags.BIND_ATTRIBUTE_GET_SET
        if (parent.caseSensitive) flags = flags or BoundAttributeParameterFlags.CASE_SENSITIVE
        val collected = ArrayList<RazorDiagnostic>()
        val name = name
        if (name.isNullOrBlank()) {
            collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeParameterNullOrWhitespace(parent.name ?: "")
        } else {
            for (ch in name) {
                if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) {
                    collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidBoundAttributeParameterName(parent.name ?: "", name, ch)
                }
            }
        }
        return BoundAttributeParameterDescriptor(flags, name ?: "", propertyName ?: "", typeNameObject, documentation, distinct(collected + diagnostics))
    }
}

class TagMatchingRuleDescriptorBuilder(private val parent: TagHelperDescriptorBuilder) {
    var tagName: String? = null
    var parentTag: String? = null
    var tagStructure: TagStructure = TagStructure.Unspecified
    val diagnostics = ArrayList<RazorDiagnostic>()
    private val attributes = ArrayList<RequiredAttributeDescriptorBuilder>()

    val caseSensitive: Boolean get() = parent.caseSensitive

    fun attribute(configure: RequiredAttributeDescriptorBuilder.() -> Unit) {
        attributes += RequiredAttributeDescriptorBuilder(this).apply(configure)
    }

    fun build(): TagMatchingRuleDescriptor {
        val collected = ArrayList<RazorDiagnostic>()
        val tagName = tagName
        if (tagName.isNullOrBlank()) {
            collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedTagNameNullOrWhitespace()
        } else if (tagName != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME) {
            for (ch in tagName) {
                if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedTagName(tagName, ch)
            }
        }
        val parentTag = parentTag
        if (parentTag != null) {
            if (parentTag.isBlank()) {
                collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedParentTagNameNullOrWhitespace()
            } else {
                for (ch in parentTag) {
                    if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) {
                        collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedParentTagName(parentTag, ch)
                    }
                }
            }
        }
        return TagMatchingRuleDescriptor(tagName ?: "", parentTag, tagStructure, caseSensitive, buildDistinct(attributes, { it.build() }, ::requiredAttributeKey), distinct(collected + diagnostics))
    }
}

class RequiredAttributeDescriptorBuilder(private val parent: TagMatchingRuleDescriptorBuilder) {
    var name: String? = null
    var nameComparison: RequiredAttributeNameComparison = RequiredAttributeNameComparison.FullMatch
    var value: String? = null
    var valueComparison: RequiredAttributeValueComparison = RequiredAttributeValueComparison.None
    var isDirectiveAttribute = false
    val diagnostics = ArrayList<RazorDiagnostic>()

    fun build(): RequiredAttributeDescriptor {
        var flags = RequiredAttributeDescriptorFlags.NONE
        if (isDirectiveAttribute) flags = flags or RequiredAttributeDescriptorFlags.IS_DIRECTIVE_ATTRIBUTE
        if (parent.caseSensitive) flags = flags or RequiredAttributeDescriptorFlags.CASE_SENSITIVE
        val collected = ArrayList<RazorDiagnostic>()
        val name = name
        if (name.isNullOrBlank()) {
            collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedAttributeNameNullOrWhitespace()
        } else {
            var checked = name
            if (isDirectiveAttribute) {
                if (name[0] == '@') checked = name.substring(1)
                else {
                    val displayName = if (nameComparison == RequiredAttributeNameComparison.PrefixMatch) "$name..." else name
                    collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidRequiredDirectiveAttributeName(displayName, name)
                }
            }
            for (ch in checked) {
                if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidTargetedAttributeName(name, ch)
            }
        }
        return RequiredAttributeDescriptor(flags, name ?: "", nameComparison, value, valueComparison, distinct(collected + diagnostics))
    }
}

class AllowedChildTagDescriptorBuilder(private val parent: TagHelperDescriptorBuilder) {
    var name: String? = null
    var displayName: String? = null

    fun build(): AllowedChildTagDescriptor {
        val collected = ArrayList<RazorDiagnostic>()
        val name = name
        if (name.isNullOrBlank()) {
            collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidRestrictedChildNullOrWhitespace(parent.resolvedDisplayName())
        } else if (name != TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME) {
            for (ch in name) {
                if (ch.isWhitespace() || HtmlConventions.isInvalidNonWhitespaceHtmlCharacter(ch)) {
                    collected += TagHelperDescriptorDiagnostics.tagHelper_InvalidRestrictedChild(parent.resolvedDisplayName(), name, ch)
                }
            }
        }
        return AllowedChildTagDescriptor(name ?: "", displayName ?: name ?: "", collected)
    }
}

/** `DocumentationDescriptor`: the documentation the producers give their synthesized descriptors (`ComponentResources.resx`). */
class DocumentationDescriptor private constructor(val id: DocumentationId, private val args: List<Any?>) {
    val text: String get() = id.format.replace(Regex("\\{(\\d+)}")) { m -> args.getOrNull(m.groupValues[1].toInt())?.toString() ?: "" }

    companion object {
        fun from(id: DocumentationId, vararg args: Any?) = DocumentationDescriptor(id, args.toList())

        val BindTagHelper_Fallback = from(DocumentationId.BindTagHelper_Fallback)
        val BindTagHelper_Fallback_Format = from(DocumentationId.BindTagHelper_Fallback_Format)
        val BindTagHelper_Element_After = from(DocumentationId.BindTagHelper_Element_After)
        val BindTagHelper_Element_Culture = from(DocumentationId.BindTagHelper_Element_Culture)
        val BindTagHelper_Element_Get = from(DocumentationId.BindTagHelper_Element_Get)
        val BindTagHelper_Element_Set = from(DocumentationId.BindTagHelper_Element_Set)
        val ChildContentParameterName_TopLevel = from(DocumentationId.ChildContentParameterName_TopLevel)
        val KeyTagHelper = from(DocumentationId.KeyTagHelper)
        val RefTagHelper = from(DocumentationId.RefTagHelper)
        val SplatTagHelper = from(DocumentationId.SplatTagHelper)
        val RenderModeTagHelper = from(DocumentationId.RenderModeTagHelper)
        val FormNameTagHelper = from(DocumentationId.FormNameTagHelper)
    }
}

enum class DocumentationId(val format: String) {
    BindTagHelper_Fallback(
        "Binds the provided expression to an attribute and a change event, based on the naming of the bind attribute. For example: <code>@bind-value=\"...\"</code> " +
            "and <code>@bind-value:event=\"onchange\"</code> will assign the current value of the expression to the 'value' attribute, and assign a delegate that " +
            "attempts to set the value to the 'onchange' attribute.",
    ),
    BindTagHelper_Fallback_Event("Specifies the event handler name to attach for change notifications for the value provided by the '{0}' attribute."),
    BindTagHelper_Fallback_Format(
        "Specifies a format to convert the value specified by the corresponding bind attribute. For example: <code>@bind-value:format=\"...\"</code> will apply a " +
            "format string to the value specified in <code>@bind-value=\"...\"</code>. The format string can currently only be used with expressions of type <code>DateTime</code>.",
    ),
    BindTagHelper_Element("Binds the provided expression to the '{0}' attribute and a change event delegate to the '{1}' attribute."),
    BindTagHelper_Element_After("Specifies an action to run after the new value has been set."),
    BindTagHelper_Element_Culture("Specifies the culture to use for conversions."),
    BindTagHelper_Element_Event("Specifies the event handler name to attach for change notifications for the value provided by the '{0}' attribute."),
    BindTagHelper_Element_Format(
        "Specifies a format to convert the value specified by the '{0}' attribute. The format string can currently only be used with expressions of type <code>DateTime</code>.",
    ),
    BindTagHelper_Element_Get("Specifies the expression to use for binding the value to the attribute."),
    BindTagHelper_Element_Set("Specifies the expression to use for updating the bound value when a new value is available."),
    BindTagHelper_Component("Binds the provided expression to the '{0}' property and a change event delegate to the '{1}' property of the component."),
    ChildContentParameterName("Specifies the parameter name for the '{0}' child content expression."),
    ChildContentParameterName_TopLevel("Specifies the parameter name for all child content expressions."),
    ComponentTypeParameter("Specifies the type of the type parameter {0} for the {1} component."),
    EventHandlerTagHelper("Sets the '{0}' attribute to the provided string or delegate value. A delegate value should be of type '{1}'."),
    EventHandlerTagHelper_PreventDefault("Specifies whether to cancel (if cancelable) the default action that belongs to the '{0}' event."),
    EventHandlerTagHelper_StopPropagation("Specifies whether to prevent further propagation of the '{0}' event in the capturing and bubbling phases."),
    KeyTagHelper("Ensures that the component or element will be preserved across renders if (and only if) the supplied key value matches."),
    RefTagHelper("Populates the specified field or property with a reference to the element or component."),
    SplatTagHelper("Merges a collection of attributes into the current element or component."),
    RenderModeTagHelper("Specifies the render mode for a component."),
    FormNameTagHelper("Name used to match the form handler on the server."),
}

/** `HtmlConventions`. */
object HtmlConventions {
    private val INVALID_NON_WHITESPACE_HTML_CHARACTERS = charArrayOf('@', '!', '<', '/', '?', '[', '>', ']', '=', '"', '\'', '*')

    fun isInvalidNonWhitespaceHtmlCharacter(c: Char): Boolean = c in INVALID_NON_WHITESPACE_HTML_CHARACTERS

    /** `ToHtmlCase`: `PascalCase` → `pascal-case` (`HTMLElement` → `html-element`); an all-lower-case name as it is. */
    fun toHtmlCase(input: String): String {
        if (input.isEmpty()) return input
        val builder = StringBuilder()
        var allLower = true
        for ((i, c) in input.withIndex()) {
            if (c.isUpperCase()) {
                allLower = false
                if (shouldInsertHyphen(input, i)) builder.append('-')
                builder.append(c.lowercaseChar())
            } else {
                builder.append(c)
            }
        }
        return if (allLower) input else builder.toString()
    }

    private fun shouldInsertHyphen(input: String, i: Int): Boolean {
        if (i == 0) return false
        val previous = input[i - 1]
        if (previous.isLowerCase()) return true
        return (previous.isUpperCase() || previous.isDigit()) && i + 1 < input.length && input[i + 1].isLowerCase()
    }
}

/** `RequiredAttributeParser`: the `Attributes` of `[HtmlTargetElement]` — `a, b*, [c], [d=v], [e^='v'], [f$="v"]` — into the rule's required attributes. */
object RequiredAttributeParser {
    private const val WILDCARD = '*'
    private val CSS_VALUE_COMPARISONS = mapOf(
        '=' to RequiredAttributeValueComparison.FullMatch, '^' to RequiredAttributeValueComparison.PrefixMatch, '$' to RequiredAttributeValueComparison.SuffixMatch,
    )
    private val WHITESPACE = charArrayOf(' ', '\t')
    private val INVALID_PLAIN_NAME = WHITESPACE + charArrayOf(',', WILDCARD)
    private val INVALID_CSS_NAME = WHITESPACE + charArrayOf(',', ']') + CSS_VALUE_COMPARISONS.keys.toCharArray()
    private val INVALID_CSS_QUOTELESS_VALUE = WHITESPACE + charArrayOf(']')

    fun addRequiredAttributes(input: String, ruleBuilder: TagMatchingRuleDescriptorBuilder) {
        val parser = Parser(input)
        while (true) {
            val result = parser.tryParseNext()
            if (result.success || result.diagnostics.isNotEmpty()) {
                ruleBuilder.attribute {
                    name = result.name
                    nameComparison = result.nameComparison
                    value = result.value
                    valueComparison = result.valueComparison
                    diagnostics += result.diagnostics
                }
            }
            if (!result.success) break
        }
    }

    private class Result(
        val success: Boolean, val name: String?, val nameComparison: RequiredAttributeNameComparison, val value: String?,
        val valueComparison: RequiredAttributeValueComparison, val diagnostics: List<RazorDiagnostic>,
    )

    private class Parser(private val input: String) {
        private var at = 0
        private val diagnostics = ArrayList<RazorDiagnostic>()
        private val atEnd: Boolean get() = at >= input.length
        private val current: Char get() = input[at]
        private fun at(c: Char) = !atEnd && current == c

        private fun skipWhitespace() {
            while (!atEnd && (current == ' ' || current == '\t')) at++
        }

        private fun ensureNotAtEnd(): Boolean {
            if (!atEnd) return true
            diagnostics += TagHelperDescriptorDiagnostics.tagHelper_CouldNotFindMatchingEndBrace(input)
            return false
        }

        private fun indexOfAny(chars: CharArray): Int {
            for (i in at until input.length) if (input[i] in chars) return i
            return -1
        }

        fun tryParseNext(): Result {
            if (atEnd) return Result(false, null, RequiredAttributeNameComparison.FullMatch, null, RequiredAttributeValueComparison.None, emptyList())
            skipWhitespace()
            var name: String? = null
            var value: String? = null
            var nameComparison = RequiredAttributeNameComparison.FullMatch
            var valueComparison = RequiredAttributeValueComparison.None
            diagnostics.clear()
            fun failed() = Result(false, name, nameComparison, value, valueComparison, diagnostics.toList())
            if (at('[')) {
                at++
                skipWhitespace()
                val end = indexOfAny(INVALID_CSS_NAME).let { if (it == -1) input.length else it }
                name = input.substring(at, end)
                at = end
                skipWhitespace()
                if (!ensureNotAtEnd()) return failed()
                val ch = current
                val comparison = CSS_VALUE_COMPARISONS[ch]
                if (comparison != null) {
                    at++
                    if (ch != '=') {
                        if (at('=')) at++
                        else {
                            diagnostics += TagHelperDescriptorDiagnostics.tagHelper_PartialRequiredAttributeOperator(ch, input)
                            return failed()
                        }
                    }
                    valueComparison = comparison
                } else if (!at(']')) {
                    diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidRequiredAttributeOperator(ch, input)
                    return failed()
                }
                skipWhitespace()
                if (!ensureNotAtEnd()) return failed()
                if (valueComparison != RequiredAttributeValueComparison.None) {
                    if (at('\'') || at('"')) {
                        val quote = current
                        at++
                        val next = input.indexOf(quote, at)
                        if (next == -1) {
                            diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidRequiredAttributeMismatchedQuotes(quote, input)
                            return failed()
                        }
                        value = input.substring(at, next)
                        at = next + 1
                    } else {
                        val end2 = indexOfAny(INVALID_CSS_QUOTELESS_VALUE).let { if (it == -1) input.length else it }
                        value = input.substring(at, end2)
                        at = end2
                    }
                }
                skipWhitespace()
                if (at(']')) {
                    at++
                } else {
                    diagnostics += if (atEnd) TagHelperDescriptorDiagnostics.tagHelper_CouldNotFindMatchingEndBrace(input)
                    else TagHelperDescriptorDiagnostics.tagHelper_InvalidRequiredAttributeCharacter(current, input)
                    return failed()
                }
            } else {
                val end = indexOfAny(INVALID_PLAIN_NAME)
                if (end == -1) {
                    name = input.substring(at)
                    at = input.length
                } else {
                    name = input.substring(at, end)
                    at = end
                    if (current == WILDCARD) {
                        nameComparison = RequiredAttributeNameComparison.PrefixMatch
                        at++
                    }
                }
            }
            skipWhitespace()
            if (at(',')) {
                at++
                if (!ensureNotAtEnd()) return failed()
            } else if (!atEnd) {
                diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidRequiredAttributeCharacter(current, input)
                return failed()
            }
            return Result(true, name, nameComparison, value, valueComparison, emptyList())
        }
    }
}

/** The diagnostics of the descriptor builders (`RazorDiagnosticFactory.CreateTagHelper_*` of Razor.Language, RZ3000+, and of the C# part, RZ3500+). */
@Suppress("FunctionName")
object TagHelperDescriptorDiagnostics {
    private fun error(id: Int, message: String) = RazorDiagnosticDescriptor("RZ$id", message, RazorDiagnosticSeverity.ERROR)
    private fun create(descriptor: RazorDiagnosticDescriptor, vararg args: Any?) = RazorDiagnostic(descriptor, SourceSpan.UNDEFINED, args.toList())

    val TagHelper_InvalidRestrictedChildNullOrWhitespace = error(3000, "Invalid restricted child for tag helper '{0}'. Name cannot be null or whitespace.")
    val TagHelper_InvalidRestrictedChild = error(3001, "Invalid restricted child '{1}' for tag helper '{0}'. Tag helpers cannot restrict child elements that contain a '{2}' character.")
    val TagHelper_InvalidBoundAttributeNullOrWhitespace =
        error(3002, "Invalid tag helper bound property '{1}' on tag helper '{0}'. Tag helpers cannot bind to HTML attributes with a null or empty name.")
    val TagHelper_InvalidBoundAttributeName = error(
        3003, "Invalid tag helper bound property '{1}' on tag helper '{0}'. Tag helpers cannot bind to HTML attributes with name '{2}' because the name contains a '{3}' character.",
    )
    val TagHelper_InvalidBoundAttributeNameStartsWith =
        error(3004, "Invalid tag helper bound property '{1}' on tag helper '{0}'. Tag helpers cannot bind to HTML attributes with name '{2}' because the name starts with '{3}'.")
    val TagHelper_InvalidBoundAttributePrefix = error(
        3005,
        "Invalid tag helper bound property '{1}' on tag helper '{0}'. Tag helpers cannot bind to HTML attributes with prefix '{2}' because the prefix contains a '{3}' character.",
    )
    val TagHelper_InvalidBoundAttributePrefixStartsWith =
        error(3006, "Invalid tag helper bound property '{1}' on tag helper '{0}'. Tag helpers cannot bind to HTML attributes with prefix '{2}' because the prefix starts with '{3}'.")
    val TagHelper_InvalidTargetedTagNameNullOrWhitespace = error(3007, "Targeted tag name cannot be null or whitespace.")
    val TagHelper_InvalidTargetedTagName = error(3008, "Tag helpers cannot target tag name '{0}' because it contains a '{1}' character.")
    val TagHelper_InvalidTargetedParentTagNameNullOrWhitespace = error(3009, "Targeted parent tag name cannot be null or whitespace.")
    val TagHelper_InvalidTargetedParentTagName = error(3010, "Tag helpers cannot target parent tag name '{0}' because it contains a '{1}' character.")
    val TagHelper_InvalidTargetedAttributeNameNullOrWhitespace = error(3011, "Targeted attribute name cannot be null or whitespace.")
    val TagHelper_InvalidTargetedAttributeName = error(3012, "Tag helpers cannot target attribute name '{0}' because it contains a '{1}' character.")
    val TagHelper_InvalidBoundAttributeParameterNullOrWhitespace =
        error(3013, "Invalid tag helper bound attribute parameter '{0}'. Tag helpers cannot bind to HTML attribute parameters with a null or empty name.")
    val TagHelper_InvalidBoundAttributeParameterName = error(
        3014,
        "Invalid tag helper bound attribute parameter '{1}' on bound attribute '{0}'. Tag helpers cannot bind to HTML attribute parameters with name '{1}' because the name contains a '{3}' character.",
    )
    val TagHelper_InvalidBoundDirectiveAttributeName =
        error(3015, "Invalid tag helper bound directive attribute '{1}' on tag helper '{0}'. The directive attribute '{2}' should start with a '@' character.")
    val TagHelper_InvalidRequiredDirectiveAttributeName =
        error(3016, "Invalid tag helper required directive attribute '{0}'. The directive attribute '{1}' should start with a '@' character.")
    val TagHelper_InvalidBoundDirectiveAttributePrefix = error(
        3017,
        "Invalid tag helper bound directive attribute '{1}' on tag helper '{0}'. Tag helpers cannot bind to directive attributes with prefix '{2}' because the prefix doesn't start with a '@' character.",
    )
    val TagHelper_InvalidAttributeNameNullOrEmpty = error(3500, "Invalid tag helper bound property '{1}' on tag helper '{0}'. '{2}.{3}' must be null or empty if property has no public setter.")
    val TagHelper_InvalidAttributePrefixNotNull = error(3501, "Invalid tag helper bound property '{1}' on tag helper '{0}'. '{2}.{3}' must be null unless property type implements '{4}'.")
    val TagHelper_InvalidAttributePrefixNull =
        error(3502, "Invalid tag helper bound property '{1}' on tag helper '{0}'. '{2}.{3}' must not be null if property has no public setter and its type implements '{4}'.")
    val TagHelper_InvalidRequiredAttributeCharacter = error(3503, "Invalid required attribute character '{0}' in required attribute '{1}'. Separate required attributes with commas.")
    val TagHelper_PartialRequiredAttributeOperator = error(3504, "Required attribute '{0}' has a partial CSS operator. '{1}' must be followed by an equals.")
    val TagHelper_InvalidRequiredAttributeOperator = error(3505, "Invalid character '{0}' in required attribute '{1}'. Expected supported CSS operator or ']'.")
    val TagHelper_InvalidRequiredAttributeMismatchedQuotes = error(3506, "Required attribute '{0}' has mismatched quotes '{1}' around value.")
    val TagHelper_CouldNotFindMatchingEndBrace = error(3507, "Could not find matching ']' for required attribute '{0}'.")

    fun tagHelper_InvalidRestrictedChildNullOrWhitespace(tagHelper: String) = create(TagHelper_InvalidRestrictedChildNullOrWhitespace, tagHelper)
    fun tagHelper_InvalidRestrictedChild(tagHelper: String, child: String, ch: Char) = create(TagHelper_InvalidRestrictedChild, tagHelper, child, ch)
    fun tagHelper_InvalidBoundAttributeNullOrWhitespace(tagHelper: String, property: String) = create(TagHelper_InvalidBoundAttributeNullOrWhitespace, tagHelper, property)
    fun tagHelper_InvalidBoundAttributeName(tagHelper: String, property: String, name: String, ch: Char) = create(TagHelper_InvalidBoundAttributeName, tagHelper, property, name, ch)
    fun tagHelper_InvalidBoundAttributeNameStartsWith(tagHelper: String, property: String, name: String) =
        create(TagHelper_InvalidBoundAttributeNameStartsWith, tagHelper, property, name, "data-")
    fun tagHelper_InvalidBoundAttributePrefix(tagHelper: String, property: String, name: String, ch: Char) = create(TagHelper_InvalidBoundAttributePrefix, tagHelper, property, name, ch)
    fun tagHelper_InvalidBoundAttributePrefixStartsWith(tagHelper: String, property: String, name: String) =
        create(TagHelper_InvalidBoundAttributePrefixStartsWith, tagHelper, property, name, "data-")
    fun tagHelper_InvalidTargetedTagNameNullOrWhitespace() = create(TagHelper_InvalidTargetedTagNameNullOrWhitespace)
    fun tagHelper_InvalidTargetedTagName(name: String, ch: Char) = create(TagHelper_InvalidTargetedTagName, name, ch)
    fun tagHelper_InvalidTargetedParentTagNameNullOrWhitespace() = create(TagHelper_InvalidTargetedParentTagNameNullOrWhitespace)
    fun tagHelper_InvalidTargetedParentTagName(name: String, ch: Char) = create(TagHelper_InvalidTargetedParentTagName, name, ch)
    fun tagHelper_InvalidTargetedAttributeNameNullOrWhitespace() = create(TagHelper_InvalidTargetedAttributeNameNullOrWhitespace)
    fun tagHelper_InvalidTargetedAttributeName(name: String, ch: Char) = create(TagHelper_InvalidTargetedAttributeName, name, ch)
    fun tagHelper_InvalidBoundAttributeParameterNullOrWhitespace(attribute: String) = create(TagHelper_InvalidBoundAttributeParameterNullOrWhitespace, attribute)
    fun tagHelper_InvalidBoundAttributeParameterName(attribute: String, name: String, ch: Char) = create(TagHelper_InvalidBoundAttributeParameterName, attribute, name, ch)
    fun tagHelper_InvalidBoundDirectiveAttributeName(tagHelper: String, property: String, name: String) = create(TagHelper_InvalidBoundDirectiveAttributeName, tagHelper, property, name)
    fun tagHelper_InvalidRequiredDirectiveAttributeName(displayName: String, name: String) = create(TagHelper_InvalidRequiredDirectiveAttributeName, displayName, name)
    fun tagHelper_InvalidBoundDirectiveAttributePrefix(tagHelper: String, property: String, prefix: String) = create(TagHelper_InvalidBoundDirectiveAttributePrefix, tagHelper, property, prefix)
    fun tagHelper_InvalidAttributeNameNullOrEmpty(tagHelper: String, property: String) =
        create(TagHelper_InvalidAttributeNameNullOrEmpty, tagHelper, property, TagHelperTypes.HTML_ATTRIBUTE_NAME_ATTRIBUTE, "Name")
    fun tagHelper_InvalidAttributePrefixNotNull(tagHelper: String, property: String) =
        create(TagHelper_InvalidAttributePrefixNotNull, tagHelper, property, TagHelperTypes.HTML_ATTRIBUTE_NAME_ATTRIBUTE, "DictionaryAttributePrefix", "IDictionary<string, TValue>")
    fun tagHelper_InvalidAttributePrefixNull(tagHelper: String, property: String) =
        create(TagHelper_InvalidAttributePrefixNull, tagHelper, property, TagHelperTypes.HTML_ATTRIBUTE_NAME_ATTRIBUTE, "DictionaryAttributePrefix", "IDictionary<string, TValue>")
    fun tagHelper_InvalidRequiredAttributeCharacter(ch: Char, attributes: String) = create(TagHelper_InvalidRequiredAttributeCharacter, ch, attributes)
    fun tagHelper_PartialRequiredAttributeOperator(ch: Char, attributes: String) = create(TagHelper_PartialRequiredAttributeOperator, attributes, ch)
    fun tagHelper_InvalidRequiredAttributeOperator(ch: Char, attributes: String) = create(TagHelper_InvalidRequiredAttributeOperator, ch, attributes)
    fun tagHelper_InvalidRequiredAttributeMismatchedQuotes(quote: Char, attributes: String) = create(TagHelper_InvalidRequiredAttributeMismatchedQuotes, attributes, quote)
    fun tagHelper_CouldNotFindMatchingEndBrace(attributes: String) = create(TagHelper_CouldNotFindMatchingEndBrace, attributes)
}

/** `TagHelperTypes`: the names of MVC's tag helper API. */
object TagHelperTypes {
    const val I_TAG_HELPER = "Microsoft.AspNetCore.Razor.TagHelpers.ITagHelper"
    const val I_DICTIONARY = "System.Collections.Generic.IDictionary<TKey, TValue>"
    const val HTML_ATTRIBUTE_NAME_ATTRIBUTE = "Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeNameAttribute"
    const val HTML_ATTRIBUTE_NOT_BOUND_ATTRIBUTE = "Microsoft.AspNetCore.Razor.TagHelpers.HtmlAttributeNotBoundAttribute"
    const val HTML_TARGET_ELEMENT_ATTRIBUTE = "Microsoft.AspNetCore.Razor.TagHelpers.HtmlTargetElementAttribute"
    const val OUTPUT_ELEMENT_HINT_ATTRIBUTE = "Microsoft.AspNetCore.Razor.TagHelpers.OutputElementHintAttribute"
    const val RESTRICT_CHILDREN_ATTRIBUTE = "Microsoft.AspNetCore.Razor.TagHelpers.RestrictChildrenAttribute"
}
