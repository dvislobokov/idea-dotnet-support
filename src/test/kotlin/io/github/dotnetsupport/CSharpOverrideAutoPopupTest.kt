package io.github.dotnetsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees

/** The list opens by itself after `override ` with the server off (0.1.85: before, nothing opened it), and not after `public `. */
class CSharpOverrideAutoPopupTest : BasePlatformTestCase() {
    private lateinit var tester: CompletionAutoPopupTester

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
    }

    override fun tearDown() {
        try {
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testASpaceAfterOverrideOpensTheMembersToOverride() {
        tester.runWithAutoPopupEnabled {
            myFixture.configureByText("AutoPopup.cs", "class Shape\n{\n    public virtual double Area() => 0;\n}\nclass Square : Shape\n{\n    public override<caret>\n}\n")
            tester.typeWithPauses(" ")
            val items = myFixture.lookupElementStrings.orEmpty()
            assertTrue(items.toString(), "Area" in items)
        }
    }

    fun testNoListAfterAnAccessModifier() {
        tester.runWithAutoPopupEnabled {
            myFixture.configureByText("AutoPopupNone.cs", "class Square\n{\n    public<caret>\n}\n")
            tester.typeWithPauses(" ")
            assertNull(myFixture.lookup)
        }
    }
}
