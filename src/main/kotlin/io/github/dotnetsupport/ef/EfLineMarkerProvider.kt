package io.github.dotnetsupport.ef

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.awt.RelativePoint
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.msbuild.DotNetProjects
import javax.swing.Icon

/** EF classes of a file, scanned once per change of the file rather than once per token the gutter asks about. */
internal fun efDeclarationsOf(file: PsiFile): List<EfDeclaration> = CachedValuesManager.getCachedValue(file) {
    CachedValueProvider.Result.create(EfSources.declarations(file.viewProvider.contents), file)
}

/** An icon next to a `DbContext` and next to a migration; a click lists the `dotnet ef` commands, filled in for that class. */
class EfLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String = "EF Core DbContext and migrations"
    override fun getIcon(): Icon = AllIcons.Nodes.DataTables

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null // markers belong to leaves
        val file = element.containingFile as? CSharpFile ?: return null
        val offset = element.textRange.startOffset
        val declaration = efDeclarationsOf(file).firstOrNull { it.offset == offset } ?: return null
        val tooltip = if (declaration.kind == EfDeclarationKind.DB_CONTEXT) "EF Core: migrations and database of ${declaration.name}" else "EF Core: migration ${declaration.name}"
        return LineMarkerInfo(
            element, element.textRange, AllIcons.Nodes.DataTables, { tooltip },
            { event, leaf ->
                val actions = EfGutterActions.actionsFor(leaf.project, leaf.containingFile.virtualFile, declaration)
                JBPopupFactory.getInstance()
                    .createActionGroupPopup(null, DefaultActionGroup(actions), SimpleDataContext.getProjectContext(leaf.project), JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                    .show(RelativePoint(event))
            },
            GutterIconRenderer.Alignment.LEFT, { tooltip },
        )
    }
}

object EfGutterActions {
    /** The commands of a gutter icon; empty texts of the dialogs are filled from [declaration]. */
    fun actionsFor(project: Project, file: VirtualFile?, declaration: EfDeclaration): List<AnAction> {
        val projectFile = file?.let(DotNetProjects::findOwningProject)
        fun command(text: String, kind: EfCommandKind, preset: EfPreset): AnAction = action(text) { EfCommands.ask(project, kind, projectFile, preset) }
        val showMigrations = action("Show Migrations") { EfToolWindowFactory.show(project) }
        return when (declaration.kind) {
            EfDeclarationKind.DB_CONTEXT -> {
                val preset = EfPreset(dbContext = declaration.name)
                listOf(
                    command("Add Migration...", EfCommandKind.ADD, preset), command("Update Database...", EfCommandKind.UPDATE, preset),
                    command("Generate SQL Script...", EfCommandKind.SCRIPT, preset), command("Create Migration Bundle...", EfCommandKind.BUNDLE, preset),
                    Separator.create(), command("Drop Database...", EfCommandKind.DROP, preset), Separator.create(),
                    action("Create Design-Time Factory") { if (file != null) EfDesignTimeFactory.create(project, file, declaration.name) },
                    showMigrations,
                )
            }
            EfDeclarationKind.MIGRATION -> {
                val name = declaration.name
                val dbContext = file?.let(::contextOfMigration)
                listOf(
                    command("Update Database to '$name'...", EfCommandKind.UPDATE, EfPreset(dbContext, target = name)),
                    command("Generate SQL Script to '$name'...", EfCommandKind.SCRIPT, EfPreset(dbContext, to = name)),
                    command("Generate SQL Script from '$name'...", EfCommandKind.SCRIPT, EfPreset(dbContext, from = name)),
                    Separator.create(), showMigrations,
                )
            }
        }
    }

    /** The context is named by the designer file next to the migration: `20240301090000_AddUsers.Designer.cs`. */
    private fun contextOfMigration(file: VirtualFile): String? {
        val designer = file.parent?.findChild(file.nameWithoutExtension + EfMigrationFile.DESIGNER_SUFFIX) ?: return null
        return runCatching { VfsUtilCore.loadText(designer) }.getOrNull()?.let(EfSources::parseDesigner)?.dbContext
    }

    private fun action(text: String, perform: () -> Unit): AnAction = object : AnAction(text), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = perform()
    }
}
