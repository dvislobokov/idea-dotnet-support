// docs: cs8149.md #2; codes: CS8149
delegate int E();

class C
{
    static int i;
    static void M()
    {
        var e = new E(() => i);
    }
}
