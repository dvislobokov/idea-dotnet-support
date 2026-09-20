package io.github.dotnetsupport.templates

import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.github.dotnetsupport.DotNetIcons
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.msbuild.DotNetProjects
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.solution.SolutionService
import io.github.dotnetsupport.view.resolveFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

internal fun AnActionEvent.targetDirectory(choose: Boolean = false): VirtualFile? {
    val view = getData(LangDataKeys.IDE_VIEW) ?: return null
    return (if (choose) view.orChooseDirectory else view.directories.firstOrNull())?.virtualFile
}

internal fun AnActionEvent.selectedFile(extension: String): VirtualFile? =
    getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.extension.equals(extension, ignoreCase = true) }

internal fun openInEditor(project: Project, file: VirtualFile?) {
    if (file != null && file.isValid) FileEditorManager.getInstance(project).openFile(file, true)
}

private fun iconFor(fileName: String): Icon =
    DotNetIcons.forFile(fileName.substringAfterLast('/'))?.takeIf { it !== DotNetIcons.CSharp } ?: DotNetIcons.CSharpType

/**
 * New → .NET: generators grouped by category. The categories that make sense for the project of the selected
 * directory (ASP.NET for a web project, Tests for a test one, ...) are at the first level, the rest is folded
 * into "Other", so that the menu stays short.
 */
class NewDotNetItemGroup : ActionGroup(), DumbAware {
    private val categoryGroups: Map<ItemCategory, ActionGroup> by lazy {
        ItemCategory.entries.associateWith { category ->
            DefaultActionGroup(category.title, true).apply {
                ItemTemplates.ALL.filter { it.category == category }.forEach { add(NewItemAction(it)) }
                when (category) {
                    ItemCategory.CSHARP -> add(PartialPartAction())
                    ItemCategory.RESOURCES -> add(ResxCultureAction())
                    ItemCategory.EFCORE -> {
                        addSeparator()
                        add(EfAction.AddMigration()); add(EfAction.RemoveMigration()); add(EfAction.UpdateDatabase())
                    }
                    else -> {}
                }
            }
        }
    }
    private val otherGroups = ConcurrentHashMap<List<ItemCategory>, ActionGroup>()
    private val tail: List<AnAction> by lazy { listOf(Separator.create(), TestForClassAction(), SdkItemTemplateAction()) }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val directory = e.targetDirectory()
        e.presentation.isEnabledAndVisible = project != null && directory != null &&
            (DotNetProjects.findOwningProject(directory) != null || SolutionService.getInstance(project).solutionFiles().isNotEmpty())
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project ?: return emptyArray()
        val msBuildProject = e.targetDirectory()?.let(DotNetProjects::findOwningProject)?.let { SolutionService.getInstance(project).msBuildProject(it) }
        return arrange(msBuildProject).toTypedArray()
    }

    fun arrange(msBuildProject: MsBuildProject?): List<AnAction> {
        val (relevant, other) = ItemCategory.entries.partition { it.isRelevantFor(msBuildProject) }
        val otherGroup = other.takeIf { it.isNotEmpty() }?.let { categories ->
            otherGroups.computeIfAbsent(categories) { DefaultActionGroup("Other", true).apply { it.forEach { c -> add(categoryGroups.getValue(c)) } } }
        }
        return relevant.map(categoryGroups::getValue) + listOfNotNull(otherGroup) + tail
    }
}

/** One generator of [ItemTemplates]: asks for a name in the "New Class"-style popup, or creates a well-known file right away. */
class NewItemAction(private val template: ItemTemplate) : AnAction(template.title, null, iconFor(template.mainFileName)), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.targetDirectory() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = e.targetDirectory(choose = true) ?: return
        when {
            template.fixedName != null -> createWellKnownFile(project, directory)
            else -> askNameAndCreate(project, directory)
        }
    }

    private fun createWellKnownFile(project: Project, directory: VirtualFile) {
        findOutput(directory)?.let { return openInEditor(project, it) }
        val cliTemplate = template.cliTemplate
        if (cliTemplate == null) {
            openInEditor(project, ItemCreator.create(project, directory, template, "").firstOrNull())
            return
        }
        val title = "Creating ${template.title}"
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            listOf(DotNetCli.commandLine(directory.path, "new", cliTemplate, "-o", directory.path))
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(directory.path))) {
            openInEditor(project, findOutput(directory))
        }
    }

    /** SDKs before 10 put the tool manifest into `.config`. */
    private fun findOutput(directory: VirtualFile): VirtualFile? =
        directory.findFileByRelativePath(template.mainFileName) ?: directory.findFileByRelativePath(".config/${template.mainFileName}")

    private fun askNameAndCreate(project: Project, directory: VirtualFile) {
        val kind = if (template.namePrompt == "Name") template.title else template.namePrompt
        CreateFileFromTemplateDialog.createDialog(project)
            .setTitle("New ${template.title}")
            .addKind(kind, templatePresentation.icon, template.id)
            .setValidator(NameValidator(template.isIdentifier))
            .show("Cannot Create ${template.title}", null, object : CreateFileFromTemplateDialog.FileCreator<PsiFile> {
                override fun createFile(name: String, templateName: String): PsiFile? =
                    ItemCreator.create(project, directory, template, name).firstOrNull()?.let { PsiManager.getInstance(project).findFile(it) }

                override fun getActionName(name: String, templateName: String): String = "Create ${template.title} $name"
                override fun startInWriteAction(): Boolean = false
            }) { created -> openInEditor(project, created.virtualFile) }
    }
}

