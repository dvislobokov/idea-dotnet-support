// docs: -; codes: CS8149 CS8150 CS8156 CS8157 CS8162 CS8166 CS8167 CS8169 CS8173 CS8333 (the ref probe of 0.1.146: what the pages do not show)
class RefProbe
{
    struct S { public int x; }
    readonly int ro = 0;
    static readonly int sro = 0;
    readonly S rs;
    int plain;
    delegate ref int RefD();
    delegate int ValD();
    ref int A1(int p) { return ref p; }
    ref int A2(S p) { return ref p.x; }
    ref int A3() { S s; s.x = 1; return ref s.x; }
    ref int A4(int p) { ref int r = ref p; return ref r; }
    ref int A5(in int p) { return ref p; }
    ref readonly int A6(in int p) { return ref p; }
    ref readonly int A7() { return ref ro; }
    ref int A8(ref int p) { return ref p; }
    ref int A9(int[] a) { return ref a[0]; }
    ref int A10() { return ref plain; }
    ref int A11() { return ref rs.x; }
    ref readonly int A12() { return ref sro; }
    void B1() { ValD d = () => ref plain; RefD e = () => plain; RefD f = () => ref plain; }
    void B2() { int x = 1; ref int r = ref x; r = ref (x + 1); long l = 2; ref int q = ref l; }
    int C1() { return ref plain; }
    ref int C2() => plain;
    ref int C3 => ref plain;
    ref int C4 { get => plain; }
    ref int C5() { ref int r = ref plain; return ref r; }
    ref int C6(ref S p) { return ref p.x; }
}
