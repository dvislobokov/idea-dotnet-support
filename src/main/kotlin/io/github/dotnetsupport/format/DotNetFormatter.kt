package io.github.dotnetsupport.format

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.lsp.RoslynPolicy
import io.github.dotnetsupport.lsp.RoslynServerStatus
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import java.io.File

enum class FormatterChoice(val title: String) {
    AUTO("Auto: CSharpier when the repository uses it, otherwise dotnet format"),
    CSHARPIER("CSharpier"),
    DOTNET_FORMAT("dotnet format (whitespace, by .editorconfig; about a second per file, instant with the language server)"),
    NONE("None");

    override fun toString(): String = title
}

/** The formatter is a decision of the team, so it is kept with the project (`.idea/dotnet.xml`) and can be committed. */
@Service(Service.Level.PROJECT)
@State(name = "DotNetFormatting", storages = [Storage("dotnet.xml")])
class DotNetFormattingSettings : SimplePersistentStateComponent<DotNetFormattingSettings.Settings>(Settings()) {
    class Settings : BaseState() {
        var formatter by enum(FormatterChoice.AUTO)
    }

    var formatter: FormatterChoice
        get() = state.formatter
        set(value) { state.formatter = value }

    /** What [FormatterChoice.AUTO] means for a file in [directory]. */
    /** [resolve] for a file of the project: its directory and the text of the project that owns it. */
    fun resolve(file: VirtualFile): FormatterChoice =
        resolve(VfsUtilCore.virtualToIoFile(file).parentFile, DotNetProjects.findOwningProject(file)?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() })

    fun resolve(directory: File?, projectFileText: String? = null): FormatterChoice = when (val choice = formatter) {
        FormatterChoice.AUTO -> if (CSharpierLocator.isUsedBy(directory, projectFileText)) FormatterChoice.CSHARPIER else FormatterChoice.DOTNET_FORMAT
        else -> choice
    }

    companion object {
        fun getInstance(project: Project): DotNetFormattingSettings = project.service()
    }
}

/**
 * `dotnet format whitespace --folder`: the Roslyn formatter without loading the project, about 1.3 s per call. It works
 * on files, and the editor may hold unsaved text, so the text goes into a scratch copy of the file placed in a mirror of
 * its directory chain together with every `.editorconfig` that applies to it, so that sections with directory names keep matching.
 */
object DotNetFormatRunner {
    /** Directories from the one that holds the topmost applicable `.editorconfig` (or [file]'s own) down to the file's directory. */
    fun editorConfigChain(file: File): List<File> {
        val directories = generateSequence(file.parentFile) { it.parentFile }.toList()
        var top = 0
        for ((index, directory) in directories.withIndex()) {
            val config = File(directory, ".editorconfig")
            if (!config.isFile) continue
            top = index
            if (ROOT.containsMatchIn(runCatching { config.readText() }.getOrDefault(""))) break
        }
        return directories.take(top + 1).reversed()
    }

    fun format(file: File, text: String): FormatResult {
        val chain = editorConfigChain(file)
        val scratch = FileUtil.createTempDirectory("dotnet-format", null, true)
        try {
            var mirror = scratch
            for ((index, directory) in chain.withIndex()) {
                if (index > 0) mirror = File(mirror, directory.name).apply { mkdirs() }
                File(directory, ".editorconfig").takeIf { it.isFile }?.copyTo(File(mirror, ".editorconfig"))
            }
            val copy = File(mirror, file.name)
            copy.writeText(text)

            val command = DotNetCli.commandLine(scratch.path, "format", "whitespace", "--folder", scratch.path, "--include", copy.path)
            val output = CapturingProcessHandler(command).runProcess(60_000)
            return when {
                output.isTimeout -> FormatResult.Failed("dotnet format did not finish in a minute.")
                output.exitCode != 0 -> FormatResult.Failed((output.stderr.ifBlank { output.stdout }).trim().lines().lastOrNull { it.isNotBlank() } ?: "dotnet format failed.")
                else -> copy.readText().let { if (it == text) FormatResult.Unchanged else FormatResult.Formatted(it) }
            }
        } catch (e: Exception) {
            return FormatResult.Failed(e.message ?: "dotnet format could not be started.")
        } finally {
            FileUtil.delete(scratch)
        }
    }

