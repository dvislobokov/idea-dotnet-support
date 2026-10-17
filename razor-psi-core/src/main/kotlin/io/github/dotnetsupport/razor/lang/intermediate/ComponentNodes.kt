package io.github.dotnetsupport.razor.lang.intermediate

import io.github.dotnetsupport.razor.lang.syntax.*
import io.github.dotnetsupport.razor.lang.codegen.components.*
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

/** The component nodes of the IR (`Component*IntermediateNode.cs`, `CascadingGenericTypeParameter.cs`): what the component lowering makes of a tag helper node. */

/** `ComponentIntermediateNode`: a component usage, `<Counter IncrementAmount="1" />`. */
class ComponentIntermediateNode : IntermediateNode() {
    val attributes: List<ComponentAttributeIntermediateNode> get() = children.filterIsInstance<ComponentAttributeIntermediateNode>()
    val captures: List<ReferenceCaptureIntermediateNode> get() = children.filterIsInstance<ReferenceCaptureIntermediateNode>()
    val setKeys: List<SetKeyIntermediateNode> get() = children.filterIsInstance<SetKeyIntermediateNode>()
    val splats: List<SplatIntermediateNode> get() = children.filterIsInstance<SplatIntermediateNode>()
    val childContents: List<ComponentChildContentIntermediateNode> get() = children.filterIsInstance<ComponentChildContentIntermediateNode>()

    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    var component: TagHelperDescriptor? = null

    /** The child content parameter name (null if unset) applied at the component level. */
    var childContentParameterName: String? = null

    val typeArguments: List<ComponentTypeArgumentIntermediateNode> get() = children.filterIsInstance<ComponentTypeArgumentIntermediateNode>()

    /** `OrderedTypeArguments`: null is the compiler's default (unset) `ImmutableArray`. */
    var orderedTypeArguments: List<ComponentTypeArgumentIntermediateNode>? = null

    var tagName: String? = null

    /** The type inference node, when this call site needs type inference (it lives elsewhere in the tree). */
    var typeInferenceNode: ComponentTypeInferenceMethodIntermediateNode? = null

    /** The type arguments this component offers its descendants (null when none). */
    var providesCascadingGenericTypes: MutableMap<String, CascadingGenericTypeParameter>? = null

    var typeName: String? = null

    /** `StartTagSpan { get; init; }`: a struct, `default(SourceSpan)` when unset. */
    var startTagSpan: SourceSpan = SourceSpan(null, 0, 0, 0, 0)

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitComponent(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(tagName)
        formatter.writeProperty("Component", component?.displayName)
        formatter.writeProperty("TagName", tagName)
    }
}

/** `ComponentAttributeIntermediateNode`: an attribute of a component, bound to a parameter or not. */
class ComponentAttributeIntermediateNode() : IntermediateNode() {
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()

    var hasExplicitTypeName: Boolean = false
    var isOpenGeneric: Boolean = false

    /** Synthesized by the compiler, not written by the user. */
    var isSynthesized: Boolean = false

    /** A helper the runtime writes as `_ = TheProperty;` so that the C# compiler (and the IDE) sees a reference to the bind property. */
    var isDesignTimePropertyAccessHelper: Boolean = false

    /** The sub-span of a bind node that is the property: `@bind-Value:get=""` → `Value`. */
    var propertySpan: SourceSpan? = null

    var originalAttributeName: String? = null
    var originalAttributeSpan: SourceSpan? = null
    var addAttributeMethodName: String? = null

    /** The type a generic component's bound attribute has after the component is rewritten with its concrete type. */
    var concreteContainingType: String? = null

    var attributeName: String? = null
    var attributeStructure: AttributeStructure = AttributeStructure.entries[0]
    var boundAttribute: BoundAttributeDescriptor? = null
    var propertyName: String? = null
    val tagHelper: TagHelperDescriptor? get() = boundAttribute?.parent
    var typeName: String? = null
    var globallyQualifiedTypeName: String? = null

    constructor(attributeNode: TagHelperHtmlAttributeIntermediateNode) : this() {
        attributeName = attributeNode.attributeName
        attributeStructure = attributeNode.attributeStructure
        source = attributeNode.source
        for (i in 0 until attributeNode.children.size) children.add(attributeNode.children[i])
        addDiagnosticsFromNode(attributeNode)
    }

