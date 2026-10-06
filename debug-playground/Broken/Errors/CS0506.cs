// CS0506: 'D.M()': cannot override inherited member 'B.M()' because it is not marked virtual, abstract, or override. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0506;

public class Repository
{
    public void Save(string item) { }
    public virtual void Delete(string item) { }
    public int Count => 0;
    public virtual int Capacity => 10;
    public string Load(int id) => id.ToString();
    public virtual string Load(string key) => key;
}

public class CachedRepository : Repository
{
    public override void Save(string item) { } // ERROR CS0506
    public override void Delete(string item) { }
    public override int Count => 1; // ERROR CS0506
    public override int Capacity => 20;
    public override string Load(int id) => "cached"; // ERROR CS0506
    public override string Load(string key) => "cached " + key;
}

public class Base
{
    public virtual void Run() { }
}

public class Hiding : Base
{
    public new void Run() { }
}

public class AfterHiding : Hiding
{
    public override void Run() { } // ERROR CS0506
}

public class Holder<T>
{
    public void Put(T item) { }
    public virtual void Take(T item) { }
}

public class TextHolder : Holder<string>
{
    public override void Put(string item) { } // ERROR CS0506
    public override void Take(string item) { }
}

public class Names : List<string>
{
    public override void Add(string item) { } // ERROR CS0506
    public override string ToString() => string.Join(", ", this);
}
