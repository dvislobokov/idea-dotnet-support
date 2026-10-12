namespace Playground.Editor;

/// <summary>
/// Live check of the completion of what is not imported (ROADMAP: «Свой индекс сборок»): static members of the types of the
/// framework and of the packages, by the bare name, with the <c>using</c> that is missing. Lines to type on are marked
/// <c>// TYPE:name</c>: the caret on the empty line under the marker, type what the comment says, choose the item with Enter or Tab,
/// compare with EXPECT, undo (Ctrl+Z). The items come from the index of assemblies, not from the language server: they are there
/// before «Roslyn: DebugPlayground.sln» appears. The first start of the IDE builds the indexer (a few seconds, see idea.log:
/// «The indexer of assemblies is built for net…») and indexes the assemblies of the solution («Index of assemblies: …»).
/// The project has ImplicitUsings: `System`, `System.IO`, `System.Linq`, `System.Threading.Tasks` need no using here.
/// </summary>
public class ImportCompletion
{
    public void Statements()
    {
        var name = "x";

        // TYPE:import-void — `WriteLi`, choose `Console.WriteLine`. EXPECT: `Console.WriteLine(|);` with the caret inside, the parameter info
        // open, and NO new using: System is implicit in this project

        // TYPE:import-using — `Stopw`, choose `Stopwatch.StartNew`. EXPECT: `Stopwatch.StartNew();` with the caret after it (the method takes
        // nothing) and `using System.Diagnostics;` added at the top of the file, among the usings if there are any

        // TYPE:import-value — `var path = Combi`, choose `Path.Combine`. EXPECT: `var path = Path.Combine(|);` — the semicolon though Combine
        // returns a value: the call ends the declaration

        // TYPE:import-property — `var now = UtcN`, choose `DateTime.UtcNow`. EXPECT: `var now = DateTime.UtcNow` without parentheses

        // TYPE:import-generic — `var none = Empt`, choose `Array.Empty<>` (or `Enumerable.Empty<>`). EXPECT: `Array.Empty<|>();` with the caret
        // between the angle brackets

        // TYPE:import-expected — `int length = Ma`. EXPECT: `Math.Max`, `Math.Min` and the other members that give an int are above the
        // ones that do not

        // TYPE:import-silent-dot — `name.WriteLi`. EXPECT: NO `Console.WriteLine` in the list: after a dot the members of `name` are listed
        // TYPE:import-silent-name — `string WriteLi`. EXPECT: NO `Console.WriteLine`: this is the name of a variable
        // TYPE:import-silent-short — `Wr`. EXPECT: nothing from the index yet: it answers from three letters on

        Console.WriteLine(name);
    }

    // TYPE:import-package — in a file of the project `Tests` (it refers to xunit) type `Equa` inside a test method.
    // EXPECT: `Assert.Equal` is offered there, and is NOT offered here: `Console` does not refer to xunit.

    // TYPE:import-stats — .NET | Suggestion Statistics after the markers above. EXPECT: the reason `not imported` in the last block,
    // with the number of the items chosen from the index.
}
