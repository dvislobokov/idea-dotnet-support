package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.IncorrectOperationException
import io.github.dotnetsupport.msbuild.MsBuildProject
import io.github.dotnetsupport.templates.ItemCategory
import io.github.dotnetsupport.templates.ItemCreator
import io.github.dotnetsupport.templates.ItemTemplates
import io.github.dotnetsupport.templates.NewDotNetItemGroup
import io.github.dotnetsupport.templates.PartialPartAction
import io.github.dotnetsupport.templates.ResxCultureAction
import io.github.dotnetsupport.templates.TemplateRenderer
import io.github.dotnetsupport.templates.TestForClassAction
import io.github.dotnetsupport.templates.TypeDeclarationScanner

class ItemTemplatesTest : BasePlatformTestCase() {
    private val web = """<Project Sdk="Microsoft.NET.Sdk.Web"><PropertyGroup><TargetFramework>net9.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings></PropertyGroup></Project>"""
    private val legacy = """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net48</TargetFramework></PropertyGroup></Project>"""

    private fun directory(path: String): VirtualFile = myFixture.tempDirFixture.findOrCreateDir(path)
    private fun text(file: VirtualFile): String = VfsUtilCore.loadText(file)

    fun testEveryTemplateRenders() {
        myFixture.addFileToProject("All/All.csproj", web)
        for ((index, template) in ItemTemplates.ALL.filter { it.cliTemplate == null }.withIndex()) {
            val files = ItemCreator.create(project, directory("All/T$index"), template, "Sample")
            assertEquals(template.id, template.files.size, files.size)
            for (file in files) {
                val content = text(file)
                assertFalse("${template.id}: unresolved variable in ${file.name}\n$content", Regex("""\$\{[A-Z_]+}""").containsMatchIn(content))
                assertFalse("${template.id}: separator leaked into ${file.name}", content.lines().contains("---"))
                assertTrue("${template.id}: ${file.name} must end with a line break", content.endsWith("\n"))
            }
        }
    }

