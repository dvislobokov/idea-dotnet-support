package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.cli.DotNetTool
import io.github.dotnetsupport.lsp.RoslynCodeStyle
import io.github.dotnetsupport.lsp.RoslynLanguageServer
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings
import io.github.dotnetsupport.lsp.RoslynLogLevel
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.lsp.SourceGeneratorExecution
import javax.swing.JCheckBox
import javax.swing.JComponent

/** Settings of roslyn-language-server: the command line and the answers to `workspace/configuration`; the server is not started here. */
class RoslynLanguageServerTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun tearDown() {
        try {
            settings.loadState(RoslynLanguageServerSettings.Settings())
        } finally {
            super.tearDown()
        }
    }

    fun testCommandLine() {
        val state = RoslynLanguageServerSettings.Settings()
        assertEquals(
            listOf("--stdio", "--logLevel", "Information", "--extensionLogDirectory", "/logs/roslyn", "--autoLoadProjects", "--clientProcessId", "4242"),
            RoslynLanguageServer.arguments(state, "/logs/roslyn", 4242),
        )
        state.logLevel = RoslynLogLevel.Trace
        state.logDirectory = " /my/logs "
        state.autoLoadProjectsLimit = 50
        state.sourceGeneratorExecution = SourceGeneratorExecution.Balanced
        state.additionalArguments = "--telemetryLevel off --extension \"C:/my ext/a.dll\""
        assertEquals(
            listOf("--stdio", "--logLevel", "Trace", "--extensionLogDirectory", " /my/logs ", "--autoLoadProjects", "50", "--sourceGeneratorExecutionPreference", "Balanced",
                "--telemetryLevel", "off", "--extension", "C:/my ext/a.dll"),
            RoslynLanguageServer.arguments(state, "/logs/roslyn", null),
        )
        state.autoLoadProjects = false
        assertFalse("--autoLoadProjects" in RoslynLanguageServer.arguments(state, "/logs", null))
    }

    /** Every listed option is a section the real server asks for: the list was taken from server 5.12 by `tools/roslyn-lsp/probe.py`. */
    fun testOptionsAreTheOnesTheServerRequests() {
        val requested = javaClass.getResource("/roslyn/configuration-sections-5.12.txt")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map(RoslynLanguageServer::sectionKey).toSet()
        assertTrue(requested.size > 40)
        val unknown = RoslynOptions.ALL.map { it.section }.filter { it !in requested }
        assertTrue("not requested by the server: $unknown", unknown.isEmpty())
        assertEquals("a section is listed twice", RoslynOptions.ALL.size, RoslynOptions.ALL.map { it.section }.toSet().size)
        for (option in RoslynOptions.ALL.filter { it.values != null }) assertTrue(option.section, option.default in option.values!!)
    }

    fun testConfigurationAnswers() {
        val inlay = RoslynOptions.ALL.first { it.section == "inlay_hints.csharp_enable_inlay_hints_for_types" }
        val scope = RoslynOptions.ALL.first { it.section == "background_analysis.dotnet_analyzer_diagnostics_scope" }
        val binlog = RoslynOptions.ALL.first { it.section == "projects.dotnet_binary_log_path" }
        settings.setValue(inlay, "true")
        settings.setValue(scope, "fullSolution")
        settings.state.additionalOptions = """
            # by hand
            csharp|completion.dotnet_trigger_completion_on_deletion = true
            completion.dotnet_lsp_max_completion_list_size = 500
            code_lens.dotnet_enable_tests_code_lens=false
            not an option
        """.trimIndent()

        val style = RoslynCodeStyle(tabWidth = 4, indentSize = 2, useTabs = true, endOfLine = "lf", insertFinalNewline = true)
        val answer = RoslynLanguageServer.configuration(
            listOf(
                "csharp|inlay_hints.csharp_enable_inlay_hints_for_types", "visual_basic|inlay_hints.csharp_enable_inlay_hints_for_types",
                "csharp|background_analysis.dotnet_analyzer_diagnostics_scope", "csharp|background_analysis.dotnet_compiler_diagnostics_scope",
                "projects.dotnet_binary_log_path", "navigation.dotnet_navigate_to_decompiled_sources",
                "csharp|completion.dotnet_trigger_completion_on_deletion", "csharp|completion.dotnet_lsp_max_completion_list_size", "csharp|code_lens.dotnet_enable_tests_code_lens",
                "csharp|code_style.formatting.indentation_and_spacing.indent_size", "csharp|code_style.formatting.indentation_and_spacing.indent_style",
                "code_style.formatting.new_line.insert_final_newline", "razor.format.attribute_indent_style", null,
            ),
            settings, style,
        )
        assertEquals(listOf(true, true, "fullSolution", "openFiles", null, true, true, 500, false, 2, "tab", true, null, null), answer)

        // type hints are on by default: off is stored, back to the default stores nothing, and the answer is explicit either way
        settings.setValue(inlay, "false")
        assertEquals("false", settings.state.options[inlay.section])
        assertEquals(listOf(false), RoslynLanguageServer.configuration(listOf("csharp|${inlay.section}"), settings, null))
        settings.setValue(inlay, "true")
        assertFalse(inlay.section in settings.state.options)
        assertEquals(listOf(true), RoslynLanguageServer.configuration(listOf("csharp|${inlay.section}"), settings, null))
        settings.setValue(binlog, "C:/logs/binlog")
        assertEquals(listOf("C:/logs/binlog", null), RoslynLanguageServer.configuration(listOf(binlog.section, "csharp|code_style.formatting.indentation_and_spacing.tab_width"), settings, null))
    }

    fun testPage() {
        val page = RoslynLanguageServerConfigurable(project)
        val component: JComponent = page.createComponent()!!
        page.reset()
        try {
            val boxes = UIUtil.findComponentsOfType(component, JCheckBox::class.java)
            // the toggles of the catalog, "Use the language server for C#" and "Find and load the projects"
            assertEquals(RoslynOptions.ALL.count { it.isToggle } + 2, boxes.size)
            assertTrue(boxes.first { it.text == "Use the language server for C#" }.isSelected)
            assertTrue(boxes.all { it.isEnabled })
            assertTrue(boxes.first { it.text == "References" }.isSelected)
            // on by default (2026-09-22)
            assertTrue(boxes.first { it.text == "Parameter names" }.isSelected)

            boxes.first { it.text == "Parameter names" }.isSelected = false
            assertTrue(page.isModified)
            page.apply()
            assertEquals("false", settings.state.options["inlay_hints.dotnet_enable_inlay_hints_for_parameters"])
        } finally {
            page.disposeUIResources()
        }
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertTrue("id=\"io.github.dotnetsupport.settings.languageServer\"" in pluginXml)
        assertEquals("roslyn-language-server", DotNetTool.ROSLYN_LANGUAGE_SERVER.command)
        // the package is specific to a runtime, and such tools get a .cmd shim on Windows, not an .exe
        assertEquals(listOf("roslyn-language-server.exe", "roslyn-language-server.cmd"), io.github.dotnetsupport.cli.executableNames("roslyn-language-server", windows = true))
        assertEquals(listOf("roslyn-language-server"), io.github.dotnetsupport.cli.executableNames("roslyn-language-server", windows = false))
    }
}
