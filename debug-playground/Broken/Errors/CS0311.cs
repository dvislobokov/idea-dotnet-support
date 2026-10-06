// CS0311: The type 'X' cannot be used as type parameter 'T' in the generic type or method 'G<T>'. There is no implicit reference conversion from 'X' to 'C'. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS0311;

public interface IEntity { int Id { get; } }

public class Order : IEntity { public int Id => 1; }

public class SpecialOrder : Order { }

public class Customer { public int Id => 2; }

public class Animal { }

public class Dog : Animal { }

public class Repository<T> where T : IEntity { }

public class Kennel<T> where T : Animal { }

public class Sorter<T> where T : IComparable<T> { }

public class Version2 : IComparable<Version2> { public int CompareTo(Version2? other) => 0; }

public static class Factory
{
    public static T Create<T>() where T : Animal, new() => new T();
    public static void Save<T>(T item) where T : IEntity { }
}

public class Uses
{
    public void Run()
    {
        var orders = new Repository<Order>();
        var special = new Repository<SpecialOrder>();
        var customers = new Repository<Customer>(); // ERROR CS0311
        Kennel<Dog> dogs = new();
        Kennel<string>? strings = null; // ERROR CS0311
        var names = new Sorter<string>();
        var versions = new Sorter<Version2>();
        Sorter<Customer>? sorted = null; // ERROR CS0311
        var dog = Factory.Create<Dog>();
        var customer = Factory.Create<Customer>(); // ERROR CS0311
        Factory.Save<Order>(new Order());
        Factory.Save(new SpecialOrder());
        List<Kennel<Dog>> kennels = new();
        List<Kennel<Customer>>? wrong = null; // ERROR CS0311
        Factory.Save(new Order());
        Factory.Save(new Customer()); // ERROR CS0311
        Console.WriteLine($"{orders}{special}{customers}{dogs}{strings}{names}{versions}{sorted}{dog}{customer}{kennels}{wrong}");
    }
}
