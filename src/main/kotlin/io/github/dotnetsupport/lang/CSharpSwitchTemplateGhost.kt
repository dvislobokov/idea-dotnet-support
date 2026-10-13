package io.github.dotnetsupport.lang

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManagerListener
import com.intellij.codeInsight.template.impl.TemplateState

/**
 * The `switch` live template writes an empty body, as Rider's (0.1.175, Jellyfin № 12): the expression chosen, the caret lands in the body
 * by Tab or Enter without a change of the document, so nothing asks for the gray text. Here the end of the template asks for it, and the
 * rule "next case" ([NativeCSharpTypingGhost]) shows `case OperationType.Insert:` for a switch over an enum.
 */
class CSharpSwitchTemplateGhost : TemplateManagerListener {
    override fun templateStarted(state: TemplateState) {
        if (state.template?.key != "switch" || state.template?.groupName != "C#") return
        val editor = state.editor ?: return
        state.addTemplateStateListener(object : TemplateEditingAdapter() {
            override fun templateFinished(template: Template, brokenOff: Boolean) {
                if (!brokenOff) NativeCSharpTypingGhostProvider.offer(editor)
            }
        })
    }
}
