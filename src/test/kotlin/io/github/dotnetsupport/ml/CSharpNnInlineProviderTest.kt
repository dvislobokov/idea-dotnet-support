package io.github.dotnetsupport.ml

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * The provider of the network's grey text over a fake engine (no model): the switch on the settings page acts at once (pass 13 of the
 * robot review: turned on in a running IDE, no grey text), and a next provider enabled for the place but with nothing to say does not
 * silence the network.
 */
class CSharpNnInlineProviderTest : BasePlatformTestCase() {
    private class FakeEngine(val text: String) : CSharpNnEngine {
        var calls = 0
        override suspend fun complete(editor: Any, context: CSharpNnInline.Context): CSharpNnInline.Answer { calls++; return CSharpNnInline.Answer(text, show = true, confProd = 0.9) }
    }

    /** Enabled everywhere, as `NativeCSharpTypingGhostProvider` is at a type name selected in the list, and answers [text] or nothing. */
    private class NextProvider(val text: String?) : InlineCompletionProvider {
        override val id = InlineCompletionProviderID("test.next")
        override fun isEnabled(event: InlineCompletionEvent) = true
        override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion =
            InlineCompletionSingleSuggestion.build(UserDataHolderBase()) { if (text != null) emit(InlineCompletionGrayTextElement(text)) }
    }

    private var wasEnabled = false

    override fun setUp() {
        super.setUp()
        wasEnabled = CSharpMlSettings.getInstance().inlineEnabled
    }

    override fun tearDown() {
        try { CSharpMlSettings.getInstance().inlineEnabled = wasEnabled } finally { super.tearDown() }
    }

    private fun event(): InlineCompletionEvent {
        myFixture.configureByText("MlInlineProvider.cs", "class C\n{\n    void M(object customer)\n    {\n        i<caret>\n    }\n}\n")
        return InlineCompletionEvent.DirectCall(myFixture.editor, myFixture.editor.caretModel.currentCaret, DataContext.EMPTY_CONTEXT)
    }

    private fun shown(provider: InlineCompletionProvider, event: InlineCompletionEvent): String? {
        if (!provider.isEnabled(event)) return null
        val request = event.toRequest()!!
        return runBlocking(Dispatchers.Default) {
            val variants = provider.getSuggestion(request).getVariants()
            variants.firstOrNull()?.elements?.toList()?.joinToString("") { it.text }?.takeIf { it.isNotEmpty() }
        }
    }

    fun testTurningTheGreyTextOnActsWithoutARestart() {
        val engine = FakeEngine("f (customer == null)")
        val provider = CSharpNnInlineCompletionProvider({ engine }, { emptyList() })
        val event = event()
        CSharpMlSettings.getInstance().inlineEnabled = false
        assertFalse(provider.isEnabled(event))
        CSharpMlSettings.getInstance().inlineEnabled = true
        assertEquals("f (customer == null)", shown(provider, event))
        CSharpMlSettings.getInstance().inlineEnabled = false
        assertFalse(provider.isEnabled(event))
        assertEquals(1, engine.calls)
    }

    fun testANextProviderWithNothingToSayDoesNotSilenceTheNetwork() {
        CSharpMlSettings.getInstance().inlineEnabled = true
        val engine = FakeEngine("f (customer == null)")
        lateinit var provider: CSharpNnInlineCompletionProvider
        provider = CSharpNnInlineCompletionProvider({ engine }, { listOf(provider, NextProvider(null)) })
        assertEquals("f (customer == null)", shown(provider, event()))
        // the next provider's own text wins, the network is not asked
        provider = CSharpNnInlineCompletionProvider({ engine }, { listOf(provider, NextProvider("nt count = 0;")) })
        assertEquals("nt count = 0;", shown(provider, event()))
        assertEquals(1, engine.calls)
    }

    fun testNonEmptyDropsSuggestionsWithoutElements() = runBlocking {
        assertNull(CSharpNnInlineCompletionProvider.nonEmpty(InlineCompletionSuggestion.Empty))
        assertNull(CSharpNnInlineCompletionProvider.nonEmpty(InlineCompletionSingleSuggestion.build(UserDataHolderBase()) {}))
        val kept = CSharpNnInlineCompletionProvider.nonEmpty(InlineCompletionSingleSuggestion.build(UserDataHolderBase()) { emit(InlineCompletionGrayTextElement("x")) })
        assertEquals(listOf("x"), kept!!.getVariants().single().elements.toList().map { it.text })
    }
}
