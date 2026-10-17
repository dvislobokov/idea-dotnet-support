package io.github.dotnetsupport.razor.lang.discovery

import io.github.dotnetsupport.razor.lang.codegen.components.ComponentDiagnosticFactory
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentHelpers
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentMetadata
import io.github.dotnetsupport.razor.lang.codegen.components.ComponentsApi
import io.github.dotnetsupport.razor.lang.codegen.components.getChildContentProperties
import io.github.dotnetsupport.razor.lang.codegen.components.isDelegateProperty
import io.github.dotnetsupport.razor.lang.codegen.components.isEventCallbackProperty
import io.github.dotnetsupport.razor.lang.codegen.components.isParameterizedChildContentProperty
import io.github.dotnetsupport.razor.lang.codegen.mvc.RazorExtensionsDiagnosticFactory
import io.github.dotnetsupport.razor.lang.codegen.mvc.ViewComponentMetadata
import io.github.dotnetsupport.razor.lang.codegen.mvc.ViewComponentTypes
import io.github.dotnetsupport.razor.lang.syntax.AssetPathMetadata
import io.github.dotnetsupport.razor.lang.syntax.BindMetadata
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.ChildContentParameterMetadata
import io.github.dotnetsupport.razor.lang.syntax.EventHandlerMetadata
import io.github.dotnetsupport.razor.lang.syntax.PropertyMetadata
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeNameComparison
import io.github.dotnetsupport.razor.lang.syntax.RequiredAttributeValueComparison
import io.github.dotnetsupport.razor.lang.syntax.RuntimeKind
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TagHelperMatchingConventions
import io.github.dotnetsupport.razor.lang.syntax.TagStructure
import io.github.dotnetsupport.razor.lang.syntax.TypeNameObject
import io.github.dotnetsupport.razor.lang.syntax.TypeParameterMetadata

/*
 * The producers of the compiler's tag helper discovery (`Language/TagHelpers/Producers`, `CSharp/DefaultTagHelperDescriptorFactory.cs`,
 * `Mvc/ViewComponentTagHelperDescriptorFactory.cs`) over the symbol model of RazorSymbols.kt: each one creates itself from the compilation
 * (a factory's `TryCreate`: the types it needs are there) and then adds its static descriptors to an assembly and the descriptors of the
 * types it takes.
 */

/** `TagHelperProducer`. */
abstract class TagHelperProducer {
    open val supportsStaticTagHelpers: Boolean get() = false
    open fun addStaticTagHelpers(assembly: RAssembly, results: MutableList<TagHelperDescriptor>) {}
    open val supportsTypes: Boolean get() = false
    open val supportsNestedTypes: Boolean get() = false
    open fun isCandidateType(type: RTypeDefinition): Boolean = false
    open fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {}
}

/** `TypeNameObject.From(INamedTypeSymbol)`: the full name of the type (with its type parameters), its namespace and its name. */
internal fun typeNameObjectOf(type: RTypeDefinition): TypeNameObject = TypeNameObject.from(RazorTypeDisplay.fullName(type), type.namespace, type.name)

/** The declared types from [type] up its base classes with the substitution of each (`currentType = currentType.BaseType`). */
private fun baseChain(type: RTypeDefinition): Sequence<Pair<RTypeDefinition, RSubstitution>> = sequence {
    var current: RTypeDefinition? = type
    var substitution = RSubstitution.NONE
    val seen = HashSet<String>()
    while (current != null && seen.add(current.key)) {
        yield(current to substitution)
        val base = current.baseType ?: break
        substitution = RSubstitution.of(base, substitution)
        current = base.definition
    }
}

// ---- components

/** `ComponentTagHelperProducer`: a public non-abstract type implementing `IComponent`, its `[Parameter]` properties, child content, type parameters. */
class ComponentTagHelperProducer private constructor(private val compilation: RCompilation, private val bindProducer: BindTagHelperProducer?) : TagHelperProducer() {
    private val hasStringObjectDictionary = compilation.typeByMetadataName(ComponentsApi.Dictionary.metadataName) != null

    override val supportsTypes: Boolean get() = true

