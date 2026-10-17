package io.github.dotnetsupport.solution

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile

/**
 * What a framework module knows about the folders of a solution that the core does not (RAZOR_PLAN.md, "Модульность"): a directory the
 * tools never look into — the solution finder skips it (the `.sln` files inside Unity's `Library/PackageCache` are nobody's solutions),
 * the Solution view hides it. The first implementation is Unity's (UNITY_PLAN.md).
 */
interface SolutionLayout {
    /** A generated or cached directory of a project of the framework: skipped by the solution finder, hidden in the Solution view. */
    fun isGeneratedDirectory(directory: VirtualFile): Boolean

    companion object {
        val EP_NAME: ExtensionPointName<SolutionLayout> = ExtensionPointName.create("io.github.dotnetsupport.solutionLayout")

        fun isGeneratedDirectory(directory: VirtualFile): Boolean = EP_NAME.extensionList.any { it.isGeneratedDirectory(directory) }
    }
}
