// docs: cs8157.md #1; codes: CS8157
// CS8157.cs (8,21)

class C
{
    ref int M()
    {
        int x = 0;
        ref int rx = ref x;
        return ref (rx = ref (new int[1])[0]);
    }
}
