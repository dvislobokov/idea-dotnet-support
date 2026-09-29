using System.Diagnostics;
using System.Diagnostics.Tracing;
using System.Text;
using System.Text.Json;
using Microsoft.Diagnostics.NETCore.Client;
using Microsoft.Diagnostics.Symbols;
using Microsoft.Diagnostics.Tracing;
using Microsoft.Diagnostics.Tracing.Etlx;
using Microsoft.Diagnostics.Tracing.Parsers.Clr;

namespace DotNetSupport.AllocWatch;

// Which line of the source allocates how much, while the program runs.
//
//   dotnet AllocWatch.dll --pid <pid>[,<pid>...] --root <folder of the sources> [--window 10] [--interval 1] [--wait 20]
//
// Several process ids: the candidates, the likeliest first (`dotnet run` is a launcher, the program is its child); the first of them
// that has a diagnostic port is the one. A line of JSON a second goes to the standard output:
//
//   {"started":true,"pid":1234}
//   {"seconds":12.0,"window":10.0,"bytesPerSecond":20350972,"events":1910,"lines":[{"file":"…","line":40,"method":"…",
//     "bytesPerSecond":19284733,"objectsPerSecond":18401,"share":95.1,"totalBytes":193040000,"types":{"System.Byte[]":100}}]}
//   {"stopped":true,"reason":"the process has exited"}
//
// The numbers of a line are of the last [window] seconds; `totalBytes` is of the whole time. The watcher ends when the process does,
// or when its own standard input is closed: that is how the plugin stops it.
//
// The events are a sample (GCAllocationTick, about every 100 KB a thread has allocated): see tools/alloc-probe/README.md for what
// that means and what it costs. An allocation is charged to the first frame of its stack whose source lies under --root.
public static class Program
{
    private const long GcKeyword = 0x1;
    private const long LoaderKeyword = 0x8;
    private const long JitKeyword = 0x10;
    private const long JittedMethodIlToNativeMapKeyword = 0x20000;
    public const int MaxLines = 400;

    public static int Main(string[] args)
    {
        var pids = new List<int>();
        string? root = null;
        double window = 10, interval = 1, wait = 20;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--pid": pids.AddRange(args[++i].Split(',', StringSplitOptions.RemoveEmptyEntries).Select(int.Parse)); break;
                case "--root": root = Path.GetFullPath(args[++i]); break;
                case "--window": window = double.Parse(args[++i], System.Globalization.CultureInfo.InvariantCulture); break;
                case "--interval": interval = double.Parse(args[++i], System.Globalization.CultureInfo.InvariantCulture); break;
                case "--wait": wait = double.Parse(args[++i], System.Globalization.CultureInfo.InvariantCulture); break;
            }
        }
        if (pids.Count == 0)
        {
            Console.Error.WriteLine("usage: dotnet AllocWatch.dll --pid <pid>[,<pid>...] --root <folder> [--window 10] [--interval 1] [--wait 20]");
            return 2;
        }
        Console.OutputEncoding = new UTF8Encoding(false);

        var pid = Target(pids, TimeSpan.FromSeconds(wait));
        if (pid == 0)
        {
            Print(new Dictionary<string, object?> { ["stopped"] = true, ["reason"] = "no .NET process to attach to among " + string.Join(", ", pids) });
            return 3;
        }

        var client = new DiagnosticsClient(pid);
        var providers = new[]
        {
            new EventPipeProvider("Microsoft-Windows-DotNETRuntime", EventLevel.Verbose, GcKeyword | JitKeyword | LoaderKeyword | JittedMethodIlToNativeMapKeyword),
        };
        EventPipeSession session;
        try
        {
            session = client.StartEventPipeSession(providers, requestRundown: false, circularBufferMB: 256);
        }
        catch (Exception e)
        {
            Print(new Dictionary<string, object?> { ["stopped"] = true, ["reason"] = "could not attach to " + pid + ": " + e.Message });
            return 3;
        }

        using var source = TraceLog.CreateFromEventPipeSession(session, TraceLog.EventPipeRundownConfiguration.Enable(client));
        using var symbols = new SymbolReader(TextWriter.Null) { SecurityCheck = _ => true };
        var lines = new Lines(root, window, symbols);
        source.Clr.GCAllocationTick += lines.Add;

        Print(new Dictionary<string, object?> { ["started"] = true, ["pid"] = pid });
        var watch = Stopwatch.StartNew();
        using var timer = new Timer(_ => Print(lines.Report(watch.Elapsed.TotalSeconds)), null, TimeSpan.FromSeconds(interval), TimeSpan.FromSeconds(interval));

        // the plugin closes the input to stop the watcher; the session is let go, the program runs on
        var asked = false;
        var input = new Thread(() =>
        {
            try
            {
                while (Console.In.ReadLine() != null) { }
            }
            catch (Exception)
            {
                // no input at all: run until the process ends
                return;
            }
            asked = true;
            try { session.Stop(); } catch (Exception) { /* the process is gone already */ }
        }) { IsBackground = true };
        input.Start();

        var reason = "the process has exited";
        try
        {
            source.Process();
        }
        catch (Exception e)
        {
            reason = e.Message;
        }
        timer.Dispose();
        Print(lines.Report(watch.Elapsed.TotalSeconds));
        Print(new Dictionary<string, object?> { ["stopped"] = true, ["reason"] = asked ? "stopped" : reason });
        session.Dispose();
        return 0;
    }

    /// <summary>The first of the candidates that publishes a diagnostic port: a program that has only just started has none yet.</summary>
    private static int Target(List<int> candidates, TimeSpan wait)
    {
        var watch = Stopwatch.StartNew();
        while (true)
        {
            HashSet<int> published;
            try
            {
                published = DiagnosticsClient.GetPublishedProcesses().ToHashSet();
            }
            catch (Exception)
            {
                published = new HashSet<int>();
            }
            foreach (var candidate in candidates)
            {
                if (published.Contains(candidate)) return candidate;
            }
            if (watch.Elapsed > wait) return 0;
            Thread.Sleep(200);
        }
    }

    private static readonly object Output = new();

    private static void Print(Dictionary<string, object?> row)
    {
        lock (Output)
        {
            Console.Out.WriteLine(JsonSerializer.Serialize(row));
            Console.Out.Flush();
        }
    }
}

