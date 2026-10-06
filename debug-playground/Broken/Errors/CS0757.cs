// CS0757: A partial method may not have multiple implementing declarations. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0757;

public partial class Importer
{
    partial void OnRow(int index);
    partial void OnRow(int index) { }
    partial void OnRow(int row) { } // ERROR CS0757

    public partial bool Validate(string line);
    public partial bool Validate(string line) => line.Length > 0;
    public partial bool Validate(string text) => true; // ERROR CS0757
    public partial bool Validate(int length);
    public partial bool Validate(int length) => length > 0;
}

public partial class Importer
{
    partial void OnDone(bool success);
    partial void OnDone(bool success) => Console.WriteLine(success);
}

public partial class Importer
{
    partial void OnDone(bool ok) { } // ERROR CS0757
}

public partial record Batch
{
    partial void Flush(int size);
    partial void Flush(long size);
    partial void Flush(int size) { }
    partial void Flush(long size) { }
}
