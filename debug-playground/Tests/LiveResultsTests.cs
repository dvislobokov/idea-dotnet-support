using Xunit;
using Xunit.Abstractions;

namespace Playground.Tests;

/// <summary>
/// LIVE: results while the tests run. Run the class (▶ next to it, or Run on Tests.csproj) and watch the Unit Tests window:
/// EXPECT: every test appears as it starts (spinner) and turns green / red / grey about a second apart, not all at once at the end;
/// EXPECT: `Fails` is red with "Assert.Equal() Failure" and a stack trace whose `LiveResultsTests.cs:line N` is a link;
/// EXPECT: `Skipped` is grey with "Shows the reason"; `WritesOutput` shows its two lines when selected, and only there;
/// EXPECT: Stop during `Slow3` leaves it marked as terminated, the finished ones keep their state.
/// </summary>
public class LiveResultsTests(ITestOutputHelper output)
{
    [Fact]
    public void Slow1() => Thread.Sleep(1000); // LIVE:slow — one second each, the tree fills in step by step

    [Fact]
    public void Slow2() => Thread.Sleep(1000);

    [Fact]
    public void Slow3() => Thread.Sleep(3000); // LIVE:stop — press Stop while this one runs

    [Fact]
    public void Fails()
    {
        Thread.Sleep(500);
        Assert.Equal(2, 1 + 2); // LIVE:fail — red, the message and a clickable stack trace
    }

    [Fact(Skip = "Shows the reason")]
    public void Skipped() { } // LIVE:skip — grey, with the reason

    [Fact]
    public void WritesOutput()
    {
        output.WriteLine("first line of the test output"); // LIVE:output — under this test only
        Thread.Sleep(500);
        output.WriteLine("second line of the test output");
    }
}
