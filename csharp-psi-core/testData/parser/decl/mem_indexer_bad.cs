class C
{
    int this { get; }
    int this[] { get; }
    int this[int i];
    int this[int i] => i { get; }
}
