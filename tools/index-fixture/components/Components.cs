using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Microsoft.AspNetCore.Components
{
    public interface IComponent
    {
        void Attach(RenderHandle renderHandle);
        Task SetParametersAsync(ParameterView parameters);
    }

    public readonly struct RenderHandle { }

    public readonly struct ParameterView { }

    public abstract class ComponentBase : IComponent
    {
        public void Attach(RenderHandle renderHandle) { }
        public virtual Task SetParametersAsync(ParameterView parameters) => Task.CompletedTask;
        protected virtual void BuildRenderTree(Rendering.RenderTreeBuilder builder) { }
        protected virtual void OnInitialized() { }
        protected void StateHasChanged() { }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = true)]
    public sealed class ParameterAttribute : Attribute
    {
        public bool CaptureUnmatchedValues { get; set; }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = true)]
    public sealed class CascadingParameterAttribute : Attribute
    {
        public string? Name { get; set; }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = true)]
    public sealed class EditorRequiredAttribute : Attribute { }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = true)]
    public sealed class InjectAttribute : Attribute { }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true, Inherited = false)]
    public sealed class RouteAttribute : Attribute
    {
        public RouteAttribute(string template) { Template = template; }
        public string Template { get; }
    }

    public delegate void RenderFragment(Rendering.RenderTreeBuilder builder);

    public delegate RenderFragment RenderFragment<TValue>(TValue value);

    public readonly struct EventCallback
    {
        public static readonly EventCallbackFactory Factory = new();
        public Task InvokeAsync(object? arg) => Task.CompletedTask;
        public Task InvokeAsync() => Task.CompletedTask;
    }

    public readonly struct EventCallback<TValue>
    {
        public Task InvokeAsync(TValue? arg) => Task.CompletedTask;
    }

    public sealed class EventCallbackFactory
    {
        public EventCallback Create(object receiver, Action callback) => default;
        public EventCallback<TValue> Create<TValue>(object receiver, Action<TValue> callback) => default;
        public EventCallback<TValue> Create<TValue>(object receiver, EventCallback<TValue> callback) => default;
    }

    public static class BindConverter
    {
        public static string? FormatValue(string? value) => value;
        public static string? FormatValue(int value) => value.ToString();
    }

    public interface IComponentRenderMode { }

    public readonly struct ElementReference { }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true, Inherited = true)]
    public sealed class EventHandlerAttribute : Attribute
    {
        public EventHandlerAttribute(string attributeName, Type eventArgsType) : this(attributeName, eventArgsType, false, false) { }
        public EventHandlerAttribute(string attributeName, Type eventArgsType, bool enableStopPropagation, bool enablePreventDefault) { }
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true, Inherited = true)]
    public sealed class BindElementAttribute : Attribute
    {
        public BindElementAttribute(string element, string? suffix, string valueAttribute, string changeAttribute) { }
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true, Inherited = true)]
    public sealed class BindInputElementAttribute : Attribute
    {
        public BindInputElementAttribute(string? type, string? suffix, string valueAttribute, string changeAttribute, bool isInvariantCulture, string? format) { }
    }

    public class ChangeEventArgs : EventArgs
    {
        public object? Value { get; set; }
    }
}

namespace Microsoft.AspNetCore.Components.Rendering
{
    public sealed class RenderTreeBuilder
    {
        public void OpenElement(int sequence, string elementName) { }
        public void CloseElement() { }
        public void OpenComponent<TComponent>(int sequence) where TComponent : IComponent { }
        public void CloseComponent() { }
        public void AddMarkupContent(int sequence, string? markupContent) { }
        public void AddContent(int sequence, string? textContent) { }
        public void AddContent(int sequence, object? textContent) { }
        public void AddAttribute(int sequence, string name, object? value) { }
        public void AddAttribute<TArgument>(int sequence, string name, EventCallback<TArgument> value) { }
        public void AddComponentParameter(int sequence, string name, object? value) { }
        public void AddNamedEvent(string eventType, string assignedName) { }
        public void SetUpdatesAttributeName(string updatesAttributeName) { }
    }
}

namespace Microsoft.AspNetCore.Components.Web
{
    public class MouseEventArgs : EventArgs
    {
        public double ClientX { get; set; }
    }

    [EventHandler("onclick", typeof(MouseEventArgs), true, true)]
    [EventHandler("onchange", typeof(ChangeEventArgs), true, true)]
    public static class EventHandlers { }

    [BindElement("select", null, "value", "onchange")]
    [BindInputElement(null, null, "value", "onchange", false, null)]
    [BindInputElement("text", null, "value", "onchange", false, null)]
    public static class BindAttributes { }
}
