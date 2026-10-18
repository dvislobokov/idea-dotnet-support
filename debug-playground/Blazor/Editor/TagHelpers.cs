using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Razor.TagHelpers;

namespace Playground.Blazor.Editor;

// RAZOR_PLAN.md, R5e part 3: tag helpers of the project for CshtmlTagHelpers.cshtml (the markers are there).

/** `<email mail-to="…">`: a tag by the name of the class (EmailTagHelper → email), an attribute by the name of the property (MailTo → mail-to). */
public class EmailTagHelper : TagHelper
{
    public string? MailTo { get; set; }

    public override void Process(TagHelperContext context, TagHelperOutput output)
    {
        output.TagName = "a";
        output.Attributes.SetAttribute("href", "mailto:" + MailTo);
    }
}

/** `tooltip="…"` on any element: the attribute is named by [HtmlAttributeName], not by the property. */
[HtmlTargetElement(Attributes = "tooltip")]
public class TooltipTagHelper : TagHelper
{
    [HtmlAttributeName("tooltip")] public string? Text { get; set; }

    public override void Process(TagHelperContext context, TagHelperOutput output)
    {
        output.Attributes.RemoveAll("tooltip");
        output.Attributes.SetAttribute("title", Text);
    }
}

/** `<vc:latest-news count="3" />`: a view component — a tag `vc:` + its name in kebab case, an attribute per parameter of `Invoke`. */
public class LatestNewsViewComponent : ViewComponent
{
    public IViewComponentResult Invoke(int count) => Content($"{count} news");
}

/** The model of the form of CshtmlTagHelpers.cshtml (`asp-for`). */
public class Contact
{
    public string Name { get; set; } = "";
    public string Email { get; set; } = "";
}
