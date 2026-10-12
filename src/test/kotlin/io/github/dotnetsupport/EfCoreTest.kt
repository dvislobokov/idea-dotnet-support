package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.cli.DotNetCli
import io.github.dotnetsupport.cli.DotNetToolManifest
import io.github.dotnetsupport.ef.EfCommand
import io.github.dotnetsupport.ef.EfCommandBuilder
import io.github.dotnetsupport.ef.EfContext
import io.github.dotnetsupport.ef.EfDatabaseStatus
import io.github.dotnetsupport.ef.EfDeclaration
import io.github.dotnetsupport.ef.EfDeclarationKind
import io.github.dotnetsupport.ef.EfDesignTimeFactory
import io.github.dotnetsupport.ef.EfGutterActions
import io.github.dotnetsupport.ef.EfMigration
import io.github.dotnetsupport.ef.EfMigrationStatus
import io.github.dotnetsupport.ef.EfMigrationsModel
import io.github.dotnetsupport.ef.EfMigrationsService
import io.github.dotnetsupport.ef.EfToolWindowFactory
import io.github.dotnetsupport.ef.EfToolWindowModel
import io.github.dotnetsupport.ef.EfOutputParser
import io.github.dotnetsupport.ef.EfProblem
import io.github.dotnetsupport.ef.EfProjects
import io.github.dotnetsupport.ef.EfRunner
import io.github.dotnetsupport.ef.EfSettings
import io.github.dotnetsupport.ef.EfSources

class EfCoreTest : BasePlatformTestCase() {
    private val context = EfContext(project = "C:/repo/Data/Data.csproj", startupProject = "C:/repo/Api/Api.csproj", dbContext = "AppDbContext", configuration = "Debug")

    fun testCommonOptionsFollowTheCommand() {
        assertEquals(
            listOf("migrations", "add", "Init", "--output-dir", "Data/Migrations", "--project", "C:/repo/Data/Data.csproj",
                "--startup-project", "C:/repo/Api/Api.csproj", "--context", "AppDbContext", "--configuration", "Debug"),
            EfCommandBuilder.arguments(EfCommand.AddMigration("Init", "Data/Migrations"), context),
        )
        // one project for both roles: the tool takes --project as the startup one
        val single = EfContext(project = "C:/repo/App/App.csproj", startupProject = "C:/repo/App/App.csproj", noBuild = true, applicationArguments = listOf("--tenant", "a b"))
        assertEquals(
            listOf("database", "update", "--project", "C:/repo/App/App.csproj", "--no-build", "--", "--tenant", "a b"),
            EfCommandBuilder.arguments(EfCommand.UpdateDatabase(), single),
        )
    }

    fun testCommandSpecificArguments() {
        fun head(command: EfCommand) = EfCommandBuilder.arguments(command, EfContext("P.csproj")).takeWhile { it != "--project" }
        assertEquals(listOf("migrations", "remove", "--force"), head(EfCommand.RemoveMigration(force = true)))
        assertEquals(listOf("database", "update", "0", "--connection", "Host=db"), head(EfCommand.UpdateDatabase("0", "Host=db")))
        assertEquals(listOf("database", "drop", "--force"), head(EfCommand.DropDatabase))
        assertEquals(listOf("migrations", "script"), head(EfCommand.Script()))
        // TO alone needs the positional FROM
        assertEquals(listOf("migrations", "script", "0", "AddUsers", "--idempotent", "--output", "out.sql"), head(EfCommand.Script(to = "AddUsers", idempotent = true, output = "out.sql")))
        assertEquals(listOf("migrations", "script", "Init"), head(EfCommand.Script(from = "Init")))
        assertEquals(listOf("migrations", "list", "--no-connect", "--json", "--prefix-output"), head(EfCommand.ListMigrations(noConnect = true)))
        // the list of contexts is not narrowed by the chosen one
        assertFalse("--context" in EfCommandBuilder.arguments(EfCommand.ListContexts, context))
    }