    override fun isCandidateType(type: RTypeDefinition): Boolean =
        type.accessibility == RAccessibility.Public && !type.isAbstract && type.allInterfaces.any { it.hasFullName(ComponentsApi.IComponent.metadataName) }

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        val properties = getProperties(type)
        val acceptsUnmatchedAttributes = acceptsUnmatchedAttributes(properties)
        val shortName = createNameMatchingDescriptor(type, properties, acceptsUnmatchedAttributes, fullyQualified = false)
        results += shortName
        // a component in the global namespace has no second descriptor: it would be the same as the short name one
        val fullyQualified = if (type.namespace.isNotEmpty()) createNameMatchingDescriptor(type, properties, acceptsUnmatchedAttributes, fullyQualified = true).also { results += it } else null
        if (bindProducer != null && bindProducer.supportsTypes) {
            bindProducer.addTagHelpersForComponent(shortName, results)
            if (fullyQualified != null) bindProducer.addTagHelpersForComponent(fullyQualified, results)
        }
        for (childContent in shortName.getChildContentProperties()) {
            results += createChildContentDescriptor(shortName, childContent)
            if (fullyQualified != null) results += createChildContentDescriptor(fullyQualified, childContent)
        }
    }

    private fun createNameMatchingDescriptor(type: RTypeDefinition, properties: List<Pair<RProperty, PropertyKind>>, acceptsUnmatchedAttributes: Boolean, fullyQualified: Boolean): TagHelperDescriptor {
        val typeName = typeNameObjectOf(type)
        val builder = TagHelperDescriptorBuilder(TagHelperKind.Component, typeName.fullName!!, type.assemblyName)
        builder.runtimeKind = RuntimeKind.IComponent
        builder.typeNameObject = typeName
        val metadata = ComponentMetadata.Builder()
        metadata.acceptsUnmatchedAttributes = acceptsUnmatchedAttributes
        builder.caseSensitive = true
        if (fullyQualified) {
            val fullName = if (type.namespace.isEmpty()) type.name else "${type.namespace}.${type.name}"
            builder.tagMatchingRule { tagName = fullName }
            builder.isFullyQualifiedNameMatch = true
        } else {
            builder.tagMatchingRule { tagName = type.name }
        }
        if (type.isGenericType) {
            metadata.isGeneric = true
            val cascading = HashSet<String>()
            for (attribute in type.attributes) {
                if (attribute.hasFullName(ComponentsApi.CascadingTypeParameterAttribute.metadataName)) {
                    (attribute.constructorArguments.firstOrNull()?.value as? String)?.let { cascading += it }
                }
            }
            for (parameter in type.typeParameters) createTypeParameterProperty(builder, parameter, parameter.name in cascading)
        }
        if (hasRenderModeDirective(type)) metadata.hasRenderModeDirective = true
        type.documentation?.takeIf { it.isNotEmpty() }?.let { builder.documentation = it }
        for ((property, kind) in properties) {
            if (kind == PropertyKind.Ignored) continue
            createProperty(builder, type, property, kind)
        }
        if (builder.boundAttributes.any { it.isParameterizedChildContent() } &&
            builder.boundAttributes.none { it.name.equals(ComponentHelpers.ChildContent.PARAMETER_ATTRIBUTE_NAME, ignoreCase = true) }
        ) {
            // a parameterized child content gets a 'Context' parameter to name its variable, unless the component declares one itself
            createContextParameter(builder, null)
        }
        builder.metadata = metadata.build()
        return builder.build()
    }

    private fun BoundAttributeDescriptorBuilder.isParameterizedChildContent(): Boolean =
        (metadata as? PropertyMetadata)?.isChildContent == true && typeName != ComponentsApi.RenderFragment.fullTypeName

    private fun acceptsUnmatchedAttributes(properties: List<Pair<RProperty, PropertyKind>>): Boolean {
        var capture: RProperty? = null
        for ((property, kind) in properties) {
            if (kind == PropertyKind.Ignored || !hasCaptureUnmatchedValues(property)) continue
            if (capture != null) return false // a component cannot have two parameters capturing unmatched values
            capture = property
        }
        if (capture == null || !hasStringObjectDictionary) return false
        // the runtime assigns the dictionary by reflection: user-defined conversions do not apply
        return capture.dictionaryAssignable
    }

    private fun hasCaptureUnmatchedValues(property: RProperty): Boolean {
        val parameter = property.attributes.attribute(ComponentsApi.ParameterAttribute.metadataName) ?: return false
        return parameter.namedArguments.any { (name, value) -> name == ComponentsApi.ParameterAttribute.captureUnmatchedValues && value.value == true }
    }

    private fun createProperty(builder: TagHelperDescriptorBuilder, containing: RTypeDefinition, property: RProperty, kind: PropertyKind) {
        builder.bindAttribute {
            name = property.name
            containingType = RazorTypeDisplay.fullName(containing)
            typeName = RazorTypeDisplay.fullName(property.type)
            propertyName = property.name
            isEditorRequired = property.attributes.any { it.hasFullName("Microsoft.AspNetCore.Components.EditorRequiredAttribute") }
            caseSensitive = false
            var isChildContent = false
            var isEventCallback = false
            var isDelegateSignature = false
            var isDelegateWithAwaitableResult = false
            when (kind) {
                PropertyKind.Enum -> isEnum = true
                PropertyKind.ChildContent -> isChildContent = true
                PropertyKind.EventCallback -> isEventCallback = true
                PropertyKind.Delegate -> {
                    isDelegateSignature = true
                    isDelegateWithAwaitableResult = property.isAwaitableDelegate
                }
                else -> {}
            }
            var acceptsAssetPath = false
            if (hasAssetPathAttribute(property)) {
                if (property.type.specialType == "System_String") acceptsAssetPath = true
                else diagnostics += ComponentDiagnosticFactory.createAssetPath_NonStringParameter(null, property.name)
            }
            metadata = PropertyMetadata(
                globallyQualifiedTypeName = RazorTypeDisplay.globallyQualified(property.type), isChildContent = isChildContent, isEventCallback = isEventCallback,
                isDelegateSignature = isDelegateSignature, isDelegateWithAwaitableResult = isDelegateWithAwaitableResult, isGenericTyped = hasTypeParameter(property.type),
                isInitOnlyProperty = property.isInitOnly, acceptsStringLiteral = property.acceptsStringLiteral, acceptsAssetPath = acceptsAssetPath,
            )
            property.documentation?.takeIf { it.isNotEmpty() }?.let { documentation = it }
        }
    }

    private fun hasAssetPathAttribute(property: RProperty): Boolean {
        var current: RProperty? = property
        val seen = HashSet<RProperty>()
        while (current != null && seen.add(current)) {
            if (current.attributes.any { it.hasFullName(ComponentsApi.AssetPathAttribute.metadataName) }) return true
            current = current.overriddenProperty
        }
        return false
    }

    /** `[Parameter] public List<T> P` (the containing class' type parameter, also nested: `RenderFragment<List<T>>`, `T[]`, `Outer<T>.Inner`). */
    private fun hasTypeParameter(type: RType): Boolean = when (type) {
        is RTypeParameterRef -> true
        is RNamed -> type.isGenericType && (type.typeArguments.any(::hasTypeParameter) || type.containingType?.let(::hasTypeParameter) == true)
        is RError -> type.typeArguments.any(::hasTypeParameter) // an unbound Func<T, string> is still generic
        is RArray -> hasTypeParameter(type.elementType)
        else -> false
    }

    private fun createTypeParameterProperty(builder: TagHelperDescriptorBuilder, typeParameter: RTypeParameter, cascade: Boolean) {
        builder.bindAttribute {
            displayName = typeParameter.name
            name = typeParameter.name
            typeName = "System.Type"
            propertyName = typeParameter.name
            val constraints = ArrayList<String>()
            // CS0449: class, struct, unmanaged, notnull and default come first; CS0401: new() last
            if (typeParameter.hasReferenceTypeConstraint) constraints += "class"
            if (typeParameter.hasNotNullConstraint) constraints += "notnull"
            if (typeParameter.hasUnmanagedTypeConstraint) constraints += "unmanaged"
            else if (typeParameter.hasValueTypeConstraint) constraints += "struct"
            for (constraint in typeParameter.constraintTypes) constraints += RazorTypeDisplay.globallyQualified(constraint)
            if (typeParameter.hasConstructorConstraint) constraints += "new()"
            val whereClause = if (constraints.isEmpty()) null else "where ${typeParameter.name} : ${constraints.joinToString(", ")}"
            // the attributes propagated to the type inference method
            val withAttributes = StringBuilder()
            for (attribute in typeParameter.attributes) {
                if (!attribute.hasFullName("System.Diagnostics.CodeAnalysis.DynamicallyAccessedMembersAttribute")) continue
                withAttributes.append(if (withAttributes.isNotEmpty()) ", " else "[")
                withAttributes.append(RazorTypeDisplay.globallyQualified(attribute.attributeClass.asType())).append('(')
                for ((i, argument) in attribute.constructorArguments.withIndex()) {
                    if (i > 0) withAttributes.append(", ")
                    if (argument.kind == RConstantKind.Enum) {
                        withAttributes.append("unchecked((").append(argument.type?.let(RazorTypeDisplay::globallyQualified)).append(')').append(hex(argument.value)).append(')')
                    } else {
                        withAttributes.append(argument.value)
                    }
                }
                withAttributes.append(')')
            }
            val nameWithAttributes = if (withAttributes.isEmpty()) null else withAttributes.append("] ").append(typeParameter.name).toString()
            metadata = TypeParameterMetadata(isCascading = cascade, constraints = whereClause, nameWithAttributes = nameWithAttributes)
            setDocumentation(DocumentationDescriptor.from(DocumentationId.ComponentTypeParameter, typeParameter.name, builder.name))
        }
    }

    /** `SymbolDisplay.FormatPrimitive(value, quoteStrings: true, useHexadecimalNumbers: true)` of an enum's value (an int: eight digits). */
    private fun hex(value: Any?): String {
        val number = (value as? Number)?.toLong() ?: return value.toString()
        return if (number in Int.MIN_VALUE..Int.MAX_VALUE.toLong()) "0x%08x".format(number.toInt()) else "0x%016x".format(number)
    }

    private fun createChildContentDescriptor(component: TagHelperDescriptor, attribute: BoundAttributeDescriptor): TagHelperDescriptor {
        val builder = TagHelperDescriptorBuilder(TagHelperKind.ChildContent, component.typeName + "." + attribute.name, component.assemblyName)
        builder.setTypeName(component.typeName + "." + attribute.name, component.typeNamespace, component.typeNameIdentifier)
        builder.caseSensitive = true
        attribute.documentation?.takeIf { it.isNotEmpty() }?.let { builder.documentation = it }
        // child content matches the property name, only as a direct child of the component
        builder.tagMatchingRule {
            tagName = attribute.name
            parentTag = component.tagMatchingRules[0].tagName
        }
        if (attribute.isParameterizedChildContentProperty()) createContextParameter(builder, attribute.name)
        if (component.isFullyQualifiedNameMatch) builder.isFullyQualifiedNameMatch = true
        return builder.build()
    }

    private fun createContextParameter(builder: TagHelperDescriptorBuilder, childContentName: String?) {
        builder.bindAttribute {
            name = ComponentHelpers.ChildContent.PARAMETER_ATTRIBUTE_NAME
            typeName = "System.String"
            propertyName = name
            metadata = ChildContentParameterMetadata.DEFAULT
            setDocumentation(
                if (childContentName == null) DocumentationDescriptor.ChildContentParameterName_TopLevel
                else DocumentationDescriptor.from(DocumentationId.ChildContentParameterName, childContentName),
            )
        }
    }

    private enum class PropertyKind { Ignored, Default, Enum, ChildContent, Delegate, EventCallback }

    /**
     * The parameters: up the base classes (stopping at `ComponentBase`), by name — the public properties with `[Parameter]` and a public
     * setter, not indexers, not static, not hidden by a property of the same name lower down; an override without `[Parameter]` passes
     * through to the base class.
     */
    private fun getProperties(type: RTypeDefinition): List<Pair<RProperty, PropertyKind>> {
        val names = HashSet<String>()
        val results = ArrayList<Pair<RProperty, PropertyKind>>()
        for ((current, substitution) in baseChain(type)) {
            if (current.hasFullName(ComponentsApi.ComponentBase.metadataName)) break // ComponentBase has no [Parameter], nor has object
            for (declared in current.properties) {
                if (declared.name in names) continue
                val property = substitution.apply(declared)
                var kind = PropertyKind.Default
                if (property.accessibility != RAccessibility.Public || property.parameterCount != 0 || property.setter == null ||
                    property.setter != RAccessibility.Public || property.isStatic
                ) {
                    if (!property.isOverride) {
                        names += property.name
                        results += property to PropertyKind.Ignored
                        continue
                    }
                    kind = PropertyKind.Ignored
                }
                if (property.attributes.none { it.hasFullName(ComponentsApi.ParameterAttribute.metadataName) }) {
                    if (property.isOverride) continue // an override without [Parameter]: the base class decides
                    kind = PropertyKind.Ignored
                }
                if (kind == PropertyKind.Default) {
                    kind = when {
                        property.type.typeKind == RTypeKind.Enum -> PropertyKind.Enum
                        isRenderFragment(property.type) -> PropertyKind.ChildContent
                        isEventCallback(property.type) -> PropertyKind.EventCallback
                        property.type.typeKind == RTypeKind.Delegate -> PropertyKind.Delegate
                        else -> PropertyKind.Default
                    }
                }
                names += property.name
                results += property to kind
            }
        }
        return results
    }

    private fun isRenderFragment(type: RType): Boolean = type.hasFullName(ComponentsApi.RenderFragment.metadataName) ||
        (type is RNamed && type.isGenericType && type.definition.hasFullName(ComponentsApi.RenderFragmentOfT.displayName))

    private fun isEventCallback(type: RType): Boolean = type.hasFullName(ComponentsApi.EventCallback.metadataName) ||
        (type is RNamed && type.isGenericType && type.definition.hasFullName(ComponentsApi.EventCallbackOfT.displayName))

    private fun hasRenderModeDirective(type: RTypeDefinition): Boolean = type.attributes.any { attribute ->
        var current: RTypeDefinition? = attribute.attributeClass
        val seen = HashSet<String>()
        var found = false
        while (current != null && seen.add(current.key)) {
            if (current.hasFullName(ComponentsApi.RenderModeAttribute.fullTypeName)) {
                found = true
                break
            }
            current = current.baseType?.definition
        }
        found
    }

    companion object {
        fun create(compilation: RCompilation, bindProducer: BindTagHelperProducer?): TagHelperProducer = ComponentTagHelperProducer(compilation, bindProducer)
    }
}