/// <summary>What every line has allocated: in the seconds of the window, and since the watcher was attached.</summary>
public sealed class Lines(string? root, double window, SymbolReader symbols)
{
    private sealed class Line
    {
        public string File = "";
        public int Number;
        public string Method = "";
        public long TotalBytes;
        public readonly Queue<(double At, long Bytes, double Objects, string Type)> Recent = new();
    }

    private readonly Dictionary<string, Line> _lines = new();
    private readonly Dictionary<ulong, (string File, int Number, string Method)?> _sources = new();
    private readonly Stopwatch _watch = Stopwatch.StartNew();
    private long _events;
    private long _bytes;
    private readonly Queue<(double At, long Bytes)> _recent = new();

    public void Add(GCAllocationTickTraceData tick)
    {
        var amount = tick.AllocationAmount64;
        var at = _watch.Elapsed.TotalSeconds;
        (string File, int Number, string Method)? found = null;
        try
        {
            for (var frame = tick.CallStack(); frame != null; frame = frame.Caller)
            {
                var address = frame.CodeAddress;
                if (!_sources.TryGetValue(address.Address, out var source))
                {
                    source = Source(address);
                    // a method that is not jitted yet at the time of the first event gets its name later: not remembered as unknown
                    if (source != null || address.Method != null) _sources[address.Address] = source;
                }
                if (source == null) continue;
                if (root != null && !source.Value.File.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
                found = source;
                break;
            }
        }
        catch (Exception)
        {
            // a stack that cannot be read is an event that is not charged to a line
        }

        long size;
        try { size = tick.ObjectSize; } catch (Exception) { size = 0; }
        var type = string.IsNullOrEmpty(tick.TypeName) ? "?" : tick.TypeName;

        lock (_lines)
        {
            _events++;
            _bytes += amount;
            _recent.Enqueue((at, amount));
            if (found == null) return;
            var key = found.Value.File + ":" + found.Value.Number;
            if (!_lines.TryGetValue(key, out var line)) _lines[key] = line = new Line { File = found.Value.File, Number = found.Value.Number, Method = found.Value.Method };
            line.TotalBytes += amount;
            line.Recent.Enqueue((at, amount, size > 0 ? (double)amount / size : 0, type));
        }
    }

    public Dictionary<string, object?> Report(double seconds)
    {
        lock (_lines)
        {
            var now = _watch.Elapsed.TotalSeconds;
            var from = now - window;
            var span = Math.Max(0.5, Math.Min(window, now));
            while (_recent.Count > 0 && _recent.Peek().At < from) _recent.Dequeue();
            var rows = new List<(long Bytes, Dictionary<string, object?> Row)>();
            long charged = 0;
            foreach (var line in _lines.Values)
            {
                while (line.Recent.Count > 0 && line.Recent.Peek().At < from) line.Recent.Dequeue();
                charged += line.Recent.Sum(entry => entry.Bytes);
            }
            foreach (var line in _lines.Values)
            {
                if (line.Recent.Count == 0) continue;
                var bytes = line.Recent.Sum(entry => entry.Bytes);
                var objects = line.Recent.Sum(entry => entry.Objects);
                var types = line.Recent.GroupBy(entry => entry.Type).Select(group => (Type: group.Key, Bytes: group.Sum(entry => entry.Bytes)))
                    .OrderByDescending(type => type.Bytes).Take(3).ToDictionary(type => type.Type, type => (object)Math.Round(100.0 * type.Bytes / bytes));
                rows.Add((bytes, new Dictionary<string, object?>
                {
                    ["file"] = line.File,
                    ["line"] = line.Number,
                    ["method"] = line.Method,
                    ["bytesPerSecond"] = Math.Round(bytes / span),
                    ["objectsPerSecond"] = Math.Round(objects / span),
                    ["share"] = charged == 0 ? 0 : Math.Round(100.0 * bytes / charged, 1),
                    ["totalBytes"] = line.TotalBytes,
                    ["types"] = types,
                }));
            }
            return new Dictionary<string, object?>
            {
                ["seconds"] = Math.Round(seconds, 1),
                ["window"] = Math.Round(span, 1),
                ["bytesPerSecond"] = Math.Round(_recent.Sum(entry => entry.Bytes) / span),
                ["events"] = _events,
                ["lines"] = rows.OrderByDescending(row => row.Bytes).Take(Program.MaxLines).Select(row => row.Row).ToList(),
            };
        }
    }

    private (string File, int Number, string Method)? Source(TraceCodeAddress address)
    {
        try
        {
            var line = address.GetSourceLine(symbols);
            var file = line?.SourceFile?.BuildTimeFilePath;
            if (line == null || string.IsNullOrEmpty(file) || line.LineNumber <= 0) return null;
            // 0xFEEFEE: a line the compiler has hidden on purpose
            if (line.LineNumber >= 0xFEEFEE) return null;
            return (Path.GetFullPath(file), line.LineNumber, address.FullMethodName);
        }
        catch (Exception)
        {
            return null;
        }
    }
}
