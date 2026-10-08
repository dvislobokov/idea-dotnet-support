// docs: cs8152.md #2; codes: CS8152
public class Test : ITest
{
    int m;
    public ref readonly int M() => ref m;
}
