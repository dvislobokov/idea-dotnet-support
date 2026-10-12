// CS0111: Type 'T' already defines a member called 'M' with the same parameter types. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0111;

public interface IStore
{
    void Save(string key);
}

public class Store : IStore
{
    public void Save(string key) { }
    public void Save(string name) { } // ERROR CS0111
    public int Save(string value, int retries = 0) => retries;
    public bool Load(int id) => true;
    public string Load(int key) => ""; // ERROR CS0111
    public static void Load(long id) { }
    public void Clear(params int[] ids) { }
    public void Clear(int[] keys) { } // ERROR CS0111
    public void Find<T>(T item) { }
    public void Find<U>(U value) { } // ERROR CS0111
    public void Find(int item) { }
    public void Find<T>(int item) { }
    public void Name(string text) { }
    public void Name(string? other) { } // ERROR CS0111
    public void Pair((int a, int b) pair) { }
    public void Pair((int x, int y) other) { } // ERROR CS0111
    public void Count(ref int total) { }
    public void Count(int total) { }
    public void Items(List<int> items) { }
    public void Items(System.Collections.Generic.List<int> list) { } // ERROR CS0111
    public void Items(List<long> items) { }
    void IStore.Save(string key) { }

    public Store() { }
    public Store(int capacity) { }
    public Store(int size) { } // ERROR CS0111

    public int this[int index] => index;
    public int this[int position] => position; // ERROR CS0111
    public int this[string key] => 0;
}

public static class StoreExtensions
{
    public static void Reset(this Store store) { }
    public static void Reset(Store other) { } // ERROR CS0111
}

public class Session(string user)
{
    public string User => user;
    public Session(string name) : this(name) { } // ERROR CS0111
    public Session() : this("") { }
}

public partial class Hooks
{
    partial void OnSave(int id);
    partial void OnSave(int id) { }
    partial void OnLoad(int id);
    public void OnLoad(int id) { } // ERROR CS0111
    partial void OnClose(string reason);
    partial void OnClose(string text); // ERROR CS0756 CS0111
    partial void OnClose(int code);
}

public readonly struct Vector(int x, int y)
{
    public int X => x;
    public int Y => y;
    public static Vector operator +(Vector left, Vector right) => new(left.X + right.X, left.Y + right.Y);
    public static Vector operator +(Vector a, Vector b) => a; // ERROR CS0111
    public static Vector operator -(Vector value) => new(-value.X, -value.Y);
    public static Vector operator -(Vector left, Vector right) => new(left.X - right.X, left.Y - right.Y);
    public static Vector op_Addition(Vector left, Vector right) => left; // ERROR CS0111
    public static Vector op_Addition(Vector left, int right) => left;
    public static bool operator ==(Vector left, Vector right) => left.X == right.X;
    public static bool operator !=(Vector left, Vector right) => !(left == right);
    public static bool operator ==(Vector a, Vector b) => true; // ERROR CS0111
    public static implicit operator (int, int)(Vector value) => (value.X, value.Y);
    public static (int, int) op_Implicit(Vector value) => default; // ERROR CS0111
    public override bool Equals(object? obj) => obj is Vector other && this == other;
    public override int GetHashCode() => X ^ Y;
}

public interface IClock
{
    DateTime Now();
    int this[int offset] { get; }
}

public class Clock : IClock // ERROR CS8646 CS8646
{
    DateTime IClock.Now() => DateTime.Now;
    DateTime IClock.Now() => DateTime.UtcNow; // ERROR CS0111
    int IClock.this[int offset] => offset;
    int IClock.this[int position] => 0; // ERROR CS0111
    public DateTime Now() => DateTime.Now;
}

public class Mixed
{
    public int Total;
    public void Total(int amount) { } // ERROR CS0102
    public void Total(int other) { } // ERROR CS0102 CS0111
    public void Total(long amount) { } // ERROR CS0102
}