    constructor(propertyNode: TagHelperPropertyIntermediateNode) : this() {
        val attributeName = propertyNode.attributeName
        this.attributeName = attributeName
        attributeStructure = propertyNode.attributeStructure
        boundAttribute = propertyNode.boundAttribute
        originalAttributeSpan = propertyNode.originalAttributeSpan
        propertyName = propertyNode.boundAttribute.propertyName
        source = propertyNode.source
        typeName = if (propertyNode.boundAttribute.isWeaklyTyped) null else propertyNode.boundAttribute.typeName
        for (i in 0 until propertyNode.children.size) children.add(propertyNode.children[i])
        addDiagnosticsFromNode(propertyNode)
    }

    private constructor(node: TagHelperDirectiveAttributeIntermediateNode, addChildren: Boolean) : this() {
        attributeName = node.attributeName
        attributeStructure = node.attributeStructure
        boundAttribute = node.boundAttribute
        originalAttributeSpan = node.originalAttributeSpan
        propertyName = node.boundAttribute.propertyName
        source = node.source
        typeName = if (node.boundAttribute.isWeaklyTyped) null else node.boundAttribute.typeName
        if (addChildren) children.addAll(node.children)
        addDiagnosticsFromNode(node)
    }

    private constructor(node: TagHelperDirectiveAttributeParameterIntermediateNode, addChildren: Boolean) : this() {
        attributeName = node.attributeNameWithoutParameter
        attributeStructure = node.attributeStructure
        boundAttribute = node.boundAttribute
        originalAttributeSpan = node.originalAttributeSpan
        propertyName = node.boundAttributeParameter.propertyName
        source = node.source
        typeName = node.boundAttributeParameter.typeName
        if (addChildren) children.addAll(node.children)
        addDiagnosticsFromNode(node)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitComponentAttribute(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("AttributeStructure", attributeStructure.toString())
        formatter.writeProperty("BoundAttribute", boundAttribute?.displayName)
        formatter.writeProperty("PropertyName", propertyName)
        formatter.writeProperty("TagHelper", tagHelper?.displayName)
        formatter.writeProperty("TypeName", typeName)
        formatter.writeProperty("GloballyQualifiedTypeName", globallyQualifiedTypeName)
    }

    /**
     * `TryParseEventCallbackTypeArgument` (both overloads): the type argument of `EventCallback<T>`, null for the non-generic form or a
     * mangled name. Ad hoc as in the compiler: it runs just before the code is written, with no C# parser at this layer.
     */
    fun tryParseEventCallbackTypeArgument(): String? {
        val boundAttribute = boundAttribute
        if (boundAttribute == null || !boundAttribute.isEventCallbackProperty()) throw IllegalStateException("This attribute is not an EventCallback attribute.")
        return tryGetEventCallbackArgument(typeName ?: "")?.toString() // a null string's AsMemory() is empty
    }

    companion object {
        fun from(node: TagHelperDirectiveAttributeIntermediateNode, addChildren: Boolean) = ComponentAttributeIntermediateNode(node, addChildren)
        fun from(node: TagHelperDirectiveAttributeParameterIntermediateNode, addChildren: Boolean) = ComponentAttributeIntermediateNode(node, addChildren)

        private const val GLOBAL_PREFIX = "global::"

        /** `TryGetEventCallbackArgument`: `T` of `[global::]Microsoft.AspNetCore.Components.EventCallback<T>`, null when there is none. */
        internal fun tryGetEventCallbackArgument(candidate: CharSequence): CharSequence? {
            // Strip 'global::' from the candidate.
            var candidate = candidate
            if (candidate.startsWith(GLOBAL_PREFIX)) candidate = candidate.subSequence(GLOBAL_PREFIX.length, candidate.length)

            val eventCallbackName = ComponentsApi.EventCallback.fullTypeName

            // The non-generic form: no argument to retrieve.
            if (candidate.contentEquals(eventCallbackName)) return null

            if (candidate.length <= eventCallbackName.length + "<>".length || !candidate.startsWith(eventCallbackName)) return null

            val afterCallbackName = candidate.subSequence(eventCallbackName.length, candidate.length)
            if (afterCallbackName.length >= 2 && afterCallbackName[0] == '<' && afterCallbackName[afterCallbackName.length - 1] == '>') {
                return afterCallbackName.subSequence(1, afterCallbackName.length - 1)
            }

            // Only a name mangled through extensibility gets here; no crash.
            return null
        }

        /** `TryGetActionArgument`: `T` of `[global::]System.Action<T>`, null when there is none. */
        internal fun tryGetActionArgument(candidate: CharSequence): CharSequence? {
            var candidate = candidate
            if (candidate.startsWith(GLOBAL_PREFIX)) candidate = candidate.subSequence(GLOBAL_PREFIX.length, candidate.length)

            if (!candidate.startsWith("System.Action")) return null

            candidate = candidate.subSequence("System.Action".length, candidate.length)
            if (candidate.length >= 2 && candidate[0] == '<' && candidate[candidate.length - 1] == '>') return candidate.subSequence(1, candidate.length - 1)

            return null
        }

        /**
         * `TryGetGenericActionArgument`: for `System.Action<MyType<TItem>>`, `MyType<` + [genericType] + `>` — the generic argument of the
         * action's argument replaced; null when it cannot be constructed.
         */
        internal fun tryGetGenericActionArgument(candidate: CharSequence, genericType: String): String? {
            val actionArgument = tryGetActionArgument(candidate) ?: return null

            val genericTypeStart = actionArgument.indexOf('<')
            val genericTypeEnd = actionArgument.indexOf('>')

            // (start + 1): at least one character between the start and the end.
            if (genericTypeStart <= 0 || genericTypeEnd < genericTypeStart + 1) return null

            return actionArgument.subSequence(0, genericTypeStart + 1).toString() + genericType + actionArgument.subSequence(genericTypeEnd, actionArgument.length)
        }
    }
}

/** `ComponentChildContentIntermediateNode`: the child content of a component, `ChildContent` or a `RenderFragment` parameter. */
class ComponentChildContentIntermediateNode : IntermediateNode() {
    val attributeName: String get() = boundAttribute?.name ?: ComponentsApi.RenderTreeBuilder.childContent
    var boundAttribute: BoundAttributeDescriptor? = null
    override val children: IntermediateNodeCollection = IntermediateNodeCollection()
    val isParameterized: Boolean get() = boundAttribute?.isParameterizedChildContentProperty() ?: false
    var parameterName: String? = null
    var typeName: String? = null

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitComponentChildContent(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(attributeName)
        formatter.writeProperty("AttributeName", attributeName)
        formatter.writeProperty("BoundAttribute", boundAttribute?.displayName)
        formatter.writeProperty("ParameterName", parameterName)
        formatter.writeProperty("TypeName", typeName)
    }
}

/** `ComponentTypeArgumentIntermediateNode`: an explicit type argument of a generic component, `TItem="string"`. */
class ComponentTypeArgumentIntermediateNode(val boundAttribute: BoundAttributeDescriptor, val value: CSharpIntermediateToken) : IntermediateNode() {
    val tagHelper: TagHelperDescriptor get() = boundAttribute.parent
    val typeParameterName: String get() = boundAttribute.name

