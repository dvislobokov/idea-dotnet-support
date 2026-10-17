package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import io.github.dotnetsupport.csharp.lang.psi.CSharpLiteralExpression
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.documentText

/**
 * The names of `ProjectSettings` in the strings that take them (UNITY_PLAN.md, "плюшки", as Rider completes them): tags in
 * `CompareTag("`, layers in `LayerMask.NameToLayer("`, the scenes of the build in `SceneManager.LoadScene("`, the axes in `Input.GetAxis("`.
 */
class UnityStringCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        val root = file.virtualFile?.let(UnityProjects::rootOf) ?: return
        val text = parameters.documentText
        val offset = parameters.offset
        val line = text.subSequence(text.lastIndexOf('\n', offset - 1) + 1, offset)
        val kind = UnityStringKind.at(line) ?: return
        val prefix = line.substring(line.lastIndexOf('"') + 1)
        val settings = UnityProjectSettings.of(file.project, root)
        val set = result.withPrefixMatcher(prefix)
        val scenes = settings.scenes.associateBy { it.name }
        for (name in kind.names(settings)) {
            var item = LookupElementBuilder.create(name).withTypeText(kind.noun).withIcon(AllIcons.Nodes.Tag)
            scenes[name]?.takeIf { kind == UnityStringKind.SCENE }?.let { scene -> item = item.withTailText("  ${scene.path}" + if (scene.enabled) "" else " (disabled)", true) }
            set.addElement(PrioritizedLookupElement.withPriority(item, 100.0))
        }
        // the strings of Unity are names, nothing else fits there
        set.stopHere()
    }
}

/**
 * A tag, layer, scene or input axis the project does not define (Rider: "The tag is not defined in the 'Tags & Layers'"): `CompareTag`
 * with a misspelled tag throws at run time, `NameToLayer` gives -1, `LoadScene` of a scene not in the build fails.
 */
class UnityUnknownNameInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? CSharpFile ?: return PsiElementVisitor.EMPTY_VISITOR
        val root = file.originalFile.virtualFile?.let(UnityProjects::rootOf) ?: return PsiElementVisitor.EMPTY_VISITOR
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return PsiElementVisitor.EMPTY_VISITOR
        val settings = UnityProjectSettings.of(file.project, root)
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val literal = element as? CSharpLiteralExpression ?: return
                val raw = literal.text
                if (!raw.startsWith("\"") || !raw.endsWith("\"") || raw.length < 2) return
                val start = literal.textRange.startOffset
                if (start >= document.textLength) return
                val lineStart = document.getLineStartOffset(document.getLineNumber(start))
                val kind = UnityStringKind.at(document.charsSequence.subSequence(lineStart, start + 1)) ?: return
                val value = raw.substring(1, raw.length - 1)
                if (value.isEmpty() || '\\' in value) return
                val known = kind.names(settings)
                if (known.isEmpty() && kind != UnityStringKind.TAG) return // a project without the asset: nothing to compare with
                val found = when (kind) {
                    UnityStringKind.SCENE -> settings.scenes.any { scene -> value == scene.name || value == scene.path || value == scene.path.removePrefix("Assets/").removeSuffix(".unity") || value == scene.path.removeSuffix(".unity") }
                    else -> value in known
                }
                if (found) return
                val where = when (kind) {
                    UnityStringKind.TAG -> "Tags and Layers (ProjectSettings/TagManager.asset)"
                    UnityStringKind.LAYER -> "Tags and Layers (ProjectSettings/TagManager.asset)"
                    UnityStringKind.SCENE -> "the scenes of the build (ProjectSettings/EditorBuildSettings.asset)"
                    UnityStringKind.AXIS -> "the Input Manager (ProjectSettings/InputManager.asset)"
                }
                holder.registerProblem(literal, "The ${kind.noun} '$value' is not defined in $where")
            }
        }
    }
}
