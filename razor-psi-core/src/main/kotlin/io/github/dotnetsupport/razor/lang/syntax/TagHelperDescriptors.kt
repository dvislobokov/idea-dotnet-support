package io.github.dotnetsupport.razor.lang.syntax

/**
 * The tag helper descriptor model of the compiler (`TagHelperDescriptor` and what hangs off it; RAZOR_PLAN.md, R3b): what the tag helper
 * resolution phase and the component passes bind elements and attributes to. The descriptors themselves come from the project's
 * assemblies and components (R5); here the types, the matching conventions and the binder.
 */

enum class TagHelperKind { ITagHelper, ViewComponent, Component, ChildContent, EventHandler, Bind, Key, Ref, Splat, FormName, RenderMode, AssetPath }

val TagHelperKind.isAnyComponentKind: Boolean get() = ordinal >= TagHelperKind.Component.ordinal && ordinal <= TagHelperKind.AssetPath.ordinal
val TagHelperKind.isComponentOrChildContentKind: Boolean get() = this == TagHelperKind.Component || this == TagHelperKind.ChildContent

enum class RuntimeKind { None, ITagHelper, IComponent }
enum class TagStructure { Unspecified, NormalOrSelfClosing, WithoutEndTag }
enum class RequiredAttributeNameComparison { FullMatch, PrefixMatch }
enum class RequiredAttributeValueComparison { None, FullMatch, PrefixMatch, SuffixMatch }
enum class AttributeStructure { DoubleQuotes, SingleQuotes, NoQuotes, Minimized }
enum class TagMode { StartTagAndEndTag, SelfClosing, StartTagOnly }
enum class MetadataKind { None, TypeParameter, Property, ChildContentParameter, Bind, Component, EventHandler, ViewComponent, AssetPath }

object TagHelperFlags {
    const val CASE_SENSITIVE = 1 shl 0
    const val IS_FULLY_QUALIFIED_NAME_MATCH = 1 shl 1
    const val CLASSIFY_ATTRIBUTES_ONLY = 1 shl 2
}

object BoundAttributeFlags {
    const val CASE_SENSITIVE = 1 shl 0
    const val HAS_INDEXER = 1 shl 1
    const val IS_ENUM = 1 shl 2
    const val IS_EDITOR_REQUIRED = 1 shl 3
    const val IS_DIRECTIVE_ATTRIBUTE = 1 shl 4
    const val IS_WEAKLY_TYPED = 1 shl 5
}

object BoundAttributeParameterFlags {
    const val CASE_SENSITIVE = 1 shl 0
    const val IS_ENUM = 1 shl 1
    const val BIND_ATTRIBUTE_GET_SET = 1 shl 2
}

object RequiredAttributeDescriptorFlags {
    const val NONE = 0
    const val CASE_SENSITIVE = 1 shl 0
    const val IS_DIRECTIVE_ATTRIBUTE = 1 shl 1
}

/** `TypeNameObject`: a type name with, for the known ones, the namespace and the short name; `string` and `bool` are recognised by either spelling. */
class TypeNameObject private constructor(val fullName: String?, val namespace: String?, val name: String?) {
    val isNull: Boolean get() = fullName == null
    val isBoolean: Boolean get() = fullName == "System.Boolean" || fullName == "bool"
    val isString: Boolean get() = fullName == "System.String" || fullName == "string"

    override fun equals(other: Any?): Boolean = other is TypeNameObject && other.fullName == fullName && other.namespace == namespace && other.name == name
    override fun hashCode(): Int = fullName.hashCode()
    override fun toString(): String = fullName ?: "<null>"

    companion object {
        val NULL = TypeNameObject(null, null, null)

        private val KNOWN: Map<String, TypeNameObject> = listOf(
            "System.Object" to "object", "System.Boolean" to "bool", "System.Int32" to "int", "System.Int64" to "long", "System.Int16" to "short", "System.Byte" to "byte",
            "System.SByte" to "sbyte", "System.UInt32" to "uint", "System.UInt64" to "ulong", "System.UInt16" to "ushort", "System.Single" to "float",
            "System.Double" to "double", "System.Decimal" to "decimal", "System.Char" to "char", "System.String" to "string",
            "System.Globalization.CultureInfo" to null, "System.Delegate" to null, "System.Type" to null,
        ).flatMap { (fullName, alias) ->
            val info = TypeNameObject(fullName, fullName.substringBeforeLast('.'), fullName.substringAfterLast('.'))
            listOfNotNull(fullName to info, alias?.let { it to info })
        }.toMap()

        fun from(fullName: String?): TypeNameObject = if (fullName == null) NULL else from(fullName, null, null)

        fun from(fullName: String, namespaceName: String?, name: String?): TypeNameObject = KNOWN[fullName] ?: TypeNameObject(fullName, namespaceName, name)
    }
}

