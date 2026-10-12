package io.github.dotnetsupport

import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.NativeCSharpSolutionRename
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Rename of types and members across the solution without the language server (task C4b of CSHARP_PSI_MIGRATION.md): every declaration and
 * usage, the hierarchy of a member when the user says so, constructors with their type, the file named after the type, `nameof` and `cref`,
 * conflicts.
 */
class CSharpSolutionRenameTest : BasePlatformTestCase() {
    private var round = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { null }
        RoslynLanguageServerSettings.getInstance().setSource(CSharpFeature.RENAME, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            NativeCSharpSolutionRename.answerHierarchyForTests(null)
            RoslynLanguageServerSettings.getInstance().state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** The three files of a solution in their own folder; the editor on [open] with the caret before [at] (its first occurrence in code). */
    private fun solution(open: String, at: String): String {
        val dir = "rename${round++}"
        myFixture.addFileToProject("$dir/IShape.cs", """
            namespace Shapes;
            /// <summary>A shape; see <see cref="Circle.Radius"/> and <see cref="Circle"/>.</summary>
            public interface IShape
            {
                double Area();
            }
        """.trimIndent())
        myFixture.addFileToProject("$dir/Circle.cs", """
            namespace Shapes;
            public class Circle : IShape
            {
                public Circle(double radius) { Radius = radius; }
                ~Circle() { }
                public double Radius { get; set; }
                public double Area() => 3.14 * Radius * Radius;
                public string Name => nameof(Radius);
            }
            public class Ring : Circle
            {
                public Ring() : base(1) { }
                public new double Area() => 0;
            }
        """.trimIndent())
        myFixture.addFileToProject("$dir/Use.cs", """
            using Shapes;
            namespace App;
            public static class Use
            {
                public static double Total(IShape[] shapes)
                {
                    var c = new Circle(2);
                    c.Radius = 3;
                    double sum = c.Area();
                    foreach (var s in shapes) sum += s.Area();
                    return sum;
                }
            }
        """.trimIndent())
        myFixture.configureFromTempProjectFile("$dir/$open")
        val offset = myFixture.editor.document.text.indexOf(at)
        assertTrue(at, offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
        return dir
    }

    private fun text(path: String): String = myFixture.findFileInTempDir(path)?.let { com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(it)!!.text } ?: error("no $path")

    fun testAPropertyAcrossFilesWithNameofAndCref() {
        val dir = solution("Use.cs", "Radius = 3")
        myFixture.renameElementAtCaretUsingHandler("Size")
        val circle = text("$dir/Circle.cs")
        assertTrue(circle, circle.contains("public double Size { get; set; }"))
        assertTrue(circle, circle.contains("{ Size = radius; }"))
        assertTrue(circle, circle.contains("3.14 * Size * Size"))
        assertTrue(circle, circle.contains("nameof(Size)"))
        assertTrue(text("$dir/Use.cs").contains("c.Size = 3;"))
        assertTrue(text("$dir/IShape.cs").contains("<see cref=\"Circle.Size\"/>"))
    }

    fun testATypeWithItsConstructorsFinalizerAndFile() {
        val dir = solution("Circle.cs", "Circle : IShape")
        myFixture.renameElementAtCaretUsingHandler("Disk")
        assertNull("the file named after the type follows it", myFixture.findFileInTempDir("$dir/Circle.cs"))
        val disk = text("$dir/Disk.cs")
        assertTrue(disk, disk.contains("public class Disk : IShape"))
        assertTrue(disk, disk.contains("public Disk(double radius)"))
        assertTrue(disk, disk.contains("~Disk() { }"))
        assertTrue(disk, disk.contains("public class Ring : Disk"))
        assertTrue(text("$dir/Use.cs").contains("var c = new Disk(2);"))
        assertTrue(text("$dir/IShape.cs").contains("<see cref=\"Disk.Radius\"/> and <see cref=\"Disk\"/>"))
    }

    fun testAConstructorRenamesItsType() {
        val dir = solution("Circle.cs", "Circle(double")
        myFixture.renameElementAtCaretUsingHandler("Disk")
        assertTrue(text("$dir/Disk.cs").contains("public class Disk : IShape"))
    }

    fun testTheHierarchyOfAMemberWhenAsked() {
        NativeCSharpSolutionRename.answerHierarchyForTests(true)
        val dir = solution("Circle.cs", "Area() => 3.14")
        myFixture.renameElementAtCaretUsingHandler("Surface")
        assertTrue(text("$dir/IShape.cs").contains("double Surface();"))
        assertTrue(text("$dir/Circle.cs").contains("public double Surface() => 3.14"))
        assertTrue("`new` hides, it is not of the hierarchy", text("$dir/Circle.cs").contains("public new double Area() => 0;"))
        val use = text("$dir/Use.cs")
        assertTrue(use, use.contains("c.Surface()") && use.contains("s.Surface()"))
    }

    fun testOnlyTheMemberWhenNotAsked() {
        NativeCSharpSolutionRename.answerHierarchyForTests(false)
        val dir = solution("Circle.cs", "Area() => 3.14")
        myFixture.renameElementAtCaretUsingHandler("Surface")
        assertTrue(text("$dir/IShape.cs").contains("double Area();"))
        val use = text("$dir/Use.cs")
        assertTrue(use, use.contains("c.Surface()") && use.contains("s.Area()"))
    }

    fun testAMemberOfTheSameNameIsAConflict() {
        solution("Circle.cs", "Radius { get")
        val messages = try {
            myFixture.renameElementAtCaretUsingHandler("Name")
            fail("conflicts expected")
            emptyList()
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            e.messages.toList()
        }
        assertTrue(messages.toString(), messages.any { it.contains("already has a member named 'Name'") })
    }

    fun testATypeOfTheSameNameIsAConflict() {
        solution("Circle.cs", "Ring : Circle")
        val messages = try {
            myFixture.renameElementAtCaretUsingHandler("IShape")
            fail("conflicts expected")
            emptyList()
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            e.messages.toList()
        }
        assertTrue(messages.toString(), messages.any { it.contains("A type named 'IShape' is already declared") })
    }

    fun testAMemberOfAnAssemblyIsRefused() {
        myFixture.configureByText("RenameLibrary.cs", "class R { string M(string s) => s.Tri<caret>m(); }")
        val hint = try {
            myFixture.renameElementAtCaretUsingHandler("Cut")
            ""
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            e.message!!
        }
        // without assemblies the name resolves to nothing: the hint of a symbol nobody renames
        assertTrue(hint, hint.isNotEmpty())
    }
}
