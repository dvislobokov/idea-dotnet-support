package io.github.dotnetsupport

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpIdentifierAnnotator
import io.github.dotnetsupport.lang.CSharpIdentifierClassifier
import io.github.dotnetsupport.lang.IdentifierKind.MEMBER
import io.github.dotnetsupport.lang.IdentifierKind.METHOD
import io.github.dotnetsupport.lang.IdentifierKind.TYPE

class IdentifierColorsTest : BasePlatformTestCase() {
    private fun classify(text: String): List<String> =
        CSharpIdentifierClassifier.classify(text).map { (range, kind) -> "${range.substring(text)}:$kind" }

    /** The snippet from the Rider screenshot the colors were compared on. */
    fun testRiderSnippet() {
        val code = """
            public static (Stream Input, Stream Output) Open()
            {
                if (OperatingSystem.IsWindows())
                    return (Console.OpenStandardInput(), Console.OpenStandardOutput());

                int inFd = dup(0), outFd = dup(1);
                if (inFd < 0 || outFd < 0)
                    return (Console.OpenStandardInput(), Console.OpenStandardOutput());
                return (
                    new FileStream(new SafeFileHandle(inFd, ownsHandle: true), FileAccess.Read, 1, isAsync: false),
                    new FileStream(new SafeFileHandle(outFd, ownsHandle: true), FileAccess.Write, 1, isAsync: false));
            }

            [DllImport("libc", SetLastError = true)]
            private static extern int dup(int fd);
        """.trimIndent()

        val kinds = classify(code)
        assertEquals(
            listOf(
                "Stream:$TYPE", "Stream:$TYPE", "Open:$METHOD",
                "OperatingSystem:$TYPE", "IsWindows:$METHOD",
                "Console:$TYPE", "OpenStandardInput:$METHOD", "Console:$TYPE", "OpenStandardOutput:$METHOD",
                "dup:$METHOD", "dup:$METHOD",
                "Console:$TYPE", "OpenStandardInput:$METHOD", "Console:$TYPE", "OpenStandardOutput:$METHOD",
                "FileStream:$TYPE", "SafeFileHandle:$TYPE", "FileAccess:$TYPE", "Read:$MEMBER",
                "FileStream:$TYPE", "SafeFileHandle:$TYPE", "FileAccess:$TYPE", "Write:$MEMBER",
                "DllImport:$TYPE", "dup:$METHOD",
            ),
            kinds,
        )
        // locals, parameters, tuple element names and named arguments keep the default color
        assertTrue(kinds.none { it.substringBefore(':') in setOf("inFd", "outFd", "fd", "Input", "Output", "ownsHandle", "isAsync", "SetLastError") })
    }

    fun testDeclarationsAndGenerics() {
        assertEquals(
            listOf(
                "Repository:$TYPE", "TKey:$TYPE", "IRepository:$TYPE", "TKey:$TYPE", "IDisposable:$TYPE",
                "Dictionary:$TYPE", "TKey:$TYPE", "List:$TYPE", "Order:$TYPE",
                "Task:$TYPE", "Order:$TYPE", "FindAsync:$METHOD", "TKey:$TYPE", "CancellationToken:$TYPE",
                "GetRequiredService:$METHOD", "IClock:$TYPE",
                "Order:$TYPE", "Order:$TYPE", "Status:$MEMBER", "Status:$TYPE", "Open:$MEMBER",
            ),
            classify(
                """
                using System.Collections.Generic;
                namespace Acme.Data;

                public class Repository<TKey> : IRepository<TKey>, IDisposable where TKey : notnull
                {
                    private readonly Dictionary<TKey, List<Order>> _items = new();

                    public async Task<Order?> FindAsync(TKey id, CancellationToken token)
                    {
                        var clock = services.GetRequiredService<IClock>();
                        if (index < count && count > 0) return null;
                        Order? order = item as Order;
                        return order.Status == Status.Open ? order : null;
                    }
                }
                """.trimIndent()
            ),
        )
    }

    fun testNotFooledByQueriesIndexersAndUsings() {
        // namespaces in directives, LINQ keywords, indexers and the ternary operator are not types
        assertEquals(
            listOf("Where:$METHOD", "ToList:$METHOD", "Length:$MEMBER"),
            classify(
                """
                using System.Linq;
                using static System.Math;
                var result = (from x in items select x).Where(y => y > 1).ToList();
                var first = matrix[row][column] + (flag ? left : right) + text.Length;
                """.trimIndent()
            ),
        )
    }

    fun testAnnotatorAndRiderPalette() {
        myFixture.configureByText("A.cs", "class Program { static void Main() { Console.WriteLine(1); } }")
        val highlighted = myFixture.doHighlighting().mapNotNull { info -> info.forcedTextAttributesKey?.let { "${info.text}:${it.externalName}" } }
        assertEquals(listOf("Program:CSHARP_TYPE", "Main:CSHARP_METHOD", "Console:CSHARP_TYPE", "WriteLine:CSHARP_METHOD"), highlighted)

        // the bundled scheme gives the keys their Rider colors
        val scheme = EditorColorsManager.getInstance().getScheme("Default")
        assertEquals(0x6B2FBA, scheme.getAttributes(CSharpIdentifierAnnotator.TYPE).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0x00855F, scheme.getAttributes(CSharpIdentifierAnnotator.METHOD).foregroundColor.rgb and 0xFFFFFF)
        assertEquals(0x0093A1, scheme.getAttributes(CSharpIdentifierAnnotator.MEMBER).foregroundColor.rgb and 0xFFFFFF)
    }
}
