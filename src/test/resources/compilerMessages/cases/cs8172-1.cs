// docs: cs8172.md #1; codes: CS8172
// CS8172.cs (10,17)

class C
{
    void M()
    {
        ref readonly int L() => ref (new int[1])[0];

        ref readonly int x = ref L();
        ref int y = x;
    }
}
