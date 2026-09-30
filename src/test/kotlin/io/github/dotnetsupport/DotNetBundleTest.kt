package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.dotnetsupport.build.DotNetBuildConfigurable
import io.github.dotnetsupport.build.MsBuildVerbosity
import io.github.dotnetsupport.coverage.CoverageSettingsConfigurable
import io.github.dotnetsupport.format.FormatterChoice
import io.github.dotnetsupport.lsp.RoslynLanguageServerConfigurable
import io.github.dotnetsupport.lsp.RoslynOptions
import io.github.dotnetsupport.nuget.NuGetSettingsConfigurable
import io.github.dotnetsupport.settings.DotNetDebuggerConfigurable
import io.github.dotnetsupport.settings.DotNetSettings
import io.github.dotnetsupport.settings.DotNetSettingsConfigurable
import io.github.dotnetsupport.welcome.WelcomePage
import java.nio.file.Files
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JRadioButton

/** The settings pages in two languages, and the documentation that names their options. */
class DotNetBundleTest : BasePlatformTestCase() {
    private val cyrillic = Regex("[а-яА-ЯёЁ]")
    private val placeholder = Regex("""\{\d}""")

    override fun tearDown() {
        try {
            DotNetBundle.forced = null
            DotNetSettings.getInstance().language = PluginLanguage.AUTO
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun <T> inRussian(action: () -> T): T {
        DotNetBundle.forced = PluginLanguage.RUSSIAN
        try {
            return action()
        } finally {
            DotNetBundle.forced = null
        }
    }

    private fun build(page: UnnamedConfigurable): JComponent = page.createComponent()!!.also { page.reset() }

    /** What is ticked and chosen on the pages that start nothing when they are built. */
    private fun choices(): List<String> {
        val pages = listOf(NuGetSettingsConfigurable(), DotNetBuildConfigurable(project), DotNetDebuggerConfigurable(project), CoverageSettingsConfigurable(),
            RoslynLanguageServerConfigurable(project))
        return pages.map(::build).flatMap { page ->
            UIUtil.findComponentsOfType(page, JCheckBox::class.java).map { it.text } + UIUtil.findComponentsOfType(page, JRadioButton::class.java).map { it.text }
        }
    }

    fun testTheSameKeysInBothLanguages() {
        val english = DotNetBundle.bundle("en").keySet()
        // the keys of the Russian file itself, without the ones it inherits from the English one
        val russian = javaClass.getResourceAsStream("/messages/DotNetBundle_ru.properties")!!.use { it.readBytes().toString(Charsets.UTF_8) }.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }.map { it.substringBefore('=').trim() }.toSet()
        assertTrue("the file is read as UTF-8", cyrillic.containsMatchIn(DotNetBundle.bundle("ru").getString("settings.cli.check")))

        assertEquals("not translated", emptySet<String>(), english - russian)
        // the options of the language server have their English texts in the code
        assertEquals("translated, but not there in English", emptySet<String>(), russian.filterNot { it.startsWith("roslyn.") }.toSet() - english)
        for (key in english) {
            val there = placeholder.findAll(DotNetBundle.bundle("en").getString(key)).map { it.value }.toSet()
            val here = placeholder.findAll(DotNetBundle.bundle("ru").getString(key)).map { it.value }.toSet()
            assertEquals("the parameters of $key", there, here)
        }
    }

    fun testTheOptionsOfTheLanguageServerAreTranslated() {
        val russian = DotNetBundle.bundle("ru")
        for (option in RoslynOptions.ALL) {
            assertTrue(option.section, russian.containsKey("roslyn.option.${option.section}"))
            if (option.comment != null) assertTrue(option.section + ": the comment", russian.containsKey("roslyn.option.${option.section}.comment"))
        }
        for (group in RoslynOptions.GROUPS) assertTrue(group, russian.containsKey(RoslynOptions.key(group)))
        val known = RoslynOptions.ALL.flatMap { listOf("roslyn.option.${it.section}", "roslyn.option.${it.section}.comment") }.toSet() + RoslynOptions.GROUPS.map(RoslynOptions::key)
        assertEquals("translated, but no such option", emptyList<String>(), russian.keySet().filter { it.startsWith("roslyn.") && it !in known })

        val option = RoslynOptions.ALL.first { it.section == "completion.dotnet_provide_regex_completions" }
        assertEquals("Completion inside regular expressions", option.text)
        assertEquals("Автодополнение внутри регулярных выражений", inRussian { option.text })
        assertEquals("Inlay Hints", RoslynOptions.title("Inlay Hints"))
        assertEquals("Подсказки в коде", inRussian { RoslynOptions.title("Inlay Hints") })
    }

    fun testTheLanguageIsTheOneChosen() {
        assertEquals("the IDE of the tests speaks English", "en", DotNetBundle.language())
        assertEquals("Check", DotNetBundle.message("settings.cli.check"))
        assertEquals("C:\\dotnet.exe, newest SDK 10.0.100", DotNetBundle.message("settings.cli.newest", "C:\\dotnet.exe", "10.0.100"))
        assertEquals("an apostrophe stays one", "Install and Update run 'dotnet tool update --global'.", DotNetBundle.message("settings.tools.comment").substringAfter("tools. "))
        assertEquals("!no.such.key!", DotNetBundle.message("no.such.key"))

        DotNetSettings.getInstance().language = PluginLanguage.RUSSIAN
        assertEquals("ru", DotNetBundle.language())
        assertEquals("Проверить", DotNetBundle.message("settings.cli.check"))
        assertEquals("C:\\dotnet.exe, новейший SDK 10.0.100", DotNetBundle.message("settings.cli.newest", "C:\\dotnet.exe", "10.0.100"))
        assertEquals("Подробный", MsBuildVerbosity.DETAILED.toString())
        assertEquals("detailed", MsBuildVerbosity.DETAILED.argument)
        assertEquals("Нет", FormatterChoice.NONE.toString())
        assertEquals("Reformat Code не трогает файлы C#.", DotNetSettingsConfigurable.describeFormatter(FormatterChoice.NONE, null))
        assertEquals("Ошибка (код завершения -1): вывода нет", DotNetSettingsConfigurable.installationSummary(-1, ""))
        assertEquals("the languages are named in themselves", listOf("Как в IDE", "English", "Русский"), PluginLanguage.entries.map { it.toString() })

        DotNetSettings.getInstance().language = PluginLanguage.ENGLISH
        assertEquals("Detailed", MsBuildVerbosity.DETAILED.toString())
        assertEquals(listOf("As in the IDE", "English", "Русский"), PluginLanguage.entries.map { it.toString() })
    }

    fun testThePagesInRussian() {
        val english = choices()
        val russian = inRussian { choices() }
        assertEquals("the same options in either language", english.size, russian.size)
        assertTrue(english.size > 40)
        assertTrue("the pages of the tests are English: " + english.filter { cyrillic.containsMatchIn(it) }, english.none { cyrillic.containsMatchIn(it) })
        assertEquals("left in English", emptyList<String>(), russian.filterNot { cyrillic.containsMatchIn(it) })
        assertEquals("Включать предварительные версии", russian.first())

        assertEquals("Инструменты и сборка", inRussian { DotNetBuildConfigurable(project).displayName })
        assertEquals("Toolset and Build", DotNetBuildConfigurable(project).displayName)
        // in the tree of Settings the platform names the pages by the language of the IDE
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        for (page in listOf("dotnet", "build", "nuget", "coverage", "debugger", "languageServer")) {
            assertTrue(page, "key=\"page.$page\" bundle=\"messages.DotNetBundle\"" in pluginXml)
        }
    }

    fun testTheDocumentationNamesEveryOption() {
        val guide = WelcomePage.html(dark = true, page = WelcomePage.GUIDE)
        assertNotNull("welcome/guide.html is packed from docs/guide.html by the build", guide)
        guide!!
        fun escaped(text: String) = text.trimEnd(':').replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val missing = (inRussian { choices() } + choices()).filterNot { escaped(it) in guide }
        assertEquals("on a settings page and not in the documentation: run tools/guide/generate.py", emptyList<String>(), missing)
        for (option in RoslynOptions.ALL) assertTrue(option.section, escaped(inRussian { option.text }) in guide)
    }

    fun testThePagesLeadToEachOther() {
        val index = WelcomePage.html(dark = false)!!
        val guide = WelcomePage.html(dark = false, page = WelcomePage.GUIDE)!!
        assertTrue(guide.contains("""<html lang="ru" data-host="ide" data-theme="light">"""))
        assertTrue("to the documentation", index.contains("href=\"guide.html\""))
        assertTrue("and back: the first page is index.html in the plugin", guide.contains("href=\"index.html\""))
        assertFalse(guide.contains("href=\"demo.html"))
        assertFalse(index.contains("href=\"demo.html"))

        for (page in listOf(index, guide)) {
            // inside the IDE there is no network: nothing is loaded from outside
            assertEquals(emptyList<String>(), Regex("""(?:src|href)\s*=\s*"(https?:)?//[^"]*"""").findAll(page).map { it.value }.toList())
            for (link in Regex("""<a [^>]*href="\.\./[^>]*>""").findAll(page)) assertTrue(link.value, link.value.contains("class=\"repo\""))
            for (anchor in Regex("""href="#([\w-]+)"""").findAll(page).map { it.groupValues[1] }.toSet()) assertTrue("no element with id $anchor", page.contains("id=\"$anchor\""))
        }

        val directory = Files.createTempDirectory("welcome-pages")
        try {
            assertEquals(directory, WelcomePage.write(directory, dark = true))
            assertEquals(WelcomePage.PAGES.toSet(), Files.list(directory).use { files -> files.map { it.fileName.toString() }.toList().toSet() })
            assertTrue(Files.readString(directory.resolve(WelcomePage.GUIDE)).contains("data-theme=\"dark\""))
            val address = WelcomePage.address(directory, WelcomePage.GUIDE, "settings")
            assertTrue(address, address.startsWith("file:///") && address.endsWith("/guide.html#settings"))
            assertTrue(guide.contains("id=\"settings\""))
        } finally {
            directory.toFile().deleteRecursively()
        }

        val actions = ActionManager.getInstance()
        val menu = (actions.getAction("DotNet.MainMenu") as DefaultActionGroup).childActionsOrStubs.map { actions.getId(it) }
        assertEquals("next to the page about the plugin", menu.indexOf("DotNet.Welcome") + 1, menu.indexOf("DotNet.Documentation"))
    }
}
