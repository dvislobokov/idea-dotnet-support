// docs: cs1750.md #1; codes: CS1750
public struct S
{
    public override string ToString() { return "S::ToString"; }
}
public class A
{
    public static S Goo(S p = 42) { return p; }
}
