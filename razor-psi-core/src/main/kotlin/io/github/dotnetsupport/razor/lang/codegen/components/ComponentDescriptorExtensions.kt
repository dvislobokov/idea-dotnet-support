package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.syntax.BindMetadata
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.ChildContentParameterMetadata
import io.github.dotnetsupport.razor.lang.syntax.EventHandlerMetadata
import io.github.dotnetsupport.razor.lang.syntax.MetadataKind
import io.github.dotnetsupport.razor.lang.syntax.PropertyMetadata
import io.github.dotnetsupport.razor.lang.syntax.TagHelperBinding
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind
import io.github.dotnetsupport.razor.lang.syntax.TypeParameterMetadata
import io.github.dotnetsupport.razor.lang.syntax.isAnyComponentKind
import io.github.dotnetsupport.razor.lang.syntax.isComponentOrChildContentKind

// The component extensions of the descriptors (`Components/TagHelperDescriptorExtensions.cs`, `Components/TagHelperBoundAttributeDescriptorExtensions.cs`):
// what a descriptor's kind and metadata say about it. `AcceptsStringLiteral` is in TagHelperDescriptors.kt; the overloads on the
// descriptor builders are not ported (the descriptors are built elsewhere).

// TagHelperDescriptorExtensions

fun TagHelperDescriptor.isAnyComponentDocumentTagHelper(): Boolean = kind.isAnyComponentKind

fun TagHelperDescriptor.isComponentOrChildContentTagHelper(): Boolean = kind.isComponentOrChildContentKind

fun TagHelperDescriptor.isFallbackBindTagHelper(): Boolean = kind == TagHelperKind.Bind && (metadata as? BindMetadata)?.isFallback == true

fun TagHelperDescriptor.isGenericTypedComponent(): Boolean = kind == TagHelperKind.Component && (metadata as? ComponentMetadata)?.isGeneric == true

fun TagHelperDescriptor.acceptsUnmatchedAttributes(): Boolean =
    kind == TagHelperKind.Component && (metadata as? ComponentMetadata)?.acceptsUnmatchedAttributes == true

/**
 * `TryGetGenericTypeNameFromComponent`: the value the element gives the single type parameter of a component (`TItem="string"` gives
 * `string`); null when the descriptor is not a component, has several type parameters or none is given. A type parameter the element
 * does not set gives null too, as the compiler's `default` key-value pair does.
 */
fun TagHelperDescriptor.tryGetGenericTypeNameFromComponent(binding: TagHelperBinding): String? {
    var typeName: String? = null

    if (kind != TagHelperKind.Component) return null

    for (boundAttribute in boundAttributes) {
        // The bound attribute marked as a type parameter names the property ("TItem"); its value is in the binding's attributes.
        if (boundAttribute.isTypeParameterProperty()) {
            val bindingTypeName = binding.attributes.firstOrNull { it.first == boundAttribute.propertyName }?.second
            if (typeName != null) {
                // Multiple generic types were found and currently not supported.
                return null
            }
            typeName = bindingTypeName
        }
    }

    return typeName
}

/** `IsInputElementBindTagHelper`: a bind tag helper of exactly two rules, the first for `input`. */
fun TagHelperDescriptor.isInputElementBindTagHelper(): Boolean = kind == TagHelperKind.Bind && tagMatchingRules.size == 2 && tagMatchingRules[0].tagName == "input"

fun TagHelperDescriptor.isInputElementFallbackBindTagHelper(): Boolean =
    isInputElementBindTagHelper() && (metadata as? BindMetadata)?.typeAttribute == null

fun TagHelperDescriptor.getValueAttributeName(): String? = (metadata as? BindMetadata)?.valueAttribute

fun TagHelperDescriptor.getChangeAttributeName(): String? = (metadata as? BindMetadata)?.changeAttribute

fun TagHelperDescriptor.getExpressionAttributeName(): String? = (metadata as? BindMetadata)?.expressionAttribute

/** A bind tag helper whose default culture is `CultureInfo.InvariantCulture`. */
fun TagHelperDescriptor.isInvariantCultureBindTagHelper(): Boolean = (metadata as? BindMetadata)?.isInvariantCulture == true

/** The default format of a bind tag helper, or null. */
fun TagHelperDescriptor.getFormat(): String? = (metadata as? BindMetadata)?.format

fun TagHelperDescriptor.getEventArgsType(): String? = (metadata as? EventHandlerMetadata)?.eventArgsType

/** The attributes that can accept child content (`RenderFragment` or `RenderFragment<T>`). */
fun TagHelperDescriptor.getChildContentProperties(): List<BoundAttributeDescriptor> = boundAttributes.filter { it.isChildContentProperty() }

/** The attributes that are the generic type parameters of the component type. */
fun TagHelperDescriptor.getTypeParameters(): List<BoundAttributeDescriptor> = boundAttributes.filter { it.isTypeParameterProperty() }

/** Whether the component supplies any cascading generic type parameters to its descendants. */
fun TagHelperDescriptor.suppliesCascadingGenericParameters(): Boolean = boundAttributes.any { it.isCascadingTypeParameterProperty() }

// TagHelperBoundAttributeDescriptorExtensions

fun BoundAttributeDescriptor.isDelegateProperty(): Boolean = (metadata as? PropertyMetadata)?.isDelegateSignature == true

fun BoundAttributeDescriptor.isDelegateWithAwaitableResult(): Boolean = (metadata as? PropertyMetadata)?.isDelegateWithAwaitableResult == true

/** Whether the attribute is of type `EventCallback` or `EventCallback<T>`. */
fun BoundAttributeDescriptor.isEventCallbackProperty(): Boolean = (metadata as? PropertyMetadata)?.isEventCallback == true

fun BoundAttributeDescriptor.isGenericTypedProperty(): Boolean = (metadata as? PropertyMetadata)?.isGenericTyped == true

fun BoundAttributeDescriptor.isTypeParameterProperty(): Boolean = metadata.kind == MetadataKind.TypeParameter

fun BoundAttributeDescriptor.isCascadingTypeParameterProperty(): Boolean = (metadata as? TypeParameterMetadata)?.isCascading == true

/** A child content property: of type `RenderFragment` or `RenderFragment<T>`. */
fun BoundAttributeDescriptor.isChildContentProperty(): Boolean = (metadata as? PropertyMetadata)?.isChildContent == true

/** A parameterized child content property: of type `RenderFragment<T>` (for some T). */
fun BoundAttributeDescriptor.isParameterizedChildContentProperty(): Boolean =
    isChildContentProperty() && typeNameObject.fullName != ComponentsApi.RenderFragment.fullTypeName

/** The property that names the parameter of a parameterized child content (`Context`). */
fun BoundAttributeDescriptor.isChildContentParameterNameProperty(): Boolean = metadata is ChildContentParameterMetadata
