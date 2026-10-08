package io.github.dotnetsupport

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FindSymbolParameters
import io.github.dotnetsupport.csharp.lang.psi.CSharpElement
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubIndexKeys
import io.github.dotnetsupport.csharp.lang.psi.stubs.CSharpStubs
import io.github.dotnetsupport.build.DotNetBuildSettings
import io.github.dotnetsupport.lang.CSharpGotoClassContributor
import io.github.dotnetsupport.lang.CSharpParseOptions
import io.github.dotnetsupport.lang.CSharpGotoSymbolContributor
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.DeclarationKind
import io.github.dotnetsupport.lang.NativeCSharpStubDeclarations
import io.github.dotnetsupport.lang.NativeCSharpSyntaxModel
import com.intellij.psi.stubs.StubIndex
import java.io.File

/**
 * Go to Class / Symbol on the stub indexes of csharp-psi (CSHARP_PSI_MIGRATION.md, step 8): names, items and their rows (text, location, icon)
 * without loading the AST of a file, and the rows from the stubs equal to those of the declaration model on the snapshot inputs of
 * `CSharpSyntaxSnapshotTest`.
 */
class CSharpStubNavigationTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    fun testGotoWithoutLoadingTheAst() {
        val file = myFixture.addFileToProject("StubGoto/Orders.cs", """
            namespace Shop.StubGoto
            {
                public class StubOrder<T> { public decimal Total(decimal discount) => 0; public int A, B; public class StubLine { } }
                public static class StubExtensions { public static int Twice(this int x) => x * 2; }
            }
        """.trimIndent()).virtualFile
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        val noAst = Disposer.newDisposable(testRootDisposable)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, noAst)
        val scope = GlobalSearchScope.projectScope(project)
        val classes = CSharpGotoClassContributor()
        val symbols = CSharpGotoSymbolContributor()
        val classNames = HashSet<String>().also { names -> classes.processNames({ names.add(it); true }, scope, null) }
        assertTrue(classNames.toString(), classNames.containsAll(listOf("StubOrder", "StubLine", "StubExtensions")))
        assertFalse("members are for Go to Symbol", "Total" in classNames)
        val symbolNames = HashSet<String>().also { names -> symbols.processNames({ names.add(it); true }, scope, null) }
        assertTrue(symbolNames.toString(), symbolNames.containsAll(listOf("StubOrder", "Total", "A", "B", "Twice")))

        fun rows(contributor: com.intellij.navigation.ChooseByNameContributorEx, name: String): List<String> = ArrayList<NavigationItem>()
            .also { items -> contributor.processElementsWithName(name, { items.add(it); true }, FindSymbolParameters.simple(project, false)) }
            .map { "${it.presentation!!.presentableText} (${it.presentation!!.locationString}) icon=${it.presentation!!.getIcon(false) != null} ${it.name}" }
        assertEquals(listOf("StubOrder (Shop.StubGoto) icon=true StubOrder"), rows(classes, "StubOrder"))
        assertEquals(listOf("StubLine (Shop.StubGoto.StubOrder) icon=true StubLine"), rows(classes, "StubLine"))
        assertEquals(listOf("Total(decimal discount) (StubOrder) icon=true Total"), rows(symbols, "Total"))
        assertEquals(listOf("B (StubOrder) icon=true B"), rows(symbols, "B"))
        assertEquals(listOf("Twice(this int x) (StubExtensions) icon=true Twice"), rows(symbols, "Twice"))
        assertFalse("Go to Class / Symbol read the stubs only", (psiManager.findFile(file) as PsiFileImpl).isContentsLoaded)
        Disposer.dispose(noAst)
    }

    /** The stub knows an explicit interface implementation (no member by its simple name), so the semantics ask it without the AST. */
    fun testExplicitImplementationsFromTheStubs() {
        val file = myFixture.addFileToProject("StubExplicit/Bag.cs", """
            using System.Collections;
            using System.Collections.Generic;
            public class StubExplicitBag : IEnumerable<int>
            {
                public IEnumerator<int> GetEnumerator() => null;
                IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();
                int ICollection<int>.Count => 0;
            }
        """.trimIndent()).virtualFile
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        val noAst = Disposer.newDisposable(testRootDisposable)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, noAst)
        val scope = GlobalSearchScope.fileScope(project, file)
        fun explicit(name: String): List<Boolean> = StubIndex.getElements(CSharpStubIndexKeys.MEMBER_NAMES, name, project, scope, CSharpElement::class.java)
            .sortedBy { NativeCSharpStubDeclarations.stub(it)!!.isExplicitImplementation }.map { CSharpStubs.isExplicitImplementation(it) }
        assertEquals(listOf(false, true), explicit("GetEnumerator"))
        assertEquals(listOf(true), explicit("Count"))
        assertFalse((psiManager.findFile(file) as PsiFileImpl).isContentsLoaded)
        Disposer.dispose(noAst)
    }

    /**
     * Another framework in the toolbar flips `#if`: Go to Class by the name of the new branch gives the class of the new branch, the old name gives
     * nothing, whatever the PSI of the file was before the change (stubs only, the AST, none) — the PSI is rebuilt as the index is.
     */
    fun testGotoAfterTheSymbolsChange() {
        myFixture.addFileToProject("StubFlip/Multi/Multi.csproj", """<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFrameworks>net9.0;net10.0</TargetFrameworks></PropertyGroup></Project>""")
        val files = listOf("Stubs", "Ast", "None").map { state ->
            state to myFixture.addFileToProject("StubFlip/Multi/Branch$state.cs", "namespace N\n{\n#if NET10_0\npublic static class Net10$state { }\n#else\npublic static class Net9$state { }\n#endif\n}\n").virtualFile
        }
        val options = CSharpParseOptions.getInstance(project)
        val toolbar = DotNetBuildSettings.getInstance(project)
        val classes = CSharpGotoClassContributor()
        fun found(name: String): List<String?> = ArrayList<NavigationItem>()
            .also { items -> classes.processElementsWithName(name, { items.add(it); true }, FindSymbolParameters.simple(project, false)) }
            .map { it.name }
        try {
            toolbar.framework = "net9.0"
            options.update(files.map { it.second })
            for ((state, file) in files) {
                assertEquals(listOf("Net9$state"), found("Net9$state"))
                assertEquals(emptyList<String>(), found("Net10$state"))
                val psi = psiManager.findFile(file) as PsiFileImpl
                when (state) {
                    "Stubs" -> assertFalse(psi.isContentsLoaded)
                    "Ast" -> assertNotNull(psi.node.text)
                    else -> PsiManagerEx.getInstanceEx(project).fileManager.setViewProvider(file, null)
                }
            }

            toolbar.framework = "net10.0"
            // the background refresh is a non-blocking read action: a write action restarts it, a newer refresh cancels it, after it has put the
            // keys and asked for the reindex; the run that finishes sees no change
            ReadAction.compute<List<VirtualFile>, RuntimeException> { options.fill(files.map { it.second }) }
            options.update(files.map { it.second })
            for ((state, _) in files) {
                assertEquals(state, listOf("Net10$state"), found("Net10$state"))
                assertEquals(state, emptyList<String>(), found("Net9$state"))
            }
        } finally {
            toolbar.framework = null
        }
    }

    /** Text, location, kind, modifiers and the kind around of every indexed declaration of the snapshot inputs: from the stubs as from the model. */
    fun testRowsFromStubsAreTheModelRows() {
        val inputs = File("src/test/resources/syntaxSnapshots").walkTopDown().filter { it.isFile && it.extension == "cs" }.sortedBy { it.path }.toList()
        assertTrue(inputs.isNotEmpty())
        val files = inputs.map { myFixture.addFileToProject("StubRows/${it.parentFile.name}/${it.name}", it.readText().replace("\r\n", "\n")).virtualFile }
        PsiManagerEx.getInstanceEx(project).dropPsiCaches()
        val keys = listOf(CSharpStubIndexKeys.TYPE_NAMES, CSharpStubIndexKeys.MEMBER_NAMES)
        val scope = GlobalSearchScope.filesScope(project, files)
        val elements = keys.flatMap { key ->
            StubIndex.getInstance().getAllKeys(key, project).sorted().flatMap { name -> StubIndex.getElements(key, name, project, scope, CSharpElement::class.java) }
        }
        assertTrue(elements.size > 100)

        val noAst = Disposer.newDisposable(testRootDisposable)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter.ALL, noAst)
        val fromStubs = elements.map { element ->
            val stub = NativeCSharpStubDeclarations.stub(element)!!
            val kind = NativeCSharpStubDeclarations.kind(stub)
            val containers = NativeCSharpStubDeclarations.containers(stub)
            val presentation = (element as NavigationItem).presentation!!
            "${presentation.presentableText} (${presentation.locationString}) $kind ${NativeCSharpStubDeclarations.modifiers(stub).sorted()} ${containers.lastOrNull()?.second}"
        }
        Disposer.dispose(noAst)

        val fromModel = elements.map { element ->
            val info = NativeCSharpSyntaxModel.declarationOf(element) ?: return@map "not a declaration: $element ${element.text}"
            val containers = NativeCSharpSyntaxModel.declarations(element.containingFile).containersOf(info)
            val location = containers.filter { info.kind.isType || it.kind != DeclarationKind.NAMESPACE }.joinToString(".") { it.name }
            "${info.name}${info.parameters.orEmpty()} ($location) ${info.kind} ${info.modifiers.sorted()} ${containers.lastOrNull()?.kind}"
        }
        assertEquals(fromModel.joinToString("\n"), fromStubs.joinToString("\n"))
    }
}
