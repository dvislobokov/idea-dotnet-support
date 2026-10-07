package io.github.dotnetsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.CSharpLanguageVersion
import io.github.dotnetsupport.csharp.lang.CSharpPreprocessorSymbols
import io.github.dotnetsupport.csharp.lang.psi.CSharpClassDeclaration
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpParseOptions
import io.github.dotnetsupport.lang.CSharpSyntaxTreeSwitch
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.HeuristicCSharpFile
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/** CSHARP_PSI_MIGRATION.md, steps 7 and 10: the switch of the tree rebuilds the PSI, the project model gives each file its `#if` symbols. */
class CSharpParseOptionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()

    override fun tearDown() {
        try {
            CSharpSyntaxTreeSwitch.reactInTests = false
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            DotNetBuildSettings.getInstance(project).framework = null
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun applied() = ApplicationManager.getApplication().messageBus.syncPublisher(RoslynLanguageServerSettings.CHANGED).settingsChanged(false)

    /** An open file gets the tree of the new answer once the page is applied, and back; the server switched off counts too. */
    fun testSwitchingTheTreeReparsesOpenFiles() {
        settings.state.enabled = true // ROSLYN is the server's path: the server is off by default since 0.1.76
        CSharpSyntaxTreeSwitch.reactInTests = true
        // the native tree is the default (0.1.45): start from a stored ROSLYN, the heuristic tree
        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.ROSLYN)
        val heuristic = myFixture.configureByText("TreeSwitch.cs", "namespace N { class A { } }")
        val file = heuristic.virtualFile
        fun current() = PsiManager.getInstance(project).findFile(file) as CSharpFile
        assertInstanceOf(heuristic, HeuristicCSharpFile::class.java)

        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.NATIVE)
        applied()
        assertNotNull("the native tree", current().compilationUnit)
        assertFalse("the old PSI is gone", heuristic.isValid)
        assertEquals("A", PsiTreeUtil.findChildOfType(current(), CSharpClassDeclaration::class.java)?.identifier?.text)

        settings.setSource(CSharpFeature.SYNTAX_TREE, CSharpFeatureSource.ROSLYN)
        applied()
        assertInstanceOf(current(), HeuristicCSharpFile::class.java)
        val before = current()
        applied()
        assertSame("nothing changed: nothing is parsed again", before, current())

        settings.state.enabled = false
        applied()
        assertNotNull("no server: the native tree", current().compilationUnit)
    }

    /** `net10.0;net48`, the framework of the toolbar: the native tree has the class of the active `#if` branch. */
    fun testTheFrameworkOfTheToolbarFlipsIfRegions() {
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        myFixture.addFileToProject("ParseOptions/Multi/Multi.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFrameworks>net10.0;net48</TargetFrameworks></PropertyGroup></Project>""")
        val file = myFixture.addFileToProject("ParseOptions/Multi/Lib.cs", "#if NET48\nclass A { }\n#else\nclass B { }\n#endif\n").virtualFile
        fun classes() = PsiTreeUtil.findChildrenOfType(PsiManager.getInstance(project).findFile(file), CSharpClassDeclaration::class.java).map { it.identifier?.text }
        val options = CSharpParseOptions.getInstance(project)
        val toolbar = DotNetBuildSettings.getInstance(project)

        toolbar.framework = "net48"
        options.update(listOf(file))
        assertTrue("NET48" in file.getUserData(CSharpPreprocessorSymbols.KEY)!!)
        assertEquals(CSharpLanguageVersion.CSharp7_3, file.getUserData(CSharpLanguageLevel.KEY))
        assertEquals(listOf("A"), classes())

        toolbar.framework = null // the first framework, as the debug launch takes it
        options.update(listOf(file))
        assertTrue("NET10_0" in file.getUserData(CSharpPreprocessorSymbols.KEY)!!)
        assertEquals(CSharpLanguageVersion.CSharp14, file.getUserData(CSharpLanguageLevel.KEY))
        assertEquals(listOf("B"), classes())
        val parsed = PsiManager.getInstance(project).findFile(file)
        options.update(listOf(file))
        assertSame("the same values: no reparse", parsed, PsiManager.getInstance(project).findFile(file))

        // the heuristic tree ignores the keys: they are set, nothing is parsed again
        CSharpSyntaxTrees.forceNativeTreeForTests(false)
        toolbar.framework = "net48"
        options.update(listOf(file))
        assertTrue("NET48" in file.getUserData(CSharpPreprocessorSymbols.KEY)!!)
        assertSame(parsed, PsiManager.getInstance(project).findFile(file))
    }

    fun testTheKeysOfAFile() {
        val file = LightVirtualFile("Loose.cs", "")
        assertTrue(CSharpParseOptions.put(file, setOf("DEBUG", "NET48"), "7.3"))
        assertEquals(setOf("DEBUG", "NET48"), file.getUserData(CSharpPreprocessorSymbols.KEY))
        assertEquals(CSharpLanguageVersion.CSharp7_3, file.getUserData(CSharpLanguageLevel.KEY))
        assertFalse("the same values", CSharpParseOptions.put(file, setOf("DEBUG", "NET48"), "7.3"))
        assertTrue(CSharpParseOptions.put(file, setOf("DEBUG", "NET48"), "latest"))
        assertEquals(CSharpLanguageVersion.Latest, file.getUserData(CSharpLanguageLevel.KEY))
        // out of every project: no keys, the parser's defaults
        assertTrue(CSharpParseOptions.put(file, null, null))
        assertNull(file.getUserData(CSharpPreprocessorSymbols.KEY))
        assertNull(file.getUserData(CSharpLanguageLevel.KEY))
    }

    /** The pass over the files of the project yields to a write action (a cancelled indicator here) and does not redo the files it has put. */
    fun testThePassOverTheFilesChecksForCancellationAndResumes() {
        val files = (1..3).map { myFixture.addFileToProject("Pass/File$it.cs", "class C$it { }").virtualFile }
        val options = CSharpParseOptions.getInstance(project)
        val done: MutableSet<VirtualFile> = HashSet()
        val indicator = EmptyProgressIndicator()
        // a plain thread: the test runner keeps the EDT in a non-cancelable section, and the application pool inherits that thread context;
        // cancelled inside the process: runProcess starts the indicator, and the start clears a cancellation made before it
        var cancelled = false
        Thread {
            try {
                ProgressManager.getInstance().runProcess({ indicator.cancel(); ReadAction.run<RuntimeException> { options.fill(files, done) } }, indicator)
            } catch (_: ProcessCanceledException) {
                cancelled = true
            }
        }.apply { start(); join() }
        assertTrue("a cancelled indicator stops the pass before the first file", cancelled)
        assertEmpty(done)
        done += files[0]
        options.fill(files, done)
        assertEquals("the skipped file keeps whatever it had, the others are put", files.toSet(), done)
        files.drop(1).forEach { assertTrue("put a loose file: no symbols, the parser's defaults", it.getUserData(CSharpPreprocessorSymbols.KEY) == null) }
    }
}
