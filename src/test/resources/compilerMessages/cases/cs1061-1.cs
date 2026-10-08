// docs: cs1061.md #1; codes: CS1061
public class Person
{
    private string _name;

    public Person(string name) => _name = name;

    // Person has one method, called WriteName.
    public void WriteName()
    {
        System.Console.WriteLine(_name);
    }
}

public class Program
{
    public static void Main()
    {
        var p = new Person("PersonName");

        // The following call fails because Person does not have
        // a method called DisplayName.
        p.DisplayName(); // CS1061
    }
}
