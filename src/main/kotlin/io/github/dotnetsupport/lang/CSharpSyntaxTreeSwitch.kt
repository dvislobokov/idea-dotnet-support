package io.github.dotnetsupport.lang

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.TransactionGuard
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.util.FileContentUtilCore
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import org.jetbrains.annotations.TestOnly

/**
 * The PSI of C# files follows [CSharpSyntaxTrees.nativeTree] (CSHARP_PSI_MIGRATION.md, step 7). The parser definition is asked when a file
 * is parsed, so a file parsed under the old answer keeps its tree: when the settings page is applied (the switch, the server on or off),
 * the cached C# files of every open project whose tree is of the other kind are parsed again, highlighting restarts, and the declaration
 * indexes, whose content depends on the tree (the declaration index of the heuristic one, the stubs of the native one), are rebuilt.
 */
class CSharpSyntaxTreeSwitch : RoslynLanguageServerSettings.Listener {
    override fun settingsChanged(restart: Boolean) {
        if (ApplicationManager.getApplication().isUnitTestMode && !reactInTests) return
        val application = ApplicationManager.getApplication()
        // the reparse is a model change: at once only where writing is allowed (the settings page), else later (a write-unsafe EDT event, a background thread)
        if (application.isDispatchThread && TransactionGuard.getInstance().isWritingAllowed) apply() else application.invokeLater(::apply, ModalityState.nonModal())
    }

    companion object {
        /** In unit tests the reactions are off unless a test asks: other tests switch the server off and must not reparse the shared project. */
        @Volatile
        @TestOnly
        var reactInTests: Boolean = false

        /** True when [file] is a tree of the other kind than [native] asks for. */
        fun stale(file: PsiFile, native: Boolean): Boolean = file is CSharpFile && (file is HeuristicCSharpFile) == native

        /** On the EDT: reparses what is stale; the index only when the answer did change ([CSharpSyntaxTrees.lastAnswer]). */
        private fun apply() {
            val before = CSharpSyntaxTrees.lastAnswer
            val native = CSharpSyntaxTrees.nativeTree()
            for (project in ProjectManager.getInstance().openProjects) if (!project.isDisposed) reparse(project, native)
            if (before != null && before != native) CSharpDeclarationIndex.requestRebuild()
        }

        private fun reparse(project: Project, native: Boolean) {
            val stale: List<VirtualFile> = runReadAction {
                PsiManagerEx.getInstanceEx(project).fileManager.allCachedFiles.filter { stale(it, native) }.mapNotNull { it.viewProvider.virtualFile.takeIf { file -> file.isValid } }
            }
            if (stale.isEmpty()) return
            FileContentUtilCore.reparseFiles(stale)
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
