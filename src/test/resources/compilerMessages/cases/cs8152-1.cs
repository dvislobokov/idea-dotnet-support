// docs: cs8152.md #1; codes: CS8152
// CS8152.cs (6,21)

public interface ITest
{
    ref readonly int M();
}
public class Test : ITest
{
    public int M() => 0;
} 
