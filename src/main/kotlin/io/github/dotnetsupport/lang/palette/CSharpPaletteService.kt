package io.github.dotnetsupport.lang.palette

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import io.github.dotnetsupport.DotNetBundle
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.lang.CSharpFileType

/** An entry of the combo box and of the popup: a palette, or "IDE default" ([palette] null). */
data class CSharpPaletteChoice(val id: String, val palette: CSharpPalette?) {
    val name: String get() = palette?.name ?: DotNetBundle.message("palette.default")

    override fun toString(): String = name

    companion object {
        fun all(): List<CSharpPaletteChoice> = listOf(CSharpPaletteChoice(CSharpPalettes.DEFAULT_ID, null)) + CSharpPalettes.ALL.map { CSharpPaletteChoice(it.id, it) }

        fun of(id: String): CSharpPaletteChoice = all().firstOrNull { it.id == id } ?: all().first()
    }
}

/**
 * The C# palette chosen for the machine (Settings | .NET, .NET → C# Color Palette…), kept in the current color scheme of the IDE: written into
 * it when chosen, written again in the matching variant when the scheme or the theme changes ([CSharpPaletteSchemeListener]), taken out of
 * every scheme it went into when "IDE default" is chosen. What each scheme had before is kept in [State.backups] (see [CSharpPaletteWriter]).
 */
@Service(Service.Level.APP)
@State(name = "DotNetCSharpPalette", storages = [Storage("dotnet-support.xml")])
class CSharpPaletteService : SimplePersistentStateComponent<CSharpPaletteService.State>(State()) {
    class State : BaseState() {
        var palette by string(CSharpPalettes.DEFAULT_ID)
        /** "Don't Show Again" (or a palette chosen from the suggestion): the suggestion of palettes is not shown any more. */
        var promptDismissed by property(false)
        /** Name of a scheme a palette went into -> the C# attributes it had of its own before, see [CSharpPaletteWriter.backup]. */
        var backups by map<String, String>()
    }

    /** What the popup shows while the selection moves through its list; Esc puts [paletteId] back. */
    private var previewId: String? = null

    var paletteId: String
        get() = state.palette?.takeIf { it == CSharpPalettes.DEFAULT_ID || CSharpPalettes.find(it) != null } ?: CSharpPalettes.DEFAULT_ID
        private set(value) { state.palette = value }

    val effectiveId: String get() = previewId ?: paletteId

    var promptDismissed: Boolean
        get() = state.promptDismissed
        set(value) { state.promptDismissed = value }

    /** The choice of Settings and of the popup: kept, and the current scheme recolored at once. */
    fun choose(id: String) {
        if (id == paletteId && previewId == null) return ensureApplied()
        paletteId = id
        previewId = null
        if (id == CSharpPalettes.DEFAULT_ID) restoreAll() else applyTo(globalScheme())
    }

    fun preview(id: String) {
        previewId = id
        applyTo(globalScheme())
    }

    fun endPreview() {
        if (previewId == null) return
        previewId = null
        applyTo(globalScheme())
    }

    /** After a switch of the scheme or of the theme: the scheme gets the palette in the variant of its background, if it does not have it yet. */
    fun ensureApplied(scheme: EditorColorsScheme = globalScheme()) {
        val palette = CSharpPalettes.find(effectiveId) ?: return
        if (!CSharpPaletteWriter.canWrite(scheme) || CSharpPaletteWriter.matches(scheme, palette)) return
        applyTo(scheme)
    }

    /** [effectiveId] into [scheme]; "IDE default" takes the palette out of it. */
    fun applyTo(scheme: EditorColorsScheme) {
        if (!CSharpPaletteWriter.canWrite(scheme)) return
        val palette = CSharpPalettes.find(effectiveId)
        val backup = state.backups[scheme.name]
        if (palette == null) {
            if (backup == null) return
            CSharpPaletteWriter.restore(scheme, backup)
            setBackup(scheme.name, null)
        } else {
            val kept = backup ?: CSharpPaletteWriter.backup(scheme).also { setBackup(scheme.name, it) }
            CSharpPaletteWriter.write(scheme, palette, kept)
        }
        refresh(scheme)
    }

