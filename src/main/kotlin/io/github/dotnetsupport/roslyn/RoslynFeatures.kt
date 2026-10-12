package io.github.dotnetsupport.roslyn

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatures
import io.github.dotnetsupport.lsp.RoslynServerStatus

/**
 * The side of the server of the switches [CSharpFeatures] (CSHARP_PSI_MIGRATION.md, step 2): every place of this module that answers for a
 * [CSharpFeature] asks [serves] first and stands down when the feature is switched to the plugin's own PSI. Read per call, not when the
 * server starts: a switch takes effect without a restart. Today no feature has a native implementation, so the answer is always true.
 */
object RoslynFeatures {
    fun serves(feature: CSharpFeature, project: Project): Boolean = !CSharpFeatures.native(feature, project)

    /** [serves] for [file]: never for a file the loaded server does not know (`RoslynServerStatus.outside`), the plugin answers there. */
    fun serves(feature: CSharpFeature, file: PsiFile?): Boolean = file != null && !CSharpFeatures.native(feature, file)

    /** Whether the server is asked about [file] at all: a file of a project it has not loaded gets nothing from it (no misc-files answers). */
    fun knows(project: Project, file: VirtualFile?): Boolean = !RoslynServerStatus.outside(project, file)
}
