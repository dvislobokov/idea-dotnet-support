package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.psi.CSharpAccessorList
import io.github.dotnetsupport.csharp.lang.psi.CSharpArgumentList
import io.github.dotnetsupport.csharp.lang.psi.CSharpArrowExpressionClause
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseTypeDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpBlock
import io.github.dotnetsupport.csharp.lang.psi.CSharpEqualsValueClause
import io.github.dotnetsupport.csharp.lang.psi.CSharpMethodDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpParameterList
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.documentText

/**
 * The event functions of Unity (UNITY_PLAN.md, step 3, as Rider's "Generate Unity event functions" in the completion): in the body of a
 * class that derives from `MonoBehaviour` (`ScriptableObject`, `Editor`, `EditorWindow`, `AssetPostprocessor`...), at the start of a member,
 * `Upd` offers `Update` — the messages of that base the class does not declare yet — and writes the method with its signature and an
 * empty body, the caret inside: `private void OnTriggerEnter(Collider other) { }`. A modifier typed before the name is kept
 * (`public Upd` → `public void Update()`), else the method is private, as Rider writes it.
 */
class UnityEventFunctionCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? CSharpFile ?: return
        if (file.virtualFile?.let(UnityProjects::rootOf) == null) return
        val text = parameters.documentText
        val offset = parameters.offset
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val match = MEMBER_START.matchEntire(text.subSequence(lineStart, offset)) ?: return
        val type = memberPositionType(file, lineStart, offset) ?: return
        val base = UnityTypes.unityBase(type) ?: return
        val declared = PsiTreeUtil.findChildrenOfType(type, CSharpMethodDeclaration::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, CSharpBaseTypeDeclaration::class.java) == type }
            .mapNotNullTo(HashSet()) { it.identifier?.text }
        val modifiers = match.groupValues[2].split(Regex("\\s+")).filter { it.isNotBlank() && it != "void" }
        val access = modifiers.filter { it in ACCESS }.ifEmpty { listOf("private") }.joinToString(" ")
        val set = result.withPrefixMatcher(match.groupValues[3])
        for (message in UnityMessages.of(base).sorted()) {
            if (message in declared || message in UnityMessages.OBSOLETE) continue
            val signature = UnityMessages.signature(message)
            val item = LookupElementBuilder.create(message)
                .withIcon(AllIcons.Nodes.Method)
                .withTailText("(${signature.parameters})", true)
                .withTypeText("event function")
                .withInsertHandler { context, _ -> write(context, lineStart + match.groupValues[1].length, match.groupValues[1], access, message, signature) }
            set.addElement(PrioritizedLookupElement.withPriority(item, PRIORITY))
        }
    }

    /** `private void Update()`, `{`, an empty line with the caret, `}`: in place of what was typed on the line, at its indent. */
    private fun write(context: InsertionContext, start: Int, indent: String, access: String, name: String, signature: UnityMessages.Signature) {
        val document = context.document
        val static = if (signature.isStatic) "static " else ""
        val header = "$access $static${signature.returnType} $name(${signature.parameters})"
        val body = indent + INDENT
        val returns = when (signature.returnType) {
            "void" -> ""
            "bool" -> "return false;"
            "int", "uint" -> "return 0;"
            else -> "return default;"
        }
        val generated = "$header\n$indent{\n$body$returns\n$indent}"
        document.replaceString(start, context.tailOffset, generated)
        // on the body's line, after its indent: before `return …;` where there is one
        context.editor.caretModel.moveToOffset(start + generated.indexOf("{\n") + 2 + body.length)
        PsiDocumentManager.getInstance(context.project).commitDocument(document)
    }

    /**
     * The class whose member list [offset] is in: the nearest type declaration above the element before the caret, with no method body,
     * accessor list, argument or parameter list, initializer or expression body between them; null anywhere else.
     */
    private fun memberPositionType(file: CSharpFile, lineStart: Int, offset: Int): CSharpBaseTypeDeclaration? {
        var element: PsiElement? = file.findElementAt(maxOf(lineStart, offset - 1).coerceAtMost(maxOf(file.textLength - 1, 0)))
        while (element != null && element !is CSharpFile) {
            when (element) {
                is CSharpBaseTypeDeclaration -> return element
                is CSharpBlock, is CSharpAccessorList, is CSharpArgumentList, is CSharpParameterList, is CSharpEqualsValueClause, is CSharpArrowExpressionClause -> return null
            }
            element = element.parent
        }
        return null
    }

    private companion object {
        /** The start of a member on its line: indent, modifiers (and `void`), the beginning of a name. */
        val MEMBER_START = Regex("""(\s*)((?:(?:public|private|protected|internal|void)\s+)*)([A-Za-z_]\w*)?""")
        val ACCESS = setOf("public", "private", "protected", "internal")
        const val INDENT = "    "
        const val PRIORITY = 100.0
    }
}
