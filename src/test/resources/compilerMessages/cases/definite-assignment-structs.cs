// docs: -; codes: CS0165 CS0170 CS0177 CS0161 (the definite-assignment probe of 0.1.144: struct fields, a local in its own initializer, an error type)
struct Empty { }
struct Pair { public int a; public int b; }
struct Nest { public Pair p; public int z; }
struct Auto { public int A { get; set; } public int f; }
class U { public int i; }
struct S { public U u; }
class T
{
    void M1(out Empty e) { }
    Foo M2() { }
    void M3() { int x = x + 1; }
    void M4() { S s; s.u.i = 5; }
    void M5() { Pair p; p.a = 1; System.Console.WriteLine(p.b); }
    void M6() { Pair p; p.a = 1; System.Console.WriteLine(p); }
    void M7() { Nest n; n.p.a = 1; n.p.b = 2; n.z = 3; System.Console.WriteLine(n); }
    void M8() { Auto a; a.f = 1; System.Console.WriteLine(a.f); System.Console.WriteLine(a); }
    void M9(out Pair p) { p.a = 1; }
    void M10() { Pair p; p.a = 1; int q = p.a + p.b; }
    void M11() { Nest n; n.z = 1; System.Console.WriteLine(n.p.a); }
    void M12() { Pair p; p.a = 1; Take(out p.b); System.Console.WriteLine(p); }
    void Take(out int x) { x = 0; }
    void M13(out Auto a) { a.f = 1; }
    void M14() { Pair p; int k = p.a++; }
    void M15() { Pair p; p.b = 2; p.a += 1; }
}