    fun testPreviewAndEnvironment() {
        val staging = context.copy(environment = "Staging")
        assertEquals(
            "ASPNETCORE_ENVIRONMENT=Staging dotnet ef database update AddUsers --project Data/Data.csproj --startup-project Api/Api.csproj --context AppDbContext --configuration Debug",
            EfCommandBuilder.preview(EfCommand.UpdateDatabase("AddUsers"), staging, "C:\\repo"),
        )
        assertEquals(mapOf("ASPNETCORE_ENVIRONMENT" to "Staging", "DOTNET_ENVIRONMENT" to "Staging"), EfCommandBuilder.environment(staging))
        assertTrue(EfCommandBuilder.environment(context).isEmpty())
        assertEquals(listOf("--tenant", "a b", "-v"), EfCommandBuilder.splitArguments("""--tenant "a b"  -v"""))
        assertEquals("dotnet ef migrations add Init", EfRunner.title(EfCommand.AddMigration("Init"), context))
        assertEquals("dotnet ef migrations script", EfRunner.title(EfCommand.Script("A", "B"), context))
    }

    fun testConnectionStringIsMaskedInLogs() {
        val command = com.intellij.execution.configurations.GeneralCommandLine("dotnet", "ef", "database", "update", "--connection", "Host=db;Password=secret")
        assertEquals("dotnet ef database update --connection ********", DotNetCli.displayString(command))
    }

    fun testMigrationsListOutput() {
        val output = """
            info:    Build started...
            info:    Build succeeded.
            data:    [
            data:      { "id": "20240101120000_Init", "name": "Init", "safeName": "Init", "applied": true },
            data:      { "id": "20240301090000_AddUsers", "name": "AddUsers", "safeName": "AddUsers", "applied": false },
            data:      { "id": "20240612080000_AddOrders", "name": "AddOrders", "safeName": "AddOrders", "applied": null }
            data:    ]
        """.trimIndent()
        val migrations = EfOutputParser.migrations(output)
        assertEquals(listOf("Init", "AddUsers", "AddOrders"), migrations.map { it.name })
        assertEquals(listOf(true, false, null), migrations.map { it.applied })
        assertEquals("20240301090000_AddUsers", migrations[1].id)
        assertTrue(EfOutputParser.migrations("error:   Build failed.").isEmpty())
    }

    fun testContextsAndInfoOutput() {
        val contexts = EfOutputParser.contexts("""
            data:    [
            data:      { "fullName": "Shop.Data.AppDbContext", "safeName": "AppDbContext", "name": "AppDbContext", "assemblyQualifiedName": "..." },
            data:      { "fullName": "Shop.Data.AuditDbContext", "safeName": "AuditDbContext", "name": "AuditDbContext", "assemblyQualifiedName": "..." }
            data:    ]
        """.trimIndent())
        assertEquals(listOf("Shop.Data.AppDbContext", "Shop.Data.AuditDbContext"), contexts.map { it.fullName })

        val info = EfOutputParser.contextInfo("""
            data:    {
            data:      "type": "Shop.Data.AppDbContext",
            data:      "providerName": "Npgsql.EntityFrameworkCore.PostgreSQL",
            data:      "databaseName": "shop",
            data:      "dataSource": "tcp://localhost:5432",
            data:      "options": "None"
            data:    }
        """.trimIndent())!!
        assertEquals("shop", info.databaseName)
        assertEquals("tcp://localhost:5432", info.dataSource)
        assertNull(EfOutputParser.contextInfo("info:    nothing"))
    }

    fun testKnownFailures() {
        assertEquals(EfProblem.TOOL_MISSING, EfOutputParser.diagnose("Could not execute because the specified command or file was not found.\n  * You intended to execute a .NET program, but dotnet-ef does not exist."))
        assertEquals(EfProblem.TOOL_NOT_RESTORED, EfOutputParser.diagnose("Run \"dotnet tool restore\" to make the \"dotnet-ef\" command available."))
        assertEquals(EfProblem.DESIGN_PACKAGE_MISSING, EfOutputParser.diagnose("Your startup project 'Api' doesn't reference Microsoft.EntityFrameworkCore.Design. This package is required"))
        assertEquals(EfProblem.CANNOT_CREATE_CONTEXT, EfOutputParser.diagnose("Unable to create a 'DbContext' of type 'AppDbContext'. The exception 'Unable to resolve service"))
        assertEquals(EfProblem.CANNOT_CREATE_CONTEXT, EfOutputParser.diagnose("Unable to create an object of type 'AppDbContext'. For the different patterns"))
        assertEquals(EfProblem.MULTIPLE_CONTEXTS, EfOutputParser.diagnose("More than one DbContext was found. Specify which one to use."))
        assertEquals(EfProblem.NO_CONTEXT, EfOutputParser.diagnose("No DbContext was found in assembly 'Api'."))
        assertEquals(EfProblem.MIGRATION_APPLIED, EfOutputParser.diagnose("The migration '20240101_Init' has already been applied to the database. Revert it and try again."))
        assertEquals(EfProblem.BUILD_FAILED, EfOutputParser.diagnose("Build started...\nBuild failed. Use dotnet build to see the errors."))
        assertNull(EfOutputParser.diagnose("Done."))

        assertEquals("7.0.0" to "8.0.1", EfOutputParser.outdatedTool("The Entity Framework tools version '7.0.0' is older than that of the runtime '8.0.1'. Update the tools"))
        assertEquals("No DbContext was found", EfOutputParser.errorText("info:    Build succeeded.\nerror:   No DbContext was found\n"))
        assertEquals("plain failure", EfOutputParser.errorText("plain failure\n"))
    }