// ---- @bind

/** `BindTagHelperProducer`: the fallback `@bind-...`, the elements of `[BindElement]` / `[BindInputElement]` on `BindAttributes`, the components' `X` / `XChanged` pairs. */
class BindTagHelperProducer private constructor(
    private val bindConverter: RTypeDefinition, private val bindElementAttribute: RTypeDefinition?, private val bindInputElementAttribute: RTypeDefinition?,
) : TagHelperProducer() {
    override val supportsStaticTagHelpers: Boolean get() = true

    override fun addStaticTagHelpers(assembly: RAssembly, results: MutableList<TagHelperDescriptor>) {
        if (assembly.name != bindConverter.assemblyName) return
        results += FALLBACK
    }

    override val supportsTypes: Boolean get() = bindElementAttribute != null && bindInputElementAttribute != null

    override fun isCandidateType(type: RTypeDefinition): Boolean = type.accessibility == RAccessibility.Public && type.name == "BindAttributes"

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        val typeName = RazorTypeDisplay.defaultDisplay(type)
        for (attribute in type.attributes) {
            val arguments = attribute.constructorArguments
            fun arg(i: Int) = arguments[i].value as String?
            val tagHelper = when {
                arguments.size == 4 && attribute.attributeClass.key == bindElementAttribute?.key ->
                    createElementBindTagHelper(typeName, type.namespace, type.name, arg(0), null, arg(1), arg(2), arg(3))
                arguments.size == 4 && attribute.attributeClass.key == bindInputElementAttribute?.key ->
                    createElementBindTagHelper(typeName, type.namespace, type.name, "input", arg(0), arg(1), arg(2), arg(3))
                arguments.size == 6 && attribute.attributeClass.key == bindInputElementAttribute?.key ->
                    createElementBindTagHelper(typeName, type.namespace, type.name, "input", arg(0), arg(1), arg(2), arg(3), arguments[4].value as Boolean? ?: false, arg(5))
                else -> null
            }
            if (tagHelper != null) results += tagHelper
        }
    }

    private fun createElementBindTagHelper(
        typeName: String, typeNamespace: String, typeNameIdentifier: String, element: String?, typeAttribute: String?, suffixArgument: String?,
        valueAttribute: String?, changeAttribute: String?, isInvariantCulture: Boolean = false, format: String? = null,
    ): TagHelperDescriptor {
        val name: String
        val attributeName: String
        val formatName: String
        val formatAttributeName: String
        val eventName: String
        if (suffixArgument != null) {
            name = "Bind_$suffixArgument"
            attributeName = "@bind-$suffixArgument"
            formatName = "Format_$suffixArgument"
            formatAttributeName = "format-$suffixArgument"
            eventName = "Event_$suffixArgument"
        } else {
            name = "Bind"
            attributeName = "@bind"
            formatName = "Format_$valueAttribute"
            formatAttributeName = "format-$valueAttribute"
            eventName = "Event_$valueAttribute"
        }
        val builder = TagHelperDescriptorBuilder(TagHelperKind.Bind, name, ComponentsApi.assemblyName)
        builder.setTypeName(typeName, typeNamespace, typeNameIdentifier)
        builder.caseSensitive = true
        builder.classifyAttributesOnly = true
        builder.setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Element, valueAttribute, changeAttribute))
        builder.metadata = BindMetadata(valueAttribute = valueAttribute, changeAttribute = changeAttribute, isInvariantCulture = isInvariantCulture, format = format, typeAttribute = typeAttribute)
        fun TagMatchingRuleDescriptorBuilder.typeRule() {
            if (typeAttribute != null) attribute {
                this.name = "type"
                nameComparison = RequiredAttributeNameComparison.FullMatch
                value = typeAttribute
                valueComparison = RequiredAttributeValueComparison.FullMatch
            }
        }
        builder.tagMatchingRule {
            tagName = element
            typeRule()
            attribute {
                this.name = attributeName
                nameComparison = RequiredAttributeNameComparison.FullMatch
                isDirectiveAttribute = true
            }
        }
        builder.tagMatchingRule {
            tagName = element
            typeRule()
            attribute {
                this.name = "$attributeName:get"
                nameComparison = RequiredAttributeNameComparison.FullMatch
                isDirectiveAttribute = true
            }
            attribute {
                this.name = "$attributeName:set"
                nameComparison = RequiredAttributeNameComparison.FullMatch
                isDirectiveAttribute = true
            }
        }
        builder.bindAttribute {
            setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Element, valueAttribute, changeAttribute))
            this.name = attributeName
            this.typeName = "System.Object"
            isDirectiveAttribute = true
            propertyName = name
            bindAttributeParameter {
                this.name = "format"
                propertyName = formatName
                this.typeName = "System.String"
                setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Element_Format, attributeName))
            }
            bindAttributeParameter {
                this.name = "event"
                propertyName = eventName
                this.typeName = "System.String"
                setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Element_Event, attributeName))
            }
            cultureGetSetAfter(this)
        }
        builder.bindAttribute {
            this.name = formatAttributeName
            this.typeName = "System.String"
            setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Element_Format, attributeName))
            propertyName = formatName
        }
        return builder.build()
    }

    fun addTagHelpersForComponent(tagHelper: TagHelperDescriptor, results: MutableList<TagHelperDescriptor>) {
        if (tagHelper.kind != TagHelperKind.Component || !supportsTypes) return
        for (changeAttribute in tagHelper.boundAttributes) {
            if (!changeAttribute.name.endsWith("Changed") || (!changeAttribute.isDelegateProperty() && !changeAttribute.isEventCallbackProperty())) continue
            val valueAttributeName = changeAttribute.name.removeSuffix("Changed")
            val expressionAttributeName = valueAttributeName + "Expression"
            var valueAttribute: BoundAttributeDescriptor? = null
            var expressionAttribute: BoundAttributeDescriptor? = null
            for (attribute in tagHelper.boundAttributes) {
                if (attribute.name == valueAttributeName) valueAttribute = attribute
                if (attribute.name == expressionAttributeName) expressionAttribute = attribute
                if (valueAttribute != null && expressionAttribute != null) break
            }
            if (valueAttribute == null) continue
            val builder = TagHelperDescriptorBuilder(TagHelperKind.Bind, tagHelper.name, tagHelper.assemblyName)
            builder.typeNameObject = tagHelper.typeNameObject
            builder.displayName = tagHelper.displayName
            builder.caseSensitive = true
            builder.setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Component, valueAttribute.name, changeAttribute.name))
            val tagName = tagHelper.tagMatchingRules.single().tagName
            builder.tagMatchingRule {
                this.tagName = tagName
                attribute {
                    name = "@bind-" + valueAttribute.name
                    nameComparison = RequiredAttributeNameComparison.FullMatch
                    isDirectiveAttribute = true
                }
            }
            builder.tagMatchingRule {
                this.tagName = tagName
                attribute {
                    name = "@bind-" + valueAttribute.name + ":get"
                    nameComparison = RequiredAttributeNameComparison.FullMatch
                    isDirectiveAttribute = true
                }
                attribute {
                    name = "@bind-" + valueAttribute.name + ":set"
                    nameComparison = RequiredAttributeNameComparison.FullMatch
                    isDirectiveAttribute = true
                }
            }
            builder.bindAttribute {
                setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Component, valueAttribute.name, changeAttribute.name))
                name = "@bind-" + valueAttribute.name
                typeName = changeAttribute.typeName
                isEnum = valueAttribute.isEnum
                containingType = valueAttribute.containingType
                isDirectiveAttribute = true
                propertyName = valueAttribute.propertyName
                getSetAfter(this)
            }
            if (tagHelper.isFullyQualifiedNameMatch) builder.isFullyQualifiedNameMatch = true
            builder.metadata = BindMetadata(valueAttribute = valueAttribute.name, changeAttribute = changeAttribute.name, expressionAttribute = expressionAttribute?.name)
            results += builder.build()
        }
    }

    companion object {
        fun create(compilation: RCompilation): BindTagHelperProducer? {
            val bindConverter = compilation.typeByMetadataName(ComponentsApi.BindConverter.fullTypeName) ?: return null
            return BindTagHelperProducer(
                bindConverter, compilation.typeByMetadataName(ComponentsApi.BindElementAttribute.fullTypeName),
                compilation.typeByMetadataName(ComponentsApi.BindInputElementAttribute.fullTypeName),
            )
        }

        private fun cultureGetSetAfter(attribute: BoundAttributeDescriptorBuilder) {
            attribute.bindAttributeParameter {
                name = "culture"
                propertyName = "Culture"
                typeName = "System.Globalization.CultureInfo"
                setDocumentation(DocumentationDescriptor.BindTagHelper_Element_Culture)
            }
            getSetAfter(attribute)
        }

        private fun getSetAfter(attribute: BoundAttributeDescriptorBuilder) {
            attribute.bindAttributeParameter {
                name = "get"
                propertyName = "Get"
                typeName = "System.Object"
                setDocumentation(DocumentationDescriptor.BindTagHelper_Element_Get)
                bindAttributeGetSet = true
            }
            attribute.bindAttributeParameter {
                name = "set"
                propertyName = "Set"
                typeName = "System.Delegate"
                setDocumentation(DocumentationDescriptor.BindTagHelper_Element_Set)
            }
            attribute.bindAttributeParameter {
                name = "after"
                propertyName = "After"
                typeName = "System.Delegate"
                setDocumentation(DocumentationDescriptor.BindTagHelper_Element_After)
            }
        }

        private val FALLBACK: TagHelperDescriptor by lazy {
            val builder = TagHelperDescriptorBuilder(TagHelperKind.Bind, "Bind", ComponentsApi.assemblyName)
            builder.setTypeName("Microsoft.AspNetCore.Components.Bind", "Microsoft.AspNetCore.Components", "Bind")
            builder.caseSensitive = true
            builder.classifyAttributesOnly = true
            builder.setDocumentation(DocumentationDescriptor.BindTagHelper_Fallback)
            builder.metadata = BindMetadata(isFallback = true)
            builder.tagMatchingRule {
                tagName = "*"
                attribute {
                    name = "@bind-"
                    nameComparison = RequiredAttributeNameComparison.PrefixMatch
                    isDirectiveAttribute = true
                }
            }
            builder.bindAttribute {
                setDocumentation(DocumentationDescriptor.BindTagHelper_Fallback)
                val attributeName = "@bind-..."
                name = attributeName
                asDictionary("@bind-", "System.Object")
                isDirectiveAttribute = true
                propertyName = "Bind"
                typeName = "System.Collections.Generic.Dictionary<string, object>"
                bindAttributeParameter {
                    name = "format"
                    propertyName = "Format"
                    typeName = "System.String"
                    setDocumentation(DocumentationDescriptor.BindTagHelper_Fallback_Format)
                }
                bindAttributeParameter {
                    name = "event"
                    propertyName = "Event"
                    typeName = "System.String"
                    setDocumentation(DocumentationDescriptor.from(DocumentationId.BindTagHelper_Fallback_Event, attributeName))
                }
                cultureGetSetAfter(this)
            }
            builder.build()
        }
    }
}

