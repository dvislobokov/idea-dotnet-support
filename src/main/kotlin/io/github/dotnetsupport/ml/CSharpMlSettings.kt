package io.github.dotnetsupport.ml

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Machine-wide switches of the ML completion (Settings | .NET | ML completion; the page exists only in a build that bundles the models,
 * `-PmlEnabled=true`, [CSharpMlModels.isBundled]): the ranker of the completion list ([CSharpMlCompletionRanker]) and the grey text of
 * the transformer ([CSharpNnInlineCompletionProvider]). Both are off by default (the user's decision 2026-10-08: a build with the models
 * changes nothing until the user turns them on); the thresholds are the measured ones (ML_INLINE_TASK.md, the engine's NN-COMPLETION-API.md),
 * the ones after a dot and on an empty line are separate gates, adopted from the Go plugin's live use.
 */
@Service(Service.Level.APP)
@State(name = "CSharpMlCompletion", storages = [Storage("dotnet-support-ml.xml")])
class CSharpMlSettings : SimplePersistentStateComponent<CSharpMlSettings.Options>(Options()) {
    class Options : BaseState() {
        /** The ranker of the completion list; off (the default): the plugin's own order, the ranker pair is not even loaded. */
        var rankerEnabled by property(false)
        /** Grey "ML" after the rows the model ordered. */
        var showMarker by property(false)
        /** A directory with the model files (the names of `ml-models/csharp`) instead of the bundled ones; empty: bundled. */
        var modelDirectory by string("")
        /** Grey text to the end of the line from the transformer; off (the default): the network is not even loaded. */
        var inlineEnabled by property(false)
        /** `confProd` a suggestion needs to be shown (0.7: 14 % of positions, 94 % exact lines; 0.8: 10 % / 97 %). */
        var inlineThreshold by property(0.7f)
        /** The gate right after `.`, `?.`, `::`, `->` (0.5: 30 % of such positions, 96 % exact) and for a `;` that ends the statement: the model is as right there but less sure. */
        var inlineDotThreshold by property(0.5f)
        /** The gate on a line with nothing typed yet (after Enter): the first statement of a block is a guess among a few. */
        var inlineEmptyLineThreshold by property(0.25f)
        /** Show suggestions that are closing brackets only (`)`, `}`): off by default, the editor pairs them anyway; a `;` ending the statement is shown regardless. */
        var inlineShowClosers by property(false)
        /** Gate on the confidence of the code tokens only, so a line with a string literal is shown with its text guessed. */
        var inlineGuessStrings by property(true)
        /** A line that leaves a bracket open goes on to the lines below until the brackets close ([CSharpNnInline.continueOpenBrackets]); off: one line. */
        var inlineContinueOpenBrackets by property(true)
        /** Every answer of the network (text, confidence, gate, shown or not) goes to the plugin log (.NET | Plugin Logs, category `ml`). */
        var inlineDebugLog by property(false)
        /**
         * The memory of chosen items (`CSharpAcceptanceMemory`, 0.1.135) in the ranker's score: `weight × ln(1 + count)`. 0.3 on the scale of
         * `e18-rank` (a close call is 0.2–0.5 apart, a clear loss — not imported, a keyword at a value — 1.5 or more): three choices win the
         * close call (0.42), ten do not win the clear loss (0.72). 0: not added.
         */
        var acceptanceWeight by property(0.3f)
        /** Grey text with the caret inside a string or character literal (on: log and error messages, format strings are code-like); off: the network is not asked there. */
        var inlineInStrings by property(true)
        /** Grey text with the caret inside a comment (prose; off by default). */
        var inlineInComments by property(false)
    }

    var rankerEnabled: Boolean
        get() = state.rankerEnabled
        set(value) { state.rankerEnabled = value }

    var showMarker: Boolean
        get() = state.showMarker
        set(value) { state.showMarker = value }

    var modelDirectory: String
        get() = state.modelDirectory ?: ""
        set(value) { state.modelDirectory = value }

    var inlineEnabled: Boolean
        get() = state.inlineEnabled
        set(value) { state.inlineEnabled = value }

    var inlineThreshold: Double
        get() = state.inlineThreshold.toString().toDouble()
        set(value) { state.inlineThreshold = value.toFloat() }

    var inlineDotThreshold: Double
        get() = state.inlineDotThreshold.toString().toDouble()
        set(value) { state.inlineDotThreshold = value.toFloat() }

    var inlineEmptyLineThreshold: Double
        get() = state.inlineEmptyLineThreshold.toString().toDouble()
        set(value) { state.inlineEmptyLineThreshold = value.toFloat() }

    var inlineShowClosers: Boolean
        get() = state.inlineShowClosers
        set(value) { state.inlineShowClosers = value }

    var inlineGuessStrings: Boolean
        get() = state.inlineGuessStrings
        set(value) { state.inlineGuessStrings = value }
    var inlineContinueOpenBrackets: Boolean
        get() = state.inlineContinueOpenBrackets
        set(value) { state.inlineContinueOpenBrackets = value }

    var inlineDebugLog: Boolean
        get() = state.inlineDebugLog
        set(value) { state.inlineDebugLog = value }

    var acceptanceWeight: Double
        get() = state.acceptanceWeight.toString().toDouble()
        set(value) { state.acceptanceWeight = value.toFloat() }
    var inlineInStrings: Boolean
        get() = state.inlineInStrings
        set(value) { state.inlineInStrings = value }
    var inlineInComments: Boolean
        get() = state.inlineInComments
        set(value) { state.inlineInComments = value }

    companion object {
        fun getInstance(): CSharpMlSettings = service()
    }
}
