// DiagnosticsHelper: a helper that stays running (helpers/README.md), on ClrMD.
//   runtimes   {pids:[...]}                    -> {"<pid>": {flavor: "desktop"|"core"|null, version, bitness} | null (not accessible)}
//   retained   {dumpPath, top?, maxObjects?}   -> types with count / shallow / retained bytes, the top dominators of the heap
//   dominators {dumpPath, address?, top?}      -> the objects an object (or the roots, without an address) immediately dominates
//   close      {dumpPath}                      -> forgets the analysis of a dump (and cancels it), so the file can be deleted
// By hand: `dotnet DiagnosticsHelper.dll runtimes 1234 5678`, `... retained <dump> [top]`, `... dominators <dump> [address]`.

using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Diagnostics.Runtime;

namespace DotNetSupport.Helpers;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        if (args.Contains("--serve"))
        {
            await HelperProtocol.ServeAsync(Handle);
            return 0;
        }
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: DiagnosticsHelper --serve | runtimes <pid>... | retained <dump> [top] | dominators <dump> [address]");
            return 2;
        }
        // a run by hand, for probes: the same handlers, the answer printed as JSON
        var parameters = args[0] switch
        {
            "runtimes" => JsonSerializer.SerializeToElement(new { pids = args.Skip(1).Select(int.Parse).ToArray() }),
            "retained" => JsonSerializer.SerializeToElement(new { dumpPath = args[1], top = args.Length > 2 ? int.Parse(args[2]) : 20 }),
            "dominators" => JsonSerializer.SerializeToElement(new { dumpPath = args[1], address = args.ElementAtOrDefault(2) }),
            _ => default,
        };
        var watch = Stopwatch.StartNew();
        try
        {
            var result = await Handle(args[0], parameters, CancellationToken.None);
            Console.WriteLine(JsonSerializer.Serialize(result, new JsonSerializerOptions(HelperProtocol.Json) { WriteIndented = true }));
            Console.Error.WriteLine($"{watch.ElapsedMilliseconds} ms, peak working set {Process.GetCurrentProcess().PeakWorkingSet64 >> 20} MB");
            return 0;
        }
        catch (HelperException e)
        {
            Console.Error.WriteLine(e.Message);
            return 1;
        }
    }

    private static async Task<object?> Handle(string method, JsonElement? parameters, CancellationToken cancellation)
    {
        switch (method)
        {
            case "runtimes":
                var pids = parameters?.TryGetProperty("pids", out var list) == true ? list.EnumerateArray().Select(it => it.GetInt32()).ToList() : [];
                return Runtimes.Of(pids, cancellation);
            case "retained":
                var analysis = await Heaps.Analyze(Text(parameters, "dumpPath"), Number(parameters, "maxObjects", Heaps.DefaultMaxObjects), cancellation);
                return analysis.Summary(Number(parameters, "top", 50));
            case "dominators":
                var dump = await Heaps.Analyze(Text(parameters, "dumpPath"), Number(parameters, "maxObjects", Heaps.DefaultMaxObjects), cancellation);
                var address = parameters?.TryGetProperty("address", out var a) == true && a.ValueKind == JsonValueKind.String ? a.GetString() : null;
                return dump.Dominated(address, Number(parameters, "top", 200));
            case "close":
                return Heaps.Close(Text(parameters, "dumpPath"));
            default:
                throw new HelperException($"unknown method `{method}`");
        }
    }

    private static string Text(JsonElement? parameters, string name) =>
        parameters?.TryGetProperty(name, out var value) == true && value.ValueKind == JsonValueKind.String ? value.GetString()! : throw new HelperException($"`{name}` is missing");

    private static int Number(JsonElement? parameters, string name, int fallback) =>
        parameters?.TryGetProperty(name, out var value) == true && value.ValueKind == JsonValueKind.Number ? value.GetInt32() : fallback;
}

/** Which CLR a process has loaded. */
public sealed record ProcessRuntime(string? Flavor, string? Version, int? Bitness);

