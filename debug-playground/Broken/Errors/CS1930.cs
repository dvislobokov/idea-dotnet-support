// CS1930: The range variable 'x' has already been declared. Lines marked `// ERROR CSxxxx` must show that error in the editor (and in
// `dotnet build`), every other line no error at all: tools/diag/check_errors.py roslyn|ide checks both. Not compiled with Broken.
namespace DebugPlayground.Broken.Errors.CS1930;

public class Orders
{
    public void Query(int[] ids, string[] names)
    {
        var pairs = from id in ids
                    from id in names // ERROR CS1930
                    select id;
        var lets = from id in ids
                   let id = 2 // ERROR CS1930
                   select id;
        var joined = from id in ids
                     join id in ids on id equals id // ERROR CS1930
                     select id;
        var grouped = from id in ids
                      join other in ids on id equals other into id // ERROR CS1930
                      select id;
        var continued = from id in ids
                        select id into id
                        where id > 0
                        select id;
        var fine = from id in ids
                   from name in names
                   let length = name.Length
                   join other in ids on id equals other into matches
                   select new { id, name, length, matches };
        Console.WriteLine(pairs.Count() + lets.Count() + joined.Count() + grouped.Count() + continued.Count() + fine.Count());
    }
}
