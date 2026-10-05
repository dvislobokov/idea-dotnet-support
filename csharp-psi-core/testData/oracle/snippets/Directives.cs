class A<T> : B where T : new()
{
    // c
    var x = a + b * c;
    int F() => y is int z ? z : 0;
#if DEBUG
    void G() { }
#endif
}