    /** "IDE default": every scheme a palette went into gets back what it had. */
    private fun restoreAll() {
        val manager = EditorColorsManager.getInstance()
        val global = globalScheme()
        for ((name, backup) in state.backups.toMap()) {
            val scheme = if (global.name == name) global else manager.getScheme(name)
            if (scheme != null && CSharpPaletteWriter.canWrite(scheme)) CSharpPaletteWriter.restore(scheme, backup)
            setBackup(name, null)
        }
        refresh(global)
    }

    private fun setBackup(name: String, backup: String?) {
        // a new map: that is how BaseState notices the change
        state.backups = state.backups.toMutableMap().apply { if (backup == null) remove(name) else put(name, backup) }
    }

    /** As the Color Scheme page after Apply: the editors repaint with the new attributes. */
    private fun refresh(scheme: EditorColorsScheme) {
        ApplicationManager.getApplication().messageBus.syncPublisher(EditorColorsManager.TOPIC).globalSchemeChange(scheme)
    }

    private fun globalScheme(): EditorColorsScheme = EditorColorsManager.getInstance().globalScheme

    companion object {
        fun getInstance(): CSharpPaletteService = service()
    }
}

/** A switch of the color scheme or of the theme of the IDE: the palette follows in the variant of the new background. */
class CSharpPaletteSchemeListener : EditorColorsListener {
    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        val service = CSharpPaletteService.getInstance()
        if (service.effectiveId == CSharpPalettes.DEFAULT_ID) return
        // after the switch has settled: LafManager updates the UI later, and our own refresh comes back here (a no-op then)
        ApplicationManager.getApplication().invokeLater({ service.ensureApplied() }, ModalityState.any())
    }
}

/** .NET → C# Color Palette…: the list of palettes, each previewed in the open editors while the selection moves; Esc puts the previous back. */
class CSharpPaletteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = CSharpPalettePopup.show(e.project)
}

object CSharpPalettePopup {
    fun show(project: Project?) {
        val service = CSharpPaletteService.getInstance()
        val choices = CSharpPaletteChoice.all()
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("C# Color Palette")
            .setRenderer(SimpleListCellRenderer.create("") { it.name })
            .setSelectedValue(CSharpPaletteChoice.of(service.paletteId), true)
            .setItemSelectedCallback { it?.let { choice -> service.preview(choice.id) } }
            .setItemChosenCallback { service.choose(it.id) }
            .setNamerForFiltering { it.name }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) service.endPreview()
                }
            })
            .createPopup()
        if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }
}

/**
 * The first C# file opened while C# has no colors of its own (palette "IDE default" and a scheme of the IDE): a suggestion of the palettes,
 * once a session until one is chosen or it is dismissed. Also where a palette lost from the scheme (its file reset) is written again.
 */
class CSharpPalettePrompt(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.fileType != CSharpFileType || project.isDisposed) return
        val service = CSharpPaletteService.getInstance()
        service.ensureApplied()
        if (ApplicationManager.getApplication().isUnitTestMode || shown || service.promptDismissed || service.paletteId != CSharpPalettes.DEFAULT_ID) return
        val scheme = EditorColorsManager.getInstance().globalScheme
        if (!isIdeScheme(scheme) || CSharpPaletteWriter.definesCSharpColors(scheme)) return
        shown = true
        NotificationGroupManager.getInstance().getNotificationGroup(DotNetCli.NOTIFICATION_GROUP)
            .createNotification("Make C# colors like Rider, Visual Studio, VS Code…?",
                "The color scheme leaves C# types, methods and parameters in plain text. A palette colors only C#, the scheme and its background stay.",
                NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Choose Palette…") {
                service.promptDismissed = true
                CSharpPalettePopup.show(project)
            })
            .addAction(NotificationAction.createSimpleExpiring("Don't Show Again") { service.promptDismissed = true })
            .notify(project)
    }

    companion object {
        private var shown = false

        /** What EditorColorsManagerImpl (internal API) prefixes the editable copy of a bundled scheme with. */
        private const val EDITABLE_COPY_PREFIX = "_@user_"

        /** A scheme of the IDE (its editable copy `_@user_…`, or the bundled one itself), not one of a user. */
        fun isIdeScheme(scheme: EditorColorsScheme): Boolean = scheme.isReadOnly || scheme.name.startsWith(EDITABLE_COPY_PREFIX)
    }
}
