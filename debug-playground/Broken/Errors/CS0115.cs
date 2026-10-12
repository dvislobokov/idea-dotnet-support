// CS0115: 'M': no suitable method found to override. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0115;

public abstract class Shape
{
    public abstract double Area();
    public virtual string Describe(int precision) => Area().ToString("F" + precision);
    public virtual string Name => "shape";
    public virtual int this[int index] => index;
    public virtual event EventHandler? Changed;
    protected void Raise() => Changed?.Invoke(this, EventArgs.Empty);
}

public interface IPrintable
{
    string Print() => "printable";
}

public class Circle : Shape, IPrintable
{
    public double Radius { get; init; }
    public override double Area() => Math.PI * Radius * Radius;
    public override string Describe(int precision) => "circle " + base.Describe(precision);
    public override string Describe(string format) => format; // ERROR CS0115
    public override double Perimeter() => 2 * Math.PI * Radius; // ERROR CS0115
    public override string Name => "circle";
    public override string Title => "circle"; // ERROR CS0115
    public override int this[int index] => index * 2;
    public override int this[string key] => key.Length; // ERROR CS0115
    public override event EventHandler? Changed;
    public override event EventHandler? Resized; // ERROR CS0115
    public override string Print() => "circle"; // ERROR CS0115
    public override string ToString() => Name;
    public override bool Equals(object? obj) => obj is Circle c && c.Radius == Radius;
    public override int GetHashCode() => Radius.GetHashCode();
}

public class Box<T>
{
    public virtual T? Get(int index) => default;
}

public class IntBox : Box<int>
{
    public override int Get(int index) => index;
    public override int Get(long index) => (int)index; // ERROR CS0115
}

public class Failure : Exception
{
    public override string Message => "failure";
    public override string Reason => "failure"; // ERROR CS0115
}

public record Person(string Name)
{
    public override string ToString() => Name;
    protected virtual bool PrintMembers(System.Text.StringBuilder builder) => true;
}

public record Employee(string Name, string Company) : Person(Name)
{
    protected override bool PrintMembers(System.Text.StringBuilder builder) => true;
    public override string ToString() => Name + " at " + Company;
}

public struct Point
{
    public int X;
    public override string ToString() => X.ToString();
    public override int Compare(Point other) => X - other.X; // ERROR CS0115
}

public abstract class Store<T>
{
    public virtual void Map<U>(U item) { }
    public virtual void Load(ref int count) { }
    public virtual bool Find(out int index) { index = 0; return false; }
    public virtual void Read(in int position) { }
    public virtual void Join((int Id, string Name) row) { }
}

public class Shelf : Store<string>
{
    public override void Map<X>(X item) { }
    public override void Map(int item) { } // ERROR CS0115
    public override void Load(ref int count) { }
    public override void Load(int count) { } // ERROR CS0115
    public override bool Find(ref int index) => false; // ERROR CS0115
    public override void Read(ref int position) { } // ERROR CS0115
    public override void Join((int, string) row) { }
    public override void Join((int Key, string Text) row, int extra) { } // ERROR CS0115
}
