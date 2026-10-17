package io.github.dotnetsupport.razor.lang.codegen.components

import io.github.dotnetsupport.razor.lang.intermediate.ComponentChildContentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.ComponentIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.TagHelperDirectiveAttributeParameterIntermediateNode
import io.github.dotnetsupport.razor.lang.intermediate.findDescendantNodes
import io.github.dotnetsupport.razor.lang.syntax.BoundAttributeDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnostic
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticDescriptor
import io.github.dotnetsupport.razor.lang.syntax.RazorDiagnosticSeverity
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan
import io.github.dotnetsupport.razor.lang.syntax.TagHelperDescriptor

/**
 * `ComponentDiagnosticFactory` (`ComponentDiagnosticFactory.cs`; RZ99xx / RZ100xx): the diagnostics of the component passes, ids and
 * texts as the compiler's. The texts are those after `string.Format` (`{{` is `{`); `Environment.NewLine` is the platform's line separator.
 */
object ComponentDiagnosticFactory {
    private fun error(code: Int, message: String) = RazorDiagnosticDescriptor("RZ$code", message, RazorDiagnosticSeverity.ERROR)
    private fun warning(code: Int, message: String, level: Int = 0) = RazorDiagnosticDescriptor("RZ$code", message, RazorDiagnosticSeverity.WARNING, level)

    /** `RazorDiagnostic.Create(descriptor, SourceSpan? span, args)`: no span is `SourceSpan.Undefined`. */
    private fun create(descriptor: RazorDiagnosticDescriptor, span: SourceSpan?, vararg args: Any?) = RazorDiagnostic(descriptor, span ?: SourceSpan.UNDEFINED, args.toList())

    private val newLine: String = System.lineSeparator()

    val UnsupportedTagHelperDirective = error(
        9978,
        "The directives @addTagHelper, @removeTagHelper and @tagHelperPrefix are not valid in a component document. Use '@using <namespace>' directive instead.",
    )

    fun create_UnsupportedTagHelperDirective(source: SourceSpan?) = create(UnsupportedTagHelperDirective, source)

    val CodeBlockInAttribute = error(
        9979,
        "Code blocks delimited by '@{...}' like '@{ {0} }' for attributes are no longer supported. These features have been changed to use attribute syntax. " +
            "Use 'attr=\"@(x => {... }\"'.",
    )

    fun create_CodeBlockInAttribute(source: SourceSpan?, expression: String) = create(CodeBlockInAttribute, source, expression)

    val UnclosedTag = error(9980, "Unclosed tag '{0}' with no matching end tag.")

    fun create_UnclosedTag(span: SourceSpan?, tagName: String) = create(UnclosedTag, span, tagName)

    val UnexpectedClosingTag = error(9981, "Unexpected closing tag '{0}' with no matching start tag.")

    fun create_UnexpectedClosingTag(span: SourceSpan?, tagName: String) = create(UnexpectedClosingTag, span, tagName)

    val UnexpectedClosingTagForVoidElement = error(9983, "Unexpected closing tag '{0}'. The element '{0}' is a void element, and should be used without a closing tag.")

    fun create_UnexpectedClosingTagForVoidElement(span: SourceSpan?, tagName: String) = create(UnexpectedClosingTagForVoidElement, span, tagName)

    val InvalidHtmlContent = error(9984, "Found invalid HTML content. Text '{0}'")

    fun create_InvalidHtmlContent(span: SourceSpan?, text: String) = create(InvalidHtmlContent, span, text)

    val MultipleComponents = error(9985, "Multiple components use the tag '{0}'. Components: {1}")

    fun create_MultipleComponents(span: SourceSpan?, tagName: String, components: Iterable<TagHelperDescriptor>): RazorDiagnostic {
        val componentNames = components.joinToString(", ") { it.displayName }
        return create(MultipleComponents, span, tagName, componentNames)
    }

    val AmbiguousComponentSelection = error(
        10012,
        "The component '{0}' cannot be disambiguated between generic and non-generic versions. " +
            "Provide a type parameter (e.g., TItem=\"...\") to use the generic component, or use different parameter names to avoid ambiguity. " +
            "Components: {1}",
    )

