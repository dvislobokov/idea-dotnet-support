// docs: cs8148.md #2; codes: CS8148
public class Base
{
    public virtual int GetNumber() { return 0; }
}

public class Derived : Base
{
    private int number;

    public override int GetNumber() { return number; }
}
