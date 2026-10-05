package io.github.dotnetsupport.decompiler

import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetInstallation
import io.github.dotnetsupport.cli.HelperException
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.index.AssemblyIndexService
import io.github.dotnetsupport.msbuild.CompilationModel
import io.github.dotnetsupport.nuget.NuGetHelper
import java.io.File

/**
 * The C# of a type of an assembly in a read-only editor tab, as Rider shows the decompiled sources of a library: DotNetHelper decompiles
 * (ICSharpCode.Decompiler, no `ilspycmd` or other global tool), [DecompiledFiles] keeps the result by assembly, its time and the type.
 *
 * The one entry point for everything that leads into the code of a library — the Dependencies of the Solution view, navigation from the
 * code (Go to Declaration of a member of a library, Go to Class): [open] in the background with a progress and the caret at the member;
 * [decompiledFile] blocking, for a caller that is already in the background; [cachedFile] for one that must not wait.
 * [source] is replaced in tests.
 */
@Service(Service.Level.PROJECT)
class AssemblyDecompiler(private val project: Project) {
    @Volatile var source: DecompilerSource = DecompilerHelperSource()

    /** False when the helper could not be built in this session (no SDK 8+): a caller shows the metadata of the index instead. */
    val isAvailable: Boolean get() = NuGetHelper.HELPER.failure == null

    /**
     * The decompiled [typeName] (metadata name: `Ns.Outer+Inner`1`) of [assemblyPath] — the assembly as the project references it, a reference
     * one is decompiled from its implementation ([ImplementationAssemblies]). [projectFile] gives `LangVersion` and the folders its references
     * are in. Blocking (seconds the first time: the helper is built and the assembly read), not for the EDT; throws [HelperException].
     */
    fun decompiledFile(assemblyPath: String, typeName: String, projectFile: VirtualFile? = null, memberId: String? = null): DecompiledFile {
        val reference = File(assemblyPath)
        if (!reference.isFile) throw HelperException("$assemblyPath is not there")
        val key = key(reference, typeName, projectFile)
        DecompiledFiles.find(key)?.let { return it }
        val target = ImplementationAssemblies.forReference(reference, DotNetInstallation.root())
        val request = DecompileRequest(target.assembly.path, typeName, memberId, target.xmlDoc?.path, referenceDirs(projectFile, reference, target.assembly), key.languageVersion)
        val decompiled = source.decompile(request)
        PluginLog.info(LOG_CATEGORY, "$typeName of ${target.assembly.name} decompiled in ${decompiled.elapsedMs} ms (${decompiled.text.length} chars)" +
            (decompiled.warning?.let { ": $it" } ?: ""))
        return DecompiledFiles.put(key, decompiled)
    }

    /** What [decompiledFile] would give without decompiling: the memory or the disk; null when it has to be made. Fit for the EDT. */
    fun cachedFile(assemblyPath: String, typeName: String, projectFile: VirtualFile? = null): DecompiledFile? =
        DecompiledFiles.find(key(File(assemblyPath), typeName, projectFile))

    /**
     * Decompiles in the background and opens the type with the caret at [memberId] (an XML documentation id, `M:System.Console.WriteLine(System.String)`;
     * null: the type). A failure is a notification and [onFailure] — a caller with another view of the type (the metadata of the index) opens it there.
     */
    fun open(assemblyPath: String, typeName: String, memberId: String? = null, projectFile: VirtualFile? = null, onFailure: ((String) -> Unit)? = null) {
        cachedFile(assemblyPath, typeName, projectFile)?.let { return navigate(it, memberId) }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Decompiling ${typeName.substringAfterLast('.').replace('+', '.')}", true) {
            private var file: DecompiledFile? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                file = decompiledFile(assemblyPath, typeName, projectFile, memberId)
            }

            override fun onSuccess() {
                file?.let { navigate(it, memberId) }
            }

