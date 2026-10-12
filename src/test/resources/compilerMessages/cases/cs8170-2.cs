// docs: cs8170.md #2; codes: CS8170
delegate void D();

struct Program
{
    public event D d;

    public D M()
    {
        return d;
    }
}
