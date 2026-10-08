// Semantic errors of the built-in diagnostics (CSharpFeature.DIAGNOSTICS, «Errors and warnings» = Built-in, 0.1.74, task C4c of
// CSHARP_PSI_MIGRATION.md): Roslyn's codes, messages and places from the plugin's own resolver, without the language server. Excluded from
// the compilation of Broken (Broken.csproj), like SyntaxErrors.cs; the editor still sees the file. Every line under a "permanent" marker is
// broken as it is: compare the marks with EXPECT, and with the server (Settings | .NET | Language Server, «Errors and warnings» =
// Language server) — the same codes in the same places, each shown once. The lines marked "silent" are correct or unknowable: NO red there.
using System.Text;
using System.Collections.Concurrent;

namespace DebugPlayground.Broken;

public class Calc
{
    public int Add(int a, int b) => a + b;
    public int Two(int a) => a;
    public int Two(int a, int b) => a + b;
    public static int Twice(int a) => a * 2;
}

public interface IShape { double Area(); }
public record Point(int X, int Y);
public enum Color { Red, Green }

public class SemanticErrors
{
    public void Names()
    {
        // TYPE:sem-names (permanent) — EXPECT: «CS0103: The name 'Stopwatch' does not exist in the current context» on `Stopwatch` (Alt+Enter
        // offers «Import 'System.Diagnostics.Stopwatch'»), «CS0246: The type or namespace name 'Stopwatch' could not be found (are you missing
        // a using directive or an assembly reference?)» on the second, «CS0103» on `missing` and on `missingCall`, «CS0246» on `Unknown`.
        var sw = Stopwatch.StartNew();
        Stopwatch other = null!;
        Console.WriteLine(missing);
        missingCall();
        List<Unknown> unknown = null!;

        // TYPE:sem-namespace (permanent) — EXPECT: «CS0234: The type or namespace name 'Foo' does not exist in the namespace 'System' (are
        // you missing an assembly reference?)» on `Foo`; on the second line the mark covers `System.Nothing`.
        System.Foo.Bar foo = null!;
        System.Nothing();
    }

    public void Members(Calc calc, List<int> list, IShape shape, Point p)
    {
        // TYPE:sem-members (permanent) — EXPECT: «CS1061: 'Calc' does not contain a definition for 'Nope' and no accessible extension method
        // 'Nope' accepting a first argument of type 'Calc' could be found (…)» on `Nope`; «CS0117: 'Calc' does not contain a definition for
        // 'Static'»; «CS0117: 'Console' does not contain a definition for 'WriteLin'»; CS1061 on `Nope3` of 'List<int>', on `Volume` of
        // 'IShape', on `Z` of 'Point'; «CS0117: 'Color' does not contain a definition for 'Blue'».
        calc.Nope();
        Calc.Static();
        Console.WriteLin("x");
        list.Nope3 = 1;
        shape.Volume();
        p.Z.ToString();
        Color.Blue.ToString();

        // TYPE:sem-members-silent (permanent) — EXPECT: NO red here: `ToString` of an interface is object's, `Deconstruct` of a record is
        // made by the compiler, `First` is an extension method of System.Linq (an implicit using of the project).
        shape.ToString();
        p.Deconstruct(out var x, out var y);
        list.First();
    }

    public void Arguments(Calc calc)
    {
        // TYPE:sem-arguments (permanent) — EXPECT: «CS7036: There is no argument given that corresponds to the required parameter 'b' of
        // 'Calc.Add(int, int)'» on `Add`; «CS1501: No overload for method 'Add' takes 3 arguments»; CS1501 on both `Two` (0 and 3 arguments);
        // «CS7036 … parameter 'a' of 'Calc.Twice(int)'»; «CS1501: No overload for method 'Max' takes 1 arguments».
        calc.Add(1);
        calc.Add(1, 2, 3);
        calc.Two();
        calc.Two(1, 2, 3);
        Calc.Twice();
        Math.Max(1);
    }

    public void Conversions(List<int> list, double d, int? n, long l)
    {
        // TYPE:sem-conversions (permanent) — EXPECT, line by line: «CS0029: Cannot implicitly convert type 'string' to 'int'» on `"three"`;
        // «CS0266: Cannot implicitly convert type 'double' to 'int'. An explicit conversion exists (are you missing a cast?)» on `1.5` and on
        // `d`; «CS0029 … 'int' to 'string'» on `5`; «CS0029 … 'System.Collections.Generic.List<int>' to 'string'»; CS0266 from 'int?' and
        // from 'long'; «CS0029 … 'int' to 'bool'»; «CS0029 … 'string' to 'char'».
        int count = "three";
        int fromDouble = 1.5;
        int fromD = d;
        string s = 5;
        string s2 = list;
        int fromN = n;
        int fromL = l;
        bool b = 1;
        char c = "c";

        // TYPE:sem-conversions-silent (permanent) — EXPECT: NO red: a constant `int` fits a `byte`, `int` widens to `long` and `double`.
        byte small = 1;
        long wide = 1;
        double fine = 1;
    }

