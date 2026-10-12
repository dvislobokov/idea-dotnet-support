// docs: cs8174.md #2; codes: CS8174
class C
{
    void M()
    {
        int i = 0;
        for (ref int rx = ref i; i < 5; i++) { }
    }
}