    fun create_AmbiguousComponentSelection(span: SourceSpan?, tagName: String, genericComponent: TagHelperDescriptor, nonGenericComponent: TagHelperDescriptor): RazorDiagnostic {
        val componentNames = "${genericComponent.displayName}, ${nonGenericComponent.displayName}"
        return create(AmbiguousComponentSelection, span, tagName, componentNames)
    }

    val UnsupportedComplexContent = error(9986, "Component attributes do not support complex content (mixed C# and markup). Attribute: '{0}', text: '{1}'")

    fun create_UnsupportedComplexContent(node: IntermediateNode, attributeName: String): RazorDiagnostic {
        val content = node.findDescendantNodes<IntermediateToken>().joinToString("") { it.content }
        return create(UnsupportedComplexContent, node.source, attributeName, content)
    }

    /** `ComponentResources.PageDirectiveCannotBeImported`. */
    val PageDirective_CannotBeImported = error(9987, "The '@{0}' directive specified in {1} file will not be imported. The directive must appear at the top of each Razor file")

    /** `Path.GetFileName(source.FilePath)`: null for no path. */
    fun createPageDirective_CannotBeImported(source: SourceSpan): RazorDiagnostic {
        val fileName = source.filePath?.let { path -> path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1) }
        return create(PageDirective_CannotBeImported, source, "page", fileName)
    }

    val PageDirective_MustSpecifyRoute = error(
        9988,
        "The @page directive must specify a route template. The route template must be enclosed in quotes and begin with the '/' character.",
    )

    fun createPageDirective_MustSpecifyRoute(source: SourceSpan?) = create(PageDirective_MustSpecifyRoute, source)

    val BindAttribute_Duplicates = error(9989, "The attribute '{0}' was matched by multiple bind attributes. Duplicates:{1}")

    fun createBindAttribute_Duplicates(source: SourceSpan?, attribute: String, attributes: Iterable<TagHelperDirectiveAttributeIntermediateNode>) =
        create(BindAttribute_Duplicates, source, attribute, newLine + attributes.joinToString(newLine) { it.tagHelper.displayName })

    val EventHandler_Duplicates = error(9990, "The attribute '{0}' was matched by multiple event handler attributes. Duplicates:{1}")

    fun createEventHandler_Duplicates(source: SourceSpan?, attribute: String, attributes: List<TagHelperDirectiveAttributeIntermediateNode>) =
        create(EventHandler_Duplicates, source, attribute, newLine + attributes.joinToString(newLine) { it.tagHelper.displayName })

    val BindAttribute_InvalidSyntax = error(
        9991,
        "The attribute names could not be inferred from bind attribute '{0}'. Bind attributes should be of the form " +
            "'bind' or 'bind-value' along with their corresponding optional parameters like 'bind-value:event', 'bind:format' etc.",
    )

    fun createBindAttribute_InvalidSyntax(source: SourceSpan?, attribute: String) = create(BindAttribute_InvalidSyntax, source, attribute)

    val DisallowedScriptTag = error(
        9992,
        "Script tags should not be placed inside components because they cannot be updated dynamically. To fix this, move the script tag to the 'index.html' " +
            "file or another static location. For more information, see https://aka.ms/AAe3qu3",
    )

    // Reserved: BL9993 Component parameters should not be public

    fun create_DisallowedScriptTag(source: SourceSpan?) = create(DisallowedScriptTag, source)

    val TemplateInvalidLocation = error(9994, "Razor templates cannot be used in attributes.")

    fun create_TemplateInvalidLocation(source: SourceSpan?) = create(TemplateInvalidLocation, source)

    val ChildContentSetByAttributeAndBody = error(9995, "The child content property '{0}' is set by both the attribute and the element contents.")

    fun create_ChildContentSetByAttributeAndBody(source: SourceSpan?, attribute: String?) = create(ChildContentSetByAttributeAndBody, source, attribute)

    val ChildContentMixedWithExplicitChildContent = error(
        9996,
        "Unrecognized child content inside component '{0}'. The component '{0}' accepts child content through the following top-level items: {1}.",
    )

    fun create_ChildContentMixedWithExplicitChildContent(source: SourceSpan?, component: ComponentIntermediateNode): RazorDiagnostic {
        val supportedElements = component.component!!.getChildContentProperties().joinToString(", ") { "'${it.name}'" }
        return create(ChildContentMixedWithExplicitChildContent, source, component.tagName, supportedElements)
    }

    val ChildContentHasInvalidAttribute = error(9997, "Unrecognized attribute '{0}' on child content element '{1}'.")

    fun create_ChildContentHasInvalidAttribute(source: SourceSpan?, attribute: String?, element: String?) = create(ChildContentHasInvalidAttribute, source, attribute, element)

    val ChildContentHasInvalidParameter = error(
        9998,
        "Invalid parameter name. The parameter name attribute '{0}' on child content element '{1}' can only include literal text.",
    )

    fun create_ChildContentHasInvalidParameter(source: SourceSpan?, attribute: String?, element: String?) = create(ChildContentHasInvalidParameter, source, attribute, element)

    val ChildContentRepeatedParameterName = error(
        9999,
        "The child content element '{0}' of component '{1}' uses the same parameter name ('{2}') as enclosing child content " +
            "element '{3}' of component '{4}'. Specify the parameter name like: '<{0} Context=\"another_name\"> to resolve the ambiguity",
    )

    fun create_ChildContentRepeatedParameterName(
        source: SourceSpan?,
        childContent1: ComponentChildContentIntermediateNode,
        component1: ComponentIntermediateNode,
        childContent2: ComponentChildContentIntermediateNode,
        component2: ComponentIntermediateNode,
    ): RazorDiagnostic {
        assert(childContent1.parameterName == childContent2.parameterName) // Debug.Assert
        assert(childContent1.isParameterized)
        assert(childContent2.isParameterized)

        return create(
            ChildContentRepeatedParameterName,
            source,
            childContent1.attributeName,
            component1.tagName,
            childContent1.parameterName,
            childContent2.attributeName,
            component2.tagName,
        )
    }

    val GenericComponentMissingTypeArgument = error(
        10000,
        "The component '{0}' is missing required type arguments. Specify the missing types using the attributes: {1}.",
    )

    fun create_GenericComponentMissingTypeArgument(source: SourceSpan?, component: ComponentIntermediateNode, attributes: Iterable<BoundAttributeDescriptor>): RazorDiagnostic {
        assert(component.component!!.isGenericTypedComponent()) // Debug.Assert

        val attributesText = attributes.joinToString(", ") { "'${it.name}'" }
        return create(GenericComponentMissingTypeArgument, source, component.tagName, attributesText)
    }

    val GenericComponentTypeInferenceUnderspecified = error(
        10001,
        "The type of component '{0}' cannot be inferred based on the values provided. Consider specifying the type arguments " +
            "directly using the following attributes: {1}.",
    )

    fun create_GenericComponentTypeInferenceUnderspecified(source: SourceSpan?, component: ComponentIntermediateNode, attributes: Iterable<BoundAttributeDescriptor>): RazorDiagnostic {
        assert(component.component!!.isGenericTypedComponent()) // Debug.Assert

        val attributesText = attributes.joinToString(", ") { "'${it.name}'" }
        return create(GenericComponentTypeInferenceUnderspecified, source, component.tagName, attributesText)
    }

    val ChildContentHasInvalidParameterOnComponent = error(
        10002,
        "Invalid parameter name. The parameter name attribute '{0}' on component '{1}' can only include literal text.",
    )

    fun create_ChildContentHasInvalidParameterOnComponent(source: SourceSpan?, attribute: String?, element: String?) =
        create(ChildContentHasInvalidParameterOnComponent, source, attribute, element)

    val UnsupportedComponentImportContent = error(10003, "Markup, code and block directives are not valid in component imports.")

    fun create_UnsupportedComponentImportContent(source: SourceSpan?) = create(UnsupportedComponentImportContent, source)

    val BindAttributeParameter_MissingBind = error(10004, "Could not find the non-parameterized bind attribute that corresponds to the attribute '{0}'.")

    fun createBindAttributeParameter_MissingBind(source: SourceSpan?, attribute: String) = create(BindAttributeParameter_MissingBind, source, attribute)

    val DuplicateMarkupAttribute = error(10007, "The attribute '{0}' is used two or more times for this element. Attributes must be unique (case-insensitive).")

    fun create_DuplicateMarkupAttribute(attributeName: String, source: SourceSpan? = null) = create(DuplicateMarkupAttribute, source, attributeName)

    val DuplicateMarkupAttributeDirective = error(
        10008,
        "The attribute '{0}' is used two or more times for this element. Attributes must be unique (case-insensitive). " +
            "The attribute '{0}' is used by the '{1}' directive attribute.",
    )

    fun create_DuplicateMarkupAttributeDirective(attributeName: String, directiveAttributeName: String, source: SourceSpan? = null) =
        create(DuplicateMarkupAttributeDirective, source, attributeName, directiveAttributeName)

    val DuplicateComponentParameter = error(
        10009,
        "The component parameter '{0}' is used two or more times for this component. Parameters must be unique (case-insensitive).",
    )

    fun create_DuplicateComponentParameter(attributeName: String, source: SourceSpan? = null) = create(DuplicateComponentParameter, source, attributeName)

    val DuplicateComponentParameterDirective = error(
        10010,
        "The component parameter '{0}' is used two or more times for this component. Parameters must be unique (case-insensitive). " +
            "The component parameter '{0}' is generated by the '{1}' directive attribute.",
    )

    fun create_DuplicateComponentParameterDirective(attributeName: String, directiveAttributeName: String, source: SourceSpan? = null) =
        create(DuplicateComponentParameterDirective, source, attributeName, directiveAttributeName)

    val ComponentNamesCannotStartWithLowerCase = error(
        10011,
        "Component '{0}' starts with a lowercase character. Component names cannot start with a lowercase character.",
    )

    fun create_ComponentNamesCannotStartWithLowerCase(componentName: String, source: SourceSpan? = null) =
        create(ComponentNamesCannotStartWithLowerCase, source, componentName)

    /** RZ10012 twice, as in the compiler: this warning and [AmbiguousComponentSelection]. */
    val UnexpectedMarkupElement = warning(
        10012,
        "Found markup element with unexpected name '{0}'. If this is intended to be a component, add a @using directive for its namespace.",
    )

    fun create_UnexpectedMarkupElement(elementName: String, source: SourceSpan? = null) = create(UnexpectedMarkupElement, source, elementName)

    val InconsistentStartAndEndTagName = error(
        10013,
        "The start tag name '{0}' does not match the end tag name '{1}'. Components must have matching start and end tag names (case-sensitive).",
    )

    fun create_InconsistentStartAndEndTagName(startTagName: String, endTagName: String, source: SourceSpan? = null) =
        create(InconsistentStartAndEndTagName, source, startTagName, endTagName)

    val EventHandlerParameter_Duplicates = error(10014, "The attribute '{0}' was matched by multiple event handlers parameter attributes. Duplicates:{1}")

    fun createEventHandlerParameter_Duplicates(source: SourceSpan?, attribute: String, attributes: List<TagHelperDirectiveAttributeParameterIntermediateNode>) =
        create(EventHandlerParameter_Duplicates, source, attribute, newLine + attributes.joinToString(newLine) { it.tagHelper.displayName })

    val BindAttributeParameter_UseBindGet = error(10015, "Attribute '{0}:get' must be used with attribute '{0}:set'.")

    fun createBindAttributeParameter_UseBindGet(source: SourceSpan?, attribute: String) = create(BindAttributeParameter_UseBindGet, source, attribute)

    val BindAttributeParameter_MissingBindGet = error(10016, "Attribute '{0}:set' was used but no attribute '{0}:get' was found.")

    fun createBindAttributeParameter_MissingBindGet(source: SourceSpan?, attribute: String) = create(BindAttributeParameter_MissingBindGet, source, attribute)

    val BindAttribute_MissingBindSet = error(10017, "The attribute '{0}' must have a companion '{1}' attribute.")

    fun createBindAttribute_MissingBindSet(source: SourceSpan?, attributeGet: String, attributeSet: String) = create(BindAttribute_MissingBindSet, source, attributeGet, attributeSet)

    val BindAttributeParameter_InvalidSyntaxBindAndBindGet = error(10018, "Attribute '{0}' can't be used in conjunction with '{0}:get'.")

    fun createBindAttributeParameter_InvalidSyntaxBindAndBindGet(source: SourceSpan?, attribute: String) =
        create(BindAttributeParameter_InvalidSyntaxBindAndBindGet, source, attribute)

    val BindAttributeParameter_InvalidSyntaxBindSetAfter = error(
        10019,
        "Attribute '{0}:after' can not be used with '{0}:set'. Invoke the code in '{0}:after' inside '{0}:set' instead.",
    )

    fun createBindAttributeParameter_InvalidSyntaxBindSetAfter(source: SourceSpan?, attribute: String) =
        create(BindAttributeParameter_InvalidSyntaxBindSetAfter, source, attribute)

    val BindAttributeParameter_UnsupportedSyntaxBindGetSet = error(10020, "Attribute '{0}' can only be used with RazorLanguageVersion 7.0 or higher.")

    fun createBindAttributeParameter_UnsupportedSyntaxBindGetSet(source: SourceSpan?, attribute: String) =
        create(BindAttributeParameter_UnsupportedSyntaxBindGetSet, source, attribute)

    // Removed warning RZ10021: Attribute '@formname' can only be used when '@onsubmit' event handler is also present.

    val FormName_NotAForm = warning(10022, "Attribute '@formname' can only be applied to 'form' elements.")

    fun createFormName_NotAForm(source: SourceSpan?) = create(FormName_NotAForm, source)

    val Attribute_ValidOnlyOnComponent = error(10023, "Attribute '{0}' is only valid when used on a component.")

    fun createAttribute_ValidOnlyOnComponent(source: SourceSpan?, attribute: String) = create(Attribute_ValidOnlyOnComponent, source, attribute)

    val RenderModeAttribute_ComponentDeclaredRenderMode = error(10024, "Cannot override render mode for component '{0}' as it explicitly declares one.")

    fun createRenderModeAttribute_ComponentDeclaredRenderMode(source: SourceSpan?, component: String) =
        create(RenderModeAttribute_ComponentDeclaredRenderMode, source, component)

    val UnknownComponentParameter = warning(10025, "The component '{0}' does not have a parameter named '{1}'.", level = 11)

    fun create_UnknownComponentParameter(source: SourceSpan?, component: String?, parameter: String) = create(UnknownComponentParameter, source, component, parameter)

    val BindAttribute_MissingTarget = warning(10026, "The bind attribute '{0}' does not match any parameter on component '{1}'.", level = 11)

    fun createBindAttribute_MissingTarget(source: SourceSpan?, attribute: String, component: String?) = create(BindAttribute_MissingTarget, source, attribute, component)

    val BindAttribute_MissingChangeAttribute = warning(
        10027,
        "The bind attribute '{0}' requires a matching change parameter named '{1}' on component '{2}'.",
        level = 11,
    )

    fun createBindAttribute_MissingChangeAttribute(source: SourceSpan?, attribute: String, changeAttribute: String, component: String?) =
        create(BindAttribute_MissingChangeAttribute, source, attribute, changeAttribute, component)

    val UnboundDirectiveAttribute = warning(10028, "The attribute '{0}' could not be bound to any directive attribute.", level = 11)

    fun create_UnboundDirectiveAttribute(source: SourceSpan?, attribute: String) = create(UnboundDirectiveAttribute, source, attribute)

    val TildePath_MixedContent = warning(
        10029,
        "The '~' path prefix in '{0}' cannot be expanded because the attribute value contains mixed content. Use '@(Assets[\"...\"])' for dynamic paths.",
    )

    fun createTildePath_MixedContent(source: SourceSpan?, attribute: String?) = create(TildePath_MixedContent, source, attribute)

    val AssetPath_NonStringParameter = warning(
        10030,
        "[AssetPath] on parameter '{0}' has no effect because its type is not 'string'; '~/' asset-path expansion applies only to string parameters.",
    )

    fun createAssetPath_NonStringParameter(source: SourceSpan?, parameter: String) = create(AssetPath_NonStringParameter, source, parameter)
}
