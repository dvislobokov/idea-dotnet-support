using Playground.Lib;
using Xunit;

namespace Playground.Tests;

/// <summary>Stage 5: Debug of a test stops here. The test host waits for the debugger, the plugin attaches to it.</summary>
public class PricingTests
{
    [Fact]
    public void TotalAppliesTheDiscount()
    {
        var lines = new[] { new OrderLine("Tea", 4.5m, 2), new OrderLine("Cup", 12m, 1) };
        var total = Pricing.Total(lines, 10); // BP:test — Debug from the gutter or from the Unit Tests window stops here; F7 goes into Lib
        Assert.Equal(18.9m, total);
    }

    [Theory]
    [InlineData(0, 21)]
    [InlineData(100, 0)]
    public void DiscountBounds(decimal discount, decimal expected)
    {
        var total = Pricing.Total(new[] { new OrderLine("Tea", 4.5m, 2), new OrderLine("Cup", 12m, 1) }, discount); // BP:theory — once per row
        Assert.Equal(expected, total);
    }
}
