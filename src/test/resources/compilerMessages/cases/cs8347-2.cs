// docs: cs8347.md #2; codes: CS8347
public ref struct Entity {}

class Program
{
    internal static Entity CaptureArgument(scoped ref int customArg)
    {
        return new Entity();
    }

    public static Entity Example()
    {
        int localVariable = 1;
        return CaptureArgument(ref localVariable);    // OK.
    }
}
