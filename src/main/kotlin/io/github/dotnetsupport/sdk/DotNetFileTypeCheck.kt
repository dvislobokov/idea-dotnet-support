package io.github.dotnetsupport.sdk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.dotnetsupport.cli.PluginLog
import io.github.dotnetsupport.lang.CSharpFileType
import io.github.dotnetsupport.msbuild.MsBuildFileType
import io.github.dotnetsupport.solution.SolutionFileType
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.solution.SolutionXmlFileType
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import javax.swing.JComponent

/**
 * The file types of the plugin are claimed with the declarative `extensions` of plugin.xml, which are only *default* associations: a
 * user association in config/options/filetypes.xml always wins. An IDE without a C# plugin asks what a `.cs` is when one is first opened,
 * and "Text" is the usual answer; after the plugin is installed that answer stays and nothing of C# works in the file — no highlighting,
 * no server, no navigation — without a word. There is no declarative way to override a user mapping (the platform guards the user's
 * choice on purpose), so when the project is .NET but a mapping points elsewhere, a modal dialog says so and offers to claim the
 * extensions back; [DotNetFileTypeNotificationProvider] puts a banner with the same fix above every such file, which stays even after
 * "Don't ask again". Modelled on the Go plugin, where a colleague got no dialog at all until it was also tied to the opening of a file.
 */
object DotNetFileTypeCheck {
    private const val DISMISSED = "io.github.dotnetsupport.fileTypeCheck.dismissed"
    private const val LOG_CATEGORY = "sdk"

    /** An extension that should open as one of our file types, and what it opens as now. */
    class Claim(val extension: String, val expected: FileType, val actual: () -> FileType) {
        val what: String get() = "*.$extension"
        val wrong: Boolean get() = actual() != expected
    }

    /** `.props` / `.targets` and the .NET XML extensions stay out: a user may map them to XML on purpose and lose nothing of C#. */
    private val EXPECTED: Map<String, FileType> = mapOf(
        "cs" to CSharpFileType, "csx" to CSharpFileType,
        "sln" to SolutionFileType, "slnx" to SolutionXmlFileType,
        "csproj" to MsBuildFileType,
    )

    private fun claims(): List<Claim> {
        val ftm = FileTypeManager.getInstance()
        return EXPECTED.map { (extension, expected) -> Claim(extension, expected) { ftm.getFileTypeByExtension(extension) } }
    }

    /** The associations that point away from the plugin right now. Cheap: the file type manager alone, no index. */
    fun wrongClaims(): List<Claim> = claims().filter { it.wrong }

    /** Whether [file] is one of ours by extension but opens as something else. */
    fun isMistyped(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val expected = EXPECTED[file.extension?.lowercase()] ?: return false
        return file.fileType != expected
    }

    /** The startup path: once the project is smart and known to be .NET, the dialog if anything is off. */
    fun verify(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed) return
        DumbService.getInstance(project).runWhenSmart {
            // off EDT: the .NET check reads the filename index
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val wrong = ReadAction.nonBlocking(Callable {
                    if (project.isDisposed || !looksLikeDotNet(project)) emptyList() else wrongClaims()
                }).executeSynchronously()
                if (wrong.isEmpty()) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({ prompt(project, wrong) }, ModalityState.any(), project.disposed)
            }
        }
    }

    /** A .NET file opened with another type: the dialog right away, no index needed — the file in the editor is the proof. */
    fun verifyOpened(project: Project, file: VirtualFile) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed || !isMistyped(file)) return
        val wrong = wrongClaims()
        if (wrong.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({ prompt(project, wrong) }, ModalityState.any(), project.disposed)
    }

    /** Told without trusting the associations themselves (that is what may be broken): a solution the service found, or files by extension. */
    private fun looksLikeDotNet(project: Project): Boolean =
        SolutionService.getInstance(project).solutionFiles().isNotEmpty() ||
            listOf("cs", "csproj").any { FilenameIndex.getAllFilesByExt(project, it, GlobalSearchScope.projectScope(project)).isNotEmpty() } ||
            project.guessProjectDir()?.children.orEmpty().any { !it.isDirectory && it.extension?.lowercase() in EXPECTED }

    /** One dialog at a time (the startup path and an opened file can both ask); the answer "Not Now" holds for the session. */
    private val asking = AtomicBoolean()
    @Volatile private var declined = false

    private fun prompt(project: Project, wrong: List<Claim>) {
        if (project.isDisposed || dismissed() || declined || wrongClaims().isEmpty() || !asking.compareAndSet(false, true)) return
        try {
            PluginLog.info(LOG_CATEGORY, "File type association off: ${wrong.joinToString { "${it.what} opens as ${it.actual().name}" }}; asking")
            val mapped = wrong.joinToString("\n") { "    • ${it.what} opens as \"${it.actual().name}\"" }
            val message = ".NET files are not recognized by the plugin in this IDE:\n\n$mapped\n\n" +
                "This is usually a file type chosen when such a file was opened before the plugin was installed. " +
                "Until it is fixed there is no C# highlighting, navigation or completion in those files.\n\nAssociate them with the plugin now?"
            val answer = Messages.showYesNoDialog(
                project, message, ".NET File Types Not Associated",
                "Associate", "Not Now", Messages.getWarningIcon(),
                object : DoNotAskOption.Adapter() {
                    override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                        if (isSelected) PropertiesComponent.getInstance().setValue(DISMISSED, true)
                    }
                },
            )
            if (answer == Messages.YES) fix(project) else declined = true
        } finally {
            asking.set(false)
        }
    }

    /** Claims every wrong extension back for its file type; the open editors re-read their file types with the banners. */
    fun fix(project: Project?) {
        val wrong = wrongClaims()
        if (wrong.isEmpty()) return
        ApplicationManager.getApplication().runWriteAction {
            val ftm = FileTypeManager.getInstance()
            wrong.forEach { ftm.associateExtension(it.expected, it.extension) }
        }
        PluginLog.info(LOG_CATEGORY, "Claimed ${wrong.joinToString { it.what }} for the file types of the plugin")
        if (project != null && !project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
    }

    private fun dismissed(): Boolean = PropertiesComponent.getInstance().getBoolean(DISMISSED, false)
}

/** On startup of a .NET project: the modal dialog if a file type association was hijacked. */
class DotNetFileTypeCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) = DotNetFileTypeCheck.verify(project)

    /** A .cs / .sln / .csproj opened as another file type: the dialog at once, whatever the index says about the project. */
    class OnOpen(private val project: Project) : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = DotNetFileTypeCheck.verifyOpened(project, file)
    }
}

/** The banner above a .NET file that opens as another file type: the fix in place, and the File Types page for a look. */
class DotNetFileTypeNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!DotNetFileTypeCheck.isMistyped(file)) return null
        val actual = file.fileType.name
        val ours = if (file.extension?.lowercase() in setOf("cs", "csx")) "C#" else "a .NET project file"
        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "This file opens as \"$actual\", not as $ours: the plugin does nothing here"
                createActionLabel("Associate with C#") { DotNetFileTypeCheck.fix(project) }
                createActionLabel("File Types Settings…") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "preferences.fileTypes") }
            }
        }
    }
}