    private val ROOT = Regex("""(?im)^\s*root\s*=\s*true\s*$""")
}

/** Reformat Code, "Reformat code" of Actions on Save and of the commit dialog for C# files. */
class DotNetFormattingService : AsyncDocumentFormattingService() {
    // whole files only: CSharpier cannot format a fragment, and a fragment of `dotnet format` is not worth a second
    override fun getFeatures(): Set<FormattingService.Feature> = emptySet()

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.virtualFile?.takeIf { it.fileType == CSharpFileType } ?: return false
        val settings = DotNetFormattingSettings.getInstance(file.project)
        if (settings.formatter == FormatterChoice.NONE) return false
        // the whitespace formatter of Roslyn is what the language server runs, without a process per file: left to the LSP client
        return !RoslynServerStatus.isReady(file.project) || !RoslynPolicy.formatsByServer(settings.resolve(virtualFile), true)
    }

    override fun getNotificationGroupId(): String = DotNetCli.NOTIFICATION_GROUP
    override fun getName(): String = ".NET formatter"

    override fun createFormattingTask(request: AsyncFormattingRequest): FormattingTask? {
        val project = request.context.project
        val virtualFile = request.context.virtualFile ?: return null
        val file = VfsUtilCore.virtualToIoFile(virtualFile)
        val text = request.documentText
        val projectFileText = DotNetProjects.findOwningProject(virtualFile)?.let { runCatching { VfsUtilCore.loadText(it) }.getOrNull() }

        return object : FormattingTask {
            @Volatile private var cancelled = false

            override fun run() {
                val result = when (DotNetFormattingSettings.getInstance(project).resolve(file.parentFile, projectFileText)) {
                    FormatterChoice.CSHARPIER -> try {
                        CSharpierServer.getInstance(project).format(CSharpierLocator.find(file.parentFile), file, text)
                    } catch (e: CSharpierUnavailable) {
                        FormatResult.Failed(e.message.orEmpty())
                    }
                    FormatterChoice.DOTNET_FORMAT -> DotNetFormatRunner.format(file, text)
                    else -> FormatResult.Unchanged
                }
                if (cancelled) return
                when (result) {
                    is FormatResult.Formatted -> request.onTextReady(result.text)
                    is FormatResult.Unchanged -> request.onTextReady(null)
                    is FormatResult.Failed -> {
                        // onError has no room for actions: the two cases that need a button get their own notification
                        if (!notifyWithAction(project, file, result.message)) request.onError("Cannot format ${file.name}", result.message)
                        else request.onTextReady(null)
                    }
                }
            }

            override fun cancel(): Boolean {
                cancelled = true
                return true
            }

            override fun isRunUnderProgress(): Boolean = true
        }
    }

    private fun notifyWithAction(project: Project, file: File, message: String): Boolean {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
        when {
            message == CSharpierOutput.RESTORE_NEEDED -> {
                val directory = CSharpierLocator.manifestEntry(file.parentFile)?.first ?: return false
                group.createNotification("Cannot format ${file.name}", message, NotificationType.WARNING)
                    .addAction(NotificationAction.createSimpleExpiring("Run 'dotnet tool restore'") {
                        val commands = DotNetCli.commandLinesOrNotify(project, "dotnet tool restore") { listOf(DotNetCli.commandLine(directory.path, "tool", "restore")) }
                        if (commands != null) DotNetCli.runInBackground(project, "Restoring .NET tools", commands) { CSharpierServer.getInstance(project).stop() }
                    })
                    .notify(project)
            }
            message.startsWith("CSharpier is not installed") -> {
                group.createNotification("Cannot format ${file.name}", message, NotificationType.WARNING)
                    .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, DotNetSettingsConfigurable::class.java) })
                    .notify(project)
            }
            else -> return false
        }
        return true
    }
}
