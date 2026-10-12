package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpCompletion
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Keywords of the native list against Roslyn's `KeywordRecommenders` (dotnet/roslyn, `src/Features/CSharp/Portable/Completion/
 * KeywordRecommenders`; COMPLETION_GAPS 2.14): the place, the keywords Roslyn recommends there, and some it does not.
 */
class CSharpKeywordRecommendationsTest : BasePlatformTestCase() {
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.COMPLETION, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private class Row(val recommender: String, val code: String, val present: List<String>, val absent: List<String> = emptyList())

    private val table = listOf(
        Row("AndKeywordRecommender, OrKeywordRecommender", "class A { bool M(int x) => x is > 0 <caret>; }", listOf("and", "or"), listOf("when", "class", "public")),
        Row("AndKeywordRecommender", "class A { bool M(object o) => o is not null <caret>; }", listOf("and", "or")),
        Row("NotKeywordRecommender", "class A { void M(object o) { if (o is <caret>) { } } }", listOf("not", "null")),
        Row("WhenKeywordRecommender (switch expression arm)", "class A { int M(int x) => x switch { 1 <caret> }; }", listOf("when", "and", "or"), listOf("if", "new")),
        Row("WhenKeywordRecommender (case label)", "class A { void M(object o) { switch (o) { case int n <caret> } } }", listOf("when")),
        Row("WithKeywordRecommender, SwitchKeywordRecommender, IsKeywordRecommender, AsKeywordRecommender",
            "record R(int X); class A { void M(R r) { var b = r <caret> } }", listOf("with", "switch", "is", "as"), listOf("if", "foreach", "class")),
        Row("WithKeywordRecommender (return)", "record R(int X); class A { R M(R r) { return r <caret> } }", listOf("with", "switch")),
        Row("GetKeywordRecommender, SetKeywordRecommender, InitKeywordRecommender", "class A { int X { <caret> } }", listOf("get", "set", "init", "private", "protected", "internal")),
        Row("SetKeywordRecommender after get", "class A { int X { get; <caret> } }", listOf("set", "init"), listOf("get")),
        Row("GetKeywordRecommender after set", "class A { int X { set; <caret> } }", listOf("get"), listOf("set", "init")),
        Row("AddKeywordRecommender, RemoveKeywordRecommender", "class A { event System.Action E { <caret> } }", listOf("add", "remove"), listOf("get", "set")),
        Row("FieldKeywordRecommender (C# 14)", "class A { int X { get => <caret>; } }", listOf("field")),
        Row("FieldKeywordRecommender in a setter body", "class A { int X { get; set { <caret> } } }", listOf("field")),
        Row("FieldKeywordRecommender: not in a method", "class A { void M() { <caret> } }", emptyList(), listOf("field")),
        Row("AllowsKeywordRecommender (C# 13)", "class A<T> where T : class, <caret> { }", listOf("allows")),
        Row("ScopedKeywordRecommender (statement)", "class A { void M() { <caret> } }", listOf("scoped")),
        Row("ScopedKeywordRecommender (parameter)", "class A { void M(<caret>) { } }", listOf("scoped", "ref", "params")),
        Row("ExtensionKeywordRecommender (C# 14)", "static class E { <caret> }", listOf("extension")),
        Row("ExtensionKeywordRecommender: not in an instance class", "class E { <caret> }", emptyList(), listOf("extension")),
        Row("AssemblyKeywordRecommender, ModuleKeywordRecommender", "[<caret>\nclass A { }", listOf("assembly", "module")),
        Row("AssemblyKeywordRecommender: not on a member", "class A { [<caret> void M() { } }", emptyList(), listOf("assembly", "module")),
        Row("ManagedKeywordRecommender, UnmanagedKeywordRecommender", "unsafe class A { delegate* <caret><void> f; }", listOf("managed", "unmanaged")),
        Row("UnmanagedKeywordRecommender (constraint)", "class A<T> where T : <caret> { }", listOf("unmanaged", "notnull", "class", "struct", "default")),
        Row("NameOfKeywordRecommender: names only in nameof(", "class A { int count; void M() { var n = nameof(<caret> } }", listOf("count"), listOf("int", "out", "new")),
        Row("Query keywords stay", "class A { void M(int[] xs) { var q = from x in xs <caret> } }", listOf("where", "select"), listOf("with", "switch")),
    )

    fun testKeywordsByPlace() {
        val failures = ArrayList<String>()
        for (row in table) {
            myFixture.configureByText("Keywords${files++}.cs", row.code)
            myFixture.completeBasic()
            val items = myFixture.lookupElements?.filter { it.getUserData(NativeCSharpCompletion.NATIVE) == true }?.map { it.lookupString }.orEmpty()
            myFixture.lookup?.hideLookup(true)
            val missing = row.present.filter { it !in items }
            val extra = row.absent.filter { it in items }
            if (missing.isNotEmpty() || extra.isNotEmpty()) failures += "${row.recommender}: `${row.code}` missing $missing, unexpected $extra in $items"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    fun testAssemblyTargetInsertsColon() {
        myFixture.configureByText("Target${files++}.cs", "[<caret>]\nclass A { }")
        myFixture.completeBasic()
        val item = myFixture.lookupElements!!.first { it.lookupString == "assembly" }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        assertEquals("[assembly: ]\nclass A { }", myFixture.editor.document.text)
    }
}