    fun testApiControllerInWebProject() {
        myFixture.addFileToProject("Shop/Shop.csproj", web)
        val file = ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("apiController"), "Api/Orders").single()
        assertEquals("OrdersController.cs", file.name)
        assertEquals("Api", file.parent.name)
        assertEquals(
            """
            using Microsoft.AspNetCore.Mvc;

            namespace Shop.Api;

            [ApiController]
            [Route("api/[controller]")]
            public class OrdersController : ControllerBase
            {
                [HttpGet]
                public IActionResult Get()
                {
                    return Ok();
                }
            }

            """.trimIndent(),
            text(file),
        )
        // the suffix is not doubled, and an existing file is not overwritten
        assertEquals("OrdersController" to "Orders", ItemCreator.names(ItemTemplates.byId("apiController"), "OrdersController"))
        assertThrows(IncorrectOperationException::class.java) {
            ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("mvcController"), "Api/OrdersController")
        }
    }

    fun testImplicitUsingsAndBlockNamespace() {
        myFixture.addFileToProject("Shop/Shop.csproj", web)
        myFixture.addFileToProject("Old/Old.csproj", legacy)

        // System.* and Microsoft.Extensions.* come from the implicit usings of the web SDK
        val modern = text(ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("backgroundService"), "Cleanup").single())
        assertFalse(modern, modern.contains("using "))
        assertTrue(modern, modern.startsWith("namespace Shop;\n\npublic class Cleanup : BackgroundService\n{\n"))

        val old = text(ItemCreator.create(project, directory("Old"), ItemTemplates.byId("exception"), "Parse").single())
        assertEquals(
            """
            using System;

            namespace Old
            {
                public class ParseException : Exception
                {
                    public ParseException()
                    {
                    }

                    public ParseException(string message) : base(message)
                    {
                    }

                    public ParseException(string message, Exception innerException) : base(message, innerException)
                    {
                    }
                }
            }

            """.trimIndent(),
            old,
        )
    }

    fun testMultiFileAndPlainTemplates() {
        myFixture.addFileToProject("Shop/Shop.csproj", web)
        val component = ItemCreator.create(project, directory("Shop/Components"), ItemTemplates.byId("componentCodeBehind"), "Cart")
        assertEquals(listOf("Cart.razor", "Cart.razor.cs"), component.map { it.name })
        assertTrue(text(component[1]), text(component[1]).contains("namespace Shop.Components;\n\npublic partial class Cart : ComponentBase"))

        val page = ItemCreator.create(project, directory("Shop/Pages"), ItemTemplates.byId("razorPage"), "Index")
        assertTrue(text(page[0]).contains("@model Shop.Pages.IndexModel"))

        val launch = ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("launchSettings"), "").single()
        assertEquals("Properties", launch.parent.name)
        assertTrue(text(launch), text(launch).contains("\"\$schema\"") && text(launch).contains("\"Shop\": {"))

        val docker = text(ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("dockerfile"), "").single())
        assertTrue(docker, docker.contains("dotnet/sdk:9.0") && docker.contains("dotnet/aspnet:9.0") && docker.contains("\"Shop.dll\""))

        val settings = ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("appsettings"), "Staging").single()
        assertEquals("appsettings.Staging.json", settings.name)
        val options = text(ItemCreator.create(project, directory("Shop"), ItemTemplates.byId("options"), "Smtp").single())
        assertTrue(options, options.contains("SectionName = \"Smtp\"") && options.contains("AddSmtpOptions(this IServiceCollection"))
    }

    fun testTypeScanner() {
        val type = TypeDeclarationScanner.firstType(
            """
            using System;
            namespace Acme.Core; // class keyword in a comment
            [Serializable]
            public sealed class Repository<in TKey, TValue> : IDisposable where TValue : class
            {
                private class Nested {}
            }
            """.trimIndent()
        )!!
        assertEquals(listOf("class", "Repository", "<in TKey, TValue>", "public", "Acme.Core"), listOf(type.kind, type.name, type.typeParameters, type.accessModifier, type.namespace))
        assertFalse(type.isPartial)

        val record = TypeDeclarationScanner.firstType("namespace A { internal partial record struct Point(int X); }")!!
        assertEquals(listOf("record struct", "Point", "internal", "A"), listOf(record.kind, record.name, record.accessModifier, record.namespace))
        assertTrue(record.isPartial)
        assertNull(TypeDeclarationScanner.firstType("Console.WriteLine(\"class Foo\");"))
    }

    fun testPartialPart() {
        myFixture.addFileToProject("Shop/Shop.csproj", web)
        val source = myFixture.addFileToProject("Shop/Models/Order.cs", "namespace Shop.Domain;\n\npublic class Order<T>\n{\n}\n").virtualFile
        val type = TypeDeclarationScanner.firstType(text(source))!!
        val part = PartialPartAction.create(project, source, type, "Validation")!!

        assertEquals("Order.Validation.cs", part.name)
        // the namespace of the type, not the one of the folder
        assertEquals("namespace Shop.Domain;\n\npublic partial class Order<T>\n{\n}\n", text(part))
        assertTrue(text(source), text(source).contains("public partial class Order<T>"))
    }

    fun testTestForClass() {
        myFixture.addFileToProject("src/Shop/Shop.csproj", web)
        myFixture.addFileToProject(
            "tests/Shop.Tests/Shop.Tests.csproj",
            """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.0.0" />
                <PackageReference Include="NUnit" Version="4.0.0" />
                <ProjectReference Include="..\..\src\Shop\Shop.csproj" />
              </ItemGroup>
            </Project>
            """.trimIndent(),
        )
        val source = myFixture.addFileToProject("src/Shop/Services/PriceCalculator.cs", "namespace Shop.Services;\n\npublic class PriceCalculator {}\n").virtualFile

        // the light fixture has no solution in the project root: the target is resolved from the project files directly
        val testProject = source.parent.parent.parent.parent.findFileByRelativePath("tests/Shop.Tests/Shop.Tests.csproj")!!
        val target = TestForClassAction.Target(testProject, ItemTemplates.byId("nunitTest"), "Services")
        val created = ItemCreator.create(project, testProject.parent, target.template, "${target.relativeDirectory}/PriceCalculator", extraUsings = listOf("Shop.Services")).single()

        assertEquals("PriceCalculatorTests.cs", created.name)
        assertTrue(text(created), text(created).startsWith("using NUnit.Framework;\nusing Shop.Services;\n\nnamespace Shop.Tests.Services;\n\n[TestFixture]\npublic class PriceCalculatorTests\n"))
    }

    fun testResxCulture() {
        val resx = myFixture.addFileToProject("Shop/Strings.resx", "<root><data name=\"Hello\"><value>Hello</value></data></root>").virtualFile
        val copy = ResxCultureAction.create(project, resx, "ru")
        assertEquals("Strings.ru.resx", copy.name)
        assertEquals(text(resx), text(copy))
    }

    fun testMenuArrangement() {
        val group = ActionManager.getInstance().getAction("DotNet.NewItems") as NewDotNetItemGroup
        fun titles(project: MsBuildProject?) = group.arrange(project).map { if (it is Separator) "-" else it.templatePresentation.text }

        val tail = listOf("-", "Test for Selected Class", "From SDK Template...")
        assertEquals(listOf("C#", "ASP.NET", "Razor and Blazor", "Configuration", "Resources", "Other") + tail, titles(MsBuildProject.parse(web)))
        assertEquals(listOf("C#", "Configuration", "Resources", "Other") + tail, titles(MsBuildProject.parse(legacy)))
        assertEquals(listOf("Configuration", "Other") + tail, titles(null))

        // nothing is lost: the irrelevant categories are inside "Other"
        val other = group.arrange(MsBuildProject.parse(legacy)).first { it.templatePresentation.text == "Other" } as DefaultActionGroup
        assertEquals(listOf("ASP.NET", "Razor and Blazor", "Tests", "EF Core"), other.getChildActionsOrStubs().map { it.templatePresentation.text })

        val newGroup = ActionManager.getInstance().getAction("NewGroup") as ActionGroup
        assertTrue((newGroup as DefaultActionGroup).getChildActionsOrStubs().any { ActionManager.getInstance().getId(it) == "DotNet.NewItems" })
        assertEquals(ItemCategory.entries.size, ItemTemplates.ALL.map { it.category }.distinct().size)
    }

    fun testRendererKeepsUnknownVariables() {
        assertEquals("a \${{ github.ref }} \"\$schema\" X", TemplateRenderer.substitute("a \${{ github.ref }} \"\$schema\" \${NAME}", mapOf("NAME" to "X")))
    }
}
