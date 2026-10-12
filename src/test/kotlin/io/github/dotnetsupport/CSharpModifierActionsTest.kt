package io.github.dotnetsupport

import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * The modifiers from a header (0.1.171, `NativeCSharpModifierActions.kt`), as Rider on Jellyfin: "To private" on a public member,
 * "To internal" on a public top-level type, the others in the submenu; "To virtual", "To abstract", "Make partial"; where none is offered.
 */
class CSharpModifierActionsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        settings.setSource(CSharpFeature.CONTEXT_ACTIONS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            settings.state.enabled = RoslynLanguageServerSettings.ENABLED_BY_DEFAULT
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun configure(text: String) = myFixture.configureByText("Modifiers${files++}.cs", text.trimIndent() + "\n")

    private fun titles(text: String): List<String> {
        configure(text)
        return myFixture.availableIntentions.map { it.text }
    }

    private fun apply(text: String, title: String): String {
        configure(text)
        myFixture.launchAction(myFixture.findSingleIntention(title))
        return myFixture.editor.document.text.trimEnd()
    }

    // ---- accessibility

    fun testAPublicMethodGoesPrivateAndTheOthersAreInTheSubmenu() {
        val code = """
            public class Users
            {
                public void Rena<caret>me(string name) { }
            }
        """
        assertEquals("public class Users\n{\n    private void Rename(string name) { }\n}", apply(code, "To private"))
        configure(code)
        val action = myFixture.availableIntentions.first { it.text == "To private" }
        val options = generateSequence(action) { (it as? com.intellij.codeInsight.intention.IntentionActionDelegate)?.delegate }
            .firstNotNullOf { it as? IntentionActionWithOptions }.options.map { it.text }
        assertEquals(listOf("To protected", "To internal", "To protected internal", "To private protected"), options)
    }

    fun testTheOrderOfTheOtherModifiersIsKept() {
        val code = """
            public class Users
            {
                public static async System.Threading.Tasks.Task Sa<caret>ve() { await System.Threading.Tasks.Task.Delay(1); }
            }
        """
        assertEquals("    private static async System.Threading.Tasks.Task Save() { await System.Threading.Tasks.Task.Delay(1); }", apply(code, "To private").lines()[2])
    }

    fun testAPrivateMemberGoesPublicAndAMemberWithoutAccessibilityGetsOne() {
        assertEquals("    public int Count;", apply("public class C\n{\n    private int Cou<caret>nt;\n}", "To public").lines()[2])
        assertEquals("    public int Count { get; set; }", apply("public class C\n{\n    int Cou<caret>nt { get; set; }\n}", "To public").lines()[2])
    }

    fun testATopLevelTypeGoesInternal() {
        assertEquals("internal interface ILibrary\n{\n}", apply("public interface ILib<caret>rary\n{\n}", "To internal"))
        assertEquals("public class Shop\n{\n}", apply("class Sh<caret>op\n{\n}", "To public"))
    }

    fun testNoProtectedInAStructAndNothingOnAnOverride() {
        configure("public struct Point\n{\n    public int Le<caret>ngth() => 0;\n}")
        val action = myFixture.availableIntentions.first { it.text == "To private" }
        val options = generateSequence(action) { (it as? com.intellij.codeInsight.intention.IntentionActionDelegate)?.delegate }
            .firstNotNullOf { it as? IntentionActionWithOptions }.options.map { it.text }
        assertEquals(listOf("To internal"), options)
        assertTrue(titles("public class C\n{\n    public override string ToStr<caret>ing() => \"\";\n}").none { it.startsWith("To p") || it == "To internal" })
        assertTrue(titles("public interface I\n{\n    void Sa<caret>ve();\n}").none { it.startsWith("To p") })
    }

    fun testNotInABody() {
        assertTrue(titles("public class C\n{\n    public void M()\n    {\n        var x<caret> = 1;\n    }\n}").none { it == "To private" || it == "To virtual" })
    }

    // ---- virtual / abstract / partial

    fun testToVirtual() {
        assertEquals("    public virtual void Save() { }", apply("public class C\n{\n    public void Sa<caret>ve() { }\n}", "To virtual").lines()[2])
        assertEquals("    public virtual string Name { get; set; }", apply("public class C\n{\n    public string Na<caret>me { get; set; }\n}", "To virtual").lines()[2])
        // a static member, a private one, a sealed class, a struct: no
        assertTrue(titles("public class C\n{\n    public static void Sa<caret>ve() { }\n}").none { it == "To virtual" })
        assertTrue(titles("public class C\n{\n    private void Sa<caret>ve() { }\n}").none { it == "To virtual" })
        assertTrue(titles("public sealed class C\n{\n    public void Sa<caret>ve() { }\n}").none { it == "To virtual" })
        assertTrue(titles("public struct S\n{\n    public void Sa<caret>ve() { }\n}").none { it == "To virtual" })
    }

    fun testToAbstractInAnAbstractClass() {
        assertEquals(
            "public abstract class Shape\n{\n    public abstract double Area();\n}",
            apply("public abstract class Shape\n{\n    public virtual double Ar<caret>ea()\n    {\n        return 0;\n    }\n}", "To abstract"),
        )
        assertEquals(
            "public abstract class Shape\n{\n    public abstract string Name { get; set; }\n}",
            apply("public abstract class Shape\n{\n    public string Na<caret>me { get; set; } = \"\";\n}", "To abstract"),
        )
        assertEquals(
            "public abstract class Shape\n{\n    public abstract int Sides { get; }\n}",
            apply("public abstract class Shape\n{\n    public int Si<caret>des => 0;\n}", "To abstract"),
        )
        assertTrue(titles("public class Shape\n{\n    public double Ar<caret>ea() => 0;\n}").none { it == "To abstract" })
    }

    fun testMakePartial() {
        assertEquals("public partial interface ILibrary\n{\n}", apply("public interface ILib<caret>rary\n{\n}", "Make partial"))
        assertEquals("internal static partial class Ext\n{\n}", apply("internal static class E<caret>xt\n{\n}", "Make partial"))
        assertTrue(titles("public partial class Sh<caret>op\n{\n}").none { it == "Make partial" })
        assertTrue(titles("public enum Ki<caret>nd { A }").none { it == "Make partial" })
    }
}
