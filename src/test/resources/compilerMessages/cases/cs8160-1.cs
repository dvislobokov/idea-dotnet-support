// docs: cs8160.md #1; codes: CS8160
// CS8160.cs (8,20)

class Program
{
    readonly int i = 0;

    ref int M()
    {
        return ref i;
    }
}
