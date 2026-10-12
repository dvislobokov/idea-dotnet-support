package io.github.dotnetsupport.ef

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.solution.SolutionService
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

enum class EfCommandKind(val title: String, val okText: String) {
    ADD("Add Migration", "Add"),
    REMOVE("Remove Last Migration", "Remove"),
    UPDATE("Update Database", "Update"),
    SCRIPT("Generate SQL Script", "Generate"),
    DROP("Drop Database", "Continue"),
    SCAFFOLD("Scaffold DbContext from Database", "Scaffold"),
    BUNDLE("Create Migration Bundle", "Create"),
}

/** What the dialog opens with when it is invoked for a migration of the EF Core window. */
class EfPreset(val dbContext: String? = null, val target: String? = null, val from: String? = null, val to: String? = null)

/** What the sources of a migrations project say, read before the dialog opens: see [EfSources]. */
class EfProjectSources(val dbContexts: List<String>, val migrations: List<EfMigrationSource>)

/**
 * One dialog for every `dotnet ef` command: the options all of them share (projects, `DbContext`, environment, build),
 * the few of the command itself, and the command line that is going to run.
 */
class EfCommandDialog(
    private val project: Project,
    private val kind: EfCommandKind,
    private val sources: Map<VirtualFile, EfProjectSources>,
    initialProject: VirtualFile?,
    preset: EfPreset = EfPreset(),
) : DialogWrapper(project) {
    private val settings = EfSettings.getInstance(project).state
    private val files = LocalFileSystem.getInstance()

    private val migrationsProject = ComboBox(CollectionComboBoxModel(sources.keys.toList())).apply { renderer = projectRenderer() }
    private val startupProject = ComboBox(CollectionComboBoxModel(EfProjects.startupProjects(project))).apply { renderer = projectRenderer() }
    private val dbContext = editableCombo()
    private val environment = editableCombo()
    private val configuration = ComboBox(CollectionComboBoxModel(DotNetBuildSettings.getInstance(project).availableConfigurations()))
    private val framework = ComboBox<String>().apply { renderer = SimpleListCellRenderer.create("") { it.ifEmpty { "Default" } } }
    private val noBuild = JBCheckBox("Do not build the project", settings.noBuild)
    private val verbose = JBCheckBox("Verbose output")
    private val arguments = JBTextField(settings.arguments.orEmpty())

    private val migrationName = JBTextField()
    private val outputDir = JBTextField().apply { emptyText.text = "Migrations" }
    private val lastMigration = JBLabel()
    private val force = JBCheckBox("Revert the migration in the database if it is applied there")
    private val target = editableCombo()
    private val connection = JBTextField().apply { emptyText.text = "The one the application is configured with" }
    private val from = editableCombo()
    private val to = editableCombo()
    private val idempotent = JBCheckBox("Idempotent: a script that works on a database at any migration")
    private val noTransactions = JBCheckBox("No transaction statements")
    private val scriptOutput = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("SQL Script"))
    }
    private val scaffoldConnection = editableCombo()
    private val provider = editableCombo()
    private val scaffoldOutputDir = JBTextField("Models")
    private val contextName = JBTextField().apply { emptyText.text = "Named after the database" }
    private val contextDir = JBTextField().apply { emptyText.text = "The folder of the entities" }
    private val tables = JBTextField().apply { emptyText.text = "All tables; or: Orders, dbo.Customers" }
    private val schemas = JBTextField().apply { emptyText.text = "All schemas" }
    private val dataAnnotations = JBCheckBox("Attributes instead of the fluent API where possible")
    private val useDatabaseNames = JBCheckBox("Keep the names of the database")
    private val noOnConfiguring = JBCheckBox("No OnConfiguring with the connection string", true)
    private val noPluralize = JBCheckBox("Do not pluralize")
    private val overwrite = JBCheckBox("Overwrite existing files")
    private val bundleOutput = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor().withTitle("Migration Bundle"))
    }
    private val selfContained = JBCheckBox("Self-contained: runs without .NET installed")
    private val runtime = editableCombo().apply { model = CollectionComboBoxModel(listOf("", "win-x64", "linux-x64", "linux-arm64", "linux-musl-x64", "osx-arm64", "osx-x64")) }
    private val preview = JBTextArea(3, 60).apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true
        font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, font.size)
        background = UIUtil.getPanelBackground()
    }

    private val selectedProject: VirtualFile? get() = migrationsProject.selectedItem as? VirtualFile
    private val selectedStartup: VirtualFile? get() = startupProject.selectedItem as? VirtualFile

    val context: EfContext
        get() = EfContext(
            project = selectedProject?.path.orEmpty(),
            startupProject = selectedStartup?.path,
            dbContext = dbContext.text.ifEmpty { null },
            configuration = configuration.selectedItem as? String,
            framework = (framework.selectedItem as? String)?.ifEmpty { null },
            environment = environment.text.ifEmpty { null },
            noBuild = noBuild.isSelected,
            verbose = verbose.isSelected,
            applicationArguments = EfCommandBuilder.splitArguments(arguments.text),
        )

    val command: EfCommand
        get() = when (kind) {
            EfCommandKind.ADD -> EfCommand.AddMigration(migrationName.text.trim(), outputDir.text.trim().ifEmpty { null })
            EfCommandKind.REMOVE -> EfCommand.RemoveMigration(force.isSelected)
            EfCommandKind.UPDATE -> EfCommand.UpdateDatabase(target.text.ifEmpty { null }, connection.text.trim().ifEmpty { null })
            EfCommandKind.SCRIPT -> EfCommand.Script(from.text, to.text, idempotent.isSelected, noTransactions.isSelected, scriptOutput.text.trim().ifEmpty { null })
            EfCommandKind.DROP -> EfCommand.DropDatabase
            EfCommandKind.SCAFFOLD -> EfCommand.Scaffold(
                scaffoldConnection.text, provider.text, scaffoldOutputDir.text.trim().ifEmpty { null }, contextName.text.trim().ifEmpty { null },
                contextDir.text.trim().ifEmpty { null }, splitList(tables.text), splitList(schemas.text),
                dataAnnotations.isSelected, useDatabaseNames.isSelected, noOnConfiguring.isSelected, noPluralize.isSelected, overwrite.isSelected,
            )
            EfCommandKind.BUNDLE -> EfCommand.Bundle(bundleOutput.text.trim().ifEmpty { null }, selfContained.isSelected, runtime.text.ifEmpty { null }, overwrite.isSelected)
        }

    private fun splitList(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

    init {
        title = kind.title
        setOKButtonText(kind.okText)
        configuration.selectedItem = DotNetBuildSettings.getInstance(project).configuration
        (initialProject?.takeIf { it in sources } ?: settings.migrationsProject?.let(files::findFileByPath)?.takeIf { it in sources })?.let { migrationsProject.selectedItem = it }
        projectChanged()
        environment.selectedItem = settings.environment.orEmpty()
        preset.dbContext?.takeIf { it.isNotEmpty() }?.let { dbContext.selectedItem = it; contextChanged() }
        preset.target?.let { target.selectedItem = it }
        preset.from?.let { from.selectedItem = it }
        preset.to?.let { to.selectedItem = it }

        migrationsProject.addActionListener { projectChanged(); updatePreview() }
        startupProject.addActionListener { startupChanged(); updatePreview() }
        dbContext.addActionListener { contextChanged(); updatePreview() }
        val editableCombos = listOf(dbContext, environment, target, from, to, scaffoldConnection, provider, runtime)
        (editableCombos - dbContext + listOf(configuration, framework)).forEach { combo -> combo.addActionListener { updatePreview() } }
        listOf(noBuild, verbose, force, idempotent, noTransactions, dataAnnotations, useDatabaseNames, noOnConfiguring, noPluralize, overwrite, selfContained)
            .forEach { box -> box.addActionListener { updatePreview() } }
        val texts = listOf(arguments, migrationName, outputDir, connection, scriptOutput.textField, scaffoldOutputDir, contextName, contextDir, tables, schemas, bundleOutput.textField) +
            editableCombos.map { it.editor.editorComponent as JTextComponent }
        texts.forEach { field ->
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updatePreview()
            })
        }
        init()
        updatePreview()
    }

    override fun getPreferredFocusedComponent(): JComponent = when (kind) {
        EfCommandKind.ADD -> migrationName
        EfCommandKind.UPDATE -> target
        EfCommandKind.SCAFFOLD -> scaffoldConnection
        else -> migrationsProject
    }

    override fun createCenterPanel(): JComponent = panel {
        when (kind) {
            EfCommandKind.ADD -> {
                row("Migration name:") { cell(migrationName).align(AlignX.FILL) }
                row("Folder:") { cell(outputDir).align(AlignX.FILL).comment("Relative to the migrations project; the folder of the existing migrations by default") }
            }
            EfCommandKind.REMOVE -> {
                row("Last migration:") { cell(lastMigration) }
                row { cell(force).comment("<code>--force</code>. Without it the command stops when the migration is applied, and nothing is lost") }
            }
            EfCommandKind.UPDATE -> {
                row("Target migration:") {
                    cell(target).align(AlignX.FILL).comment("Empty: the last migration. An earlier one <b>reverts</b> what is applied after it, <code>0</code> reverts everything")
                }
                row("Connection:") { cell(connection).align(AlignX.FILL) }
            }
            EfCommandKind.SCRIPT -> {
                row("From:") { cell(from).align(AlignX.FILL).comment("Empty: from an empty database") }
                row("To:") { cell(to).align(AlignX.FILL).comment("Empty: to the last migration") }
                row { cell(idempotent) }
                row { cell(noTransactions) }
                row("Save to:") { cell(scriptOutput).align(AlignX.FILL).comment("Empty: open the script as a scratch file") }
            }
            EfCommandKind.DROP -> row { comment("The database is looked up first: its name and server are shown before anything is deleted.") }
            EfCommandKind.SCAFFOLD -> {
                row("Connection:") {
                    cell(scaffoldConnection).align(AlignX.FILL)
                        .comment("A connection string, or <code>Name=ConnectionStrings:Default</code> to take it from the configuration of the startup project and keep it out of the code")
                }
                row("Provider:") { cell(provider).align(AlignX.FILL).comment("The NuGet package of the database provider; the project has to reference it") }
                row("Entities folder:") { cell(scaffoldOutputDir).align(AlignX.FILL) }
                row("DbContext name:") { cell(contextName).align(AlignX.FILL) }
                row("DbContext folder:") { cell(contextDir).align(AlignX.FILL) }
                row("Tables:") { cell(tables).align(AlignX.FILL).comment("Comma-separated") }
                row("Schemas:") { cell(schemas).align(AlignX.FILL) }
                row { cell(dataAnnotations) }
                row { cell(useDatabaseNames); cell(noPluralize) }
                row { cell(noOnConfiguring) }
                row { cell(overwrite).comment("<code>--force</code>: needed to scaffold again after the database has changed; edits of the generated files are lost") }
            }
            EfCommandKind.BUNDLE -> {
                row("Save to:") { cell(bundleOutput).align(AlignX.FILL).comment("Empty: <code>efbundle</code> in the migrations project") }
                row { cell(selfContained) }
                row("Target runtime:") { cell(runtime).comment("Empty: the runtime of this machine") }
                row { cell(overwrite) }
                row { comment("The bundle applies the migrations where there is neither the SDK nor the sources: <code>efbundle --connection \"...\"</code>") }
            }
        }
        separator()
        row(if (kind == EfCommandKind.SCAFFOLD) "Project:" else "Migrations project:") { cell(migrationsProject).align(AlignX.FILL) }
        row("Startup project:") { cell(startupProject).align(AlignX.FILL).comment("The application EF starts to get the configured <code>DbContext</code>") }
        row("DbContext:") { cell(dbContext).align(AlignX.FILL).comment("Can be empty when the project has one") }.visible(kind != EfCommandKind.SCAFFOLD)
        row("Environment:") { cell(environment).align(AlignX.FILL).comment("<code>ASPNETCORE_ENVIRONMENT</code>: decides which <code>appsettings</code>, i.e. which database") }
        collapsibleGroup("Build and Advanced") {
            row("Configuration:") { cell(configuration) }
            row("Target framework:") { cell(framework) }
            row { cell(noBuild).comment("<code>--no-build</code>: faster, but only when the build output is up to date") }
            row { cell(verbose) }
            row("Application arguments:") { cell(arguments).align(AlignX.FILL).comment("Passed to the startup project after <code>--</code>") }
        }
        row { cell(preview).align(AlignX.FILL).label("Command:", com.intellij.ui.dsl.builder.LabelPosition.TOP) }
    }.apply { preferredSize = Dimension(640, preferredSize.height) }

    private fun projectChanged() {
        val file = selectedProject ?: return
        val remembered = settings.startupProjects[file.path]?.let(files::findFileByPath)
        (remembered ?: EfProjects.defaultStartupProject(project, file))?.let { startupProject.selectedItem = it }
        val contexts = sources[file]?.dbContexts.orEmpty()
        dbContext.model = CollectionComboBoxModel(listOf("") + contexts)
        // nothing to choose from with one context; with several the tool refuses to guess
        dbContext.selectedItem = settings.dbContexts[file.path]?.takeIf { it in contexts } ?: contexts.takeIf { it.size > 1 }?.first().orEmpty()
        val frameworks = SolutionService.getInstance(project).msBuildProject(file).targetFrameworks.takeIf { it.size > 1 }.orEmpty()
        framework.model = CollectionComboBoxModel(listOf("") + frameworks)
        framework.isEnabled = frameworks.isNotEmpty()
        val typedProvider = provider.text
        provider.model = CollectionComboBoxModel(EfProjects.PROVIDERS)
        provider.selectedItem = typedProvider.ifEmpty { EfProjects.referencedProvider(SolutionService.getInstance(project).msBuildProject(file)).orEmpty() }
        startupChanged()
        contextChanged()
    }

    private fun startupChanged() {
        val current = environment.text
        environment.model = CollectionComboBoxModel(listOf("") + EfProjects.environments(selectedStartup))
        environment.selectedItem = current
        val references = EfProjects.connectionStringReferences(selectedStartup)
        val typedConnection = scaffoldConnection.text
        scaffoldConnection.model = CollectionComboBoxModel(references)
        scaffoldConnection.selectedItem = typedConnection.ifEmpty { references.firstOrNull().orEmpty() }
    }

    /** Migrations of the chosen context, newest first: the ones to update to, to script between, to remove. */
    private fun migrations(): List<EfMigrationSource> {
        val context = dbContext.text.substringAfterLast('.')
        return sources[selectedProject]?.migrations.orEmpty().filter { context.isEmpty() || it.dbContext == null || it.dbContext == context }.asReversed()
    }

    private fun contextChanged() {
        val names = migrations().map { it.name }
        lastMigration.text = names.firstOrNull() ?: "No migrations found in the sources"
        for (combo in listOf(target, from, to)) {
            val current = combo.text
            combo.model = CollectionComboBoxModel(listOf("") + names + if (combo === to) emptyList() else listOf("0"))
            combo.selectedItem = current
        }
    }

    private fun updatePreview() {
        preview.text = if (selectedProject == null) "" else EfCommandBuilder.preview(command, context, SolutionService.getInstance(project).solutionFiles().firstOrNull()?.parent?.path)
    }

    override fun doValidate(): ValidationInfo? {
        if (selectedProject == null) return ValidationInfo("No project of the solution references Entity Framework Core", migrationsProject)
        val startup = selectedStartup ?: return ValidationInfo("Choose the application EF starts to create the DbContext", startupProject)
        if (kind == EfCommandKind.ADD) {
            val name = migrationName.text.trim()
            when {
                name.isEmpty() -> return ValidationInfo("Specify the name of the migration", migrationName)
                !(name.first() == '_' || name.first().isLetter()) || !name.all { it == '_' || it.isLetterOrDigit() } ->
                    return ValidationInfo("'$name' is not a valid C# identifier", migrationName)
                migrations().any { it.name.equals(name, ignoreCase = true) } -> return ValidationInfo("The migration '$name' already exists", migrationName)
            }
        }
        if (kind == EfCommandKind.SCAFFOLD) {
            if (scaffoldConnection.text.isEmpty()) return ValidationInfo("Specify the connection string or a Name=ConnectionStrings:... reference", scaffoldConnection)
            if (provider.text.isEmpty()) return ValidationInfo("Specify the package of the database provider", provider)
            val name = contextName.text.trim()
            if (name.isNotEmpty() && !(name.first().isLetter() || name.first() == '_') || !name.all { it == '_' || it.isLetterOrDigit() }) {
                return ValidationInfo("'$name' is not a valid C# identifier", contextName)
            }
        }
        if (kind == EfCommandKind.REMOVE && migrations().isEmpty()) return ValidationInfo("There are no migrations to remove", migrationsProject)
        if (EfProjects.hasDesignPackage(project, startup) == false) {
            return ValidationInfo("'${startup.nameWithoutExtension}' does not reference ${EfProjects.DESIGN_PACKAGE}: the command will offer to add it", startupProject).asWarning().withOKEnabled()
        }
        return null
    }

    override fun doOKAction() {
        if (kind == EfCommandKind.UPDATE && isRollback() && Messages.showYesNoDialog(
                project, "Migrations applied after '${target.text}' will be reverted in the database of the '${environment.text.ifEmpty { "default" }}' environment. " +
                    "Columns and tables they have added are dropped with their data.",
                "Revert Migrations", "Revert", Messages.getCancelButton(), Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        EfSettings.getInstance(project).remember(context, keepDbContext = kind == EfCommandKind.SCAFFOLD)
        super.doOKAction()
    }

    /** An explicit target that is not the newest migration: it reverts something when the database is further ahead. */
    private fun isRollback(): Boolean {
        val chosen = target.text
        val newest = migrations().firstOrNull() ?: return chosen == "0"
        return chosen.isNotEmpty() && !chosen.equals(newest.name, ignoreCase = true) && !chosen.equals(newest.id, ignoreCase = true)
    }

    private fun editableCombo(): ComboBox<String> = ComboBox<String>().apply { isEditable = true }

    /** What is typed, not the last selected item: an editable combo box commits its editor only on Enter. */
    private val ComboBox<String>.text: String get() = (editor.item as? String ?: selectedItem as? String).orEmpty().trim()

    private fun projectRenderer() = SimpleListCellRenderer.create<VirtualFile>("") { it.nameWithoutExtension }
}