internal class NameValidator(private val identifier: Boolean) : InputValidatorEx {
    override fun getErrorText(input: String): String? {
        val segments = input.trim().replace('\\', '/').split('/')
        return when {
            segments.any { it.isBlank() } -> "Specify the name"
            segments.any { segment -> segment.any { it in ":*?\"<>|" } } -> "The name contains characters that are not allowed in file names"
            identifier && !isIdentifier(segments.last()) -> "'${segments.last()}' is not a valid C# identifier"
            else -> null
        }
    }

    private fun isIdentifier(text: String): Boolean =
        (text.first() == '_' || text.first().isLetter()) && text.all { it == '_' || it.isLetterOrDigit() }
}

/** `Foo.Validation.cs` with another part of the type declared in the selected file; the original becomes `partial` if it is not. */
class PartialPartAction : AnAction("Partial Part of Selected Type...", null, DotNetIcons.CSharpType), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.selectedFile("cs") != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.selectedFile("cs") ?: return
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return
        val type = TypeDeclarationScanner.firstType(document.immutableCharSequence)
        if (type == null) {
            Messages.showInfoMessage(project, "No class, struct, interface or record is declared in ${file.name}.", TITLE)
            return
        }
        val part = Messages.showInputDialog(project, "Name of the part (${type.name}.<part>.cs):", TITLE, null, "", NameValidator(identifier = true))?.trim() ?: return
        openInEditor(project, create(project, file, type, part))
    }

    companion object {
        private const val TITLE = "New Partial Part"
        private val TEMPLATE = ItemTemplate("partial", "Partial Part", ItemCategory.CSHARP, listOf(ItemFile("\${PART_FILE}.cs", "partial.cs")))

        fun create(project: Project, file: VirtualFile, type: TypeDeclaration, part: String): VirtualFile? {
            if (!type.isPartial) {
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
                WriteCommandAction.runWriteCommandAction(project, "Make ${type.name} Partial", null, {
                    document.insertString(type.kindOffset, "partial ")
                    FileDocumentManager.getInstance().saveDocument(document)
                })
            }
            val variables = mapOf(
                "PART_FILE" to "${type.name}.$part",
                "NAME" to type.name + type.typeParameters,
                "KIND" to type.kind,
                "MODIFIERS" to type.accessModifier?.let { "$it " }.orEmpty(),
            )
            // the part lives in the namespace of the type, whatever folder the file is in
            return ItemCreator.create(project, file.parent, TEMPLATE, type.name, extraVariables = variables, namespaceOverride = type.namespace ?: "").firstOrNull()
        }
    }
}