    fun testToolManifest() {
        val manifest = """{ "version": 1, "isRoot": true, "tools": { "Dotnet-EF": { "version": "9.0.1", "commands": ["dotnet-ef"] } } }"""
        assertEquals("9.0.1", DotNetToolManifest.parse(manifest, "dotnet-ef"))
        assertNull(DotNetToolManifest.parse(manifest, "csharpier"))
        assertNull(DotNetToolManifest.parse("not json", "dotnet-ef"))
    }

    fun testDesignerFile() {
        val designer = """
            namespace Shop.Data.Migrations
            {
                [DbContext(typeof(Shop.Data.AppDbContext))]
                [Migration("20240301090000_AddUsers")]
                partial class AddUsers
                {
        """.trimIndent()
        val migration = EfSources.parseDesigner(designer)!!
        assertEquals("20240301090000_AddUsers", migration.id)
        assertEquals("AddUsers", migration.name)
        assertEquals("AppDbContext", migration.dbContext)
        // the model snapshot has the context but is not a migration
        assertNull(EfSources.parseDesigner("[DbContext(typeof(AppDbContext))]\npartial class AppDbContextModelSnapshot : ModelSnapshot"))
    }

    fun testDbContextClasses() {
        val text = """
            public class AppDbContext : DbContext { }
            public sealed class UsersDb(DbContextOptions<UsersDb> options) : IdentityDbContext<User>(options), IUsers { }
            internal partial class AuditContext : Microsoft.EntityFrameworkCore.DbContext
            { }
            public abstract class BaseContext : DbContext { }
            public class Factory : IDesignTimeDbContextFactory<AppDbContext> { }
            public class Options : DbContextOptions { }
        """.trimIndent()
        assertEquals(listOf("AppDbContext", "UsersDb", "AuditContext"), EfSources.dbContextClasses(text))
        assertTrue(EfSources.dbContextClasses("class Plain : Base { }").isEmpty())
    }

    fun testDestructiveOperationsOfUp() {
        val text = """
            protected override void Up(MigrationBuilder migrationBuilder)
            {
                migrationBuilder.DropColumn(
                    name: "Email",
                    table: "Users");
                migrationBuilder.DropTable(name: "Orders");
                migrationBuilder.AddColumn<string>(name: "Mail", table: "Users");
            }

            protected override void Down(MigrationBuilder migrationBuilder)
            {
                migrationBuilder.DropColumn(name: "Mail", table: "Users");
            }
        """.trimIndent()
        assertEquals(listOf("DropColumn Email", "DropTable Orders"), EfSources.destructiveOperations(text))
    }

