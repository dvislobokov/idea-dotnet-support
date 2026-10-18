package io.github.dotnetsupport.frameworks.razor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.xml.util.XmlTagUtil
import io.github.dotnetsupport.razor.lang.syntax.TagHelperKind

/**
 * The colors of the markup the compiler binds, as in Rider: the tag of a component, a view component or a tag helper (`<Counter>`,
 * `<email>`, `<a asp-action>`) in the color of a class, an attribute setting its parameter or property (`Step=`, `asp-for=`) in the color of
 * a property; HTML's own tags and attributes keep HTML's colors (RAZOR_PLAN.md, R5e).
 */
class RazorMarkupColorAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is XmlTag && element !is XmlAttribute) return
        val razor = RazorTagHelperEditor.razorFile(element.containingFile) ?: return
        val context = RazorTagHelperEditor.context(razor) ?: return
        val tag = element as? XmlTag ?: (element as XmlAttribute).parent ?: return
        val applying = context.tagHelpers.filter { it.kind in ELEMENTS && RazorTagHelperEditor.applies(context, it, tag) }
        if (applying.isEmpty()) return
        when (element) {
            is XmlTag -> if (RazorTagHelperEditor.tagDescriptors(context, tag.name).any { it in applying }) {
                for (name in listOfNotNull(XmlTagUtil.getStartTagNameElement(tag), XmlTagUtil.getEndTagNameElement(tag))) {
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(name).textAttributes(ELEMENT).create()
                }
            }
            is XmlAttribute -> if (!element.name.startsWith("@") && RazorTagHelperEditor.boundAttribute(applying, element.name) != null) {
                val name = element.nameElement ?: return
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(name).textAttributes(ATTRIBUTE).create()
            }
        }
    }

    companion object {
        // the colors of the schemes: colorSchemes/RazorMarkup*.xml; a scheme without them falls back to the C# of the names
        val ELEMENT: TextAttributesKey = createTextAttributesKey("RAZOR_COMPONENT_ELEMENT", io.github.dotnetsupport.lang.CSharpColors.CLASS)
        val ATTRIBUTE: TextAttributesKey = createTextAttributesKey("RAZOR_COMPONENT_ATTRIBUTE", io.github.dotnetsupport.lang.CSharpColors.PROPERTY)

        // the kinds an element is: the directive attributes of every element (`@bind`, `@onclick`) are colored by the lexer
        private val ELEMENTS = setOf(TagHelperKind.Component, TagHelperKind.ChildContent, TagHelperKind.ITagHelper, TagHelperKind.ViewComponent)
    }
}
