// CS0513: 'C.M()' is abstract but it is contained in non-abstract type 'C'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0513;

public class Report
{
    public abstract string Render(int width, string title); // ERROR CS0513
    public abstract int Pages { get; } // ERROR CS0513
    public abstract string this[int page] { get; set; } // ERROR CS0513 CS0513
    public abstract event EventHandler Printed; // ERROR CS0513
    public virtual string Header() => "header";
    public string Footer() => "footer";
}

public abstract class Template
{
    public abstract string Render(int width, string title);
    public abstract int Pages { get; }
    public abstract event EventHandler Printed;
    public virtual string Header() => "header";
}

public record Document(string Title)
{
    public abstract int Size(); // ERROR CS0513
}

public abstract record Draft(string Title)
{
    public abstract int Size();
}

public interface IExporter
{
    abstract string Export();
    string Name { get; }
}

public partial class Chapter
{
    public abstract string Render(); // ERROR CS0513
    public abstract int Pages { get; } // ERROR CS0513
}

public partial class Chapter
{
    public string Title => "chapter";
}

public abstract partial class Section
{
    public abstract string Render();
}

public partial class Section { }
