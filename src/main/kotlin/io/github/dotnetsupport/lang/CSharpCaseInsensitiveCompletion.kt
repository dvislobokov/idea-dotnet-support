package io.github.dotnetsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.codeInsight.lookup.WeighingContext

/**
 * In a C# file the case of what is typed does not matter to the completion list: `writeli` finds `WriteLine`, as in Rider and in
 * Visual Studio. The platform matches by the setting of the IDE (Editor | General | Code Completion | Match case), which by default
 * wants the first letter as it is written; that is the habit of Java, where a small first letter means a variable and a capital one
 * a type. It holds for every item of the list, whoever adds it — the language server, the index of assemblies, the templates: this
 * contributor is the first, and the ones after it get a matcher that does not look at the case.
 *
 * What matches in its case as well stands above what matches without it, before the priority of the kind ([CaseMatchWeigher]): `pub`
 * puts the keyword `public` above the type `PublicKey`, `s` puts `static` and `string` above `String` and `SByte`, as Rider and Visual
 * Studio order them (reported: the keywords were under every type that matched case-insensitively, out of sight).
 */
class CSharpCaseInsensitiveCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is CSharpFile) return
        val matcher = result.prefixMatcher
        if (matcher is CamelHumpMatcher && !matcher.isCaseSensitive) return
        val insensitive = result.withPrefixMatcher(CamelHumpMatcher(matcher.prefix, false))
        // the contributors after this one get a sorter of the platform; the case goes into it in front of the priority of the kind
        insensitive.runRemainingContributors(parameters, { found: CompletionResult ->
            val sorter = found.sorter.weighBefore("priority", CaseMatchWeigher())
            CompletionResult.wrap(found.lookupElement, found.prefixMatcher, sorter)?.let(insensitive::passResult)
        })
        // they have run: not a second time with the matcher of the platform
        result.stopHere()
    }
}

/** 0 for an item one of whose lookup strings starts with the typed prefix in its case, 1 for one that matches only without the case. */
class CaseMatchWeigher : LookupElementWeigher(ID, false, true) {
    override fun weigh(element: LookupElement, context: WeighingContext): Comparable<*> = if (matchesInCase(element, context.itemPattern(element))) 0 else 1

    companion object {
        const val ID = "csharpCaseMatch"

        fun matchesInCase(element: LookupElement, prefix: String): Boolean =
            prefix.isNotEmpty() && element.allLookupStrings.any { it.startsWith(prefix) }
    }
}