// ---- event handlers

/** `EventHandlerTagHelperProducer`: `@onclick` and the rest, from the `[EventHandler]` attributes of a public `EventHandlers` class. */
class EventHandlerTagHelperProducer private constructor(private val eventHandlerAttribute: RTypeDefinition) : TagHelperProducer() {
    override val supportsTypes: Boolean get() = true

    override fun isCandidateType(type: RTypeDefinition): Boolean = type.accessibility == RAccessibility.Public && type.name == "EventHandlers"

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        for (attribute in type.attributes) {
            if (attribute.attributeClass.key != eventHandlerAttribute.key) continue
            val arguments = attribute.constructorArguments
            val attributeName = arguments.getOrNull(0)?.value as? String
            val eventArgsType = arguments.getOrNull(1)?.value as? RNamed
            if (attributeName == null || eventArgsType == null) continue
            // the four-argument constructor: (attributeName, eventArgsType, enableStopPropagation, enablePreventDefault) — read swapped, as the compiler does (dotnet/razor#10497)
            val (stopPropagation, preventDefault) = when {
                arguments.size == 2 -> false to false
                arguments.size == 4 && arguments[2].value is Boolean && arguments[3].value is Boolean -> (arguments[3].value as Boolean) to (arguments[2].value as Boolean)
                else -> continue
            }
            results += createTagHelper(RazorTypeDisplay.defaultDisplay(type), type.namespace, type.name, attributeName, eventArgsType, stopPropagation, preventDefault)
        }
    }

    private fun createTagHelper(
        typeName: String, typeNamespace: String, typeNameIdentifier: String, attribute: String, eventArgsType: RNamed, enableStopPropagation: Boolean, enablePreventDefault: Boolean,
    ): TagHelperDescriptor {
        val attributeName = "@$attribute"
        val eventArgType = RazorTypeDisplay.defaultDisplay(eventArgsType)
        val builder = TagHelperDescriptorBuilder(TagHelperKind.EventHandler, attribute, ComponentsApi.assemblyName)
        builder.setTypeName(typeName, typeNamespace, typeNameIdentifier)
        builder.caseSensitive = true
        builder.classifyAttributesOnly = true
        builder.setDocumentation(DocumentationDescriptor.from(DocumentationId.EventHandlerTagHelper, attributeName, eventArgType))
        builder.metadata = EventHandlerMetadata(eventArgType)
        fun rule(name: String) = builder.tagMatchingRule {
            tagName = "*"
            attribute {
                this.name = name
                nameComparison = RequiredAttributeNameComparison.FullMatch
                isDirectiveAttribute = true
            }
        }
        rule(attributeName)
        if (enablePreventDefault) rule("$attributeName:preventDefault")
        if (enableStopPropagation) rule("$attributeName:stopPropagation")
        builder.bindAttribute {
            setDocumentation(DocumentationDescriptor.from(DocumentationId.EventHandlerTagHelper, attributeName, eventArgType))
            name = attributeName
            // event handler directive attributes default to C#
            this.typeName = "Microsoft.AspNetCore.Components.EventCallback<$eventArgType>"
            propertyName = attribute
            isDirectiveAttribute = true
            // weakly typed: delegates have their own type checking
            isWeaklyTyped = true
            if (enablePreventDefault) bindAttributeParameter {
                name = "preventDefault"
                propertyName = "PreventDefault"
                this.typeName = "System.Boolean"
                setDocumentation(DocumentationDescriptor.from(DocumentationId.EventHandlerTagHelper_PreventDefault, attributeName))
            }
            if (enableStopPropagation) bindAttributeParameter {
                name = "stopPropagation"
                propertyName = "StopPropagation"
                this.typeName = "System.Boolean"
                setDocumentation(DocumentationDescriptor.from(DocumentationId.EventHandlerTagHelper_StopPropagation, attributeName))
            }
        }
        return builder.build()
    }

    companion object {
        fun create(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.EventHandlerAttribute.fullTypeName)?.let(::EventHandlerTagHelperProducer)
    }
}

