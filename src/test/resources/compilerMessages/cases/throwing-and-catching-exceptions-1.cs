// docs: throwing-and-catching-exceptions.md #1; codes: CS8359 CS8360
using System;
using System.IO;

try
{
    Console.WriteLine(File.ReadAllText("data.txt"));
}
catch (InvalidOperationException ex) when (false) // CS8359
{
    Console.Error.WriteLine(ex.Message);
}
catch (IOException ex)
{
    Console.Error.WriteLine(ex.Message);
}
