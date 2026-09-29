package io.github.dotnetsupport

import io.github.dotnetsupport.newproject.DotNetTemplate
import io.github.dotnetsupport.newproject.DotNetTemplateSettings
import io.github.dotnetsupport.newproject.TemplateOption
import io.github.dotnetsupport.newproject.TemplateOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The options of a template, from `dotnet new <template> --help` of SDK 10.0.401 (`src/test/resources/dotnetNew`). */
class TemplateOptionsTest {
    private fun help(name: String) = File("src/test/resources/dotnetNew/$name.txt").readText()

    @Test
    fun `console options`() {
        val options = TemplateOptions.parse(help("console"))
        // --framework has its own row, --no-restore is not a property of the project
        assertEquals(listOf("--langVersion", "--use-program-main", "--aot"), options.map { it.name })
        val main = options.first { it.name == "--use-program-main" }
        assertEquals(TemplateOption.Kind.BOOL, main.kind)
        assertEquals("false", main.default)
        assertEquals("Use program main", main.label)
        assertEquals("Whether to generate an explicit Program class and Main method instead of top-level statements.", main.description)
        assertEquals(TemplateOption.Kind.TEXT, options.first { it.name == "--langVersion" }.kind)
        assertEquals(listOf("net10.0", "net9.0"), TemplateOptions.frameworks(help("console")))
    }

    @Test
    fun `mstest options with choices, aliases, multiple values and a condition`() {
        val options = TemplateOptions.parse(help("mstest"))
        assertEquals(listOf("--langVersion", "--sdk", "--test-runner", "--coverage-tool", "--extensions-profile", "--fixture"), options.map { it.name })
        val runner = options.first { it.name == "--test-runner" }
        assertEquals(TemplateOption.Kind.CHOICE, runner.kind)
        assertEquals(listOf("Microsoft.Testing.Platform", "VSTest", "MSTest"), runner.choices.map { it.value })
        assertEquals("VSTest", runner.default)
        assertTrue(runner.choices.first().description.startsWith("Use Microsoft"))
        assertEquals(listOf("-p:l"), options.first { it.name == "--langVersion" }.aliases)
        assertEquals("latest", options.first { it.name == "--langVersion" }.default)
        val profile = options.first { it.name == "--extensions-profile" }
        assertEquals("UseMSTestSdk && (TestRunner ==", profile.enabledIf?.take(30))
        assertEquals(listOf("Default", "None", "AllMicrosoft"), profile.choices.map { it.value })
        val fixture = options.first { it.name == "--fixture" }
        assertTrue(fixture.multiple)
        assertEquals(7, fixture.choices.size)
        assertEquals("None", fixture.default)
        assertEquals(17, TemplateOptions.frameworks(help("mstest")).size)
    }

    @Test
    fun `webapi options are strings and bools with long defaults`() {
        val options = TemplateOptions.parse(help("webapi"))
        val auth = options.first { it.name == "--auth" }
        assertEquals(listOf("-au"), auth.aliases)
        assertEquals(listOf("None", "IndividualB2C", "SingleOrg", "Windows"), auth.choices.map { it.value })
        assertEquals("https://qualified.domain.name.b2clogin.com/", options.first { it.name == "--aad-b2c-instance" }.default)
        assertEquals(TemplateOption.Kind.TEXT, options.first { it.name == "--aad-b2c-instance" }.kind)
        assertTrue(options.any { it.name == "--use-local-db" && it.kind == TemplateOption.Kind.BOOL })
        assertNull(TemplateOptions.parse("no such section").firstOrNull())
    }

    @Test
    fun `only what differs from the defaults goes to the command line`() {
        val options = TemplateOptions.parse(help("mstest"))
        val arguments = TemplateOptions.arguments(options, mapOf("--test-runner" to "Microsoft.Testing.Platform", "--sdk" to "true", "--coverage-tool" to "Microsoft.CodeCoverage", "--langVersion" to "latest", "--fixture" to "TestInitialize;TestCleanup"))
        assertEquals(listOf("--sdk", "--test-runner", "Microsoft.Testing.Platform", "--fixture", "TestInitialize;TestCleanup"), arguments)
        assertEquals(emptyList<String>(), TemplateOptions.arguments(options, mapOf("--test-runner" to "vstest", "--sdk" to "false")))

        // a bool whose default is true is switched off explicitly
        val option = TemplateOption("--https", emptyList(), "", TemplateOption.Kind.BOOL, emptyList(), "true", false, null)
        assertEquals(listOf("--https", "false"), TemplateOptions.arguments(listOf(option), mapOf("--https" to "false")))

        val settings = DotNetTemplateSettings(DotNetTemplate("MSTest Test Project", "mstest", listOf("C#"), "C#"), null, "net10.0", arguments)
        assertEquals(listOf("new", "mstest", "-n", "Tests", "-o", "Tests", "-f", "net10.0", "--sdk", "--test-runner", "Microsoft.Testing.Platform", "--fixture", "TestInitialize;TestCleanup"), settings.newArguments("Tests", "Tests"))
        assertFalse(settings.newArguments("Tests", "Tests").contains("--no-restore"))
    }
}
