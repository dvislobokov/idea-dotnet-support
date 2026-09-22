// ASP.NET Core under the debugger: launch profiles (http / https / no browser), environment variables, a breakpoint in a request handler.
using Playground.Lib;

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();

var startedWith = new
{
    Environment = app.Environment.EnvironmentName,                                   // from the profile: Development; "Staging" from the run configuration wins
    Urls = Environment.GetEnvironmentVariable("ASPNETCORE_URLS"),                    // `applicationUrl` of the profile
    FromProfile = Environment.GetEnvironmentVariable("PLAYGROUND_FROM_PROFILE"),     // `environmentVariables` of the profile
};
app.Logger.LogInformation("Started with {Settings}", startedWith); // BP:web-start

app.MapGet("/", () => Results.Text(
    $"""
    Playground.Web
    environment: {startedWith.Environment}
    urls:        {startedWith.Urls}
    profile var: {startedWith.FromProfile}

    /orders/3   a handler to stop in
    /slow       a request that takes 5 s (Pause, then look at the threads)
    /fail       an exception inside a handler
    """));

app.MapGet("/orders/{count:int}", (int count) =>
{
    var lines = Enumerable.Range(1, count).Select(i => new OrderLine($"Item {i}", i * 1.5m, i)).ToList(); // BP:web-handler — open /orders/3 in the browser
    var total = Pricing.Total(lines, 10);
    return Results.Json(new { count, total, lines });
});

app.MapGet("/slow", async () =>
{
    await Task.Delay(5000);
    return "done";
});

app.MapGet("/fail", () =>
{
    throw new ShopException("Failed on purpose", 500); // BP:web-exception — stage 3: caught by the framework, so it is "user-unhandled"
#pragma warning disable CS0162
    return "never";
#pragma warning restore CS0162
});

app.Run();
