using Playground.Lib;
using Xunit;

namespace Playground.TestsMtp;

/// <summary>Debug of tests on Microsoft.Testing.Platform: the test application waits for the debugger the plugin attaches.</summary>
public class PlatformTests
{
    [Fact]
    public void TotalOfTwoLines()
    {
        var total = Pricing.Total(new[] { new OrderLine("Tea", 4.5m, 2), new OrderLine("Cake", 3m, 1) }, 0); // BP:mtp-test — a breakpoint here, Debug at ▶ of the test. EXPECT: the stop here (no stop in [External Code] first: the platform's Debugger.Break is skipped), F8 → `total` = 12; Resume → the test is green in the Run window, the debug session ends by itself. Before 0.1.287 the tests ran without the debugger and a notification said so
        Assert.Equal(12m, total);
    }
}
