// docs: cs1933.md #1; codes: CS0133 CS1933
// cs1933.cs
using System.Linq;
using System.Collections;

class Program
{
    const IEnumerable e = from x in new[] { 1, 2, 3 } select x; // CS1933
    static int Main()
    {
        return 1;
    }
}