/** `MetadataObject`: what a descriptor carries beyond its structure, by kind; `NONE` for the plain ones. */
abstract class MetadataObject(val kind: MetadataKind) {
    abstract val hasDefaultValue: Boolean

    private class NoMetadataObject : MetadataObject(MetadataKind.None) {
        override val hasDefaultValue: Boolean get() = true
    }

    companion object {
        val NONE: MetadataObject = NoMetadataObject()
    }
}

data class PropertyMetadata(
    val globallyQualifiedTypeName: String? = null, val isChildContent: Boolean = false, val isEventCallback: Boolean = false, val isDelegateSignature: Boolean = false,
    val isDelegateWithAwaitableResult: Boolean = false, val isGenericTyped: Boolean = false, val isInitOnlyProperty: Boolean = false, val acceptsStringLiteral: Boolean = false,
    val acceptsAssetPath: Boolean = false,
) : MetadataObject(MetadataKind.Property) {
    override val hasDefaultValue: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = PropertyMetadata()
    }
}

data class EventHandlerMetadata(val eventArgsType: String) : MetadataObject(MetadataKind.EventHandler) {
    override val hasDefaultValue: Boolean get() = false
}

data class BindMetadata(
    val isFallback: Boolean = false, val valueAttribute: String? = null, val changeAttribute: String? = null, val expressionAttribute: String? = null,
    val typeAttribute: String? = null, val isInvariantCulture: Boolean = false, val format: String? = null,
) : MetadataObject(MetadataKind.Bind) {
    override val hasDefaultValue: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = BindMetadata()
    }
}

data class TypeParameterMetadata(val isCascading: Boolean = false, val constraints: String? = null, val nameWithAttributes: String? = null) : MetadataObject(MetadataKind.TypeParameter) {
    override val hasDefaultValue: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = TypeParameterMetadata()
    }
}

class ChildContentParameterMetadata private constructor() : MetadataObject(MetadataKind.ChildContentParameter) {
    override val hasDefaultValue: Boolean get() = true

    companion object {
        val DEFAULT = ChildContentParameterMetadata()
    }
}

data class AssetPathMetadata(val element: String, val attribute: String) : MetadataObject(MetadataKind.AssetPath) {
    override val hasDefaultValue: Boolean get() = false
}

/** `TagHelperObject<T>`: the base of the descriptors, with their diagnostics. */
abstract class TagHelperObject(val diagnostics: List<RazorDiagnostic>) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == RazorDiagnosticSeverity.ERROR }
}

