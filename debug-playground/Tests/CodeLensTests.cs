using Playground.Lib;
using Xunit;

namespace Playground.Tests;

/// <summary>
/// Live check of the "Run | Debug" Code Vision above tests (NativeCSharpCodeLens, option Code Lens → «Run and debug tests» of
/// Settings | .NET | Language Server). The lens runs the very configuration the ▶ gutter runs. Marker comments stand above their
/// declarations, so the lenses are visible in any Code Vision position.
/// </summary>
// TYPE:lens-test-class — EXPECT: "Run | Debug" at the class below (beside "no usages"); click Run → `dotnet test --filter FullyQualifiedName~Playground.Tests.CodeLensTests.` runs both tests, the Run tool window shows them green
public class CodeLensTests
{
    // TYPE:lens-test-method — EXPECT: "Run | Debug" at `[Fact]`; click Debug → the test host starts under the debugger and stops at BP:lens-test if a breakpoint is set there
    [Fact]
    public void TotalOfOneLine()
    {
        var total = Pricing.Total(new[] { new OrderLine("Tea", 4.5m, 2) }, 0); // BP:lens-test
        Assert.Equal(9m, total);
    }

    // TYPE:lens-test-theory — EXPECT: "Run | Debug" at `[Theory]`; Run runs the two rows; Code Lens → «Run and debug tests» off: the "Run | Debug" lenses of this file vanish at the next pass, "N usages" stay
    [Theory]
    [InlineData(0, 9)]
    [InlineData(50, 4.5)]
    public void TotalWithDiscount(decimal discount, decimal expected)
    {
        Assert.Equal(expected, Pricing.Total(new[] { new OrderLine("Tea", 4.5m, 2) }, discount));
    }

    // TYPE:lens-test-helper — EXPECT: "no usages" only, no Run | Debug: not a test
    private static int Helper() => 1;
}
