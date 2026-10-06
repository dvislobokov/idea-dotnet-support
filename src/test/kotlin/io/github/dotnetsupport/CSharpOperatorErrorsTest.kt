package io.github.dotnetsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.lang.CSharpFeature
import io.github.dotnetsupport.lang.CSharpFeatureSource
import io.github.dotnetsupport.lang.CSharpSyntaxTrees
import io.github.dotnetsupport.lang.semantic.CSharpSemanticEnvironment
import io.github.dotnetsupport.lsp.RoslynLanguageServerSettings

/**
 * Operators, casts and statement forms of the native pass (`CSharpOperatorChecks`): Roslyn's codes, texts and spans (as `dotnet build`
 * prints them, checked on debug-playground/Broken/Errors), and silence where an operator or a conversion may be user-defined or unknown.
 */
class CSharpOperatorErrorsTest : BasePlatformTestCase() {
    private val settings get() = RoslynLanguageServerSettings.getInstance()
    private var files = 0

    override fun setUp() {
        super.setUp()
        CSharpSyntaxTrees.forceNativeTreeForTests(true)
        CSharpSemanticEnvironment.setAssembliesForTests { CSharpUsingTypesTest.ASSEMBLIES }
        settings.setSource(CSharpFeature.DIAGNOSTICS, CSharpFeatureSource.NATIVE)
    }

