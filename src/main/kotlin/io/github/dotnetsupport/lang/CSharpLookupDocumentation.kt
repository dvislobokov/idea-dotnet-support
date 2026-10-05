package io.github.dotnetsupport.lang

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.PsiFileImpl

/**
 * Quick documentation of an item of the completion list (Ctrl+Q while the list is open, and the popup that follows the selection when
 * "Show the documentation popup" is on; COMPLETION_GAPS 2.11). The items of the native list are made of strings, so the symbol of an item
 * is found the way the code would find it: the item's name is put in place of what is typed at the caret, in a copy of the file, and
 * [NativeCSharpDocumentation] documents the name there — a local, a member after a dot, a type of the solution or of an assembly. Works
 * for the items of every contributor whose lookup string is a name; keywords, templates and `override` signatures have none.
 */
class CSharpLookupDocumentationTargetProvider : LookupElementDocumentationTargetProvider {
    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? {
        val file = psiFile as? CSharpFile ?: return null
        if (!CSharpFeatures.native(CSharpFeature.DOCUMENTATION, file.project)) return null
        val doc = CSharpLookupDocumentation.of(file, element, offset) ?: return null
        return NativeCSharpDocumentationTarget(doc, file.project)
    }
}

object CSharpLookupDocumentation {
    fun of(file: CSharpFile, element: LookupElement, offset: Int): NativeCSharpDocumentation.Doc? {
        if (CSharpCompletionBehaviourContributor.isKeyword(element)) return null
        val name = element.lookupString.substringBefore('(').substringBefore('<').trim()
        if (name.removePrefix("@").let { it.isEmpty() || !it.all { c -> c.isLetterOrDigit() || c == '_' } || it.first().isDigit() }) return null
        val text = file.viewProvider.document?.charsSequence ?: file.viewProvider.contents
        if (offset < 0 || offset > text.length) return null
        var start = offset
        while (start > 0 && isNameChar(text[start - 1])) start--
        var end = offset
        while (end < text.length && isNameChar(text[end])) end++
        val inserted = name + typeArguments(element)
        val copyText = buildString(text.length + inserted.length) {
            append(text, 0, start)
            append(inserted)
            append(text, end, text.length)
        }
        val copy = PsiFileFactory.getInstance(file.project).createFileFromText(file.name, file.language, copyText, false, false) as? CSharpFile ?: return null
        (copy as? PsiFileImpl)?.originalFile = file
        return NativeCSharpDocumentation.at(copy, start)
    }

    /** `List<>` / `Dictionary<,>` as the list shows a generic type: `<object>` / `<object,object>` so that the arity resolves. */
    private fun typeArguments(element: LookupElement): String {
        val shown = LookupElementPresentation.renderElement(element).itemText ?: return ""
        val open = shown.indexOf('<')
        if (open < 0 || !shown.endsWith(">")) return ""
        val arity = shown.substring(open + 1, shown.length - 1).count { it == ',' } + 1
        return List(arity) { "object" }.joinToString(",", "<", ">")
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
