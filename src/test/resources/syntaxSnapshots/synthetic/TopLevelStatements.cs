using System;
using System.Linq;
using static System.Math;

var numbers = new[] { 1, 2, 3 };
var total = numbers.Sum();
Console.WriteLine($"Total: {total}");

int Twice(int x) => x * 2;

if (total > 3)
{
    Console.WriteLine(Twice(total));
}

using (var reader = new System.IO.StringReader("x"))
{
    Console.WriteLine(reader.ReadToEnd());
}

record Settings(string Name);

class Helper
{
    public static int Square(int x) => x * x;
}