class TagHelperDescriptor(
    val flags: Int,
    val kind: TagHelperKind,
    val runtimeKind: RuntimeKind,
    val name: String,
    val assemblyName: String,
    val displayName: String,
    val typeNameObject: TypeNameObject,
    val documentation: String?,
    val tagOutputHint: String?,
    tagMatchingRules: List<TagMatchingRuleDescriptor>,
    attributeDescriptors: List<BoundAttributeDescriptor>,
    allowedChildTags: List<AllowedChildTagDescriptor>,
    val metadata: MetadataObject,
    diagnostics: List<RazorDiagnostic>,
) : TagHelperObject(diagnostics) {
    val tagMatchingRules: List<TagMatchingRuleDescriptor> = tagMatchingRules
    val boundAttributes: List<BoundAttributeDescriptor> = attributeDescriptors
    val allowedChildTags: List<AllowedChildTagDescriptor> = allowedChildTags

    val typeName: String get() = typeNameObject.fullName!!
    val typeNamespace: String? get() = typeNameObject.namespace
    val typeNameIdentifier: String? get() = typeNameObject.name
    val caseSensitive: Boolean get() = flags and TagHelperFlags.CASE_SENSITIVE != 0
    val isFullyQualifiedNameMatch: Boolean get() = flags and TagHelperFlags.IS_FULLY_QUALIFIED_NAME_MATCH != 0
    val classifyAttributesOnly: Boolean get() = flags and TagHelperFlags.CLASSIFY_ATTRIBUTES_ONLY != 0

    init {
        for (rule in tagMatchingRules) rule.setParent(this)
        for (attribute in attributeDescriptors) attribute.setParent(this)
        for (tag in allowedChildTags) tag.setParent(this)
    }

    val editorRequiredAttributes: List<BoundAttributeDescriptor> by lazy { boundAttributes.filter { it.isEditorRequired } }

    fun getAllDiagnostics(): List<RazorDiagnostic> {
        val result = ArrayList<RazorDiagnostic>()
        for (tag in allowedChildTags) result.addAll(tag.diagnostics)
        for (attribute in boundAttributes) result.addAll(attribute.diagnostics)
        for (rule in tagMatchingRules) result.addAll(rule.diagnostics)
        result.addAll(diagnostics)
        return result
    }

    fun withName(name: String): TagHelperDescriptor =
        TagHelperDescriptor(flags, kind, runtimeKind, name, assemblyName, displayName, typeNameObject, documentation, tagOutputHint, tagMatchingRules, boundAttributes, allowedChildTags, metadata, diagnostics)

    override fun toString(): String = displayName
}

class BoundAttributeDescriptor(
    val flags: Int,
    val name: String,
    val propertyName: String,
    val typeNameObject: TypeNameObject,
    val indexerNamePrefix: String?,
    val indexerTypeNameObject: TypeNameObject,
    val documentation: String?,
    val displayName: String,
    val containingType: String?,
    parameters: List<BoundAttributeParameterDescriptor>,
    val metadata: MetadataObject,
    diagnostics: List<RazorDiagnostic>,
) : TagHelperObject(diagnostics) {
    val parameters: List<BoundAttributeParameterDescriptor> = parameters
    private var parentField: TagHelperDescriptor? = null
    val parent: TagHelperDescriptor get() = parentField ?: throw IllegalStateException("Parent has not been set.")

    val typeName: String get() = typeNameObject.fullName!!
    val indexerTypeName: String? get() = indexerTypeNameObject.fullName
    val caseSensitive: Boolean get() = flags and BoundAttributeFlags.CASE_SENSITIVE != 0
    val hasIndexer: Boolean get() = flags and BoundAttributeFlags.HAS_INDEXER != 0
    val isIndexerStringProperty: Boolean get() = indexerTypeNameObject.isString
    val isIndexerBooleanProperty: Boolean get() = indexerTypeNameObject.isBoolean
    val isEnum: Boolean get() = flags and BoundAttributeFlags.IS_ENUM != 0
    val isStringProperty: Boolean get() = typeNameObject.isString
    val isBooleanProperty: Boolean get() = typeNameObject.isBoolean
    val isEditorRequired: Boolean get() = flags and BoundAttributeFlags.IS_EDITOR_REQUIRED != 0
    val isDirectiveAttribute: Boolean get() = flags and BoundAttributeFlags.IS_DIRECTIVE_ATTRIBUTE != 0
    val isWeaklyTyped: Boolean get() = flags and BoundAttributeFlags.IS_WEAKLY_TYPED != 0

    init {
        for (parameter in parameters) parameter.setParent(this)
    }

    internal fun setParent(parent: TagHelperDescriptor) {
        check(parentField == null)
        parentField = parent
    }

    fun getAllDiagnostics(): List<RazorDiagnostic> = parameters.flatMap { it.diagnostics } + diagnostics

    override fun toString(): String = displayName
}