// ---- @ref, @key, @attributes, @rendermode, @formname: one descriptor in the assembly of a known type

/** The producers of one directive attribute added to the assembly that declares [anchor] (`Ref`, `Key`, `Splat`, `RenderMode`, `FormName`). */
class StaticTagHelperProducer private constructor(private val anchor: RTypeDefinition, private val alsoAssembly: String?, private val descriptor: TagHelperDescriptor) : TagHelperProducer() {
    override val supportsStaticTagHelpers: Boolean get() = true

    override fun addStaticTagHelpers(assembly: RAssembly, results: MutableList<TagHelperDescriptor>) {
        if (assembly.name != anchor.assemblyName && assembly.name != alsoAssembly) return
        results += descriptor
    }

    companion object {
        private fun directive(
            kind: TagHelperKind, name: String, attributeName: String, attributeTypeName: String, documentation: DocumentationDescriptor, propertyName: String = name,
        ): TagHelperDescriptor {
            val builder = TagHelperDescriptorBuilder(kind, name, ComponentsApi.assemblyName)
            builder.setTypeName("Microsoft.AspNetCore.Components.$name", "Microsoft.AspNetCore.Components", name)
            builder.caseSensitive = true
            builder.classifyAttributesOnly = true
            builder.setDocumentation(documentation)
            builder.tagMatchingRule {
                tagName = "*"
                attribute {
                    this.name = attributeName
                    isDirectiveAttribute = true
                }
            }
            builder.bindAttribute {
                setDocumentation(documentation)
                this.name = attributeName
                typeName = attributeTypeName
                isDirectiveAttribute = true
                this.propertyName = propertyName
            }
            return builder.build()
        }

        private val REF by lazy { directive(TagHelperKind.Ref, "Ref", "@ref", "System.Object", DocumentationDescriptor.RefTagHelper) }
        private val KEY by lazy { directive(TagHelperKind.Key, "Key", "@key", "System.Object", DocumentationDescriptor.KeyTagHelper) }
        private val SPLAT by lazy {
            directive(TagHelperKind.Splat, "Attributes", "@attributes", "System.Object", DocumentationDescriptor.SplatTagHelper)
        }
        private val RENDER_MODE by lazy {
            directive(TagHelperKind.RenderMode, "RenderMode", "@rendermode", ComponentsApi.IComponentRenderMode.fullTypeName, DocumentationDescriptor.RenderModeTagHelper)
        }
        private val FORM_NAME by lazy { directive(TagHelperKind.FormName, "FormName", "@formname", "System.String", DocumentationDescriptor.FormNameTagHelper) }

        /** `RefTagHelperProducer`: in the assembly of `ElementReference`. */
        fun ref(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.ElementReference.fullTypeName)?.let { StaticTagHelperProducer(it, null, REF) }

        /** `KeyTagHelperProducer`: in the assembly of `RenderTreeBuilder`. */
        fun key(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.RenderTreeBuilder.fullTypeName)?.let { StaticTagHelperProducer(it, null, KEY) }

        /** `SplatTagHelperProducer`: in the assembly of `RenderTreeBuilder`. */
        fun splat(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.RenderTreeBuilder.fullTypeName)?.let { StaticTagHelperProducer(it, null, SPLAT) }

        /** `RenderModeTagHelperProducer`: in the assembly of `IComponentRenderMode`. */
        fun renderMode(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.IComponentRenderMode.fullTypeName)?.let { StaticTagHelperProducer(it, null, RENDER_MODE) }

        /** `FormNameTagHelperProducer`: the one public `RenderTreeBuilder` with a public `AddNamedEvent`; in its assembly and in Components'. */
        fun formName(compilation: RCompilation): TagHelperProducer? {
            val builders = compilation.typesByMetadataName(ComponentsApi.RenderTreeBuilder.fullTypeName).filter { type ->
                type.accessibility == RAccessibility.Public && type.methods.any { it.name == ComponentsApi.RenderTreeBuilder.addNamedEvent && it.accessibility == RAccessibility.Public }
            }.take(2)
            return builders.singleOrNull()?.let { StaticTagHelperProducer(it, ComponentsApi.assemblyName, FORM_NAME) }
        }
    }
}

