package io.github.dotnetsupport.frameworks.razor

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpArgumentList
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseArgumentList
import io.github.dotnetsupport.csharp.lang.psi.CSharpBracketedArgumentList
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.NativeCSharpInlayHints
import io.github.dotnetsupport.lang.NativeCSharpLibraryTypeHintHandler
import io.github.dotnetsupport.lang.semantic.CSharpInjections
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession

/**
 * The inlay hints of the C# of a Razor file (RAZOR_PLAN.md, R4): the platform asks the providers of the file's language, Razor, so those
 * of C# never see the injected document. The hints of the document are made by the C# rules and options as they are and shown in the
 * markup — those of text the user wrote only: the glue of the generator (`__builder.AddAttribute(0, "Step", …)`, `TypeCheck<int>(step)`
 * around an attribute's value) has calls and declarations too, and a `value:` before `step` of `Step="@step"` would be the generator's.
 */
abstract class RazorInlayHintsProvider(private val kind: NativeCSharpInlayHints.Kind) : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val razor = RazorTagHelperEditor.razorFile(file) ?: return null
        val options = NativeCSharpInlayHints.Options.fromSettings()
        if (!options.any(kind)) return null
        val format = if (kind == NativeCSharpInlayHints.Kind.PARAMETER) HintFormat.default.withColorKind(HintColorKind.Parameter) else HintFormat.default
        return object : OwnBypassCollector {
            override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
                val hints = try {
                    hintsOf(razor, options, kind)
                } catch (_: IndexNotReadyException) {
                    return
                }
                for ((offset, hint) in hints) {
                    val action = when (val target = hint.target) {
                        is NativeCSharpInlayHints.Target.Source -> InlayActionData(PsiPointerInlayActionPayload(SmartPointerManager.createPointer(target.element)), PsiPointerInlayActionNavigationHandler.HANDLER_ID)
                        is NativeCSharpInlayHints.Target.Library -> InlayActionData(StringInlayActionPayload(target.fullName), NativeCSharpLibraryTypeHintHandler.ID)
                        null -> null
                    }
                    sink.addPresentation(InlineInlayPosition(offset, relatedToPrevious = hint.after), hintFormat = format) { text(hint.text, action) }
                }
            }
        }
    }

    companion object {
        /** The hints of [kind] of the C# document of [razor], by the offset in the Razor file they are shown at. */
        fun hintsOf(razor: RazorFile, options: NativeCSharpInlayHints.Options, kind: NativeCSharpInlayHints.Kind): List<Pair<Int, NativeCSharpInlayHints.Hint>> {
            val host = RazorCSharpDocument.places(razor).firstOrNull()?.host ?: return emptyList()
            val manager = InjectedLanguageManager.getInstance(razor.project)
            val injected = manager.getInjectedPsiFiles(host)?.firstNotNullOfOrNull { it.first as? CSharpFile } ?: return emptyList()
            if (!NativeCSharpInlayHints.serves(injected)) return emptyList()
            val resolver = CSharpSemanticSession(razor.project).resolver(injected)
            return NativeCSharpInlayHints.hints(injected, options, resolver).filter { it.kind == kind && isWritten(injected, it) }
                .map { manager.injectedToHost(injected, it.offset) to it }
        }

        /** The hint is at text the user wrote, and a parameter's is in a call the user wrote (its parenthesis is written too). */
        private fun isWritten(injected: CSharpFile, hint: NativeCSharpInlayHints.Hint): Boolean {
            val at = if (hint.after) TextRange(hint.offset - 1, hint.offset) else TextRange(hint.offset, hint.offset + 1)
            if (at.startOffset < 0 || at.endOffset > injected.textLength || !CSharpInjections.isWritten(injected, at)) return false
            if (hint.kind != NativeCSharpInlayHints.Kind.PARAMETER) return true
            val arguments = PsiTreeUtil.getParentOfType(injected.findElementAt(hint.offset), CSharpBaseArgumentList::class.java) ?: return false
            val open = when (arguments) {
                is CSharpArgumentList -> arguments.openParenToken
                is CSharpBracketedArgumentList -> arguments.openBracketToken
                else -> null
            } ?: return false
            return CSharpInjections.isWritten(injected, open.textRange)
        }
    }
}

class RazorParameterHintsProvider : RazorInlayHintsProvider(NativeCSharpInlayHints.Kind.PARAMETER)

class RazorTypeHintsProvider : RazorInlayHintsProvider(NativeCSharpInlayHints.Kind.TYPE)