class BoundAttributeParameterDescriptor(
    val flags: Int,
    val name: String,
    val propertyName: String,
    val typeNameObject: TypeNameObject,
    val documentation: String?,
    diagnostics: List<RazorDiagnostic>,
) : TagHelperObject(diagnostics) {
    private var parentField: BoundAttributeDescriptor? = null
    val parent: BoundAttributeDescriptor get() = parentField ?: throw IllegalStateException("Parent has not been set.")

    val typeName: String get() = typeNameObject.fullName!!
    val displayName: String get() = ":$name"
    val caseSensitive: Boolean get() = flags and BoundAttributeParameterFlags.CASE_SENSITIVE != 0
    val isEnum: Boolean get() = flags and BoundAttributeParameterFlags.IS_ENUM != 0
    val isStringProperty: Boolean get() = typeNameObject.isString
    val isBooleanProperty: Boolean get() = typeNameObject.isBoolean
    val bindAttributeGetSet: Boolean get() = flags and BoundAttributeParameterFlags.BIND_ATTRIBUTE_GET_SET != 0

    internal fun setParent(parent: BoundAttributeDescriptor) {
        check(parentField == null)
        parentField = parent
    }

    override fun toString(): String = displayName
}

class TagMatchingRuleDescriptor(
    val tagName: String,
    val parentTag: String?,
    val tagStructure: TagStructure,
    val caseSensitive: Boolean,
    attributes: List<RequiredAttributeDescriptor>,
    diagnostics: List<RazorDiagnostic>,
) : TagHelperObject(diagnostics) {
    val attributes: List<RequiredAttributeDescriptor> = attributes
    private var parentField: TagHelperDescriptor? = null
    val parent: TagHelperDescriptor get() = parentField ?: throw IllegalStateException("Parent has not been set.")

    init {
        for (attribute in attributes) attribute.setParent(this)
    }

    internal fun setParent(parent: TagHelperDescriptor) {
        check(parentField == null)
        parentField = parent
    }

    fun getAllDiagnostics(): List<RazorDiagnostic> = attributes.flatMap { it.diagnostics } + diagnostics
}

class RequiredAttributeDescriptor(
    val flags: Int,
    val name: String,
    val nameComparison: RequiredAttributeNameComparison,
    val value: String?,
    val valueComparison: RequiredAttributeValueComparison,
    diagnostics: List<RazorDiagnostic>,
) : TagHelperObject(diagnostics) {
    private var parentField: TagMatchingRuleDescriptor? = null
    val parent: TagMatchingRuleDescriptor get() = parentField ?: throw IllegalStateException("Parent has not been set.")

    val displayName: String get() = if (nameComparison == RequiredAttributeNameComparison.PrefixMatch) "$name..." else name
    val caseSensitive: Boolean get() = flags and RequiredAttributeDescriptorFlags.CASE_SENSITIVE != 0
    val isDirectiveAttribute: Boolean get() = flags and RequiredAttributeDescriptorFlags.IS_DIRECTIVE_ATTRIBUTE != 0

    internal fun setParent(parent: TagMatchingRuleDescriptor) {
        check(parentField == null)
        parentField = parent
    }

    override fun toString(): String = displayName
}

class AllowedChildTagDescriptor(val name: String, val displayName: String, diagnostics: List<RazorDiagnostic>) : TagHelperObject(diagnostics) {
    private var parentField: TagHelperDescriptor? = null
    val parent: TagHelperDescriptor get() = parentField ?: throw IllegalStateException("Parent has not been set.")

    internal fun setParent(parent: TagHelperDescriptor) {
        check(parentField == null)
        parentField = parent
    }

    override fun toString(): String = displayName
}

/** `TagHelperDescriptorExtensions` / `BoundAttributeDescriptorExtensions`. */
fun TagHelperDescriptor.isDefaultKind(): Boolean = kind == TagHelperKind.ITagHelper
fun TagHelperDescriptor.kindUsesDefaultTagHelperRuntime(): Boolean = runtimeKind == RuntimeKind.ITagHelper
fun BoundAttributeDescriptor.getGloballyQualifiedTypeName(): String? = (metadata as? PropertyMetadata)?.globallyQualifiedTypeName
fun BoundAttributeDescriptor.isDefaultKind(): Boolean = parent.kind == TagHelperKind.ITagHelper
fun BoundAttributeDescriptor.acceptsStringLiteral(): Boolean = (metadata as? PropertyMetadata)?.acceptsStringLiteral == true
fun BoundAttributeDescriptor.expectsStringValue(name: String): Boolean {
    if (isStringProperty || acceptsStringLiteral()) return true
    return TagHelperMatchingConventions.satisfiesBoundAttributeIndexer(this, name) && isIndexerStringProperty
}
fun BoundAttributeDescriptor.expectsBooleanValue(name: String): Boolean {
    if (isBooleanProperty) return true
    return TagHelperMatchingConventions.satisfiesBoundAttributeIndexer(this, name) && isIndexerBooleanProperty
}
fun BoundAttributeParameterDescriptor.isDefaultKind(): Boolean = parent.parent.kind == TagHelperKind.ITagHelper