    override val children: IntermediateNodeCollection = IntermediateNodeCollection().also { it.add(value) }

    constructor(node: TagHelperPropertyIntermediateNode) : this(node.boundAttribute, getValue(node)) {
        source = node.source
        addDiagnosticsFromNode(node)
    }

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitComponentTypeArgument(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(typeParameterName)
        formatter.writeProperty("BoundAttribute", boundAttribute.displayName)
        formatter.writeProperty("TagHelper", tagHelper.displayName)
    }

    private companion object {
        fun getValue(node: TagHelperPropertyIntermediateNode): CSharpIntermediateToken {
            val children = node.children
            if (children.size == 1) {
                val only = children[0]
                if (only is CSharpIntermediateToken) return only
                if (only is CSharpExpressionIntermediateNode && only.children.size == 1) {
                    val t = only.children[0]
                    if (t is CSharpIntermediateToken) return t
                }
                // The value lowered as HTML content (the unresolved tag helper pipeline).
                if (only is HtmlContentIntermediateNode && only.children.size == 1) {
                    val t = only.children[0]
                    if (t is HtmlIntermediateToken) {
                        return if (t.isLazy) IntermediateNodeFactory.csharpToken(t, { token -> token.content }, t.source) else CSharpIntermediateToken(t.content, t.source)
                    }
                }
            }
            // Fallback: a more complex shape (literal text mixed with expressions): the content of all descendants as one C# token,
            // so that the C# compiler reports an invalid type instead of the Razor compiler crashing.
            return mergeChildTokens(node)
        }

        fun mergeChildTokens(node: TagHelperPropertyIntermediateNode): CSharpIntermediateToken {
            val sb = StringBuilder()
            val spans = arrayOfNulls<SourceSpan>(2) // firstSpan, lastSpan
            appendTokens(node, sb, spans)
            val first = spans[0]
            val last = spans[1]
            val mergedSpan = if (first != null && last != null && first.absoluteIndex <= last.absoluteIndex) mergeSourceSpans(first, last) else first ?: last ?: node.source
            return CSharpIntermediateToken(sb.toString(), mergedSpan)
        }

        fun appendTokens(node: IntermediateNode, sb: StringBuilder, spans: Array<SourceSpan?>) {
            for (child in node.children) {
                if (child is IntermediateToken) {
                    sb.append(child.content)
                    val s = child.source
                    if (s != null) {
                        if (spans[0] == null) spans[0] = s
                        spans[1] = s
                    }
                } else {
                    // Unwrap the containers (CSharpExpressionIntermediateNode, HtmlContentIntermediateNode...).
                    appendTokens(child, sb, spans)
                }
            }
        }

        /** `DefaultTagHelperResolutionPhase.MergeSourceSpans`, copied here: from the start of [first] to the end of [last]. */
        fun mergeSourceSpans(first: SourceSpan, last: SourceSpan): SourceSpan = SourceSpan(
            first.filePath, first.absoluteIndex, first.lineIndex, first.characterIndex, last.absoluteIndex + last.length - first.absoluteIndex,
            last.lineIndex + last.lineCount - first.lineIndex, last.endCharacterIndex,
        )
    }
}

/** `ComponentTypeInferenceMethodIntermediateNode`: the type inference thunk the generated component code calls. */
class ComponentTypeInferenceMethodIntermediateNode : IntermediateNode() {
    override val children: IntermediateNodeCollection get() = IntermediateNodeCollection.READ_ONLY

