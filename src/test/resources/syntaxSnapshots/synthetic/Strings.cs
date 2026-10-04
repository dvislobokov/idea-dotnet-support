namespace Synthetic.Strings;

public class Templates
{
    private const string Open = "{";
    private const string Close = "}";
    private readonly string _verbatim = @"C:\{dir}\";
    private readonly char _brace = '{';

    public string Interpolated(int x) => $"{{x}} = {x} {(x > 0 ? "{" : "}")}";

    public string Raw() => """
        { "json": true }
        """;

    public string RawInterpolated(int x) => $$"""
        { "value": {{x}} }
        """;

    public string Nested(int x)
    {
        return $"outer {$"inner {x}"} {Open}";
    }

    // a } in a comment
    /* and a { in a block comment */
    public void AfterStrings() { }
}
