package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment

/**
 * The members of `object` on an enum, a struct, a delegate of an assembly: the index writes no base of them, the set gives `System.Enum`,
 * `System.ValueType`, `System.MulticastDelegate` (`DbType.Binary.ToString()` of EFCore.BulkExtensions was CS1061, 0.1.184).
 */
class LibraryEnumMembersTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
    }

    override fun tearDown() {
        try {
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } finally {
            super.tearDown()
        }
    }

    fun testTheMembersOfObjectOnAnEnumAStructAndADelegateOfAnAssembly() {
        myFixture.configureByText("Scratch.cs", """
            using System;
            using Fixture;

            class C
            {
                void M(string columnType, object value, Color c)
                {
                    var a = Color.Green.ToString();
                    var b = columnType.Contains(Color.Green.ToString());
                    var d = columnType.Contains(Color.Green.ToString(), StringComparison.OrdinalIgnoreCase);
                    if (value == null && columnType.Contains(Color.Green.ToString(), StringComparison.OrdinalIgnoreCase)) { }
                    var e = c.ToString() == Color.Red.ToString();
                    var f = c.GetHashCode() + Color.Red.CompareTo(c) + StringComparison.Ordinal.GetHashCode();
                    object o = c; var t0 = o.GetType(); ValueType vt = c; var t1 = vt.GetType(); Enum en = c; var t2 = en.GetType();
                    var g = c.HasFlag(Color.Red) && c.Equals(Color.Green) && c.GetType() != null;
                    var money = default(Money);
                    var h = money.GetHashCode() + money.ToString().Length;
                    Handler handler = null;
                    var k = handler.GetInvocationList().Length + handler.Method.Name.Length;
                    Enum day = DayOfWeek.Monday; ValueType box = money; Delegate any = handler; MulticastDelegate many = handler; object boxed = day;
                }
            }
        """.trimIndent())
        val document = myFixture.editor.document.text
        val found = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }
            .map { "line " + (document.substring(0, it.startOffset).count { c -> c == '\n' } + 1) + ": " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
        assertEquals(emptyList<String>(), found)
    }

    fun testProbeLambdaAndSourceEnum() {
        myFixture.configureByText("Scratch2.cs", """
            using System;
            using Fixture;

            [Flags] public enum Mode : ushort { None = 0, Dir = 1, File = 2 }
            public class Policy { public string Name { get; set; } public Policy() { } }

            class C
            {
                void M(Mode mode, Box<Policy> box)
                {
                    var a = mode.HasFlag(Mode.Dir);
                    var b = Mode.File.HasFlag(mode);
                    var c = box.Map<int>(p => p.Name.Length);
                    var d = box.Map(p => p.Name);
                }

                void N<TPolicy>(Box<TPolicy> box, System.Collections.Generic.List<TPolicy> list) where TPolicy : Policy, new()
                {
                    var e = box.Map<int>(p => p.Name.Length);
                    var f = box.Map<string>(p => p.Name);
                    var g = list.ConvertAll<int>(p => p.Name.Length);
                    var h = list.ConvertAll<string>(p => { p.Name = "x"; return p.Name; });
                    var k = list.ConvertAll<int>((p) => p.Name.Length);
                    var l = System.Linq.Enumerable.Select<TPolicy, int>(list, (p, i) => p.Name.Length + i);
                }
            }
        """.trimIndent())
        val document = myFixture.editor.document.text
        val found = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR }
            .map { "line " + (document.substring(0, it.startOffset).count { c -> c == '\n' } + 1) + ": " + document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
        assertEquals(emptyList<String>(), found)
    }
}
