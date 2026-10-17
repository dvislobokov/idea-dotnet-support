package io.github.dotnetsupport.solution

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.vfs.VirtualFile

/**
 * What a framework module knows about the folders of a solution that the core does not (RAZOR_PLAN.md, "Модульность"): a directory the
 * tools never look into — the solution finder skips it (the `.sln` files inside Unity's `Library/PackageCache` are nobody's solutions),
 * the Solution view hides it — and the namespace a folder stands for when the framework counts it differently from the SDK. The first
 * implementation is Unity's (UNITY_PLAN.md).
 */
interface SolutionLayout {
    /** A generated or cached directory of a project of the framework: skipped by the solution finder, hidden in the Solution view. */
    fun isGeneratedDirectory(directory: VirtualFile): Boolean

    /**
     * The namespace of the files of [directory] in [projectFile] (`RootNamespace` [rootNamespace]) by the framework's rule, `""` for none;
     * null when the framework has no say there and the SDK's rule counts (the root namespace and the folders from the project file).
     */
    fun namespaceFor(directory: VirtualFile, projectFile: VirtualFile, rootNamespace: String?): String? = null

    companion object {
        val EP_NAME: ExtensionPointName<SolutionLayout> = ExtensionPointName.create("io.github.dotnetsupport.solutionLayout")

        fun isGeneratedDirectory(directory: VirtualFile): Boolean = EP_NAME.extensionList.any { it.isGeneratedDirectory(directory) }

        fun namespaceFor(directory: VirtualFile, projectFile: VirtualFile, rootNamespace: String?): String? =
            EP_NAME.extensionList.firstNotNullOfOrNull { it.namespaceFor(directory, projectFile, rootNamespace) }
    }
}
