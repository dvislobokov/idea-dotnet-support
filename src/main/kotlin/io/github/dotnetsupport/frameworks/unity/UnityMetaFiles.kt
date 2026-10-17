package io.github.dotnetsupport.frameworks.unity

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.listeners.RefactoringElementListenerProvider

/**
 * The `.meta` beside an asset goes where the asset goes (UNITY_PLAN.md, "плюшки", as Rider does it): Unity keeps the identity of a file —
 * the GUID every scene and prefab refers to it by — in `Player.cs.meta`; a file renamed or moved without it gets a new GUID on the next
 * import, and every reference to the script in the scenes breaks ("The referenced script is missing"). Rename and Move of a file or a
 * folder under a Unity project's `Assets` / `Packages` take the `.meta` along.
 */
class UnityMetaFilesListenerProvider : RefactoringElementListenerProvider {
    override fun getListener(element: PsiElement): RefactoringElementListener? {
        val item = element as? PsiFileSystemItem ?: return null
        val file = item.virtualFile ?: return null
        if (UnityProjects.rootOf(file) == null || UnityProjects.isMetaFile(file)) return null
        // the VirtualFile itself, not its path: it stays the same file whatever happens to the asset beside it (and works on any file system)
        val metaFile = file.parent?.findChild(file.name + ".meta") ?: return null
        return object : RefactoringElementListener {
            override fun elementMoved(newElement: PsiElement) = follow(newElement)
            override fun elementRenamed(newElement: PsiElement) = follow(newElement)

            private fun follow(newElement: PsiElement) {
                val moved = (newElement as? PsiFileSystemItem)?.virtualFile ?: return
                val target = moved.parent ?: return
                if (!metaFile.isValid) return
                val newName = moved.name + ".meta"
                if (metaFile.parent == target && metaFile.name == newName) return
                if (target.findChild(newName) != null) return // the target has one of its own: Unity decides
                runCatching {
                    WriteAction.run<Exception> {
                        if (metaFile.parent != target) metaFile.move(this, target)
                        if (metaFile.name != newName) metaFile.rename(this, newName)
                    }
                }.onFailure { LOG.warn("The .meta of ${moved.path} could not follow it", it) }
            }
        }
    }

    private companion object {
        val LOG = logger<UnityMetaFilesListenerProvider>()
    }
}
