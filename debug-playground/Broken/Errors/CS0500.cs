// CS0500: 'C.M()' cannot declare a body because it is marked abstract. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0500;

public abstract class Parser
{
    public abstract int Parse(string text) { return text.Length; } // ERROR CS0500
    public abstract bool TryParse(string text) => text.Length > 0; // ERROR CS0500
    public abstract string Name { get { return "parser"; } } // ERROR CS0500
    public abstract int Limit { get; set => value = 0; } // ERROR CS0500
    public abstract int Depth { get; set; }
    public abstract void Reset();
    public virtual void Clear() { }
    public int Count() => 0;
}

public class JsonParser : Parser
{
    public override int Parse(string text) { return text.Length; }
    public override bool TryParse(string text) => text.Length > 0;
    public override string Name { get { return "json"; } }
    public override int Limit { get; set; }
    public override int Depth { get; set; }
    public override void Reset() { }
}
