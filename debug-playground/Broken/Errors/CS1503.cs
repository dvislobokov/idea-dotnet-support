// CS1503: Argument N: cannot convert from 'A' to 'B' (overloads: the first candidate that takes as many arguments). Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1503;

public class Product { }

public static class Labels
{
    public static void Label(this Product product, int number) { }
    public static void Label(this Product product, long number) { }
}

public class Shelf
{
    public void Place(int slot) { }
    public void Place(string name, int slot) { }
    public void Check(string code) { }
}

public class ColdShelf : Shelf
{
    public void Place(long slot) { }
    public void Check(int code) { }
}

public class Catalog
{
    public void Add(int id) { }
    public void Add(int id, string name) { }

    public void Find(int id) { }
    public void Find(string code) { }

    public void Rename(string code) { }
    public void Rename(int id) { }

    public void Put(int id, string name) { }
    public void Put(string name, int id) { }

    public void Keep<T>(T item, List<T> into) { }
    public void Keep(int item) { }

    public void Use(Product product, ColdShelf shelf, List<int> numbers)
    {
        Add("first"); // ERROR CS1503
        Add(1);
        Add(1, 2); // ERROR CS1503
        Add(1, "second");
        Find(1.5); // ERROR CS1503
        Find(1);
        Find("code");
        Rename(product); // ERROR CS1503
        Rename(2);
        Put(1.5, 2.5); // ERROR CS1503 CS1503
        Put(1, "name");
        Put("name", 1);
        shelf.Place("cold"); // ERROR CS1503
        shelf.Place(1.5); // ERROR CS1503
        shelf.Place("cold", 1);
        shelf.Place(1);
        shelf.Check(2.5); // ERROR CS1503
        shelf.Check("code");
        product.Label("first"); // ERROR CS1503
        product.Label(1);
        Keep("text", 1); // ERROR CS1503
        Keep("text", new List<string>());
        Keep(1);
        Math.Abs("negative"); // ERROR CS1503
        Math.Abs(-1);
        numbers.Take("three"); // ERROR CS1503
        numbers.Take(3);
    }
}
