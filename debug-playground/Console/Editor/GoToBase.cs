namespace Playground.Editor;

/// <summary>
/// Live check of Go to Base (Ctrl+U) on members (ROADMAP: «До уровня Rider и .NET Framework», stage 1). Places to press it are marked
/// <c>// TYPE:name</c>: put the caret where the comment says, press Ctrl+U, compare with EXPECT. Nothing is typed here and nothing is called,
/// the file only has to compile. Wait for the widget «Roslyn: DebugPlayground.sln» first: the supertypes come from the server.
/// </summary>
public interface IBaseShape
{
    double Area();
    string Name { get; }
    event EventHandler? Changed;
    double this[int index] { get; }
}

public interface INamedShape : IBaseShape
{
    void Rename(string name);
    void Rename(string name, bool notify);
}

public abstract class BaseShape : INamedShape
{
    public abstract double Area();
    public virtual string Name => "shape";
    public event EventHandler? Changed;
    public double this[int index] => index;
    public void Rename(string name) => Rename(name, notify: true);
    public void Rename(string name, bool notify) { if (notify) Changed?.Invoke(this, EventArgs.Empty); }
}

public class MiddleShape : BaseShape
{
    public override double Area() => 1;
}

public class GoToBase : MiddleShape, IDisposable
{
    // TYPE:go-to-base-member — caret on `Area` below, Ctrl+U. EXPECT: a jump to `Area` of MiddleShape (the nearest override), not to BaseShape
    // or IBaseShape and no list
    public override double Area() => 2;

    // TYPE:go-to-base-property — caret on `Name` below, Ctrl+U. EXPECT: a jump to `Name` of BaseShape
    public override string Name => "go to base";

    // TYPE:go-to-base-body — caret inside the body of Rename below (on `notify`), Ctrl+U. EXPECT: a list «Choose Base Symbol of Rename» with
    // `Rename(string name, bool notify)` of BaseShape and of INamedShape — the overload with two parameters only, not `Rename(string name)`
    public new void Rename(string name, bool notify)
    {
        Console.WriteLine(notify);
    }

    // TYPE:go-to-base-metadata — caret on `Dispose` below, Ctrl+U. EXPECT: no exception; IDisposable is metadata: a jump to the decompiled
    // IDisposable if the server gives its file, otherwise the hint «No base symbols of Dispose found…»
    public void Dispose() { }

    // TYPE:go-to-base-none — caret on `Own` below, Ctrl+U. EXPECT: the hint «No base symbols of Own found»
    public void Own() { }

    // TYPE:go-to-base-type — caret on `GoToBase` in the class header, Ctrl+U. EXPECT: as before, a list of the base types MiddleShape and IDisposable
}
