// docs: -; codes: CS8156 CS8333 CS8334 (ref returns of library members, 0.1.147: the index must say ref and ref readonly)
using System;
class LibRef
{
    static int[] arr = new int[1];
    ref int A(Span<int> s) => ref s[0];
    ref int B(ReadOnlySpan<int> s) => ref s[0];
    ref readonly int C(ReadOnlySpan<int> s) => ref s[0];
    ref int D() => ref System.Runtime.InteropServices.MemoryMarshal.GetArrayDataReference(arr);
    ref int E() => ref Math.Max(1, 2);
    ref int F(Span<int> s) => ref s.Length;
    void G(ReadOnlySpan<int> s) { ref int r = ref s[0]; ref readonly int q = ref s[0]; int v = 0; ref readonly int w = ref v; }
    ref int H() { int v = 0; ref readonly int w = ref v; return ref w; }
    ref int I(ref readonly int p) => ref p;
    ref readonly int J(ReadOnlySpan<int> s) => ref s.GetPinnableReference();
    ref int K(ReadOnlySpan<int> s) => ref s.GetPinnableReference();
    ref int L(ref readonly int p) { ref readonly int w = ref p; return ref w; }
    ref readonly int M() { int v = 0; ref readonly int w = ref v; return ref w; }
    ref readonly int N(ref readonly int p) { ref readonly int w = ref p; return ref w; }
    void O(in int p, ref readonly int q) { ref int a = ref p; ref int b = ref q; ref readonly int c = ref p; }
    ref int P(Span<int> s) { ref int r = ref s[0]; return ref r; }
    ref int Q(Span<int> s) { ref readonly int r = ref s[0]; return ref r; }
}
