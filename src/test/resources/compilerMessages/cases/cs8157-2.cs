// docs: cs8157.md #2; codes: CS8157
class C
{
    int M()
    {
        int x = 0;
        ref int rx = ref x;
        return rx = ref (new int[1])[0];
    }
}
