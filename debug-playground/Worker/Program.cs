// A worker that talks to Web: the "Compound" scenarios of the README (one build of Lib for both, "Wait for" Web).
// Without the "Wait for" of its run configuration the first request may come before Web listens: "Web is not up yet" in the console.
using Playground.Lib;

var web = args.FirstOrDefault() ?? Environment.GetEnvironmentVariable("PLAYGROUND_WEB_URL") ?? "http://localhost:5187";
using var http = new HttpClient { BaseAddress = new Uri(web), Timeout = TimeSpan.FromSeconds(5) };
Console.WriteLine($"Playground.Worker {Environment.ProcessId}, Web at {web}");

for (var round = 1; ; round++)
{
    var local = Pricing.Total([new OrderLine("Local", 2.5m, round)], 0); // BP:worker-round — Debug of the compound stops here and in Web
    try
    {
        var answer = await http.GetStringAsync($"orders/{round % 5 + 1}");
        Console.WriteLine($"round {round}: local total {local}, Web answered {answer.Length} chars");
    }
    catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
    {
        Console.WriteLine($"round {round}: Web is not up yet ({e.Message})");
    }
    await Task.Delay(TimeSpan.FromSeconds(3));
}