    override fun tearDown() {
        try {
            settings.state.features = mutableMapOf()
            CSharpSemanticEnvironment.setAssembliesForTests(null)
            CSharpSyntaxTrees.forceNativeTreeForTests(null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Each error of the codes of this group as `text under it -> CSxxxx: message`. */
    private fun errors(text: String): List<String> {
        myFixture.configureByText("OperatorErrors${files++}.cs", text.trimIndent())
        val document = myFixture.editor.document.text
        return myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.severity == HighlightSeverity.ERROR && it.description?.take(6) in CODES }
            .sortedBy { it.startOffset }.map { document.substring(it.startOffset, it.endOffset) + " -> " + it.description }
    }

    private fun wrap(body: String, members: String = ""): String = """
        using System;
        using System.Collections.Generic;

        namespace Probe.Ops;

        public enum Color { Red, Green }
        public enum Size { Small, Large }
        public struct Pt { public int X; }
        public class Box { public int V; public class Inner { } }
        public class Derived : Box { }
        public record Rec(int A);
        public struct Money { public decimal A; public static Money operator +(Money a, Money b) => a; public static implicit operator decimal(Money m) => m.A; }
        public class Meters { public static implicit operator double(Meters m) => 1; }
        public interface IShape { }

        public class Ops
        {
            ${members.replace("\n", "\n            ")}
            public int M() => 1;
            public void Body(int i, long l, ulong ul, decimal m, double d, bool b, bool? nb, string s, char c, Color e, Size z, Pt p, Box x, Derived dx,
                int? ni, object o, Money money, Meters meters, IShape shape, Rec rec, (int, int) t, Action act, DateTime when, TimeSpan span)
            {
                ${body.replace("\n", "\n                ")}
            }
        }
    """

    fun testBinaryOperatorsOfPredefinedTypes() {
        assertEquals(listOf(
            "b + i -> CS0019: Operator '+' cannot be applied to operands of type 'bool' and 'int'",
            "m * d -> CS0019: Operator '*' cannot be applied to operands of type 'decimal' and 'double'",
            "s - i -> CS0019: Operator '-' cannot be applied to operands of type 'string' and 'int'",
            "s < s -> CS0019: Operator '<' cannot be applied to operands of type 'string' and 'string'",
            "b && nb -> CS0019: Operator '&&' cannot be applied to operands of type 'bool' and 'bool?'",
            "d & 1 -> CS0019: Operator '&' cannot be applied to operands of type 'double' and 'int'",
            "i << l -> CS0019: Operator '<<' cannot be applied to operands of type 'int' and 'long'",
            "ni + b -> CS0019: Operator '+' cannot be applied to operands of type 'int?' and 'bool'",
            "i += b -> CS0019: Operator '+=' cannot be applied to operands of type 'int' and 'bool'",
        ), errors(wrap("""
            var a1 = b + i;
            var a2 = m * d;
            var a3 = s - i;
            var a4 = s < s;
            var a5 = b && nb;
            var a6 = d & 1;
            var a7 = i << l;
            var a8 = ni + b;
            i += b;
            var ok = s + b + e + nb + p + x + o + i * d + (c - 1) + (ni ?? 0) + (b & b ? 1 : 0) + (i << 2) + m * i;
            s += i;
        """)))
    }

    fun testEnumsStructsAndClasses() {
        assertEquals(listOf(
            "e + e -> CS0019: Operator '+' cannot be applied to operands of type 'Color' and 'Color'",
            "e == z -> CS0019: Operator '==' cannot be applied to operands of type 'Color' and 'Size'",
            "e < i -> CS0019: Operator '<' cannot be applied to operands of type 'Color' and 'int'",
            "p == p -> CS0019: Operator '==' cannot be applied to operands of type 'Pt' and 'Pt'",
            "x + 1 -> CS0019: Operator '+' cannot be applied to operands of type 'Box' and 'int'",
            "x == s -> CS0019: Operator '==' cannot be applied to operands of type 'Box' and 'string'",
            "x == ni -> CS0019: Operator '==' cannot be applied to operands of type 'Box' and 'int?'",
            "list + 1 -> CS0019: Operator '+' cannot be applied to operands of type 'List<int>' and 'int'",
            "bx - 1 -> CS0019: Operator '-' cannot be applied to operands of type 'Box' and 'int'",
        ), errors(wrap("""
            var list = new List<int>();
            Box? bx = null;
            var a1 = e + e;
            var a2 = e == z;
            var a3 = e < i;
            var a4 = p == p;
            var a5 = x + 1;
            var a6 = x == s;
            var a7 = x == ni;
            var ok1 = e == 0 || e - e == 0 || (e & e) == e || e < Color.Green || x == dx || x != null || o == x;
            var ok2 = e + i;
            var ok3 = e - 1;
            var a8 = list + 1;
            var a9 = bx - 1;
        """)))
    }

    fun testUserDefinedAndUnknownOperatorsAreLeftAlone() {
        assertEquals(emptyList<String>(), errors(wrap("""
            var a1 = money + money;
            var a2 = money * 2;
            var a3 = meters + 1;
            var a4 = when - when;
            var a5 = span + span;
            var a6 = rec == rec;
            var a7 = t == t;
            var a8 = act + act;
            var a9 = shape == x;
            var a10 = ul + i;
            var a11 = o + 1;
            var a12 = unknown + 1;
            dynamic dyn = o;
            var a13 = dyn + 1;
        """)).filter { "CS0103" !in it })
    }

    fun testExtensionOperatorsAreLeftAlone() {
        assertEquals(emptyList<String>(), errors("""
            namespace Probe.Ext;
            public class Vec { public int X; }
            public static class VecOperators
            {
                extension(Vec)
                {
                    public static Vec operator +(Vec a, Vec b) => a;
                }
            }
            public class Use
            {
                public Vec Sum(Vec a, Vec b) => a + b;
            }
        """))
    }

    fun testUnaryOperators() {
        assertEquals(listOf(
            "-b -> CS0023: Operator '-' cannot be applied to operand of type 'bool'",
            "!i -> CS0023: Operator '!' cannot be applied to operand of type 'int'",
            "~d -> CS0023: Operator '~' cannot be applied to operand of type 'double'",
            "-e -> CS0023: Operator '-' cannot be applied to operand of type 'Color'",
            "!x -> CS0023: Operator '!' cannot be applied to operand of type 'Box'",
            "!ni -> CS0023: Operator '!' cannot be applied to operand of type 'int?'",
            "-ul -> CS0023: Operator '-' cannot be applied to operand of type 'ulong'",
            "b++ -> CS0023: Operator '++' cannot be applied to operand of type 'bool'",
        ), errors(wrap("""
            var u1 = -b;
            var u2 = !i;
            var u3 = ~d;
            var u4 = -e;
            var u5 = !x;
            var u6 = !ni;
            var u7 = -ul;
            b++;
            var ok = !b == !nb;
            var ok2 = -i + -d + ~i + ~e + -ni;
            i++;
            e--;
            var ok3 = x!.V;
            var ok4 = -money;
        """)).filter { "CS0023" in it || "CS0019" in it })
    }

    fun testCasts() {
        assertEquals(listOf(
            "(int)b -> CS0030: Cannot convert type 'bool' to 'int'",
            "(string)i -> CS0030: Cannot convert type 'int' to 'string'",
            "(Derived)p -> CS0030: Cannot convert type 'Probe.Ops.Pt' to 'Probe.Ops.Derived'",
            "(Pt)x -> CS0030: Cannot convert type 'Probe.Ops.Box' to 'Probe.Ops.Pt'",
            "(string)x -> CS0030: Cannot convert type 'Probe.Ops.Box' to 'string'",
            "(int?)b -> CS0030: Cannot convert type 'bool' to 'int?'",
            "(Color)s -> CS0030: Cannot convert type 'string' to 'Probe.Ops.Color'",
        ), errors(wrap("""
            var c1 = (int)b;
            var c2 = (string)i;
            var c3 = (Derived)p;
            var c4 = (Pt)x;
            var c5 = (string)x;
            var c6 = (int?)b;
            var c7 = (Color)s;
            var ok = (Derived)x;
            var ok2 = (int)d + (long)e + (char)i + (int)(ni ?? 0);
            var ok3 = (Pt)o;
            var ok4 = (decimal)money;
            var ok5 = (double)meters;
            var ok6 = (Box)o;
            var ok7 = (Color?)ni;
        """)))
    }

    fun testStatements() {
        assertEquals(listOf(
            "i + 1 -> CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement",
            "x.V -> CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement",
            "M -> CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement",
            "\"a\" -> CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement",
            "(M()) -> CS0201: Only assignment, call, increment, decrement, await, and new object expressions can be used as a statement",
        ), errors(wrap("""
            i + 1;
            x.V;
            M;
            "a";
            (M());
            M();
            i++;
            x.V = 2;
            new Box();
            Console.WriteLine();
        """)))
    }

    fun testTypesAndMethodGroupsAsValues() {
        assertEquals(listOf(
            "Box -> CS0119: 'Box' is a type, which is not valid in the given context",
            "Box.Inner -> CS0119: 'Box.Inner' is a type, which is not valid in the given context",
            "M -> CS0119: 'Ops.M()' is a method, which is not valid in the given context",
            "M -> CS0428: Cannot convert method group 'M' to non-delegate type 'int'. Did you intend to invoke the method?",
            "x.ToString -> CS0428: Cannot convert method group 'ToString' to non-delegate type 'string'. Did you intend to invoke the method?",
            "M -> CS0428: Cannot convert method group 'M' to non-delegate type 'Box'. Did you intend to invoke the method?",
        ), errors(wrap("""
            Console.WriteLine(Box);
            object o2 = Box.Inner;
            var n = M.ToString();
            int k = M;
            string t2 = x.ToString;
            x = M;
            Func<int> f = M;
            var g = M;
            object h = M();
            var ty = typeof(Box);
        """)))
    }

    fun testImplicitlyTypedVariables() {
        assertEquals(listOf(
            "v1 -> CS0815: Cannot assign <null> to an implicitly-typed variable",
            "v2 -> CS0815: Cannot assign void to an implicitly-typed variable",
            "v3 -> CS0818: Implicitly-typed variables must be initialized",
            "v4 -> CS0820: Cannot initialize an implicitly-typed variable with an array initializer",
        ), errors(wrap("""
            var v1 = null;
            var v2 = Console.WriteLine("a");
            var v3;
            var v4 = { 1, 2 };
            var ok1 = M();
            int[] ok2 = { 1 };
            var ok3 = (string?)null;
            int ok4;
        """)))
    }

    fun testIndexing() {
        assertEquals(listOf(
            "i[0] -> CS0021: Cannot apply indexing with [] to an expression of type 'int'",
            "x[0] -> CS0021: Cannot apply indexing with [] to an expression of type 'Box'",
            "M[0] -> CS0021: Cannot apply indexing with [] to an expression of type 'method group'",
            "e[1] -> CS0021: Cannot apply indexing with [] to an expression of type 'Color'",
        ), errors(wrap("""
            var n1 = i[0];
            var n2 = x[0];
            var n3 = M[0];
            var n4 = e[1];
            var ok = s[0] + new List<int>()[0] + (new int[1])[0];
            var ok2 = s[1..];
            var ok3 = o is int[] arr ? arr[0] : 0;
        """)))
    }

    fun testThisWithoutAnInstance() {
        assertEquals(listOf(
            "this -> CS0026: Keyword 'this' is not valid in a static property, static method, or static field initializer",
            "this -> CS0026: Keyword 'this' is not valid in a static property, static method, or static field initializer",
            "this -> CS0026: Keyword 'this' is not valid in a static property, static method, or static field initializer",
            "this -> CS0027: Keyword 'this' is not available in the current context",
            "this -> CS0027: Keyword 'this' is not available in the current context",
            "this -> CS0027: Keyword 'this' is not available in the current context",
        ), errors("""
            using System;
            namespace Probe.This;
            public class Holder
            {
                private int _v = 1;
                public static int S() => this._v;
                public static int SP => this._v;
                public static int SF = this.Get();
                public int IF = this.Get();
                public int IP { get; } = this.Get();
                public Holder() : this(this._v) { }
                public Holder(int v) { _v = v; }
                public int Get() => this._v;
                public int P => this._v;
                public Func<int> L() => () => this._v;
                public static Func<int> SL() => static () => 1;
            }
        """))
    }

    companion object {
        private val CODES = setOf("CS0019", "CS0023", "CS0030", "CS0201", "CS0119", "CS0428", "CS0815", "CS0818", "CS0820", "CS0021", "CS0026", "CS0027")
    }
}
