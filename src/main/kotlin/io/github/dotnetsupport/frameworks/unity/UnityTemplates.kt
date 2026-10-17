package io.github.dotnetsupport.frameworks.unity

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpMemberTemplateContext

/**
 * The live templates of Unity (`liveTemplates/Unity.xml`: `sfield`, `coroutine`, `menuitem`, `header`) go where a member of a type goes, in
 * the files of a Unity project only: in a plain .NET project `sfield` means nothing.
 */
class UnityMemberTemplateContext : TemplateContextType("Unity type member") {
    private val member = CSharpMemberTemplateContext()

    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? CSharpFile ?: return false
        if (file.originalFile.virtualFile?.let(UnityProjects::rootOf) == null) return false
        return member.isInContext(templateActionContext)
    }
}