// ---- [AcceptsAssetPath]

/** `AcceptsAssetPathTagHelperProducer`: the `[AcceptsAssetPath(element, attribute)]` of a public `AssetPathAttributes` class. */
class AcceptsAssetPathTagHelperProducer private constructor(private val acceptsAssetPathAttribute: RTypeDefinition) : TagHelperProducer() {
    override val supportsTypes: Boolean get() = true

    override fun isCandidateType(type: RTypeDefinition): Boolean =
        type.accessibility == RAccessibility.Public && type.name == ComponentsApi.AcceptsAssetPathAttribute.candidateTypeName

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        val typeName = RazorTypeDisplay.defaultDisplay(type)
        for (attribute in type.attributes) {
            if (attribute.attributeClass.key != acceptsAssetPathAttribute.key) continue
            val arguments = attribute.constructorArguments
            val element = arguments.getOrNull(0)?.value as? String
            val attributeName = arguments.getOrNull(1)?.value as? String
            if (arguments.size != 2 || element == null || attributeName == null) continue
            val builder = TagHelperDescriptorBuilder(TagHelperKind.AssetPath, "$element[$attributeName]", ComponentsApi.assemblyName)
            builder.setTypeName(typeName, type.namespace, type.name)
            builder.caseSensitive = true
            builder.metadata = AssetPathMetadata(element, attributeName)
            results += builder.build()
        }
    }

    companion object {
        fun create(compilation: RCompilation): TagHelperProducer? =
            compilation.typeByMetadataName(ComponentsApi.AcceptsAssetPathAttribute.metadataName)?.let(::AcceptsAssetPathTagHelperProducer)
    }
}

// ---- MVC tag helpers