    // TYPE:sem-paths (permanent) — EXPECT: «CS0161: 'SemanticErrors.NoReturn(int)': not all code paths return a value» on `NoReturn`,
    // «CS0161: 'SemanticErrors.Prop2.get': not all code paths return a value» on `get`; NO red on `Loop` and `Both`.
    public int NoReturn(int a)
    {
        if (a > 0) return 1;
    }

    public int Prop2 { get { if (Prop3) return 1; } }
    public bool Prop3 { get; set; }
    public int Loop() { while (true) { } }
    public int Both(int a) { if (a > 0) return 1; else return 2; }

    // TYPE:sem-unused (permanent) — EXPECT: the two `using` directives at the top of the file are gray, the tooltip «CS8019: Unnecessary
    // using directive.»; Alt+Enter on one → «Remove unused directives in file» removes both (Ctrl+Z to undo).

    // TYPE:sem-typing — on the empty line below type `var watch = Stopwatch.StartNew();`.
    // EXPECT: «CS0103» on `Stopwatch` at once (no build), a blue hint «System.Diagnostics.Stopwatch? Alt+Enter» over it; Alt+Enter adds
    // `using System.Diagnostics;` at the top and the mark goes away. Ctrl+Z twice.
    public void Typing()
    {

    }

    public void BrokenStatement(int id)
    {
        // TYPE:sem-broken-statement — type `Log("Customer {CustomerId} not found", id");` on the empty line below (the `"` after `id` opens a
        // string to the end of the line). EXPECT: that line gets its syntax errors only (unterminated string, `)` and `;` expected at its end);
        // `nope` on the line after it KEEPS its red «CS1061: 'SemanticErrors' does not contain a definition for 'nope' …» — the name itself is
        // painted red, as Rider paints an unresolved symbol. Undo with Ctrl+Z.

        this.nope.Add(id);
    }
}

// 0.1.143: declaration errors, as Roslyn reports them. EXPECT: each marked line is red with the code in the comment, nothing else in this block.
class HiddenType { }
public class Accessibility
{
    public static HiddenType Make() => new HiddenType(); // ERR:accessibility  EXPECT: CS0050 on Make
    public void Take(HiddenType t) { }                   // EXPECT: CS0051 on Take
    public HiddenType Field;                             // EXPECT: CS0052 on Field
}
public class Modifiers
{
    public virtual int field;              // ERR:modifiers  EXPECT: CS0106 on virtual
    public readonly void M() { }           // EXPECT: CS0106 on readonly
    static const int Limit = 1;            // EXPECT: CS0504 on Limit
}
struct Self { public Self other; }         // ERR:struct-cycle  EXPECT: CS0523 on other
public partial class Halves
{
    public partial void Defined(int x);    // ERR:partial-half  EXPECT: CS8795 on Defined (no implementation anywhere)
    public partial void Implemented() { }  // EXPECT: CS0759 on Implemented
}
class Awaiting
{
    async System.Threading.Tasks.Task M(int id) { await id; } // ERR:await-int  EXPECT: CS1061 on id ('int' has no GetAwaiter)
}

// 0.1.144: definite assignment of structs, field by field. EXPECT: only the marked lines are red.
struct Pair2 { public int A; public int B; }
struct Nest2 { public Pair2 P; public int Z; }
class StructFlow
{
    delegate void Del();
    void Fields()
    {
        Pair2 p; p.A = 1;
        System.Console.WriteLine(p.B);      // ERR:struct-field  EXPECT: CS0170 on p.B ("Use of possibly unassigned field 'B'")
        System.Console.WriteLine(p);        // ERR:struct-whole  EXPECT: CS0165 on p
        Nest2 n; n.P.A = 1; n.P.B = 2; n.Z = 3; System.Console.WriteLine(n);   // EXPECT: nothing (a field of a field is not modeled)
        Del d = delegate() { System.Console.WriteLine(d); };   // ERR:self-init  EXPECT: CS0165 on the d inside the delegate
        int Local(int a) { if (a > 0) return 1; }               // ERR:local-paths  EXPECT: CS0161 on Local
    }
}

// 0.1.146: the rules of ref. EXPECT: only the marked lines are red.
class RefRules
{
    int plain;
    ref int field;                          // ERR:ref-field  EXPECT: CS9059 on ref (a ref field only in a ref struct)
    ref int Local() { int x = 1; return ref x; }   // ERR:ref-local  EXPECT: CS8168 on x ("Cannot return local 'x' by reference")
    ref int Value() { return plain; }       // ERR:ref-return  EXPECT: CS8150 on plain (by-value return in a ref method)
    ref int Fine(int[] arr) { ref int ok = ref arr[0]; return ref ok; }   // EXPECT: nothing
    ref int Library(System.ReadOnlySpan<int> s) => ref s[0];   // ERR:ref-library  EXPECT: CS8333 on s[0] (a ref readonly indexer of the library by writable reference)
    ref int LibraryFine(System.Span<int> s) => ref s[0];       // EXPECT: nothing
}
struct RefStruct
{
    public int d;
    public ref int M() { return ref d; }    // ERR:ref-struct-this  EXPECT: CS8170 on d
    [System.Diagnostics.CodeAnalysis.UnscopedRef] public ref int N() { return ref d; }   // EXPECT: nothing
}
