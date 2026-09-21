package io.github.dotnetsupport.roslyn

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.components.service
import com.intellij.patterns.StandardPatterns
import io.github.dotnetsupport.lang.CSharpFile

/**
 * `public const string`: the popup came for `public` and never again. A space is a trigger character of Roslyn, so completion starts
 * right after `public `, with an empty prefix; the platform does not show such a popup and remembers "nothing here" (the phase
 * EmptyAutoPopup), and while that phase lasts the letters typed next start no new popup: the rule is meant for a word that has no
 * matches, and here the word has not even begun. A completion that starts with an empty prefix is therefore restarted by the first
 * letter. Only by the first one: further letters filter the list locally, without the server.
 */
class RoslynCompletionRestart : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is CSharpFile || !parameters.originalFile.project.service<RoslynWorkspace>().isLoaded) return
        if (result.prefixMatcher.prefix.isEmpty()) result.restartCompletionOnPrefixChange(StandardPatterns.string().withLength(1))
    }
}
