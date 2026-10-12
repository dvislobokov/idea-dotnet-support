// docs: cs8148.md #1; codes: CS8148
// CS8148.cs (11,29)

public class Base
{
    public virtual int GetNumber() { return 0; }
}

public class Derived : Base
{
    private int number;

    public override ref int GetNumber() { return ref number; }
}
