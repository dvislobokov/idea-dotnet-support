namespace Playground.Editor;

/// <summary>
/// Live check of the grey text of the transformer (0.1.139; a build with <c>-PmlEnabled=true</c>, Settings | .NET | ML completion).
/// Type what a <c>// TYPE:name</c> comment says on the empty line under it, compare with EXPECT, undo with Ctrl+Z. Turn on
/// «Write every answer of the network to the journal» to see the answer, its confidence and the gate in .NET | Plugin Logs (category ml).
/// The file only has to compile; the model answers what it answers, EXPECT names the rule, not the exact words.
/// </summary>
public class MlInline
{
    private readonly Dictionary<int, Customer> _customers = new();

    public Result Get(int id)
    {
        var customer = _customers.GetValueOrDefault(id);
        if (customer == null)
        {
            // TYPE:ml-return-line — put the caret at the end of the `{` line above the empty line and press Enter (the grey text answers a change of the document; a caret move alone does not ask it), or press Alt+\ on the empty line. EXPECT: grey `return Result.NotFound();`
            // (or another statement) to its `;` — a certain start of an uncertain line may end with `;`; never cut to `return Result.NotFound`

        }
        return Result.Ok(customer);
    }

    public Result Find(int id)
    {
        var customer = _customers.GetValueOrDefault(id);
        if (customer == null)
        {
            // TYPE:ml-semicolon — type `return Result.NotFound()` (the editor pairs the `)`, type over it). EXPECT: grey `;` right after `)`,
            // no space before it, Tab inserts it; the journal says `gate 0.5 (statement end)`. With the paired `)` still ahead of the caret
            // (`NotFound(|)`): nothing, the `;` belongs after that `)`

        }
        return Result.Ok(customer);
    }

    public Result Check(int id)
    {
        var customer = _customers.GetValueOrDefault(id);
        // TYPE:ml-if-header — type `i`. EXPECT: grey `f (customer == null)` to the end of the line, without `{`: the grey text is one line
        // and the model expects the brace on the next line; the body is not suggested here (a multi-line suggestion is a separate feature)

        return Result.Ok(customer);
    }
}

public class MlChains
{
    public void Build()
    {
        // the style the model copies: a chain written line by line, the brackets closing a few lines down
        var first = new Pipeline()
            .Configure(options => options
                .AddStep("load")
                .AddStep("validate")
                .Finish());
        // TYPE:ml-open-bracket — type `var second = new Pipeline()`, Enter, then `.Con` on the new line. EXPECT: grey text that goes on to the
        // lines below until the brackets close — `.Configure(options => options` + `.AddStep(…)` lines + `.Finish());` — Tab inserts the whole
        // block; the journal says `N lines`. With "Continue to the next lines while a bracket is open" off: one line, as before

        Console.WriteLine(first.Steps);
    }
}

public sealed class Pipeline
{
    public int Steps { get; private set; }
    public Pipeline Configure(Func<PipelineOptions, PipelineOptions> configure) { Steps = configure(new PipelineOptions()).Count; return this; }
}

public sealed class PipelineOptions
{
    public int Count { get; private set; }
    public PipelineOptions AddStep(string name) { Count++; return this; }
    public PipelineOptions Finish() => this;
}

public sealed record Customer(int Id, string Name);

public sealed class Result
{
    public bool Found { get; private init; }
    public Customer? Customer { get; private init; }

    public static Result Ok(Customer? customer) => new() { Found = customer != null, Customer = customer };
    public static Result NotFound() => new() { Found = false };
}