    fun testSourcesOfProject() {
        val projectFile = myFixture.addFileToProject("EfData/EfData.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        fun designer(id: String, context: String) = "[DbContext(typeof($context))]\n[Migration(\"$id\")]\npartial class M { }"
        myFixture.addFileToProject("EfData/Migrations/20240301090000_AddUsers.Designer.cs", designer("20240301090000_AddUsers", "AppDbContext"))
        myFixture.addFileToProject("EfData/Migrations/20240301090000_AddUsers.cs", "partial class AddUsers { }")
        myFixture.addFileToProject("EfData/Migrations/20240101120000_Init.Designer.cs", designer("20240101120000_Init", "AppDbContext"))
        myFixture.addFileToProject("EfData/AppDbContext.cs", "public class AppDbContext : DbContext { }")
        myFixture.addFileToProject("EfData/Audit/AuditDbContext.cs", "public class AuditDbContext : DbContext { }")
        myFixture.addFileToProject("EfData/obj/Generated.cs", "public class ObjContext : DbContext { }")

        val migrations = EfSources.migrations(projectFile)
        assertEquals(listOf("Init", "AddUsers"), migrations.map { it.migration.name })
        assertEquals("20240301090000_AddUsers.cs", migrations.last().source?.name)
        assertNull(migrations.first().source)
        // the context with migrations first; build output is not a source
        assertEquals(listOf("AppDbContext", "AuditDbContext"), EfSources.dbContexts(projectFile))
    }

    fun testProjectsAndStartupProject() {
        val data = myFixture.addFileToProject("EfShop/Data/Data.csproj", """
            <Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="9.0.0"/></ItemGroup></Project>
        """.trimIndent()).virtualFile
        val api = myFixture.addFileToProject("EfShop/Api/Api.csproj", """
            <Project Sdk="Microsoft.NET.Sdk.Web"><ItemGroup>
              <ProjectReference Include="..\Data\Data.csproj"/>
              <PackageReference Include="Microsoft.EntityFrameworkCore.Design" Version="9.0.0"/>
            </ItemGroup></Project>
        """.trimIndent()).virtualFile
        val tool = myFixture.addFileToProject("EfShop/Tool/Tool.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><OutputType>Exe</OutputType></PropertyGroup></Project>").virtualFile
        myFixture.addFileToProject("EfShop/Api/appsettings.json", "{}")
        myFixture.addFileToProject("EfShop/Api/appsettings.Testing.json", "{}")
        val solution = myFixture.addFileToProject(
            "EfShop.slnx", "<Solution><Project Path=\"EfShop/Data/Data.csproj\"/><Project Path=\"EfShop/Api/Api.csproj\"/><Project Path=\"EfShop/Tool/Tool.csproj\"/></Solution>",
        ).virtualFile
        try {
            val migrationsProjects = EfProjects.migrationsProjects(project)
            assertTrue(data in migrationsProjects && api in migrationsProjects)
            assertFalse(tool in migrationsProjects)
            assertTrue(EfProjects.startupProjects(project).containsAll(listOf(api, tool)))
            assertFalse(data in EfProjects.startupProjects(project))

            // the application that references the library, not just any executable
            assertEquals(api, EfProjects.defaultStartupProject(project, data))
            assertEquals(api, EfProjects.defaultStartupProject(project, api))
            assertEquals(true, EfProjects.hasDesignPackage(project, api))
            assertNull("not restored: unknown", EfProjects.hasDesignPackage(project, tool))
            assertEquals(listOf("Testing", "Development", "Staging", "Production"), EfProjects.environments(api))
        } finally {
            runWriteAction { solution.delete(this) }
        }
    }

    fun testSettingsRememberTheChoicePerMigrationsProject() {
        val settings = EfSettings()
        settings.remember(context.copy(environment = "Staging", applicationArguments = listOf("--tenant", "a b")))
        assertEquals("C:/repo/Api/Api.csproj", settings.state.startupProjects["C:/repo/Data/Data.csproj"])
        assertEquals("AppDbContext", settings.state.dbContexts["C:/repo/Data/Data.csproj"])
        assertEquals("Staging", settings.state.environment)
        assertEquals(listOf("--tenant", "a b"), EfCommandBuilder.splitArguments(settings.state.arguments.orEmpty()))
        settings.remember(context.copy(dbContext = null))
        assertNull(settings.state.dbContexts["C:/repo/Data/Data.csproj"])
    }

    fun testScaffoldAndBundleArguments() {
        val scaffold = EfCommand.Scaffold(
            "Name=ConnectionStrings:Default", "Npgsql.EntityFrameworkCore.PostgreSQL", outputDir = "Models", contextName = "ShopContext", contextDir = "Data",
            tables = listOf("orders", "public.customers"), schemas = listOf("public"), dataAnnotations = true, noOnConfiguring = true, force = true,
        )
        // --context is the class to generate: the chosen DbContext of the common options does not apply
        assertEquals(
            listOf("dbcontext", "scaffold", "Name=ConnectionStrings:Default", "Npgsql.EntityFrameworkCore.PostgreSQL", "--output-dir", "Models", "--context", "ShopContext",
                "--context-dir", "Data", "--schema", "public", "--table", "orders", "--table", "public.customers", "--data-annotations", "--no-onconfiguring", "--force",
                "--project", "C:/repo/Data/Data.csproj", "--startup-project", "C:/repo/Api/Api.csproj", "--configuration", "Debug"),
            EfCommandBuilder.arguments(scaffold, context),
        )
        assertEquals(
            listOf("migrations", "bundle", "--output", "out/efbundle", "--self-contained", "--target-runtime", "linux-x64", "--force", "--project", "P.csproj", "--context", "AppDb"),
            EfCommandBuilder.arguments(EfCommand.Bundle("out/efbundle", selfContained = true, runtime = "linux-x64", force = true), EfContext("P.csproj", dbContext = "AppDb")),
        )
        assertEquals("dotnet ef dbcontext scaffold", EfRunner.title(scaffold, context))

        // a connection string is a secret, a reference to the configuration is not
        fun shown(connection: String) = DotNetCli.displayString(com.intellij.execution.configurations.GeneralCommandLine("dotnet", "ef", "dbcontext", "scaffold", connection, "Provider"))
        assertEquals("dotnet ef dbcontext scaffold ******** Provider", shown("Host=db;Password=secret"))
        assertEquals("dotnet ef dbcontext scaffold Name=ConnectionStrings:Default Provider", shown("Name=ConnectionStrings:Default"))
        assertEquals(EfProblem.PROVIDER_MISSING, EfOutputParser.diagnose("Unable to find provider assembly 'Npgsql.EntityFrameworkCore.PostgreSQL'. Ensure the name is correct"))
    }

    fun testConnectionStringReferencesAndProvider() {
        val json = """
            {
              // JSONC, as ASP.NET Core reads it
              "ConnectionStrings": { "Default": "Host=localhost;Database=shop", "Audit": "Host=localhost;Database=audit", },
              "Logging": {}
            }
        """.trimIndent()
        assertEquals(listOf("Name=ConnectionStrings:Default", "Name=ConnectionStrings:Audit"), EfProjects.connectionStringReferences(json))
        assertTrue(EfProjects.connectionStringReferences("{}").isEmpty())
        assertTrue(EfProjects.connectionStringReferences("broken").isEmpty())

        val project = io.github.dotnetsupport.msbuild.MsBuildProject.parse("<Project><ItemGroup><PackageReference Include=\"npgsql.entityframeworkcore.postgresql\" Version=\"9.0.0\"/></ItemGroup></Project>")
        assertEquals("Npgsql.EntityFrameworkCore.PostgreSQL", EfProjects.referencedProvider(project))
    }

    fun testGutterIconsOfContextsAndMigrations() {
        val text = "namespace Shop;\n\npublic class ShopContext : DbContext { }\n\npublic partial class AddUsers : Migration { }\n\npublic class Plain : Base { }\n"
        val declarations = EfSources.declarations(text)
        assertEquals(listOf(EfDeclarationKind.DB_CONTEXT to "ShopContext", EfDeclarationKind.MIGRATION to "AddUsers"), declarations.map { it.kind to it.name })
        assertEquals(text.indexOf("ShopContext"), declarations[0].offset)

        myFixture.addFileToProject("EfGutter/EfGutter.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("EfGutter/Migrations/20240301090000_AddUsers.Designer.cs", "[DbContext(typeof(ShopContext))]\n[Migration(\"20240301090000_AddUsers\")]\npartial class AddUsers { }")
        val migration = myFixture.addFileToProject("EfGutter/Migrations/20240301090000_AddUsers.cs", text)
        myFixture.configureFromExistingVirtualFile(migration.virtualFile)
        assertEquals(2, myFixture.findAllGutters().size)

        fun texts(declaration: EfDeclaration) = EfGutterActions.actionsFor(project, migration.virtualFile, declaration).mapNotNull { it.templatePresentation.text }
        assertEquals(
            listOf("Add Migration...", "Update Database...", "Generate SQL Script...", "Create Migration Bundle...", "Drop Database...", "Create Design-Time Factory", "Show Migrations"),
            texts(declarations[0]),
        )
        assertEquals(
            listOf("Update Database to 'AddUsers'...", "Generate SQL Script to 'AddUsers'...", "Generate SQL Script from 'AddUsers'...", "Show Migrations"),
            texts(declarations[1]),
        )
    }

    fun testDesignTimeFactory() {
        assertEquals("UseSqlite(connectionString)", EfDesignTimeFactory.variables("Microsoft.EntityFrameworkCore.Sqlite", "shop")["EF_USE_PROVIDER"])
        assertEquals("Data Source=shop.db", EfDesignTimeFactory.variables("Microsoft.EntityFrameworkCore.Sqlite", "shop")["EF_CONNECTION_STRING"])
        assertTrue(EfDesignTimeFactory.variables("Pomelo.EntityFrameworkCore.MySql", "shop").getValue("EF_USE_PROVIDER").contains("ServerVersion.AutoDetect"))
        assertTrue("an unknown provider is said so in the code", EfDesignTimeFactory.variables(null, "shop").getValue("EF_USE_PROVIDER").contains("TODO"))

        myFixture.addFileToProject("EfFactory/Shop.Data.csproj", """
            <Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><ImplicitUsings>enable</ImplicitUsings></PropertyGroup>
            <ItemGroup><PackageReference Include="Npgsql.EntityFrameworkCore.PostgreSQL" Version="9.0.0"/></ItemGroup></Project>
        """.trimIndent())
        val contextFile = myFixture.addFileToProject("EfFactory/Persistence/ShopContext.cs", "public class ShopContext : DbContext { }").virtualFile

        EfDesignTimeFactory.create(project, contextFile, "ShopContext")
        val factory = contextFile.parent.findChild("ShopContextFactory.cs")!!
        val text = com.intellij.openapi.vfs.VfsUtilCore.loadText(factory)
        assertTrue(text, "public class ShopContextFactory : IDesignTimeDbContextFactory<ShopContext>" in text)
        assertTrue(text, ".UseNpgsql(connectionString)" in text)
        assertTrue(text, "Host=localhost;Database=shop;Username=postgres;Password=postgres" in text)
        assertEquals("product", EfDesignTimeFactory.databaseName("Company.Product.Infrastructure.Data"))
        assertEquals("data", EfDesignTimeFactory.databaseName("Data"))
        assertTrue(text, "ConnectionStrings__Default" in text)
        assertTrue(text, "namespace Shop.Data.Persistence" in text)
        assertTrue(text, "using Microsoft.EntityFrameworkCore.Design;" in text)
        assertFalse("implicit usings", "using System;" in text)

        // again: the existing file is opened, not overwritten or reported
        EfDesignTimeFactory.create(project, contextFile, "ShopContext")
        assertEquals(factory, com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
        assertEquals(listOf(contextFile to "ShopContext"), EfSources.dbContextFiles(contextFile.parent.parent.findChild("Shop.Data.csproj")!!))
    }

    fun testPendingModelChanges() {
        assertEquals(listOf("migrations", "has-pending-model-changes", "--project", "P.csproj"), EfCommandBuilder.arguments(EfCommand.HasPendingModelChanges, EfContext("P.csproj")))
        assertEquals(true, EfOutputParser.pendingModelChanges("Build succeeded.\nChanges have been made to the model since the last migration. Add a new migration."))
        assertEquals(false, EfOutputParser.pendingModelChanges("No changes have been made to the model since the last migration."))
        assertNull("a tool before EF 8", EfOutputParser.pendingModelChanges("Unrecognized command or argument 'has-pending-model-changes'"))
    }

    fun testMigrationRowsMergeSourcesWithTheDatabase() {
        val projectFile = myFixture.addFileToProject("EfRows/EfRows.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>").virtualFile
        for (id in listOf("20240101120000_Init", "20240301090000_AddUsers", "20240612080000_AddOrders")) {
            myFixture.addFileToProject("EfRows/Migrations/$id.Designer.cs", "[DbContext(typeof(AppDbContext))]\n[Migration(\"$id\")]\npartial class M { }")
        }
        val files = EfSources.migrations(projectFile)

        // nothing asked yet: newest first, status unknown
        val unknown = EfMigrationsModel.rows(files, null)
        assertEquals(listOf("AddOrders", "AddUsers", "Init"), unknown.map { it.name })
        assertTrue(unknown.all { it.status == EfMigrationStatus.UNKNOWN })

        // AddOrders is added after the status was loaded; Legacy is known to the tool only
        val listed = listOf(EfMigration("20230101000000_Legacy", "Legacy", true), EfMigration("20240101120000_Init", "Init", true), EfMigration("20240301090000_AddUsers", "AddUsers", false))
        val rows = EfMigrationsModel.rows(files, listed)
        assertEquals(listOf("AddOrders", "AddUsers", "Init", "Legacy"), rows.map { it.name })
        assertEquals(listOf(EfMigrationStatus.PENDING, EfMigrationStatus.PENDING, EfMigrationStatus.APPLIED, EfMigrationStatus.APPLIED), rows.map { it.status })
        assertNull(rows.last().file)
        assertEquals("2 applied, 2 pending", EfToolWindowModel.summary(rows))
        assertEquals("database is not reachable", EfToolWindowModel.summary(EfMigrationsModel.rows(files, listed.map { it.copy(applied = null) })))

        assertEquals("2024-03-01 09:00", EfMigrationsModel.timestamp("20240301090000_AddUsers"))
        assertNull(EfMigrationsModel.timestamp("Manual_Name"))
        // a context without migrations is still a node to add the first one from
        assertEquals(listOf("AppDbContext" to 3, "AuditDbContext" to 0), EfMigrationsModel.byContext(files, listOf("AppDbContext", "AuditDbContext")).map { it.key to it.value.size })
    }

    fun testToolWindowModelAndContent() {
        val data = myFixture.addFileToProject("EfWindow/Data/Data.csproj", """
            <Project Sdk="Microsoft.NET.Sdk"><ItemGroup><PackageReference Include="Microsoft.EntityFrameworkCore.SqlServer" Version="9.0.0"/></ItemGroup></Project>
        """.trimIndent()).virtualFile
        // references EF, but has nothing of it: not a node
        myFixture.addFileToProject("EfWindow/Web/Web.csproj", """
            <Project Sdk="Microsoft.NET.Sdk.Web"><ItemGroup><PackageReference Include="Microsoft.EntityFrameworkCore.Design" Version="9.0.0"/></ItemGroup></Project>
        """.trimIndent())
        myFixture.addFileToProject("EfWindow/Data/ShopContext.cs", "public class ShopContext : DbContext { }")
        myFixture.addFileToProject("EfWindow/Data/Migrations/20240101120000_Init.Designer.cs", "[DbContext(typeof(ShopContext))]\n[Migration(\"20240101120000_Init\")]\npartial class Init { }")
        val solution = myFixture.addFileToProject("EfWindow.slnx", "<Solution><Project Path=\"EfWindow/Data/Data.csproj\"/><Project Path=\"EfWindow/Web/Web.csproj\"/></Solution>").virtualFile
        try {
            val node = EfToolWindowModel.load(project).single { it.file.path.contains("/EfWindow/") }
            assertEquals(data, node.file)
            val context = node.contexts.single()
            assertEquals("ShopContext", context.name)
            val migration = EfToolWindowModel.migrations(context, null).single()
            assertTrue(migration.isNewest)
            assertEquals(EfMigrationStatus.UNKNOWN, migration.row.status)
            assertEquals(EfMigrationStatus.APPLIED, EfToolWindowModel.migrations(context, EfDatabaseStatus.Loaded(listOf(EfMigration("20240101120000_Init", "Init", true)), false)).single().row.status)

            // the status is asked with the application that would be offered in the dialogs
            val options = EfMigrationsService.getInstance(project).context(context.key)
            assertEquals("ShopContext", options.dbContext)
            assertEquals(data.path, options.project)

            val toolWindow = com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project)
            try {
                EfToolWindowFactory().createToolWindowContent(project, toolWindow)
                assertEquals(1, toolWindow.contentManager.contentCount)
            } finally {
                com.intellij.openapi.util.Disposer.dispose(toolWindow.disposable)
            }
        } finally {
            runWriteAction { solution.delete(this) }
        }
    }

    fun testMenus() {
        val actions = ActionManager.getInstance()
        val group = actions.getAction("DotNet.EfCore") as DefaultActionGroup
        assertEquals(
            listOf("DotNet.EfAddMigration", "DotNet.EfRemoveMigration", "DotNet.EfUpdateDatabase", "DotNet.EfGenerateScript", "DotNet.EfDropDatabase",
                "DotNet.EfCreateBundle", "DotNet.EfScaffold", "DotNet.EfShowMigrations", "DotNet.EfInstallTool"),
            group.childActionsOrStubs.mapNotNull(actions::getId),
        )
        for (parent in listOf("DotNet.MainMenu", "DotNet.SolutionViewPopup")) {
            assertTrue(parent, (actions.getAction(parent) as DefaultActionGroup).childActionsOrStubs.any { it === group || actions.getId(it) == "DotNet.EfCore" })
        }
    }
}
