using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace Microsoft.AspNetCore.Razor.TagHelpers
{
    public interface ITagHelper
    {
        int Order { get; }
        void Init(TagHelperContext context);
        Task ProcessAsync(TagHelperContext context, TagHelperOutput output);
    }

    public abstract class TagHelper : ITagHelper
    {
        public virtual int Order => 0;
        public virtual void Init(TagHelperContext context) { }
        public virtual void Process(TagHelperContext context, TagHelperOutput output) { }
        public virtual Task ProcessAsync(TagHelperContext context, TagHelperOutput output) => Task.CompletedTask;
    }

    public class TagHelperContext
    {
        public string TagName { get; } = "";
    }

    public class TagHelperOutput
    {
        public string? TagName { get; set; }
    }

    public enum TagStructure
    {
        Unspecified = 0,
        NormalOrSelfClosing = 1,
        WithoutEndTag = 2,
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = true, Inherited = false)]
    public sealed class HtmlTargetElementAttribute : Attribute
    {
        public const string ElementCatchAllTarget = "*";
        public HtmlTargetElementAttribute() : this(ElementCatchAllTarget) { }
        public HtmlTargetElementAttribute(string tag) { Tag = tag; }
        public string Tag { get; }
        public string? Attributes { get; set; }
        public TagStructure TagStructure { get; set; }
        public string? ParentTag { get; set; }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = false)]
    public sealed class HtmlAttributeNameAttribute : Attribute
    {
        public HtmlAttributeNameAttribute() { }
        public HtmlAttributeNameAttribute(string name) { Name = name; }
        public string? Name { get; }
        public string? DictionaryAttributePrefix { get; set; }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = false)]
    public sealed class HtmlAttributeNotBoundAttribute : Attribute { }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = false, Inherited = false)]
    public sealed class RestrictChildrenAttribute : Attribute
    {
        public RestrictChildrenAttribute(string childTag, params string[] childTags) { ChildTags = childTags; }
        public IEnumerable<string> ChildTags { get; }
    }

    [AttributeUsage(AttributeTargets.Class, AllowMultiple = false, Inherited = false)]
    public sealed class OutputElementHintAttribute : Attribute
    {
        public OutputElementHintAttribute(string outputElement) { OutputElement = outputElement; }
        public string OutputElement { get; }
    }
}

namespace Microsoft.AspNetCore.Mvc.ViewFeatures
{
    public sealed class ModelExpression
    {
        public string Name { get; } = "";
        public object? Model { get; }
    }

    [AttributeUsage(AttributeTargets.Property, AllowMultiple = false, Inherited = true)]
    public class ViewContextAttribute : Attribute { }
}

namespace Microsoft.AspNetCore.Mvc.Rendering
{
    public class ViewContext { }
}

namespace Microsoft.AspNetCore.Mvc.TagHelpers
{
    using Microsoft.AspNetCore.Mvc.Rendering;
    using Microsoft.AspNetCore.Mvc.ViewFeatures;
    using Microsoft.AspNetCore.Razor.TagHelpers;

    [HtmlTargetElement("a", Attributes = "asp-action")]
    [HtmlTargetElement("a", Attributes = "asp-controller")]
    [HtmlTargetElement("a", Attributes = "asp-area")]
    [HtmlTargetElement("a", Attributes = "asp-page")]
    [HtmlTargetElement("a", Attributes = "asp-all-route-data")]
    [HtmlTargetElement("a", Attributes = "asp-route-*")]
    public class AnchorTagHelper : TagHelper
    {
        public override int Order => -1000;
        [HtmlAttributeName("asp-action")] public string? Action { get; set; }
        [HtmlAttributeName("asp-controller")] public string? Controller { get; set; }
        [HtmlAttributeName("asp-area")] public string? Area { get; set; }
        [HtmlAttributeName("asp-page")] public string? Page { get; set; }
        [HtmlAttributeName("asp-all-route-data", DictionaryAttributePrefix = "asp-route-")]
        public IDictionary<string, string> RouteValues { get; set; } = new Dictionary<string, string>();
        [HtmlAttributeNotBound, ViewContext] public ViewContext ViewContext { get; set; } = new();
    }

    [HtmlTargetElement("form")]
    public class FormTagHelper : TagHelper
    {
        [HtmlAttributeName("asp-action")] public string? Action { get; set; }
        [HtmlAttributeName("asp-controller")] public string? Controller { get; set; }
        [HtmlAttributeName("asp-antiforgery")] public bool? Antiforgery { get; set; }
        public string? Method { get; set; }
    }

    [HtmlTargetElement("input", Attributes = "asp-for", TagStructure = TagStructure.WithoutEndTag)]
    public class InputTagHelper : TagHelper
    {
        [HtmlAttributeName("asp-for")] public ModelExpression For { get; set; } = new();
        [HtmlAttributeName("asp-format")] public string? Format { get; set; }
        [HtmlAttributeName("type")] public string? InputTypeName { get; set; }
        public string? Name { get; set; }
        public string? Value { get; set; }
    }

    [HtmlTargetElement("label", Attributes = "asp-for")]
    public class LabelTagHelper : TagHelper
    {
        [HtmlAttributeName("asp-for")] public ModelExpression For { get; set; } = new();
    }

    [HtmlTargetElement("environment")]
    public class EnvironmentTagHelper : TagHelper
    {
        public string? Names { get; set; }
        public string? Include { get; set; }
        public string? Exclude { get; set; }
    }

    [HtmlTargetElement("partial", Attributes = "name", TagStructure = TagStructure.WithoutEndTag)]
    public class PartialTagHelper : TagHelper
    {
        public string Name { get; set; } = "";
        [HtmlAttributeName("for")] public ModelExpression? For { get; set; }
        public object? Model { get; set; }
    }
}