/** `TagHelperAttributeMatch`: an attribute name bound to a descriptor, maybe through an indexer or a parameter (`@bind:format`). */
class TagHelperAttributeMatch(val name: String, val attribute: BoundAttributeDescriptor, val parameter: BoundAttributeParameterDescriptor? = null) {
    val isIndexerMatch: Boolean by lazy { TagHelperMatchingConventions.satisfiesBoundAttributeIndexer(attribute, name) }
    val isParameterMatch: Boolean get() = parameter != null

    val expectsStringValue: Boolean
        get() {
            if (parameter != null) return parameter.isStringProperty
            return attribute.isStringProperty || attribute.acceptsStringLiteral() || (isIndexerMatch && attribute.isIndexerStringProperty)
        }

    val expectsBooleanValue: Boolean
        get() {
            if (parameter != null) return parameter.isBooleanProperty
            return attribute.isBooleanProperty || (isIndexerMatch && attribute.isIndexerBooleanProperty)
        }
}

/** `TagHelperCollection`: the descriptors of a document; equal by content. */
class TagHelperCollection(items: List<TagHelperDescriptor>) : AbstractList<TagHelperDescriptor>() {
    private val items: List<TagHelperDescriptor> = items
    override val size: Int get() = items.size
    override fun get(index: Int): TagHelperDescriptor = items[index]

    fun where(predicate: (TagHelperDescriptor) -> Boolean): TagHelperCollection {
        if (isEmpty()) return EMPTY
        val filtered = items.filter(predicate)
        return if (filtered.size == items.size) this else if (filtered.isEmpty()) EMPTY else TagHelperCollection(filtered)
    }

    companion object {
        val EMPTY = TagHelperCollection(emptyList())
        fun of(vararg items: TagHelperDescriptor) = TagHelperCollection(items.toList())
    }
}

/** `TagHelperMatchingConventions`. */
object TagHelperMatchingConventions {
    const val ELEMENT_CATCH_ALL_NAME = "*"
    const val ELEMENT_OPT_OUT_CHARACTER = '!'

    fun satisfiesRule(rule: TagMatchingRuleDescriptor, tagNameWithoutPrefix: String, parentTagNameWithoutPrefix: String?, tagAttributes: List<Pair<String, String>>): Boolean =
        satisfiesTagName(rule, tagNameWithoutPrefix) && satisfiesParentTag(rule, parentTagNameWithoutPrefix) && satisfiesAttributes(rule, tagAttributes)

    fun satisfiesTagName(rule: TagMatchingRuleDescriptor, tagNameWithoutPrefix: String, ignoreCaseOverride: Boolean? = null): Boolean {
        if (tagNameWithoutPrefix.isEmpty()) return false
        if (tagNameWithoutPrefix[0] == ELEMENT_OPT_OUT_CHARACTER) return false
        val tagName = rule.tagName
        if (tagName != ELEMENT_CATCH_ALL_NAME && !tagNameWithoutPrefix.equals(tagName, ignoreCase = ignoreCaseOverride ?: !rule.caseSensitive)) return false
        return true
    }

    fun satisfiesParentTag(rule: TagMatchingRuleDescriptor, parentTagNameWithoutPrefix: String?): Boolean {
        val parentTag = rule.parentTag
        if (parentTag != null && !(parentTagNameWithoutPrefix ?: "").equals(parentTag, ignoreCase = !rule.caseSensitive)) return false
        return true
    }

    fun satisfiesAttributes(rule: TagMatchingRuleDescriptor, tagAttributes: List<Pair<String, String>>): Boolean {
        for (requiredAttribute in rule.attributes) {
            if (tagAttributes.none { (name, value) -> satisfiesRequiredAttribute(requiredAttribute, name, value) }) return false
        }
        return true
    }

