// CS0236: A field initializer cannot reference the non-static field, method, or property 'C.M'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0236;

public class Connection
{
    private string host = "localhost";
    private int port = 80;
    private string url = host + ":80"; // ERROR CS0236
    private string address = $"{Prefix}:{Port()}";
    private int next = Port() + 1;
    private readonly Func<string> describe = () => host; // ERROR CS0236
    private static string Prefix => "tcp";
    private static int Port() => 80;
    public string Host { get; } = host; // ERROR CS0236
    public static string Default = url; // ERROR CS0236
    public int Timeout { get; set; } = 30;
    public string Endpoint => host + ":" + port;
    private int Retries() => 3;
    private int attempts = Retries(); // ERROR CS0236

    public Connection()
    {
        url = host + ":" + port;
    }

    public override string ToString() => $"{url}{address}{next}{describe()}{attempts}";
}

public class Base
{
    protected int Size = 1;
}

public class Derived : Base
{
    private int doubled = Size * 2; // ERROR CS0236
    public int Doubled => doubled;
}

public class Primary(int size)
{
    public int Size { get; } = size;
}

public class Numbers : List<int>
{
    private int size = Count; // ERROR CS0236
    private object copy = ToArray(); // ERROR CS0236
    private static int Zero = 0;
    private int first = Zero;
    public int Size => size + copy.GetHashCode() + first;
}

public class Failure : Exception
{
    private string text = Message; // ERROR CS0236
    private string empty = string.Empty;
    public string Text => text + empty;
}
