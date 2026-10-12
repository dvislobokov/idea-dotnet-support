package io.github.dotnetsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFile
import io.github.dotnetsupport.lang.CSharpSafeDeleteAction
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSafeDelete
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * Safe Delete without the language server (0.1.174, Jellyfin comparison № 50): what goes with a declaration (its doc comment, a blank line,
 * its comma, the file of a type alone in it, the arguments of a parameter in the whole hierarchy), and the usages left as conflicts.
 */
class CSharpSafeDeleteTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
    }

    override fun tearDown() {
        try {
            NativeCSharpSafeDelete.answerDeleteFileForTests(null)
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** [text] with `<caret>` opened as a file of its own folder, Safe Delete there; the text after it. */
    private fun delete(text: String, vararg others: Pair<String, String>): String {
        val dir = "del${round++}"
        for ((name, other) in others) myFixture.addFileToProject("$dir/$name", other.trimIndent())
        val main = myFixture.addFileToProject("$dir/Main.cs", text.trimIndent().replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(main.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.trimIndent().indexOf("<caret>"))
        NativeCSharpSafeDelete.invoke(project, myFixture.editor, myFixture.file as CSharpFile)
        return myFixture.editor.document.text
    }

    private fun text(dir: Int, name: String): String? =
        myFixture.findFileInTempDir("del$dir/$name")?.takeIf { it.isValid }?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)?.text }

    fun testAnUnusedMethodGoesWithItsDocCommentAndABlankLine() {
        val result = delete("""
            namespace Del;
            public class Orders0
            {
                public void Keep() { }

                /// <summary>Not used.</summary>
                public void <caret>Unused0() { }

                public void Other() { }
            }
        """)
        assertEquals("namespace Del;\npublic class Orders0\n{\n    public void Keep() { }\n\n    public void Other() { }\n}", result)
    }

    fun testAUsedMethodIsAConflict() {
        try {
            delete("""
                namespace Del;
                public class Orders1
                {
                    public void <caret>Used1() { }
                    public void Run() { Used1(); }
                }
            """)
            fail("a conflict expected")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.single().contains("Method 'Used1' is used in Main.cs:5"))
        }
    }

    fun testAParameterGoesWithItsArgumentsInTheHierarchy() {
        val dir = round
        val result = delete("""
            namespace Del;
            public class Store2
            {
                /// <summary>Saves.</summary>
                /// <param name="id">The id.</param>
                /// <param name="name">The name.</param>
                public virtual void Save2(int id, string <caret>name, bool force) { }
            }
            public class Fast2 : Store2
            {
                public override void Save2(int id, string name, bool force) { }
            }
        """, "Use.cs" to """
            namespace Del;
            public static class Use2
            {
                public static void Run(Store2 s)
                {
                    s.Save2(1, "a", true);
                    s.Save2(2, name: "b", force: false);
                    new Fast2().Save2(3, "c", false);
                }
            }
        """)
        assertTrue(result, result.contains("public virtual void Save2(int id, bool force) { }"))
        assertTrue(result, result.contains("public override void Save2(int id, bool force) { }"))
        assertFalse(result, result.contains("<param name=\"name\">"))
        assertTrue(result, result.contains("<param name=\"id\">"))
        val use = text(dir, "Use.cs")!!
        assertTrue(use, use.contains("s.Save2(1, true);") && use.contains("s.Save2(2, force: false);") && use.contains("new Fast2().Save2(3, false);"))
    }

    fun testAParameterUsedInTheBodyIsAConflict() {
        try {
            delete("""
                namespace Del;
                public class Store3
                {
                    public int Twice3(int <caret>x) => x * 2;
                }
            """)
            fail("a conflict expected")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.single().startsWith("Parameter 'x' is used"))
        }
    }

    fun testATypeAloneInItsFileTakesTheFile() {
        val dir = round
        delete("""
            namespace Del;
            /// <summary>Nobody uses it.</summary>
            public class <caret>Lonely4
            {
            }
        """)
        assertNull(text(dir, "Main.cs"))
    }

    fun testATypeWithOthersGoesFromItsFile() {
        val result = delete("""
            namespace Del;
            public class Kept5
            {
            }

            public class <caret>Gone5
            {
            }
        """)
        assertEquals("namespace Del;\npublic class Kept5\n{\n}\n", result)
    }

    fun testLocalsAndLocalFunctions() {
        assertEquals("class A6 { void M() { var b = 2; Use(b); } void Use(int x) { } }",
            delete("class A6 { void M() { var <caret>a = 1; var b = 2; Use(b); } void Use(int x) { } }"))
        // a value with an effect stays as a statement
        assertEquals("class A7 { int Save() => 1; void M() { Save(); } }", delete("class A7 { int Save() => 1; void M() { var <caret>saved = Save(); } }"))
        val function = delete("""
            class A8
            {
                void M()
                {
                    Work();
                    void Work() { }
                    int <caret>Unused() => 1;
                }
            }
        """)
        assertEquals("class A8\n{\n    void M()\n    {\n        Work();\n        void Work() { }\n    }\n}", function)
    }

    fun testEnumMembersFieldsAndTypeParameters() {
        assertEquals("enum Color9 { Red, Blue }", delete("enum Color9 { Red, <caret>Green, Blue }"))
        assertEquals("class F10 { int a, c; int Sum() => a + c; }", delete("class F10 { int a, <caret>b, c; int Sum() => a + c; }"))
        val dir = round
        val result = delete("""
            namespace Del;
            public class Box11<T, <caret>U> where T : class where U : struct
            {
                public T Value;
            }
        """, "Use.cs" to """
            namespace Del;
            public class Use11 { Box11<string, int> box = new Box11<string, int>(); }
        """)
        assertEquals("namespace Del;\npublic class Box11<T> where T : class\n{\n    public T Value;\n}", result)
        assertEquals("namespace Del;\npublic class Use11 { Box11<string> box = new Box11<string>(); }", text(dir, "Use.cs"))
    }

    fun testTheActionIsTheNativeOneInACSharpEditor() {
        assertTrue(ActionManager.getInstance().getAction(CSharpSafeDeleteAction.ID) is CSharpSafeDeleteAction)
        myFixture.configureByText("Action12.cs", "class Action12 { void <caret>M() { } }")
        val presentation = myFixture.testAction(ActionManager.getInstance().getAction(CSharpSafeDeleteAction.ID))
        assertTrue(presentation.isEnabled)
        assertEquals("class Action12 { }", myFixture.editor.document.text)
    }

    companion object {
        private var round = 0
    }
}