/**
 * Which CLR is in a process, by its modules first: `clr.dll` / `mscorwks.dll` is the desktop CLR, `coreclr.dll` / `libcoreclr` the one of
 * .NET; the version is that of the module file. A helper of 64 bits sees the modules of a process of 32 bits only with
 * `TH32CS_SNAPMODULE32` (`Process.Modules` lists the 64-bit ones of WOW64 there), so on Windows the snapshot of the tool help is taken directly.
 * ClrMD only when the file of the module gives no version. A process that cannot be opened (another user, protected) is null, not an error.
 */
public static class Runtimes
{
    public static Dictionary<string, ProcessRuntime?> Of(IEnumerable<int> pids, CancellationToken cancellation)
    {
        // a few at a time: the list of all the processes of the machine (hundreds) is what Attach to Process asks for
        var found = new ConcurrentDictionary<int, ProcessRuntime?>();
        var distinct = pids.Distinct().ToList();
        Parallel.ForEach(distinct, new ParallelOptions { MaxDegreeOfParallelism = Math.Clamp(Environment.ProcessorCount, 2, 8), CancellationToken = cancellation }, pid =>
        {
            try { found[pid] = Of(pid); }
            catch (Exception) { found[pid] = null; }
        });
        return distinct.ToDictionary(pid => pid.ToString(), pid => found.GetValueOrDefault(pid));
    }

    private static ProcessRuntime? Of(int pid)
    {
        int? bitness;
        List<string>? modules;
        if (OperatingSystem.IsWindows()) (modules, bitness) = Windows.Modules(pid);
        else
        {
            using var process = Process.GetProcessById(pid);
            modules = process.Modules.Cast<ProcessModule>().Select(it => it.FileName).ToList();
            bitness = Environment.Is64BitOperatingSystem ? 64 : 32;
        }
        if (modules == null) return null;
        string? desktop = null, core = null;
        foreach (var path in modules)
        {
            var name = Path.GetFileName(path).ToLowerInvariant();
            if (name is "clr.dll" or "mscorwks.dll") desktop ??= path;
            else if (name is "coreclr.dll" or "libcoreclr.so" or "libcoreclr.dylib") core ??= path;
        }
        // a process with both (a desktop host that has started .NET too) is offered to the debugger of the desktop CLR first
        var module = desktop ?? core;
        if (module == null) return new ProcessRuntime(null, null, bitness);
        if (desktop != null) return new ProcessRuntime("desktop", FileVersion(desktop) ?? ClrMdVersion(pid), bitness);
        return new ProcessRuntime("core", SharedFrameworkVersion(core!) ?? ClrMdVersion(pid) ?? FileVersion(core!), bitness);
    }

    /** `...\shared\Microsoft.NETCore.App\9.0.6\coreclr.dll` -> 9.0.6; the file version of coreclr (9.0.625.26613) is not the version of .NET. */
    private static string? SharedFrameworkVersion(string path)
    {
        var directory = Path.GetFileName(Path.GetDirectoryName(path));
        return directory != null && char.IsDigit(directory.FirstOrDefault()) && Version.TryParse(directory.Split('-')[0], out _) ? directory : null;
    }

