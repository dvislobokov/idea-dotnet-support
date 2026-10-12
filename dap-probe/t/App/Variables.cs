namespace DebugTarget;

[Flags] enum Perm { None = 0, Read = 1, Write = 2, Exec = 4 }

struct Point
{
    public int X;
    public int Y;
    public Point(int x, int y) { X = x; Y = y; }
    public override string ToString() => $"({X},{Y})";
}

record Person(string Name, int Age);

class Base
{
    protected int baseField = 7;
    public virtual string Kind => "base";
}

class Derived : Base
{
    public int Own = 1;
    private readonly string _secret = "s3cret";
    public override string Kind => "derived";
    public static int Counter = 42;
    public string Throws => throw new InvalidOperationException("getter failed");
    public int SideEffect { get { Counter++; return Counter; } }
    public int this[int index] => index * 10;
    public string Greet(string name, int times = 1) => string.Concat(Enumerable.Repeat("hi " + name + ";", times));
    public static int Twice(int x) => x * 2;
}

static class Variables
{
    public static void Run()
    {
        int i = 42; long big = long.MaxValue; double d = 3.5; float f = 1.25f; decimal m = 12.34m; bool flag = true; char c = 'x'; byte b = 255;
        string s = "hello \"quoted\"\n\tПривет"; string? nothing = null; string empty = "";
        string longText = new string('a', 5000);
        int? maybe = 5; int? none = null;
        Perm perm = Perm.Read | Perm.Write; DayOfWeek day = DayOfWeek.Friday;
        var point = new Point(1, 2); var tuple = (Id: 1, Name: "t");
        var person = new Person("Ann", 30); var anon = new { A = 1, B = "two" };
        int[] numbers = Enumerable.Range(0, 250).ToArray(); int[,] grid = { { 1, 2, 3 }, { 4, 5, 6 } }; int[][] jagged = { new[] { 1 }, new[] { 2, 3 } };
        var list = new List<string> { "a", "b", "c" }; var dict = new Dictionary<string, int> { ["one"] = 1, ["two"] = 2 }; var set = new HashSet<int> { 1, 2, 3 };
        var date = new DateTime(2024, 5, 1, 10, 0, 0, DateTimeKind.Utc); var guid = Guid.Parse("6f9619ff-8b86-d011-b42d-00c04fc964ff"); var span = TimeSpan.FromMinutes(90);
        object boxed = 5; dynamic dyn = "dynamic string"; Base asBase = new Derived(); var derived = new Derived();
        IEnumerable<int> lazy = numbers.Where(n => n % 2 == 0);
        Func<int, int> twice = x => x * 2; Task<int> task = Task.FromResult(9); Exception ex = new ArgumentException("bad", "param");
        Console.WriteLine("variables ready"); // BP:variables
        Console.WriteLine($"{i} {s.Length} {derived.Own} {Derived.Counter} {numbers.Length} {list.Count}"); // BP:variables-after
    }
}