    /** The component usage this method infers the types of. */
    var component: ComponentIntermediateNode? = null

    /** The full type name of the generated class containing this method. */
    var fullTypeName: String? = null
        internal set

    var methodName: String? = null

    /** The additional arguments for type inference (or null), from the ancestors that cascade their type parameters. */
    var receivesCascadingGenericTypes: MutableList<CascadingGenericTypeParameter>? = null

    /** The constraints of the generic types of the method: `["where T: Foo, new()", "where U: Image"]`. */
    var genericTypeConstraints: List<String>? = null

    override fun accept(visitor: IntermediateNodeVisitor) = visitor.visitComponentTypeInferenceMethod(this)

    override fun formatNode(formatter: IntermediateNodeFormatter) {
        formatter.writeContent(component?.tagName)
        formatter.writeProperty("Component", component?.component?.displayName)
        formatter.writeProperty("FullTypeName", fullTypeName)
        formatter.writeProperty("MethodName", methodName)
    }
}

/** `CascadingGenericTypeParameter`: a type argument an ancestor component cascades to its descendants. */
class CascadingGenericTypeParameter {
    /** The type parameter names the value type covers, e.g. TKey and TItem. */
    var genericTypeNames: Collection<String>? = null

    /** The attribute that supplies the content of [valueExpression]; null for explicitly specified generic parameters. */
    internal var valueSourceNode: ComponentAttributeIntermediateNode? = null

    /** The type of [valueExpression], e.g. `Dictionary[TKey, TItem]`. */
    internal var valueType: String? = null

    /** The expression defining the type of the generic parameter; for inferred ones set once a variable is emitted for [valueSourceNode]. */
    internal var valueExpression: Any? = null
}
