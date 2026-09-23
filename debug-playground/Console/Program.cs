// The entry point is top-level statements on purpose: breakpoints have to work here as well as in methods.
// Arguments pick scenarios (see Scenarios.cs); without arguments every safe one runs in order.
using Playground;

Console.OutputEncoding = System.Text.Encoding.UTF8;
var names = args.Length > 0 ? args : Scenarios.Safe; // BP:main — the first stop: Main Thread, frame Program.<Main>$
Console.WriteLine($"Scenarios: {string.Join(", ", names)}");

foreach (var name in names)
{
    Console.WriteLine($"--- {name}");
    await Scenarios.Run(name); // Step Into (F7) goes to Scenarios.Run, Step Over (F8) runs the scenario
}

foreach (var item in names)
{

}

if (names == null)
{

}
else
{

}

Console.WriteLine("Done.");
return 0;
