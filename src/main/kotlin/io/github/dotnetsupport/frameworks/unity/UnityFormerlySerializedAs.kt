package io.github.dotnetsupport.frameworks.unity

import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.CSharpBaseFieldDeclaration
import io.github.dotnetsupport.csharp.lang.psi.CSharpVariableDeclarator
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpRenameParticipant

/**
 * The rename of a serialized field the assets set (UNITY_PLAN.md, the asset index), as Rider does it: the scenes and prefabs keep the
 * value under the old name, and Unity drops it on the next load unless the field says `[FormerlySerializedAs("old")]`. The attribute goes
 * above the field (its `using UnityEngine.Serialization;` too) when some asset stores a value under [oldName]; a field of several
 * declarators (`int a, b;` — the attribute would name both) and one that already has the attribute are left as they are.
 */
class UnityFormerlySerializedAs : CSharpRenameParticipant {
    override fun insertions(declaration: PsiElement, oldName: String): List<CSharpRenameParticipant.Insertion> {
        if (declaration !is CSharpVariableDeclarator && declaration !is CSharpBaseFieldDeclaration) return emptyList()
        val file = declaration.containingFile as? CSharpFile ?: return emptyList()
        if (file.virtualFile?.let(UnityProjects::rootOf) == null) return emptyList()
        val field = UnityAssetUsages.serializedField(declaration) ?: return emptyList()
        if ((field.declaration?.variables?.size ?: 0) != 1) return emptyList()
        if (oldName in UnityAssetUsages.formerNames(field)) return emptyList()
        if (UnityAssetUsages.fieldValues(declaration).none { (_, value) -> value.name == oldName }) return emptyList()
        val text = file.viewProvider.contents
        val start = field.textRange.startOffset
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val indent = text.subSequence(lineStart, start).takeWhile { it == ' ' || it == '\t' }
        val result = mutableListOf(CSharpRenameParticipant.Insertion(file, start, "[FormerlySerializedAs(\"$oldName\")]\n$indent"))
        usingInsertion(file, text)?.let(result::add)
        return result
    }

    /** `using UnityEngine.Serialization;` after the last `using` of the file's top, at its start when there is none; null when it is there. */
    private fun usingInsertion(file: CSharpFile, text: CharSequence): CSharpRenameParticipant.Insertion? {
        if (Regex("""(?m)^\s*using\s+UnityEngine\.Serialization\s*;""").containsMatchIn(text)) return null
        val firstDeclaration = FIRST_DECLARATION.find(text)?.range?.first ?: text.length
        val last = USING.findAll(text).takeWhile { it.range.first < firstDeclaration }.lastOrNull()
        return if (last != null) CSharpRenameParticipant.Insertion(file, last.range.last + 1, "\nusing UnityEngine.Serialization;")
        else CSharpRenameParticipant.Insertion(file, 0, "using UnityEngine.Serialization;\n\n")
    }

    private companion object {
        val USING = Regex("""(?m)^[ \t]*(global\s+)?using\s+(static\s+)?[\w.]+\s*;[ \t]*$""")
        val FIRST_DECLARATION = Regex("""(?m)^[ \t]*(?:\w+[ \t]+)*(?:namespace|class|struct|interface|enum|record)\b""")
    }
}