    fun canSatisfyBoundAttribute(name: String, descriptor: BoundAttributeDescriptor): Boolean =
        satisfiesBoundAttributeName(descriptor, name) || satisfiesBoundAttributeIndexer(descriptor, name) || getSatisfyingBoundAttributeWithParameter(descriptor, name) != null

    private fun getSatisfyingBoundAttributeWithParameter(descriptor: BoundAttributeDescriptor, name: String): BoundAttributeParameterDescriptor? =
        descriptor.parameters.firstOrNull { satisfiesBoundAttributeWithParameter(it, name, descriptor) }

    fun satisfiesBoundAttributeIndexer(descriptor: BoundAttributeDescriptor, name: String): Boolean {
        val prefix = descriptor.indexerNamePrefix
        return prefix != null && !satisfiesBoundAttributeName(descriptor, name) && name.startsWith(prefix, ignoreCase = !descriptor.caseSensitive)
    }

    fun satisfiesBoundAttributeWithParameter(descriptor: BoundAttributeParameterDescriptor, name: String, parent: BoundAttributeDescriptor): Boolean {
        val index = name.indexOf(':')
        if (name.isEmpty() || index < 0) return false
        val attributeName = name.substring(0, index)
        val parameterName = name.substring(index + 1)
        val satisfiesBoundAttributeName = satisfiesBoundAttributeName(parent, attributeName)
        val satisfiesBoundAttributeIndexer = satisfiesBoundAttributeIndexer(parent, attributeName)
        val matchesParameter = parameterName.equals(descriptor.name, ignoreCase = !descriptor.caseSensitive)
        return (satisfiesBoundAttributeName || satisfiesBoundAttributeIndexer) && matchesParameter
    }

    /** `TryGetBoundAttributeParameter`: the attribute name before the `:`, null without one. */
    fun tryGetBoundAttributeParameter(fullAttributeName: String?): String? {
        if (fullAttributeName.isNullOrEmpty()) return null
        val index = fullAttributeName.indexOf(':')
        if (index < 0) return null
        return fullAttributeName.substring(0, index)
    }

    fun hasAttributeMatches(tagHelpers: Iterable<TagHelperDescriptor>, name: String): Boolean = tagHelpers.any { tryGetFirstBoundAttributeMatch(it, name) != null }

    fun getAttributeMatches(tagHelpers: Iterable<TagHelperDescriptor>, name: String, matches: MutableList<TagHelperAttributeMatch>) {
        for (tagHelper in tagHelpers) tryGetFirstBoundAttributeMatch(tagHelper, name)?.let { matches.add(it) }
    }

    fun tryGetFirstBoundAttributeMatch(descriptor: TagHelperDescriptor?, name: String?): TagHelperAttributeMatch? {
        if (descriptor == null || name.isNullOrEmpty()) return null
        for (attribute in descriptor.boundAttributes) {
            val parameter = getSatisfyingBoundAttributeWithParameter(attribute, name)
            if (parameter != null) return TagHelperAttributeMatch(name, attribute, parameter)
        }
        for (attribute in descriptor.boundAttributes) {
            if (canSatisfyBoundAttribute(name, attribute)) return TagHelperAttributeMatch(name, attribute, null)
        }
        return null
    }

    private fun satisfiesBoundAttributeName(descriptor: BoundAttributeDescriptor, name: String): Boolean = name.equals(descriptor.name, ignoreCase = !descriptor.caseSensitive)

    fun satisfiesRequiredAttribute(descriptor: RequiredAttributeDescriptor, attributeName: String, attributeValue: String): Boolean {
        val nameMatches = when (descriptor.nameComparison) {
            RequiredAttributeNameComparison.FullMatch -> descriptor.name.equals(attributeName, ignoreCase = !descriptor.caseSensitive)
            RequiredAttributeNameComparison.PrefixMatch -> attributeName.length != descriptor.name.length && attributeName.startsWith(descriptor.name, ignoreCase = !descriptor.caseSensitive)
        }
        if (!nameMatches) return false
        return when (descriptor.valueComparison) {
            RequiredAttributeValueComparison.None -> true
            RequiredAttributeValueComparison.PrefixMatch -> attributeValue.startsWith(descriptor.value!!)
            RequiredAttributeValueComparison.SuffixMatch -> attributeValue.endsWith(descriptor.value!!)
            RequiredAttributeValueComparison.FullMatch -> attributeValue == descriptor.value
        }
    }
}