/** `FooTests` for the selected class: in the test project of the solution, in the mirrored folder and namespace. */
class TestForClassAction : AnAction("Test for Selected Class", null, DotNetIcons.CSharpType), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.selectedFile("cs")
        e.presentation.isEnabledAndVisible = project != null && file != null && findTarget(project, file) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.selectedFile("cs") ?: return
        openInEditor(project, create(project, file))
    }

    class Target(val testProjectFile: VirtualFile, val template: ItemTemplate, val relativeDirectory: String)

    companion object {
        fun findTarget(project: Project, sourceFile: VirtualFile): Target? {
            val solutions = SolutionService.getInstance(project)
            val sourceProject = DotNetProjects.findOwningProject(sourceFile) ?: return null
            if (solutions.msBuildProject(sourceProject).isTestProject) return null

            val testProjects = solutions.solutionFiles()
                .flatMap { solutionFile -> solutions.solution(solutionFile).allProjects.mapNotNull { it.resolveFile(solutionFile) } }
                .distinct()
                .filter { solutions.msBuildProject(it).isTestProject }
            // the test project that references the project of the class, if there is one
            val testProject = testProjects.firstOrNull { candidate ->
                solutions.msBuildProject(candidate).projectReferences.any { candidate.parent.findFileByRelativePath(it) == sourceProject }
            } ?: testProjects.firstOrNull() ?: return null

            val packages = solutions.msBuildProject(testProject).packages.map { it.name.lowercase() }
            val template = when {
                packages.any { it.startsWith("nunit") } -> "nunitTest"
                packages.any { it.startsWith("mstest") } -> "mstestTest"
                else -> "xunitTest"
            }
            val relativeDirectory = VfsUtilCore.getRelativePath(sourceFile.parent, sourceProject.parent, '/').orEmpty()
            return Target(testProject, ItemTemplates.byId(template), relativeDirectory)
        }

        fun create(project: Project, sourceFile: VirtualFile): VirtualFile? {
            val target = findTarget(project, sourceFile) ?: return null
            val text = FileDocumentManager.getInstance().getDocument(sourceFile)?.immutableCharSequence ?: return null
            val type = TypeDeclarationScanner.firstType(text)
            val className = type?.name ?: sourceFile.nameWithoutExtension
            val sourceNamespace = type?.namespace ?: ItemContext(project, sourceFile.parent).namespace

            val testDirectory = target.testProjectFile.parent
            val input = listOf(target.relativeDirectory, className).filter { it.isNotEmpty() }.joinToString("/")
            val (testName, _) = ItemCreator.names(target.template, className)
            testDirectory.findFileByRelativePath(listOf(target.relativeDirectory, "$testName.cs").filter { it.isNotEmpty() }.joinToString("/"))?.let { return it }
            return ItemCreator.create(project, testDirectory, target.template, input, extraUsings = listOfNotNull(sourceNamespace)).firstOrNull()
        }
    }
}

/** `Strings.resx` -> `Strings.ru.resx` with the same keys, to be translated. */
class ResxCultureAction : AnAction("Copy of Selected .resx for Culture...", null, DotNetIcons.Resx), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.selectedFile("resx") != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.selectedFile("resx") ?: return
        val culture = Messages.showInputDialog(project, "Culture (ru, de, pt-BR, ...):", "Resources for Culture", null, "", object : InputValidatorEx {
            override fun getErrorText(input: String): String? = when {
                !CULTURE.matches(input.trim()) -> "Specify a culture name like 'ru' or 'pt-BR'"
                file.parent.findChild(cultureFileName(file, input.trim())) != null -> "${cultureFileName(file, input.trim())} already exists"
                else -> null
            }
        })?.trim() ?: return
        openInEditor(project, create(project, file, culture))
    }

    companion object {
        private val CULTURE = Regex("""[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*""")

        fun cultureFileName(file: VirtualFile, culture: String): String = "${file.nameWithoutExtension}.$culture.resx"

        fun create(project: Project, file: VirtualFile, culture: String): VirtualFile =
            WriteCommandAction.writeCommandAction(project).withName("Create Resources for Culture")
                .compute<VirtualFile, Exception> { file.copy(this, file.parent, cultureFileName(file, culture)) }
    }
}

/** `dotnet ef` commands for the project of the selected directory. */
sealed class EfAction(text: String, private val asksName: Boolean, private vararg val command: String) : AnAction(text), DumbAware {
    class AddMigration : EfAction("Migration...", true, "migrations", "add")
    class RemoveMigration : EfAction("Remove Last Migration", false, "migrations", "remove")
    class UpdateDatabase : EfAction("Update Database", false, "database", "update")

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && projectFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val projectFile = projectFile(e) ?: return
        val name = if (!asksName) null else {
            Messages.showInputDialog(project, "Migration name:", "Add Migration", null, "", NameValidator(identifier = true))?.trim() ?: return
        }
        val title = "dotnet ef ${command.joinToString(" ")}" + name?.let { " $it" }.orEmpty()
        val commands = DotNetCli.commandLinesOrNotify(project, title) {
            listOf(DotNetCli.commandLine(projectFile.parent.path, "ef", *command, *listOfNotNull(name).toTypedArray(), "--project", projectFile.path))
        } ?: return
        DotNetCli.runInBackground(project, title, commands, refresh = listOf(File(projectFile.parent.path))) {
            DotNetCli.notifyInfo(project, "$title: done")
        }
    }

    private fun projectFile(e: AnActionEvent): VirtualFile? =
        (e.targetDirectory() ?: e.getData(CommonDataKeys.VIRTUAL_FILE))?.let(DotNetProjects::findOwningProject)
}
