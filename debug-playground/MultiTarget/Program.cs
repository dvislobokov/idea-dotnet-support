// A project with two target frameworks: the debugger has to start the output of the framework chosen in the toolbar
// (Debug | .NET 9.0 / .NET 10.0), or of the first one when "Default" is chosen.
using System.Runtime.InteropServices;

var framework = RuntimeInformation.FrameworkDescription; // BP:multitarget — check the value against the toolbar
Console.WriteLine($"Running on {framework}");
