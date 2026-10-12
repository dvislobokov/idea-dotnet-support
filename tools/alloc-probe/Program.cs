using System.Diagnostics;
using System.Diagnostics.Tracing;
using System.Text.Json;
using Microsoft.Diagnostics.NETCore.Client;
using Microsoft.Diagnostics.Symbols;
using Microsoft.Diagnostics.Tracing;
using Microsoft.Diagnostics.Tracing.Etlx;
using Microsoft.Diagnostics.Tracing.Parsers.Clr;

namespace DotNetSupport.AllocProbe;

// Which line of the source allocates how much, measured in a running process.
//
//   dotnet AllocProbe.dll --pid <pid> [--seconds 10] [--root <folder of the sources>] [--out <file.json>] [--keep <file.nettrace>]
//
// The runtime raises GCAllocationTick about every 100 KB a thread has allocated, with the type of the object that crossed the mark
// and the stack it was allocated on. So the events are a sample: a line that allocates a tenth of the bytes gets about a tenth of
// the events, and a line that allocates little may get none at all in ten seconds. The stack is walked from the allocation outwards
// to the first frame whose source file lies under --root: that is the line of the user's code the allocation is charged to, be it
// made there or in the framework code that line has called.
public static class Program
{
    private const long GcKeyword = 0x1;
    private const long JitKeyword = 0x10;
    private const long LoaderKeyword = 0x8;
    private const long JittedMethodIlToNativeMapKeyword = 0x20000;

