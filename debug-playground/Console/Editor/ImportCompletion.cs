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
        // TYPE:import-silent-short — `Wr`. EXPECT: no `Console.WriteLine` yet: static members answer from three letters on (types do from the first)
        // TYPE:import-silent-type — `List<Str`, then `var t = typeof(Str`. EXPECT: NO static member from the index (`Conversion.Str` of
        // Microsoft.VisualBasic was offered in `Task<str>` before 0.1.48): only a type stands in `<…>`, `typeof(…)`, after `as` and `:` of a base list

        Console.WriteLine(name);
    }

    /// <summary>
    /// 0.1.87: types and extension methods without the language server — the types of the namespaces the file sees with nothing typed,
    /// and, from the first letter, what is not imported (of the framework, of the packages and of this solution) as rows
    /// «Name (in Namespace)» that add the using. The neighbour namespaces are in ImportCompletionTargets.cs.
    /// </summary>
    public void NotImported()
    {
        var name = "x";
        var numbers = new List<int> { 1, 2, 3 };

        // TYPE:import-types-visible — Ctrl+Space on the empty line, nothing typed. EXPECT: `List<>`, `Dictionary<,>`, `Task`, `File` among
        // the types (System.Collections.Generic, System.IO, System.Threading.Tasks are implicit here), and NO row «… (in …)»

        // TYPE:import-types-short — `Li`, choose `List<>`. EXPECT: `List<|>` with the caret between the brackets, no new using

        // TYPE:import-neighbour-type — `Recei`. EXPECT: `Receipt (in Playground.ImportCompletionTargets.Billing)`; choosing it writes `Receipt`
        // and adds `using Playground.ImportCompletionTargets.Billing;` at the top of the file

        // TYPE:import-neighbour-new — `var book = new ReceiptB`, choose `ReceiptBook<> (in …Billing)`. EXPECT: `new ReceiptBook<|>()` and the using

        // TYPE:import-library-type — `StringBu`. EXPECT: `StringBuilder (in System.Text)`; choosing it adds `using System.Text;`

        // TYPE:import-qualified — `Time`, choose `Timer (in System.Timers)`. EXPECT: `System.Timers.Timer` written whole and NO using: the file
        // sees `System.Threading.Timer` through the implicit usings, a using would make `Timer` ambiguous

        // TYPE:import-extension — `name.Yel`. EXPECT: `Yell() (in Playground.ImportCompletionTargets.Text) : string`; choosing it writes
        // `name.Yell()` and adds `using Playground.ImportCompletionTargets.Text;`

        // TYPE:import-extension-generic — `numbers.EveryO`. EXPECT: `EveryOther() (in Playground.ImportCompletionTargets.Text)` (a generic
        // `this IEnumerable<T>`); `numbers.Yel` gives NO `Yell`: it takes a string

        // TYPE:import-extension-library — `numbers.ToImm`. EXPECT: `ToImmutableArray() (in System.Collections.Immutable)`, `ToImmutableList()`…;
        // choosing one adds `using System.Collections.Immutable;`

        // TYPE:import-extension-silent — `numbers.` and wait. EXPECT: the members of List<int> and the LINQ methods, NO row «(in …)» until a
        // letter is typed; then they come (the list is made again at the first letter)

        Console.WriteLine(name + numbers.Count);
    }

    // TYPE:import-attribute — on the empty line under this comment type `[Obs`. EXPECT: `Obsolete` (System is imported) and
    // `ObsoletedOSPlatform (in System.Runtime.Versioning)`; choosing the second adds `using System.Runtime.Versioning;`

    public void Attributed()
    {
    }

    // TYPE:import-package — in a file of the project `Tests` (it refers to xunit) type `Equa` inside a test method.
    // EXPECT: `Assert.Equal` is offered there, and is NOT offered here: `Console` does not refer to xunit.

    // TYPE:import-stats — .NET | Suggestion Statistics after the markers above. EXPECT: the reason `not imported` in the last block,
    // with the number of the items chosen from the index.
}
