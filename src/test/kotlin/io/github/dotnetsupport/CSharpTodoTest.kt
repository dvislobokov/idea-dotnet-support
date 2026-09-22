package io.github.dotnetsupport

import com.intellij.psi.search.PsiTodoSearchHelper
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** TODO / FIXME of C# comments: counted by the index, found in the file, never in a string or a name. */
class CSharpTodoTest : BasePlatformTestCase() {
    fun testTodoInComments() {
        val file = myFixture.addFileToProject("TodoProbe/Shop.cs", """
            namespace Shop;

            // TODO line comment
            public class Order
            {
                /* FIXME block comment */
                /// TODO doc comment
                public string Note = "TODO not a comment";
                public int TodoCount;
            }
        """.trimIndent())
        val helper = PsiTodoSearchHelper.getInstance(project)
        val items = helper.findTodoItems(file)
        // the platform keeps the space before the end of a block comment, as it does for Java
        val texts = items.map { file.text.substring(it.textRange.startOffset, it.textRange.endOffset).trimEnd() }.sorted()
        assertEquals(listOf("FIXME block comment", "TODO doc comment", "TODO line comment"), texts)
        assertEquals("the index counts them as well", 3, helper.getTodoItemsCount(file))
    }
}
