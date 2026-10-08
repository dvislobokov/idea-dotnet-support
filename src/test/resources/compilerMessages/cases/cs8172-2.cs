// docs: cs8172.md #2; codes: CS8172
class C
{
    void M()
    {
        ref readonly int L() => ref (new int[1])[0];

        ref readonly int x = ref L();
        ref int y = ref x;
    }
}