    /** clr.dll: 4.8.9300.0 (its version text adds "built by: ..."). */
    private static string? FileVersion(string path)
    {
        try
        {
            var info = FileVersionInfo.GetVersionInfo(path);
            return info.FileMajorPart > 0 ? $"{info.FileMajorPart}.{info.FileMinorPart}.{info.FileBuildPart}.{info.FilePrivatePart}" : null;
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static string? ClrMdVersion(int pid)
    {
        try
        {
            using var target = DataTarget.AttachToProcess(pid, suspend: false);
            return target.ClrVersions.FirstOrDefault()?.Version.ToString();
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static class Windows
    {
        private const uint QueryLimitedInformation = 0x1000, SnapModule = 0x08, SnapModule32 = 0x10;
        private const int BadLength = 24;

        /** One walk over the loader list of the process, so the names come with the list (psapi reads the list again for every name). */
        public static (List<string>?, int?) Modules(int pid)
        {
            int? bitness = null;
            var process = OpenProcess(QueryLimitedInformation, false, pid);
            if (process == IntPtr.Zero) return (null, null);
            try { bitness = !Environment.Is64BitOperatingSystem ? 32 : IsWow64Process(process, out var wow64) ? (wow64 ? 32 : 64) : null; }
            finally { CloseHandle(process); }

            // a process that is starting or loading a module at this moment fails with ERROR_BAD_LENGTH: once more, then give up
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var snapshot = CreateToolhelp32Snapshot(SnapModule | SnapModule32, pid);
                if (snapshot == new IntPtr(-1))
                {
                    if (Marshal.GetLastWin32Error() == BadLength) continue;
                    return (null, bitness);
                }
                try
                {
                    var names = new List<string>();
                    var entry = new ModuleEntry { Size = (uint)Marshal.SizeOf<ModuleEntry>() };
                    for (var more = Module32FirstW(snapshot, ref entry); more; more = Module32NextW(snapshot, ref entry)) names.Add(entry.Path);
                    return (names, bitness);
                }
                finally
                {
                    CloseHandle(snapshot);
                }
            }
            return (null, bitness);
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct ModuleEntry
        {
            public uint Size, ModuleId, ProcessId, GlobalUsage, ProcessUsage;
            public IntPtr BaseAddress;
            public uint BaseSize;
            public IntPtr Module;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string Name;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string Path;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint access, bool inherit, int pid);

        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool IsWow64Process(IntPtr process, out bool wow64);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr CreateToolhelp32Snapshot(uint flags, int pid);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool Module32FirstW(IntPtr snapshot, ref ModuleEntry entry);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool Module32NextW(IntPtr snapshot, ref ModuleEntry entry);
    }
}

public sealed record TypeRetained(string Name, string MethodTable, int Count, long Shallow, long Retained);

/** An object of the dominator tree: [Children] is how many objects it dominates immediately, [Root] the kind of root that holds it, if any. */
public sealed record DominatorObject(string Address, string Type, long Size, long Retained, int Children, string? Root);

public sealed record HeapSummary(
    int Objects, long Bytes, int UnreachableObjects, long UnreachableBytes, List<TypeRetained> Types, List<DominatorObject> Dominators, int OmittedDominators,
    long ElapsedMs, long PeakWorkingSet);

public sealed record Dominated(DominatorObject? Parent, List<DominatorObject> Children, int Omitted, long OmittedRetained);

/** Analyses of dumps, one per file, kept until `close`: drilling down the dominators takes no new pass over the dump. */
public static class Heaps
{
    public const int DefaultMaxObjects = 20_000_000; // about 100 bytes an object while the tree is built: 2 GB

    private sealed class Entry
    {
        public readonly CancellationTokenSource Cancellation = new();
        public readonly Lazy<Task<HeapAnalysis>> Analysis;

        public Entry(string path, int maxObjects)
        {
            var token = Cancellation.Token;
            Analysis = new(() => Task.Run(() => HeapAnalysis.Compute(path, maxObjects, token), CancellationToken.None));
        }
    }

    private static readonly ConcurrentDictionary<string, Entry> Entries = new();

    private static string Key(string path) => Path.GetFullPath(path).ToLowerInvariant();

    public static async Task<HeapAnalysis> Analyze(string path, int maxObjects, CancellationToken cancellation)
    {
        if (!File.Exists(path)) throw new HelperException($"No dump at {path}");
        var entry = Entries.GetOrAdd(Key(path), _ => new Entry(path, maxObjects));
        var task = entry.Analysis.Value;
        try
        {
            return await task.WaitAsync(cancellation);
        }
        catch (Exception) when (task.IsFaulted || task.IsCanceled)
        {
            Entries.TryRemove(new KeyValuePair<string, Entry>(Key(path), entry)); // the next request tries again
            throw;
        }
    }

    /** Cancels an analysis that is running and waits a little for it to let the file go. */
    public static bool Close(string path)
    {
        if (!Entries.TryRemove(Key(path), out var entry)) return false;
        entry.Cancellation.Cancel();
        if (entry.Analysis.IsValueCreated) try { entry.Analysis.Value.Wait(TimeSpan.FromSeconds(5)); } catch (Exception) { }
        GC.Collect();
        return true;
    }
}

/**
 * The dominator tree of the heap of a dump: object A dominates B when every path from the roots to B goes through A, so B is freed with
 * A; the retained size of A is the size of what it dominates and itself. The graph is kept in arrays indexed by the order of addresses
 * (no object per object: the heap of a server has millions), the tree is found by the iterative algorithm of Cooper, Harvey and
 * Kennedy over the reverse postorder, which takes two or three passes on real heaps. Node [n] is the root of everything, with an edge to
 * every object a root of ClrMD (handle, stack, finalizer queue) refers to. Objects no root reaches (garbage the GC has not collected yet)
 * are in no tree and counted apart.
 */
public sealed class HeapAnalysis
{
    private readonly ulong[] addresses;
    private readonly int[] typeOf;
    private readonly uint[] sizes;
    private readonly long[] retained;
    private readonly int[] childStart, children;
    private readonly string[] typeNames;
    private readonly ulong[] methodTables;
    private readonly Dictionary<int, string> rootKinds;
    private readonly int reachable;
    private readonly int unreachable;
    private readonly long unreachableBytes;
    private readonly long elapsedMs;

    private HeapAnalysis(ulong[] addresses, int[] typeOf, uint[] sizes, long[] retained, int[] childStart, int[] children, string[] typeNames, ulong[] methodTables,
        Dictionary<int, string> rootKinds, int reachable, int unreachable, long unreachableBytes, long elapsedMs)
    {
        this.addresses = addresses; this.typeOf = typeOf; this.sizes = sizes; this.retained = retained; this.childStart = childStart; this.children = children;
        this.typeNames = typeNames; this.methodTables = methodTables; this.rootKinds = rootKinds; this.reachable = reachable; this.unreachable = unreachable;
        this.unreachableBytes = unreachableBytes; this.elapsedMs = elapsedMs;
    }

    private int Root => addresses.Length;

    /** What ClrMD may keep of the dump in memory while reading it. */
    private const long DumpCacheBytes = 256L << 20;

    /** The graph of the heap as read from the dump; the dump is closed once it is read, so its caches are gone before the tree is built. */
    private sealed class Graph
    {
        public ulong[] Addresses = [];
        public uint[] Sizes = [];
        public int[] TypeOf = [];
        public string[] TypeNames = [];
        public ulong[] MethodTables = [];
        public int[] EdgeStart = [];
        public int[] Edges = [];
        public Dictionary<int, string> RootKinds = new();
    }

    public static HeapAnalysis Compute(string path, int maxObjects, CancellationToken cancellation)
    {
        var watch = Stopwatch.StartNew();
        void Phase(string what) => HelperProtocol.Log("info", $"{Path.GetFileName(path)}: {what} ({watch.ElapsedMilliseconds} ms, {Environment.WorkingSet >> 20} MB)");

        var graph = Read(path, maxObjects, cancellation, Phase);
        var addresses = graph.Addresses;
        var sizes = graph.Sizes;
        var typeOf = graph.TypeOf;
        var typeNames = graph.TypeNames;
        var methodTables = graph.MethodTables;
        var rootKinds = graph.RootKinds;
        var edgeStart = graph.EdgeStart;
        var edgeArray = graph.Edges;
        graph = null;
        var n = addresses.Length;

        // 3. depth-first from the root, without recursion: the postorder numbers
        var nodes = n + 1;
        var post = new int[nodes];
        Array.Fill(post, -1);
        var order = new int[nodes];
        var count = 0;
        {
            // as deep as the longest chain of references (a linked list of a million nodes is that deep): grown when needed
            var stack = new int[1024];
            var position = new int[1024];
            var depth = 0;
            stack[0] = n;
            position[0] = edgeStart[n];
            post[n] = -2;
            while (depth >= 0)
            {
                var v = stack[depth];
                if (position[depth] < edgeStart[v + 1])
                {
                    var w = edgeArray[position[depth]++];
                    if (post[w] != -1) continue;
                    post[w] = -2;
                    depth++;
                    if (depth == stack.Length)
                    {
                        Array.Resize(ref stack, stack.Length * 2);
                        Array.Resize(ref position, position.Length * 2);
                    }
                    stack[depth] = w;
                    position[depth] = edgeStart[w];
                }
                else
                {
                    post[v] = count;
                    order[count++] = v;
                    depth--;
                }
            }
        }
        var reachable = count - 1;
        cancellation.ThrowIfCancellationRequested();

        // 4. the predecessors among the reachable nodes, in postorder numbers
        var predStart = new int[count + 1];
        for (var v = 0; v < nodes; v++)
        {
            if (post[v] < 0) continue;
            for (var e = edgeStart[v]; e < edgeStart[v + 1]; e++) if (post[edgeArray[e]] >= 0) predStart[post[edgeArray[e]] + 1]++;
        }
        for (var i = 0; i < count; i++) predStart[i + 1] += predStart[i];
        var preds = new int[predStart[count]];
        {
            var fill = (int[])predStart.Clone();
            for (var v = 0; v < nodes; v++)
            {
                if (post[v] < 0) continue;
                for (var e = edgeStart[v]; e < edgeStart[v + 1]; e++) if (post[edgeArray[e]] >= 0) preds[fill[post[edgeArray[e]]]++] = post[v];
            }
        }
        edgeArray = null!;
        edgeStart = null!;
        Phase($"{reachable:N0} reachable objects");

        // 5. Cooper-Harvey-Kennedy: idom in postorder numbers, the root is count - 1
        var rootPost = count - 1;
        var doms = new int[count];
        Array.Fill(doms, -1);
        doms[rootPost] = rootPost;
        var changed = true;
        var passes = 0;
        while (changed)
        {
            changed = false;
            passes++;
            for (var b = rootPost - 1; b >= 0; b--)
            {
                var idom = -1;
                for (var p = predStart[b]; p < predStart[b + 1]; p++)
                {
                    var pred = preds[p];
                    if (doms[pred] == -1) continue;
                    if (idom == -1) { idom = pred; continue; }
                    var x = pred;
                    var y = idom;
                    while (x != y)
                    {
                        while (x < y) x = doms[x];
                        while (y < x) y = doms[y];
                    }
                    idom = x;
                }
                if (doms[b] != idom) { doms[b] = idom; changed = true; }
                if ((b & 0xFFFFF) == 0) cancellation.ThrowIfCancellationRequested();
            }
        }
        preds = null!;
        Phase($"dominator tree in {passes} passes");

        // 6. retained sizes: the dominator of a node finishes after it in the depth-first search, so the postorder adds children first
        var retained = new long[nodes];
        for (var i = 0; i < rootPost; i++) retained[order[i]] += sizes[order[i]];
        for (var i = 0; i < rootPost; i++) retained[order[doms[i]]] += retained[order[i]];

        // 7. the tree as arrays of children, kept for drilling down
        var childStart = new int[nodes + 1];
        for (var i = 0; i < rootPost; i++) childStart[order[doms[i]] + 1]++;
        for (var v = 0; v < nodes; v++) childStart[v + 1] += childStart[v];
        var children = new int[rootPost];
        {
            var fill = (int[])childStart.Clone();
            for (var i = 0; i < rootPost; i++) children[fill[order[doms[i]]]++] = order[i];
        }
        long unreachableBytes = 0;
        for (var v = 0; v < n; v++) if (post[v] < 0) unreachableBytes += sizes[v];
        post = null!;
        order = null!;
        doms = null!;
        GC.Collect(); // the arrays of the build are many times what is kept: given back before the next request

        Phase("retained sizes");
        return new HeapAnalysis(addresses, typeOf, sizes, retained, childStart, children, typeNames, methodTables, rootKinds,
            reachable, n - reachable, unreachableBytes, watch.ElapsedMilliseconds);
    }

    private static Graph Read(string path, int maxObjects, CancellationToken cancellation, Action<string> phase)
    {
        // the cache of the dump is what takes most of the memory of a reader of ClrMD: a pass in the order of addresses needs little of it
        using var target = OpenDump(path);
        var clr = target.ClrVersions.FirstOrDefault() ?? throw new HelperException("The dump has no .NET runtime");
        using var runtime = clr.CreateRuntime();
        var heap = runtime.Heap;
        if (!heap.CanWalkHeap) throw new HelperException("The heap of the dump cannot be walked: it was taken in the middle of a garbage collection");

        // 1. the objects: an index is a binary search away from an address
        var addressList = new GrowingArray<ulong>();
        var sizeList = new GrowingArray<uint>();
        var typeList = new GrowingArray<int>();
        var typeIndex = new Dictionary<ulong, int>();
        var types = new List<ClrType>();
        foreach (var item in heap.EnumerateObjects())
        {
            var type = item.Type;
            if (type == null || item.IsFree) continue;
            if (addressList.Count >= maxObjects)
                throw new HelperException($"The heap has more than {maxObjects:N0} objects: retained sizes are not computed for a heap that large (the helper would need several GB)");
            if (!typeIndex.TryGetValue(type.MethodTable, out var t))
            {
                typeIndex[type.MethodTable] = t = types.Count;
                types.Add(type);
            }
            addressList.Add(item.Address);
            sizeList.Add((uint)Math.Min(item.Size, uint.MaxValue));
            typeList.Add(t);
            if ((addressList.Count & 0xFFFF) == 0) cancellation.ThrowIfCancellationRequested();
        }
        var graph = new Graph { Addresses = addressList.ToArray(), Sizes = sizeList.ToArray(), TypeOf = typeList.ToArray() };
        addressList = null!; sizeList = null!; typeList = null!;
        var n = graph.Addresses.Length;
        var addresses = graph.Addresses;
        var sorted = true;
        for (var i = 1; i < n && sorted; i++) sorted = addresses[i - 1] < addresses[i];
        if (!sorted)
        {
            // the segments (regions) of the heap do not come in the order of their addresses
            var permutation = new int[n];
            for (var i = 0; i < n; i++) permutation[i] = i;
            Array.Sort(addresses, permutation);
            var sizes = new uint[n];
            var typeOf = new int[n];
            for (var i = 0; i < n; i++) { sizes[i] = graph.Sizes[permutation[i]]; typeOf[i] = graph.TypeOf[permutation[i]]; }
            graph.Sizes = sizes;
            graph.TypeOf = typeOf;
        }
        graph.TypeNames = types.Select(it => it.Name ?? $"<unknown type {it.MethodTable:x}>").ToArray();
        graph.MethodTables = types.Select(it => it.MethodTable).ToArray();
        phase($"{n:N0} objects of {types.Count:N0} types");

        // 2. the references, as arrays of edges (CSR): node i refers to edges[edgeStart[i] .. edgeStart[i + 1]); node n is the root of all
        var edgeStart = new int[n + 2];
        var edges = new GrowingArray<int>();
        for (var i = 0; i < n; i++)
        {
            edgeStart[i] = edges.Count;
            foreach (var reference in heap.GetObject(addresses[i], types[graph.TypeOf[i]]).EnumerateReferenceAddresses(carefully: false, considerDependantHandles: true))
            {
                var target_ = Array.BinarySearch(addresses, reference);
                if (target_ >= 0 && target_ != i) edges.Add(target_);
            }
            if ((i & 0xFFFF) == 0) cancellation.ThrowIfCancellationRequested();
        }
        edgeStart[n] = edges.Count;
        foreach (var root in heap.EnumerateRoots())
        {
            var index = Array.BinarySearch(addresses, root.Object.Address);
            if (index >= 0 && graph.RootKinds.TryAdd(index, root.RootKind.ToString())) edges.Add(index);
        }
        edgeStart[n + 1] = edges.Count;
        graph.EdgeStart = edgeStart;
        graph.Edges = edges.ToArray();
        phase($"{graph.Edges.Length:N0} references, {graph.RootKinds.Count:N0} rooted objects");
        return graph;
    }

    private static DataTarget OpenDump(string path)
    {
        try
        {
            return DataTarget.LoadDump(path, new CacheOptions { MaxDumpCacheSize = DumpCacheBytes });
        }
        catch (Exception e) when (e is IOException or InvalidDataException or NotSupportedException)
        {
            throw new HelperException($"The dump cannot be read: {e.Message}");
        }
    }

    /**
     * Per type: the objects, their own bytes, and the bytes the type retains as a whole: the retained sizes of its objects that no other
     * object of the type dominates (an inner node of a linked list is not counted twice). A walk down the tree with a counter per type.
     */
    public HeapSummary Summary(int top)
    {
        var types = typeNames.Length;
        var counts = new int[types];
        var shallow = new long[types];
        var byType = new long[types];
        var active = new int[types];
        long bytes = 0;
        var stack = new int[reachable + 1];
        var position = new int[reachable + 1];
        var depth = 0;
        stack[0] = Root;
        position[0] = childStart[Root];
        while (depth >= 0)
        {
            var v = stack[depth];
            if (position[depth] < childStart[v + 1])
            {
                var w = children[position[depth]++];
                var t = typeOf[w];
                counts[t]++;
                shallow[t] += sizes[w];
                bytes += sizes[w];
                if (active[t]++ == 0) byType[t] += retained[w];
                depth++;
                stack[depth] = w;
                position[depth] = childStart[w];
            }
            else
            {
                if (v != Root) active[typeOf[v]]--;
                depth--;
            }
        }
        var list = new List<TypeRetained>();
        for (var t = 0; t < types; t++) if (counts[t] > 0) list.Add(new TypeRetained(typeNames[t], methodTables[t].ToString("x"), counts[t], shallow[t], byType[t]));
        list.Sort((a, b) => b.Retained.CompareTo(a.Retained));
        var dominated = Dominated(null, top);
        return new HeapSummary(reachable, bytes, unreachable, unreachableBytes, list, dominated.Children, dominated.Omitted, elapsedMs,
            Process.GetCurrentProcess().PeakWorkingSet64);
    }

    /** The objects [address] immediately dominates, the largest first; without an address, the top of the tree. */
    public Dominated Dominated(string? address, int top)
    {
        var parent = Root;
        if (!string.IsNullOrWhiteSpace(address))
        {
            var text = address.Trim();
            if (text.StartsWith("0x", StringComparison.OrdinalIgnoreCase)) text = text[2..];
            if (!ulong.TryParse(text, System.Globalization.NumberStyles.HexNumber, null, out var value)) throw new HelperException($"Not an address: {address}");
            parent = Array.BinarySearch(addresses, value);
            if (parent < 0) throw new HelperException($"No object at {address} in the dump");
        }
        var all = new List<int>(childStart[parent + 1] - childStart[parent]);
        for (var c = childStart[parent]; c < childStart[parent + 1]; c++) all.Add(children[c]);
        all.Sort((a, b) => retained[b].CompareTo(retained[a]));
        var shown = all.Take(Math.Max(top, 0)).Select(Describe).ToList();
        var omitted = all.Skip(Math.Max(top, 0)).ToList();
        return new Dominated(parent == Root ? null : Describe(parent), shown, omitted.Count, omitted.Sum(it => retained[it]));
    }

    private DominatorObject Describe(int v) => new(addresses[v].ToString("x"), typeNames[typeOf[v]], sizes[v], retained[v], childStart[v + 1] - childStart[v],
        rootKinds.GetValueOrDefault(v));

    /** A list that grows by chunks, not by copying one array into one twice as large: the objects and references of a heap are many. */
    private sealed class GrowingArray<T>
    {
        private const int ChunkSize = 1 << 22;
        private readonly List<T[]> chunks = new();
        public int Count { get; private set; }

        public void Add(T value)
        {
            if (Count == Array.MaxLength) throw new HelperException("The heap has more than 2 billion references: retained sizes are not computed for a heap that large");
            if ((Count & (ChunkSize - 1)) == 0 && Count / ChunkSize == chunks.Count) chunks.Add(new T[ChunkSize]);
            chunks[Count / ChunkSize][Count & (ChunkSize - 1)] = value;
            Count++;
        }

        public T[] ToArray()
        {
            var result = new T[Count];
            for (var c = 0; c < chunks.Count; c++)
            {
                var length = Math.Min(ChunkSize, Count - c * ChunkSize);
                if (length > 0) Array.Copy(chunks[c], 0, result, (long)c * ChunkSize, length);
                chunks[c] = null!; // given back as it goes
            }
            chunks.Clear();
            return result;
        }
    }
}
