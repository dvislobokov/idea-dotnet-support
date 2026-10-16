package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.highlighting.TemplateLanguageErrorFilter
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.templateLanguages.OuterLanguageElement
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlTokenType

/** The HTML parser's errors next to Razor (an `@if` around a start tag, a value made by C#) are not errors of the page. */
class RazorErrorFilter : TemplateLanguageErrorFilter(RazorTokenTypes.TEMPLATE, RazorFileViewProvider::class.java, "HTML")

/**
 * The tags HTML sees open or close in two branches (`@if (x) { <span> } else { <span class="b"> } … </span>`) are no error of the page:
 * an error of HTML on a tag with Razor inside it is dropped.
 */
class RazorHtmlStructureFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, psiFile: PsiFile?): Boolean {
        if (highlightInfo.severity != HighlightSeverity.ERROR) return true
        val viewProvider = psiFile?.viewProvider as? RazorFileViewProvider ?: return true
        val html = viewProvider.getPsi(viewProvider.templateDataLanguage) ?: return true
        val leaf = html.findElementAt(highlightInfo.startOffset) ?: return true
        if (leaf.node.elementType !in TAG_TOKENS) return true
        val tag = PsiTreeUtil.getParentOfType(leaf, XmlTag::class.java, false) ?: return true
        return PsiTreeUtil.findChildOfType(tag, OuterLanguageElement::class.java) == null && PsiTreeUtil.findChildOfType(tag.parentTag ?: tag, OuterLanguageElement::class.java) == null
    }

    private companion object {
        val TAG_TOKENS = TokenSet.create(XmlTokenType.XML_START_TAG_START, XmlTokenType.XML_END_TAG_START, XmlTokenType.XML_NAME, XmlTokenType.XML_TAG_NAME, XmlTokenType.XML_TAG_END, XmlTokenType.XML_EMPTY_ELEMENT_END)
    }
}

/**
 * HTML does not know components (`<Counter />`, `<MudButton>`) and tag helpers (`<partial>`, `asp-for`): until Razor knows them (R5)
 * their tags and attributes are not "unknown".
 */
class RazorHtmlInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (toolId !in TOOLS || element.containingFile?.viewProvider !is RazorFileViewProvider) return false
        val tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return false
        if (isComponentOrTagHelper(tag.name)) return true
        val attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute::class.java, false)
        return attribute != null && (attribute.name.startsWith("asp-") || attribute.name.startsWith("@"))
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    private fun isComponentOrTagHelper(name: String): Boolean =
        name.firstOrNull()?.isUpperCase() == true || '.' in name || ':' in name || name.lowercase() in TAG_HELPERS

    private companion object {
        val TOOLS = setOf("HtmlUnknownTag", "HtmlUnknownAttribute", "HtmlUnknownBooleanAttribute", "CheckEmptyScriptTag", "HtmlRequiredAltAttribute")
        val TAG_HELPERS = setOf("partial", "environment", "cache", "distributed-cache", "component", "persist-component-state")
    }
}