/** `DefaultTagHelperProducer` + `DefaultTagHelperDescriptorFactory`: the public non-abstract non-generic `ITagHelper` types. */
class DefaultTagHelperProducer private constructor(private val iTagHelper: RTypeDefinition, private val includeDocumentation: Boolean, private val excludeHidden: Boolean) :
    TagHelperProducer() {
    override val supportsTypes: Boolean get() = true

    override fun isCandidateType(type: RTypeDefinition): Boolean = type.kind != RTypeKind.Error && type.accessibility == RAccessibility.Public && !type.isAbstract &&
        !type.isGenericType && type.allInterfaces.any { it.definition.key == iTagHelper.key }

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        createDescriptor(type)?.let { results += it }
    }

    private fun createDescriptor(type: RTypeDefinition): TagHelperDescriptor? {
        if (shouldSkip(type.attributes)) return null
        val typeName = typeNameObjectOf(type)
        val builder = TagHelperDescriptorBuilder(TagHelperKind.ITagHelper, typeName.fullName!!, type.assemblyName)
        builder.typeNameObject = typeName
        builder.runtimeKind = RuntimeKind.ITagHelper
        addBoundAttributes(type, builder)
        addTagMatchingRules(type, builder)
        addAllowedChildren(type, builder)
        if (includeDocumentation) type.documentation?.takeIf { it.isNotEmpty() }?.let { builder.documentation = it }
        type.attributes.attribute(TagHelperTypes.OUTPUT_ELEMENT_HINT_ATTRIBUTE)?.constructorArguments?.firstOrNull()?.let { (it.value as? String)?.let { v -> builder.tagOutputHint = v } }
        return builder.build()
    }

    /** `[EditorBrowsable(EditorBrowsableState.Never)]` when hidden ones are excluded. */
    private fun shouldSkip(attributes: List<RAttribute>): Boolean {
        if (!excludeHidden) return false
        val editorBrowsable = attributes.attribute("System.ComponentModel.EditorBrowsableAttribute") ?: return false
        return (editorBrowsable.constructorArguments.firstOrNull()?.value as? Number)?.toInt() == 1
    }

    private fun addTagMatchingRules(type: RTypeDefinition, builder: TagHelperDescriptorBuilder) {
        val targets = type.attributes.filter { it.hasFullName(TagHelperTypes.HTML_TARGET_ELEMENT_ATTRIBUTE) }
        if (targets.isEmpty()) {
            var name = type.name
            if (name.endsWith(TAG_HELPER_NAME_ENDING, ignoreCase = true)) name = name.substring(0, name.length - TAG_HELPER_NAME_ENDING.length)
            builder.tagMatchingRule { tagName = HtmlConventions.toHtmlCase(name) }
            return
        }
        for (target in targets) {
            builder.tagMatchingRule {
                tagName = target.constructorArguments.firstOrNull()?.value as? String ?: TagHelperMatchingConventions.ELEMENT_CATCH_ALL_NAME
                parentTag = target.named("ParentTag")?.value as String?
                tagStructure = (target.named("TagStructure")?.value as? Number)?.let { TagStructure.entries[it.toInt()] } ?: TagStructure.Unspecified
                (target.named("Attributes")?.value as String?)?.let { RequiredAttributeParser.addRequiredAttributes(it, this) }
            }
        }
    }

    private fun RAttribute.named(name: String): RConstant? = namedArguments.firstOrNull { it.first == name }?.second

    private fun addBoundAttributes(type: RTypeDefinition, builder: TagHelperDescriptorBuilder) {
        for (property in accessibleProperties(type)) {
            if (shouldSkip(property.attributes)) continue
            builder.bindAttribute { configureBoundAttribute(this, property, type) }
        }
    }

    private fun addAllowedChildren(type: RTypeDefinition, builder: TagHelperDescriptorBuilder) {
        val restrict = type.attributes.attribute(TagHelperTypes.RESTRICT_CHILDREN_ATTRIBUTE) ?: return
        val arguments = restrict.constructorArguments
        val first = arguments.firstOrNull() ?: return
        builder.allowChildTag { name = first.value as String? }
        arguments.getOrNull(1)?.let { rest -> for (value in rest.values) builder.allowChildTag { name = value.value as String? } }
    }

    private fun configureBoundAttribute(builder: BoundAttributeDescriptorBuilder, property: RProperty, containingType: RTypeDefinition) {
        val attributeNameAttribute = property.attributes.attribute(TagHelperTypes.HTML_ATTRIBUTE_NAME_ATTRIBUTE)
        val explicit = (attributeNameAttribute?.constructorArguments?.firstOrNull()?.value as? String)?.takeIf { it.isNotEmpty() }
        val hasExplicitName = explicit != null
        val attributeName = explicit ?: HtmlConventions.toHtmlCase(property.name)
        builder.typeName = RazorTypeDisplay.fullName(property.type)
        builder.propertyName = property.name
        if (property.acceptsStringLiteral) builder.metadata = PropertyMetadata(acceptsStringLiteral = true)
        val hasPublicSetter = property.setter == RAccessibility.Public
        if (hasPublicSetter) {
            builder.name = attributeName
            if (property.type.typeKind == RTypeKind.Enum) builder.isEnum = true
            if (includeDocumentation) property.documentation?.takeIf { it.isNotEmpty() }?.let { builder.documentation = it }
        } else if (hasExplicitName && !isPotentialDictionaryProperty(property)) {
            builder.diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidAttributeNameNullOrEmpty(RazorTypeDisplay.fullName(containingType), property.name)
        }
        configureDictionaryBoundAttribute(builder, property, containingType, attributeNameAttribute, attributeName, hasPublicSetter)
    }

    private fun configureDictionaryBoundAttribute(
        builder: BoundAttributeDescriptorBuilder, property: RProperty, containingType: RTypeDefinition, attributeNameAttribute: RAttribute?, attributeName: String, hasPublicSetter: Boolean,
    ) {
        var prefix: String? = null
        var prefixSet = false
        if (attributeNameAttribute != null) {
            for ((name, argument) in attributeNameAttribute.namedArguments) {
                if (name == "DictionaryAttributePrefix") {
                    prefix = argument.value as String?
                    prefixSet = true
                    break
                }
            }
        }
        val arguments = property.dictionaryArguments.orEmpty()
        if (arguments.isNotEmpty()) {
            val dictionaryPrefix = if (attributeNameAttribute == null || !prefixSet) "$attributeName-" else prefix
            if (dictionaryPrefix != null) builder.asDictionary(dictionaryPrefix, RazorTypeDisplay.fullName(arguments[1]))
        }
        if (arguments.firstOrNull()?.specialType != "System_String") {
            if (prefix != null) builder.diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidAttributePrefixNotNull(RazorTypeDisplay.fullName(containingType), property.name)
            return
        }
        if (!hasPublicSetter && attributeNameAttribute != null && !prefixSet) {
            builder.diagnostics += TagHelperDescriptorDiagnostics.tagHelper_InvalidAttributePrefixNull(RazorTypeDisplay.fullName(containingType), property.name)
        }
    }

    private fun isPotentialDictionaryProperty(property: RProperty): Boolean = property.dictionaryArguments?.firstOrNull()?.specialType == "System_String"

    /** Up the base classes by name: public getters, not indexers, not `[HtmlAttributeNotBound]`; with `[HtmlAttributeName]`, a public setter or a string dictionary. */
    private fun accessibleProperties(type: RTypeDefinition): List<RProperty> {
        val names = HashSet<String>()
        val result = ArrayList<RProperty>()
        for ((current, substitution) in baseChain(type)) {
            for (declared in current.properties) {
                val property = substitution.apply(declared)
                if (!isAccessible(property) || !names.add(property.name)) continue
                result += property
            }
        }
        return result
    }

    private fun isAccessible(property: RProperty): Boolean {
        if (property.getter != RAccessibility.Public || property.parameterCount != 0) return false
        var hasName = false
        for (attribute in property.attributes) {
            if (attribute.hasFullName(TagHelperTypes.HTML_ATTRIBUTE_NOT_BOUND_ATTRIBUTE)) return false
            if (!hasName && attribute.hasFullName(TagHelperTypes.HTML_ATTRIBUTE_NAME_ATTRIBUTE)) hasName = true
        }
        return hasName || property.setter == RAccessibility.Public || isPotentialDictionaryProperty(property)
    }

    companion object {
        private const val TAG_HELPER_NAME_ENDING = "TagHelper"

        fun create(compilation: RCompilation, includeDocumentation: Boolean, excludeHidden: Boolean): TagHelperProducer? {
            val iTagHelper = compilation.typeByMetadataName(TagHelperTypes.I_TAG_HELPER) ?: return null
            if (iTagHelper.kind == RTypeKind.Error) return null
            return DefaultTagHelperProducer(iTagHelper, includeDocumentation, excludeHidden)
        }
    }
}