            override fun onThrowable(error: Throwable) {
                val message = if (error is HelperException) error.message ?: "the decompiler has failed" else PluginLog.describe(error)
                PluginLog.warn(LOG_CATEGORY, "$typeName of ${File(assemblyPath).name} could not be decompiled: $message")
                if (onFailure != null) onFailure(message)
                else DotNetCli.notifyError(project, "Cannot decompile ${typeName.replace('+', '.')}", message)
            }
        })
    }

    private val prefetched = ConcurrentHashMap.newKeySet<String>()

    /**
     * Decompiles [typeName] in the background without opening it, once per session: Go to Declaration of a library symbol (AssemblyNavigation)
     * cannot wait for the helper, so it shows the metadata of the index the first time and finds the decompiled code in the cache afterwards.
     */
    fun prefetch(assemblyPath: String, typeName: String) {
        // a unit test does not build the real helper
        if (ApplicationManager.getApplication().isUnitTestMode && source is DecompilerHelperSource) return
        if (!isAvailable || !prefetched.add("$assemblyPath|$typeName")) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                decompiledFile(assemblyPath, typeName)
            } catch (e: Throwable) {
                if (e is ControlFlowException) throw e
                PluginLog.info(LOG_CATEGORY, "$typeName of ${File(assemblyPath).name} was not decompiled ahead: ${if (e is HelperException) e.message else PluginLog.describe(e)}")
            }
        }
    }

    /** Opens [file] (or brings its tab forward) with the caret on the name of [memberId], else of the type. EDT. */
    fun navigate(file: DecompiledFile, memberId: String? = null, requestFocus: Boolean = true) {
        if (project.isDisposed) return
        OpenFileDescriptor(project, file, file.decompiled.offsetOf(memberId) ?: 0).navigate(requestFocus)
    }

    /**
     * The types of [assemblies] for a chooser: from the index of assemblies when it has the assembly (no process), else asked of the helper.
     * Blocking, not for the EDT.
     */
    fun types(assemblies: List<File>, projectFile: VirtualFile?): List<Pair<File, AssemblyTypeInfo>> = assemblies.flatMap { assembly ->
        val indexed = projectFile?.let { AssemblyIndexService.getInstance(project).indexes(it) }.orEmpty()
            .firstOrNull { it.assemblyName.equals(assembly.nameWithoutExtension, ignoreCase = true) }
        val types = indexed?.allTypes?.filter { !it.isHidden }?.map { type ->
            AssemblyTypeInfo(if (type.namespace.isEmpty()) type.path else "${type.namespace}.${type.path}", type.kind.name.lowercase(), true)
        } ?: source.types(assembly.path).filter { it.isPublic }
        types.map { assembly to it }
    }

    private fun key(reference: File, typeName: String, projectFile: VirtualFile?): DecompiledKey =
        DecompiledKey(FileUtil.toSystemIndependentName(reference.path), typeName, projectFile?.let(::languageVersion))

    /** `LangVersion` of the project; null for the latest, which is what the decompiler would take anyway. */
    private fun languageVersion(projectFile: VirtualFile): String? = runCatching { CompilationModel.getInstance(project).options(projectFile).langVersion }.getOrNull()
        ?.trim()?.lowercase()?.takeUnless { it.isEmpty() || it in LATEST }

    /** The folders of what the project references (framework types resolve), and those of the assemblies themselves. */
    private fun referenceDirs(projectFile: VirtualFile?, vararg assemblies: File): List<String> {
        val references = projectFile?.let { AssemblyIndexService.getInstance(project).references(it) }?.assemblies.orEmpty()
        return (assemblies.toList() + references).mapNotNull { it.parentFile?.path }.distinct()
    }

    companion object {
        /** The category of the journal of the plugin for the decompiler. */
        const val LOG_CATEGORY = "decompiler"

        private val LATEST = setOf("latest", "default", "latestmajor")

        fun getInstance(project: Project): AssemblyDecompiler = project.service()
    }
}