/** `TagHelperBoundRulesInfo`: a descriptor with the rules an element satisfied. */
class TagHelperBoundRulesInfo(val descriptor: TagHelperDescriptor, val rules: List<TagMatchingRuleDescriptor>)

/** `TagHelperBinding`: the descriptors bound to an element. */
class TagHelperBinding(
    val allBoundRules: List<TagHelperBoundRulesInfo>,
    val tagName: String,
    val parentTagName: String?,
    val attributes: List<Pair<String, String>>,
    val tagNamePrefix: String?,
) {
    val tagHelpers: TagHelperCollection by lazy { TagHelperCollection(allBoundRules.map { it.descriptor }) }

    fun getBoundRules(descriptor: TagHelperDescriptor): List<TagMatchingRuleDescriptor> = allBoundRules.first { it.descriptor == descriptor }.rules

    val isAttributeMatch: Boolean by lazy { tagHelpers.all { it.classifyAttributesOnly } }
}

/** `TagHelperBinder`: the descriptors by tag name (with the prefix), the catch-alls apart. */
class TagHelperBinder(val tagNamePrefix: String?, val tagHelpers: TagHelperCollection) {
    private val tagNameToTagHelpersMap: Map<String, List<TagHelperDescriptor>>
    private val catchAllTagHelpers: List<TagHelperDescriptor>

    init {
        val map = java.util.TreeMap<String, ArrayList<TagHelperDescriptor>>(String.CASE_INSENSITIVE_ORDER)
        val catchAll = ArrayList<TagHelperDescriptor>()
        for (tagHelper in tagHelpers) {
            for (rule in tagHelper.tagMatchingRules) {
                val tagName = rule.tagName
                if (tagName == TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME) {
                    catchAll.add(tagHelper)
                    continue
                }
                map.getOrPut(tagName) { ArrayList() }.add(tagHelper)
            }
        }
        val prefixed = java.util.TreeMap<String, List<TagHelperDescriptor>>(String.CASE_INSENSITIVE_ORDER)
        for ((tagName, descriptors) in map) prefixed[(tagNamePrefix ?: "") + tagName] = descriptors
        tagNameToTagHelpersMap = prefixed
        catchAllTagHelpers = catchAll
    }

    fun getBinding(tagName: String, attributes: List<Pair<String, String>>, parentTagName: String?, parentIsTagHelper: Boolean): TagHelperBinding? {
        var tagNameWithoutPrefix = tagName
        var parentTagNameWithoutPrefix = parentTagName
        val prefix = tagNamePrefix
        if (!prefix.isNullOrEmpty()) {
            if (!tagName.startsWith(prefix, ignoreCase = true)) return null
            tagNameWithoutPrefix = tagName.substring(prefix.length)
            if (parentIsTagHelper) parentTagNameWithoutPrefix = parentTagName?.substring(prefix.length)
        }
        val results = ArrayList<TagHelperBoundRulesInfo>()
        val distinctSet = HashSet<TagHelperDescriptor>()
        fun collect(descriptors: List<TagHelperDescriptor>) {
            for (descriptor in descriptors) {
                if (!distinctSet.add(descriptor)) continue
                val rules = descriptor.tagMatchingRules.filter { TagHelperMatchingConventions.satisfiesRule(it, tagNameWithoutPrefix, parentTagNameWithoutPrefix, attributes) }
                if (rules.isNotEmpty()) results.add(TagHelperBoundRulesInfo(descriptor, rules))
            }
        }
        tagNameToTagHelpersMap[tagName]?.let { collect(it) }
        collect(catchAllTagHelpers)
        return if (results.isNotEmpty()) TagHelperBinding(results, tagName, parentTagName, attributes, tagNamePrefix) else null
    }
}

/** `TagHelperDocumentContext`: the prefix and the descriptors in scope of a document, with the binder over them. */
class TagHelperDocumentContext(val prefix: String?, val tagHelpers: TagHelperCollection) {
    val binder: TagHelperBinder by lazy { TagHelperBinder(prefix, tagHelpers) }

    companion object {
        fun create(prefix: String?, tagHelpers: TagHelperCollection) = TagHelperDocumentContext(prefix, tagHelpers)
    }
}
