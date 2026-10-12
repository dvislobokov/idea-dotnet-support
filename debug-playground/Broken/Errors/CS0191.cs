// CS0191: A readonly field cannot be assigned to (except in a constructor or init-only setter of the type in which the field is defined or a variable initializer). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0191;

public class Connection
{
    private readonly string _host = "localhost";
    private readonly int _port;
    private int _retries;

    public Connection(int port, Connection other)
    {
        _port = port;
        this._port += 1;
        _host = "db";
        other._port = 1; // ERROR CS0191
    }

    public int Timeout { get => _port; init => _port = value; }

    public void Reconnect()
    {
        _retries++;
        _port = 5432; // ERROR CS0191
        _port++; // ERROR CS0191
        Action reset = () => _retries = 0;
        reset();
    }
}

public class Pool
{
    public readonly int Size;

    public Pool() { Size = 4; }

    public void Grow(Pool other)
    {
        other.Size = 8; // ERROR CS0191
        Size = 2; // ERROR CS0191
    }
}