    public static int Main(string[] args)
    {
        var pid = 0;
        var seconds = 10;
        string? root = null, output = null, keep = null;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--pid": pid = int.Parse(args[++i]); break;
                case "--seconds": seconds = int.Parse(args[++i]); break;
                case "--root": root = Path.GetFullPath(args[++i]); break;
                case "--out": output = args[++i]; break;
                case "--keep": keep = args[++i]; break;
            }
        }
        if (pid == 0)
        {
            Console.Error.WriteLine("usage: dotnet AllocProbe.dll --pid <pid> [--seconds 10] [--root <folder>] [--out <file.json>] [--keep <file.nettrace>]");
            return 2;
        }

        var trace = keep ?? Path.Combine(Path.GetTempPath(), $"alloc-probe-{pid}-{Environment.ProcessId}.nettrace");
        var watch = Stopwatch.StartNew();
        var collected = Collect(pid, seconds, trace);
        var collectMs = watch.ElapsedMilliseconds;

        watch.Restart();
        var report = Analyze(trace, root, collected);
        report["collectMs"] = collectMs;
        report["analyzeMs"] = watch.ElapsedMilliseconds;
        report["traceBytes"] = new FileInfo(trace).Length;
        if (keep == null)
        {
            File.Delete(trace);
            File.Delete(Path.ChangeExtension(trace, ".etlx"));
        }

        var json = JsonSerializer.Serialize(report, new JsonSerializerOptions { WriteIndented = true });
        if (output != null) File.WriteAllText(output, json);
        Console.WriteLine(json);
        return 0;
    }

    /// <summary>The events of [seconds] seconds, written as they come; the rundown at the end names the methods of the stacks.</summary>
    private static double Collect(int pid, int seconds, string file)
    {
        var client = new DiagnosticsClient(pid);
        var providers = new[]
        {
            new EventPipeProvider("Microsoft-Windows-DotNETRuntime", EventLevel.Verbose, GcKeyword | JitKeyword | LoaderKeyword | JittedMethodIlToNativeMapKeyword),
        };
        using var session = client.StartEventPipeSession(providers, requestRundown: true, circularBufferMB: 256);
        var watch = Stopwatch.StartNew();
        using var target = new FileStream(file, FileMode.Create, FileAccess.Write, FileShare.Read);
        var copy = session.EventStream.CopyToAsync(target);
        Thread.Sleep(TimeSpan.FromSeconds(seconds));
        var measured = watch.Elapsed.TotalSeconds;
        session.Stop();
        copy.Wait(TimeSpan.FromSeconds(60));
        return measured;
    }

    private sealed class Line
    {
        public string File = "";
        public int Number;
        public string Method = "";
        public long Bytes;
        public double Objects;
        public int Events;
        public readonly Dictionary<string, long> Types = new();
    }

    private static Dictionary<string, object?> Analyze(string trace, string? root, double seconds)
    {
        var etlx = TraceLog.CreateFromEventPipeDataFile(trace);
        using var log = new TraceLog(etlx);
        using var symbols = new SymbolReader(TextWriter.Null) { SecurityCheck = _ => true };

        var lines = new Dictionary<string, Line>();
        var sources = new Dictionary<ulong, (string File, int Number, string Method)?>();
        long events = 0, bytes = 0, withStack = 0, charged = 0, chargedBytes = 0;
        var unresolved = new Dictionary<string, int>();

        foreach (var data in log.Events)
        {
            if (data is not GCAllocationTickTraceData tick) continue;
            events++;
            var amount = tick.AllocationAmount64;
            bytes += amount;
            var stack = tick.CallStack();
            if (stack == null) continue;
            withStack++;

            (string File, int Number, string Method)? found = null;
            var innermost = stack.CodeAddress.FullMethodName;
            for (var frame = stack; frame != null; frame = frame.Caller)
            {
                var address = frame.CodeAddress;
                if (!sources.TryGetValue(address.Address, out var source))
                {
                    source = Source(address, symbols);
                    sources[address.Address] = source;
                }
                if (source == null) continue;
                if (root != null && !Path.GetFullPath(source.Value.File).StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
                found = source;
                break;
            }
            if (found == null)
            {
                unresolved[innermost] = unresolved.GetValueOrDefault(innermost) + 1;
                continue;
            }
            charged++;
            chargedBytes += amount;
            var key = found.Value.File + ":" + found.Value.Number;
            if (!lines.TryGetValue(key, out var line)) lines[key] = line = new Line { File = found.Value.File, Number = found.Value.Number, Method = found.Value.Method };
            line.Events++;
            line.Bytes += amount;
            // the event tells the size of the object that crossed the mark: the bytes since the event before are that many of them
            var size = ObjectSize(tick);
            if (size > 0) line.Objects += (double)amount / size;
            var type = string.IsNullOrEmpty(tick.TypeName) ? "?" : tick.TypeName;
            line.Types[type] = line.Types.GetValueOrDefault(type) + amount;
        }

        var rows = lines.Values.OrderByDescending(line => line.Bytes).Select(line => new Dictionary<string, object?>
        {
            ["file"] = line.File,
            ["line"] = line.Number,
            ["method"] = line.Method,
            ["events"] = line.Events,
            ["bytesPerSecond"] = Math.Round(line.Bytes / seconds),
            ["objectsPerSecond"] = Math.Round(line.Objects / seconds),
            ["share"] = chargedBytes == 0 ? 0 : Math.Round(100.0 * line.Bytes / chargedBytes, 1),
            ["types"] = line.Types.OrderByDescending(type => type.Value).Take(3).ToDictionary(type => type.Key, type => Math.Round(100.0 * type.Value / line.Bytes)),
        }).ToList();

        return new Dictionary<string, object?>
        {
            ["seconds"] = Math.Round(seconds, 2),
            ["events"] = events,
            ["eventsWithStack"] = withStack,
            ["eventsCharged"] = charged,
            ["bytesPerSecond"] = Math.Round(bytes / seconds),
            ["chargedBytesPerSecond"] = Math.Round(chargedBytes / seconds),
            ["lines"] = rows,
            ["notCharged"] = unresolved.OrderByDescending(entry => entry.Value).Take(8).ToDictionary(entry => entry.Key, entry => entry.Value),
        };
    }

    private static (string File, int Number, string Method)? Source(TraceCodeAddress address, SymbolReader symbols)
    {
        try
        {
            var line = address.GetSourceLine(symbols);
            var file = line?.SourceFile?.BuildTimeFilePath;
            if (line == null || string.IsNullOrEmpty(file) || line.LineNumber <= 0) return null;
            // 0xFEEFEE: a line the compiler has hidden on purpose
            if (line.LineNumber >= 0xFEEFEE) return null;
            return (file, line.LineNumber, address.FullMethodName);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static long ObjectSize(GCAllocationTickTraceData tick)
    {
        try
        {
            return tick.ObjectSize;
        }
        catch (Exception)
        {
            return 0;
        }
    }
}
