package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.razor.lang.syntax.RazorFileKind

/**
 * Completion in the C# of a Razor file beyond what the C# of the document gives (the user's check 2026-10-19 in the sandbox, as in Rider):
 * - after `@` at the start of a line of markup, the directives of the file's kind (`inject`, `code`, `page`, `using`...) and the statements
 *   (`if`, `foreach`...) — to the C# of the document `@inj` is an expression `inj`;
 * - after the type of an `@inject`, names made of the type (`NavigationManager` → `NavigationManager`, `Manager`);
 * - none of the generator's glue: `__builder`, the render method (`BuildRenderTree`, `ExecuteAsync` and their `__2` parts).
 */
class RazorCSharpCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        val manager = InjectedLanguageManager.getInstance(position.project)
        if (!manager.isInjectedFragment(position.containingFile)) return
        val razor = RazorTagHelperEditor.razorFile(manager.getTopLevelFile(position)) ?: return
        // the host of the caret: `getInjectionHost` of a document of many pieces is its first one
        val hostOffset = manager.injectedToHost(position, parameters.offset)
        val host = listOf(hostOffset, hostOffset - 1).firstNotNullOfOrNull { offset ->
            razor.node.findLeafElementAt(offset)?.psi?.let { PsiTreeUtil.getParentOfType(it, RazorCodeLiteral::class.java, false) }?.takeIf { it.isCode }
        } ?: return
        val prefix = result.prefixMatcher.prefix
        val text = position.containingFile.text
        val start = parameters.offset - prefix.length
        val afterDot = text.substring(0, start.coerceIn(0, text.length)).trimEnd().endsWith(".")

        injectName(host)?.let { type ->
            // a name is declared here: nothing of the C# around it
            for (name in namesOf(type)) result.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name), 100.0))
            result.stopHere()
            return
        }
        val ours = if (afterDot) emptyList() else transitionItems(razor, host)
        for (item in ours) result.addElement(item)
        val names = ours.mapTo(HashSet()) { it.lookupString }
        result.runRemainingContributors(parameters) { found ->
            val name = found.lookupElement.lookupString
            if (name !in names && !isGlue(name, afterDot)) result.passResult(found)
        }
    }

    /** The directives and statements after an `@` that starts an expression in the markup; empty anywhere else. */
    private fun transitionItems(razor: RazorFile, host: RazorCodeLiteral): List<LookupElement> {
        val text = razor.text
        val at = host.textRange.startOffset - 1
        if (at < 0 || text[at] != '@') return emptyList()
        // an implicit expression of the markup: not `@(…)`, not a block, not a directive's token
        if (PsiTreeUtil.getParentOfType(host, RazorDirectivePsi::class.java) != null) return emptyList()
        if (!host.text.first().let { it.isLetter() || it == '_' }) return emptyList()
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val atLineStart = text.substring(lineStart, at).isBlank()
        val items = ArrayList<LookupElement>()
        if (atLineStart) for (name in directives(razor.name)) items += keyword(name, "directive")
        for (name in STATEMENTS) items += keyword(name, "statement")
        return items
    }

    private fun keyword(name: String, kind: String): LookupElement =
        PrioritizedLookupElement.withPriority(LookupElementBuilder.create(name).bold().withTypeText(kind).withInsertHandler(::insertSpace), 100.0)

    private fun insertSpace(context: InsertionContext, @Suppress("UNUSED_PARAMETER") item: LookupElement) {
        val document = context.document
        val tail = context.tailOffset
        if (tail < document.textLength && document.charsSequence[tail] == ' ') context.editor.caretModel.moveToOffset(tail + 1)
        else {
            document.insertString(tail, " ")
            context.editor.caretModel.moveToOffset(tail + 1)
        }
    }

    /** The type of the `@inject` whose name [host] is; null when it is no name of an `@inject`. */
    private fun injectName(host: RazorCodeLiteral): String? {
        val directive = PsiTreeUtil.getParentOfType(host, RazorDirectivePsi::class.java) ?: return null
        if (directive.directiveName != "inject") return null
        val tokens = directive.tokens
        if (tokens.size < 2 || tokens[1] != host) return null
        return tokens[0].text.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        /** The statements an `@` starts in the markup, as Razor's parser knows them. */
        private val STATEMENTS = listOf("if", "foreach", "for", "while", "do", "switch", "try", "lock", "await")

        /** The directives of the file [name]: the ones its kind registers and the parser's own. */
        fun directives(name: String): List<String> {
            val options = RazorAstBuilder.options(name)
            val own = if (options.fileKind == RazorFileKind.Legacy) listOf("using", "addTagHelper", "removeTagHelper", "tagHelperPrefix") else listOf("using")
            return (options.directives.map { it.directive } + own).distinct()
        }

        /**
         * Names for a property of the type [type]: the simple name without the `I` of an interface and its type arguments, then its endings by
         * words — `IJSRuntime` → `JSRuntime`, `Runtime`; `ILogger<Counter>` → `Logger`.
         */
        fun namesOf(type: String): List<String> {
            var name = type.substringBefore('<').trimEnd('?', '[', ']', ' ').substringAfterLast('.').substringAfterLast(':')
            if (name.length > 2 && name[0] == 'I' && name[1].isUpperCase() && (name[2].isLowerCase() || name.length > 3)) name = name.substring(1)
            if (name.isEmpty() || !name[0].isLetter()) return emptyList()
            val words = name.split(Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")).filter { it.isNotEmpty() }
            return words.indices.map { words.subList(it, words.size).joinToString("") }.distinct()
        }

        /** A member of the generator's glue: `__builder`, the render method and its parts; after a dot only the `__` names (a library may have `ExecuteAsync`). */
        fun isGlue(name: String, afterDot: Boolean): Boolean =
            name.startsWith("__") || Regex("""\w__\d+$""").containsMatchIn(name) || (!afterDot && (name == "BuildRenderTree" || name == "ExecuteAsync"))
    }
}
