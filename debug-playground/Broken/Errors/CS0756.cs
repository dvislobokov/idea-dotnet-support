// CS0756: A partial method may not have multiple defining declarations. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0756;

public partial class OrderEvents
{
    partial void OnCreated(int id);
    partial void OnCreated(int id); // ERROR CS0756 CS0111
    partial void OnCreated(int id) { }

    partial void OnShipped(string carrier);
    partial void OnShipped(string tracking); // ERROR CS0756 CS0111
    partial void OnShipped(int days);
    partial void OnShipped(int days) { }

    public partial int Count(int status);
    public partial int Count(int status) => status;
    public partial int Count(long status);
    public partial int Count(long status) => (int)status;
}

public partial class OrderEvents
{
    partial void OnCancelled(int id);
    partial void OnCancelled(int id) { }
}

public partial struct Totals
{
    partial void Recalculate();
    partial void Recalculate(); // ERROR CS0756 CS0111
    partial void Recalculate(bool force);
}
