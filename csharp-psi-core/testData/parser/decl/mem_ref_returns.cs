class C
{
    ref int M() => ref x;
    ref readonly int P => ref x;
    ref int this[int i] => ref x;
    ref int F;
}
