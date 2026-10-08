// docs: cs1737.md #1; codes: CS1737
// CS1737.cs (7,45)
class C
{
    static void F(object? x)
    {
        G(y: x);
    }
    static void G(object? x = null, object y)
    {
    }
}
