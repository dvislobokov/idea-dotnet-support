package io.github.dotnetsupport.razor.lang.codegen.components

/** `ComponentsApi`: the names of the Blazor runtime the code generation writes; kept in sync with the actual definitions. */
object ComponentsApi {
    const val assemblyName = "Microsoft.AspNetCore.Components"

    const val addMultipleAttributesTypeFullName = "global::System.Collections.Generic.IEnumerable<global::System.Collections.Generic.KeyValuePair<string, object>>"

    object ComponentBase {
        const val namespace = "Microsoft.AspNetCore.Components"
        const val fullTypeName = "$namespace.ComponentBase"
        const val metadataName = fullTypeName
        const val buildRenderTree = "BuildRenderTree"
        const val assets = "Assets"
    }

    object ParameterAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.ParameterAttribute"
        const val metadataName = fullTypeName
        const val captureUnmatchedValues = "CaptureUnmatchedValues"
    }

    object LayoutAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.LayoutAttribute"
    }

    object InjectAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.InjectAttribute"
    }

    object IComponent {
        const val fullTypeName = "Microsoft.AspNetCore.Components.IComponent"
        const val metadataName = fullTypeName
    }

    object IDictionary {
        const val metadataName = "System.Collection.IDictionary`2"
    }

    object Dictionary {
        const val metadataName = "System.Collections.Generic.Dictionary`2"
    }

    object IComponentRenderMode {
        const val fullTypeName = "Microsoft.AspNetCore.Components.IComponentRenderMode"
    }

    object RenderModeAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.RenderModeAttribute"
    }

    object RenderFragment {
        const val namespace = "Microsoft.AspNetCore.Components"
        const val fullTypeName = "$namespace.RenderFragment"
        const val metadataName = fullTypeName
    }

    object RenderFragmentOfT {
        const val namespace = "Microsoft.AspNetCore.Components"
        const val fullTypeName = "$namespace.RenderFragment<>"
        const val metadataName = "$namespace.RenderFragment`1"
        const val displayName = "$namespace.RenderFragment<TValue>"
    }

    object RenderTreeBuilder {
        const val fullTypeName = "Microsoft.AspNetCore.Components.Rendering.RenderTreeBuilder"
        const val builderParameter = "__builder"
        const val formNameVariableName = "__formName"
        const val openElement = "OpenElement"
        const val closeElement = "CloseElement"
        const val openComponent = "OpenComponent"
        const val closeComponent = "CloseComponent"
        const val addMarkupContent = "AddMarkupContent"
        const val addContent = "AddContent"
        const val addAttribute = "AddAttribute"
        const val addMultipleAttributes = "AddMultipleAttributes"
        const val addComponentParameter = "AddComponentParameter"
        const val addNamedEvent = "AddNamedEvent"
        const val addElementReferenceCapture = "AddElementReferenceCapture"
        const val addComponentReferenceCapture = "AddComponentReferenceCapture"
        const val clear = "Clear"
        const val getFrames = "GetFrames"
        const val childContent = "ChildContent"
        const val setKey = "SetKey"
        const val setUpdatesAttributeName = "SetUpdatesAttributeName"
        const val addEventPreventDefaultAttribute = "AddEventPreventDefaultAttribute"
        const val addEventStopPropagationAttribute = "AddEventStopPropagationAttribute"
        const val addComponentRenderMode = "AddComponentRenderMode"
        const val renderModeVariableName = "__renderMode"
    }

    object RuntimeHelpers {
        const val typeCheck = "global::Microsoft.AspNetCore.Components.CompilerServices.RuntimeHelpers.TypeCheck"
        const val createInferredEventCallback = "global::Microsoft.AspNetCore.Components.CompilerServices.RuntimeHelpers.CreateInferredEventCallback"
        const val createInferredBindSetter = "global::Microsoft.AspNetCore.Components.CompilerServices.RuntimeHelpers.CreateInferredBindSetter"
        const val invokeSynchronousDelegate = "global::Microsoft.AspNetCore.Components.CompilerServices.RuntimeHelpers.InvokeSynchronousDelegate"
        const val invokeAsynchronousDelegate = "global::Microsoft.AspNetCore.Components.CompilerServices.RuntimeHelpers.InvokeAsynchronousDelegate"
    }

    object RouteAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.RouteAttribute"
    }

    object BindElementAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.BindElementAttribute"
    }

    object BindInputElementAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.BindInputElementAttribute"
    }

    object EventHandlerAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.EventHandlerAttribute"
    }

    object AssetPathAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.AssetPathAttribute"
        const val metadataName = fullTypeName
    }

    object AcceptsAssetPathAttribute {
        const val fullTypeName = "Microsoft.AspNetCore.Components.AcceptsAssetPathAttribute"
        const val metadataName = fullTypeName

        /** The public type the runtime decorates with `[AcceptsAssetPath]`; the compiler finds it by name to build the asset-path allowlist. */
        const val candidateTypeName = "AssetPathAttributes"
    }

    object ElementReference {
        const val fullTypeName = "Microsoft.AspNetCore.Components.ElementReference"
    }

    object EventCallback {
        const val fullTypeName = "Microsoft.AspNetCore.Components.EventCallback"
        const val metadataName = fullTypeName
        const val factoryAccessor = "$fullTypeName.Factory"
    }

    object EventCallbackOfT {
        const val metadataName = "Microsoft.AspNetCore.Components.EventCallback`1"
        const val displayName = "Microsoft.AspNetCore.Components.EventCallback<TValue>"
    }

    object EventCallbackFactory {
        const val createMethod = "Create"
        const val createBinderMethod = "CreateBinder"
    }

    object BindConverter {
        const val fullTypeName = "Microsoft.AspNetCore.Components.BindConverter"
        const val formatValue = "Microsoft.AspNetCore.Components.BindConverter.FormatValue"
    }

    object CascadingTypeParameterAttribute {
        const val metadataName = "Microsoft.AspNetCore.Components.CascadingTypeParameterAttribute"
    }
}
