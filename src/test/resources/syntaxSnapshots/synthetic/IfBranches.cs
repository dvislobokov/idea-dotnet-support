namespace Synthetic.Directives;

public class Conditional
{
#if NET8_0_OR_GREATER
    public void Run(int x)
    {
#else
    public void Run()
    {
#endif
        System.Console.WriteLine(1);
    }

#if DEBUG
    private int _debugOnly;
#endif

#region Helpers
    public int Helper() => 1;
#endregion

#if false
    public void Disabled() { }
#endif

    public void After() { }
}
