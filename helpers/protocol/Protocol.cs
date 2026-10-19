// The protocol of the helpers that stay running (cli/HelperConnection of the plugin): one JSON object per line.
//   plugin -> helper   {"id":1,"method":"evaluate","params":{...}}      a request
//                      {"method":"cancel","params":{"id":1}}            cancels a request that is still running
//   helper -> plugin   {"id":1,"result":...}  or  {"id":1,"error":{"message":"..."}}
//                      {"method":"log","params":{"level":"info","message":"..."}}   a line for the journal of the plugin
// Requests run concurrently; the end of stdin is the end of the helper. stderr is for whatever the libraries print.
//
// Every helper carries this file: in the repository its project links it from helpers/protocol, the build of the plugin puts a copy
// next to the Program.cs of each helper.

using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace DotNetSupport.Helpers;

/** What a handler throws for an error the plugin should show as it is, without a stack trace. */
public sealed class HelperException(string message) : Exception(message);

public static class HelperProtocol
{
    private static readonly object WriteLock = new();
    private static readonly TextWriter Output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true, NewLine = "\n" };

    public static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, WriteIndented = false };

    /** Reads requests until stdin ends; [handle] gets the method, the params (null when absent) and a token cancelled by "cancel". */
    public static async Task ServeAsync(Func<string, JsonElement?, CancellationToken, Task<object?>> handle)
    {
        var running = new ConcurrentDictionary<long, CancellationTokenSource>();
        var tasks = new List<Task>();
        using var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        while (await input.ReadLineAsync() is { } line)
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            JsonElement message;
            try { message = JsonDocument.Parse(line).RootElement; }
            catch (JsonException e) { Log("error", $"not a JSON line: {e.Message}"); continue; }

            var method = message.TryGetProperty("method", out var m) ? m.GetString() ?? "" : "";
            JsonElement? parameters = message.TryGetProperty("params", out var p) && p.ValueKind != JsonValueKind.Null ? p : null;
            if (!message.TryGetProperty("id", out var idElement))
            {
                if (method == "cancel" && parameters?.TryGetProperty("id", out var target) == true && running.TryGetValue(target.GetInt64(), out var source)) source.Cancel();
                continue;
            }
            var id = idElement.GetInt64();
            var cancellation = new CancellationTokenSource();
            running[id] = cancellation;
            tasks.Add(Task.Run(async () =>
            {
                try { Write(new JsonObject { ["id"] = id, ["result"] = JsonSerializer.SerializeToNode(await handle(method, parameters, cancellation.Token), Json) }); }
                catch (OperationCanceledException) { Error(id, "cancelled"); }
                catch (HelperException e) { Error(id, e.Message); }
                catch (Exception e) { Error(id, e.ToString()); }
                finally { running.TryRemove(id, out _); cancellation.Dispose(); }
            }));
            tasks.RemoveAll(t => t.IsCompleted);
        }
        // the plugin is gone (the IDE closed or died): what still runs is cancelled and given a moment; a request that does not stop (an
        // evaluation over a whole home folder) used to keep the helper and its `dotnet msbuild` alive for hours after the IDE
        foreach (var source in running.Values) try { source.Cancel(); } catch (ObjectDisposedException) { }
        await Task.WhenAny(Task.WhenAll(tasks), Task.Delay(TimeSpan.FromSeconds(3)));
        if (tasks.Any(t => !t.IsCompleted)) Exit();
    }

    private static readonly ConcurrentDictionary<System.Diagnostics.Process, bool> Children = new();

    /** A process the helper started for a request: killed with its tree when the helper ends before it. Dispose the result when it exits. */
    public static IDisposable Track(System.Diagnostics.Process process)
    {
        Children[process] = true;
        return new Untrack(process);
    }

    private sealed class Untrack(System.Diagnostics.Process process) : IDisposable
    {
        public void Dispose() => Children.TryRemove(process, out _);
    }

    // the children outlive the helper otherwise, whichever way it ends: the end of stdin, or the plugin stopping it (SIGTERM)
    static HelperProtocol() => AppDomain.CurrentDomain.ProcessExit += (_, _) => KillChildren();

    private static void KillChildren()
    {
        foreach (var child in Children.Keys) try { child.Kill(true); } catch (Exception) { }
    }

    /** Ends the helper now, its children with it. */
    public static void Exit(int code = 0)
    {
        KillChildren();
        Environment.Exit(code);
    }

    /** A line for the journal of the plugin (category of the helper): "info", "warn" or "error". */
    public static void Log(string level, string message) =>
        Write(new JsonObject { ["method"] = "log", ["params"] = new JsonObject { ["level"] = level, ["message"] = message } });

    private static void Error(long id, string message) => Write(new JsonObject { ["id"] = id, ["error"] = new JsonObject { ["message"] = message } });

    private static void Write(JsonNode node)
    {
        var text = node.ToJsonString(Json);
        lock (WriteLock) Output.WriteLine(text);
    }
}
