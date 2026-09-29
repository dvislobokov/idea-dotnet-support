package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher

/**
 * In a C# file the case of what is typed does not matter to the completion list: `writeli` finds `WriteLine`, as in Rider and in
 * Visual Studio. The platform matches by the setting of the IDE (Editor | General | Code Completion | Match case), which by default
 * wants the first letter as it is written; that is the habit of Java, where a small first letter means a variable and a capital one
 * a type. It holds for every item of the list, whoever adds it — the language server, the index of assemblies, the templates: this
 * contributor is the first, and the ones after it get a matcher that does not look at the case. What matches in its case as well
 * still stands above what matches without it: the list is sorted by that before anything else.
 */
class CSharpCaseInsensitiveCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is CSharpFile) return
        val matcher = result.prefixMatcher
        if (matcher is CamelHumpMatcher && !matcher.isCaseSensitive) return
        result.withPrefixMatcher(CamelHumpMatcher(matcher.prefix, false)).runRemainingContributors(parameters, true)
        // they have run: not a second time with the matcher of the platform
        result.stopHere()
    }
}