// ---- view components

/** `ViewComponentTagHelperProducer` + `ViewComponentTagHelperDescriptorFactory` (MVC): `<vc:name>` of every view component. */
class ViewComponentTagHelperProducer private constructor(
    private val viewComponentAttribute: RTypeDefinition, private val nonViewComponentAttribute: RTypeDefinition?, private val task: RTypeDefinition?,
    private val genericTask: RTypeDefinition?, private val iDictionary: RTypeDefinition?,
) : TagHelperProducer() {
    override val supportsTypes: Boolean get() = true
    override val supportsNestedTypes: Boolean get() = true

    override fun isCandidateType(type: RTypeDefinition): Boolean {
        if (type.accessibility != RAccessibility.Public || type.isAbstract || type.isGenericType || attributeIsDefined(type, nonViewComponentAttribute)) return false
        return type.name.endsWith(ViewComponentTypes.viewComponentSuffix) || attributeIsDefined(type, viewComponentAttribute)
    }

    private fun attributeIsDefined(type: RTypeDefinition, attribute: RTypeDefinition?): Boolean {
        if (attribute == null) return false
        return baseChain(type).any { (current, _) -> current.attributes.any { it.attributeClass.key == attribute.key } }
    }

    override fun addTagHelpersForType(type: RTypeDefinition, results: MutableList<TagHelperDescriptor>) {
        val shortName = shortName(type)
        val tagName = "vc:" + HtmlConventions.toHtmlCase(shortName)
        val typeName = "__Generated__${shortName}ViewComponentTagHelper"
        val displayName = shortName + "ViewComponentTagHelper"
        val builder = TagHelperDescriptorBuilder(TagHelperKind.ViewComponent, typeName, type.assemblyName)
        builder.runtimeKind = RuntimeKind.ITagHelper
        builder.setTypeName(typeName, null, null)
        builder.metadata = ViewComponentMetadata(shortName, typeNameObjectOf(type))
        builder.displayName = displayName
        val (method, diagnostic) = findInvokeMethod(type)
        if (method != null) {
            builder.tagMatchingRule {
                this.tagName = tagName
                for (parameter in method.parameters) {
                    if (indexerValueTypeName(parameter) == null && !parameter.hasExplicitDefaultValue) attribute { name = HtmlConventions.toHtmlCase(parameter.name) }
                }
            }
            for (parameter in method.parameters) {
                val lowerKebabName = HtmlConventions.toHtmlCase(parameter.name)
                val parameterTypeName = RazorTypeDisplay.fullName(parameter.type)
                val simpleName = BoundAttributeDescriptorBuilder.PRIMITIVE_DISPLAY_NAMES[parameterTypeName] ?: parameterTypeName
                builder.bindAttribute {
                    name = lowerKebabName
                    this.typeName = parameterTypeName
                    this.displayName = "$simpleName $displayName.${parameter.name}"
                    propertyName = parameter.name
                    if (parameter.type.typeKind == RTypeKind.Enum) isEnum = true
                    else indexerValueTypeName(parameter)?.let { asDictionary("$lowerKebabName-", it) }
                }
            }
        } else if (diagnostic != null) {
            builder.diagnostics += diagnostic
        }
        results += builder.build()
    }

    private fun findInvokeMethod(type: RTypeDefinition): Pair<RMethod?, io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic?> {
        val methods = baseChain(type).flatMap { (current, _) ->
            current.methods.filter { it.accessibility == RAccessibility.Public && !it.isStatic && (it.name == ViewComponentTypes.asyncMethodName || it.name == ViewComponentTypes.syncMethodName) }
        }.toList()
        val fullName = RazorTypeDisplay.fullName(type)
        if (methods.isEmpty()) return null to RazorExtensionsDiagnosticFactory.createViewComponent_CannotFindMethod(fullName)
        if (methods.size > 1) return null to RazorExtensionsDiagnosticFactory.createViewComponent_AmbiguousMethods(fullName)
        val method = methods[0]
        val returnType = method.returnType as? RNamed
        val isTask = returnType != null && task != null && returnType.definition.key == task.key
        val isGenericTask = returnType != null && returnType.isGenericType && genericTask != null && returnType.definition.key == genericTask.key
        if (method.name == ViewComponentTypes.asyncMethodName) {
            if (!isTask && !isGenericTask) return null to RazorExtensionsDiagnosticFactory.createViewComponent_AsyncMethod_ShouldReturnTask(fullName)
        } else {
            if (method.returnsVoid || method.returnType.specialType == "System_Void") return null to RazorExtensionsDiagnosticFactory.createViewComponent_SyncMethod_ShouldReturnValue(fullName)
            if (isTask || isGenericTask) return null to RazorExtensionsDiagnosticFactory.createViewComponent_SyncMethod_CannotReturnTask(fullName)
        }
        return method to null
    }

    private fun indexerValueTypeName(parameter: RParameter): String? {
        if (iDictionary == null) return null
        val arguments = parameter.dictionaryArguments ?: return null
        if (arguments.size != 2 || arguments[0].specialType != "System_String") return null
        return RazorTypeDisplay.fullName(arguments[1])
    }

    private fun shortName(type: RTypeDefinition): String {
        val attribute = type.attributes.firstOrNull { it.attributeClass.key == viewComponentAttribute.key }
        val name = attribute?.namedArguments?.firstOrNull { it.first == ViewComponentTypes.ViewComponent.name }?.second?.value as? String
        if (!name.isNullOrEmpty()) return name.substringAfterLast('.')
        return if (type.name.endsWith(ViewComponentTypes.viewComponentSuffix, ignoreCase = true)) type.name.dropLast(ViewComponentTypes.viewComponentSuffix.length) else type.name
    }

    companion object {
        fun create(compilation: RCompilation): TagHelperProducer? {
            val attribute = compilation.typeByMetadataName(ViewComponentTypes.viewComponentAttribute) ?: return null
            if (attribute.kind == RTypeKind.Error) return null
            return ViewComponentTagHelperProducer(
                attribute, compilation.typeByMetadataName(ViewComponentTypes.nonViewComponentAttribute), compilation.typeByMetadataName(ViewComponentTypes.task),
                compilation.typeByMetadataName(ViewComponentTypes.genericTask), compilation.typeByMetadataName(ViewComponentTypes.iDictionary),
            )
        }
    }
}
