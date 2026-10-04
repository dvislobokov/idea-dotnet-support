"""Baseline measurement of the C# editor path (CSHARP_PSI_MIGRATION.md, step 0, "Исходные замеры"): a sandbox IDE started with
`./gradlew runIdeForUiTests` is driven through the robot, the times are taken inside the IDE (scripts/baseline_*.js), the table is printed.

    python tools/ui-robot/baseline.py [--target playground|aspnetcore] [--runs 3] [--reps 10] [--cache cold|warm]

One run: a fresh copy of the target (no .idea, bin, obj; restored with `dotnet restore`) is opened, the measured file is opened as soon
as the project is, then: the stages of the opening (lexer, the daemon, the server, its tokens and diagnostics), memory after the
server is ready, completion latency (member after `.`, an identifier prefix), an editing session, memory again; the project is closed.
`--cache cold` deletes the cache of semantic tokens of the plugin before each run (the first opening of a file on a machine), `warm`
keeps it (the file has been seen before). Results: a table on stdout and build/baseline/<target>-<time>.json.
"""
import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import robot  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
SCRIPTS = os.path.join(ROOT, "tools", "ui-robot", "scripts")
WORK = os.path.join(ROOT, "build", "baseline")

EDIT_TEXT = "var total = numbers.Count + text.Length;\\nConsole.WriteLine(total.ToString());\\n"

TARGETS = {
    "playground": {
        "source": os.path.join(ROOT, "debug-playground"),
        "solution": "DebugPlayground.sln",
        "open": "Console/Scenarios.cs",
        "anchors": "Console/Editor/Measurements.cs",
        "member": ("text", ".", "Length"),
        "prefix": ("Conso", "l", "Console"),
        "edit": EDIT_TEXT,
    },
    # made by tools/ui-robot/make-aspnetcore-sample.py from the sources of dotnet/aspnetcore
    "aspnetcore": {
        "source": os.path.join(WORK, "aspnetcore-mvc-core"),
        "solution": "MvcCoreSample.slnx",
        "open": "src/ControllerBase.cs",
        "anchors": "Measurements.cs",
        "member": ("context", ".", "HttpContext"),
        "prefix": ("ControllerBa", "s", "ControllerBase"),
        "edit": "var path = context.HttpContext.Request.Path;\\nConsole.WriteLine(path.ToString());\\n",
    },
}


def js(name, **values):
    with open(os.path.join(SCRIPTS, name), encoding="utf-8") as f:
        script = f.read()
    for key, value in values.items():
        script = script.replace("__%s__" % key.upper(), str(value))
    result = robot.call("/js/retrieveAny", robot.script_body(script), timeout=180)
    if result.get("bytes") is None:
        return ""
    raw = robot.image_bytes(result)
    # a serialized java.lang.String: magic, version, TC_STRING + 2 bytes of length or TC_LONGSTRING + 8
    if raw[:4] == b"\xac\xed\x00\x05":
        raw = raw[7:] if raw[4] == 0x74 else raw[13:] if raw[4] == 0x7C else raw[4:]
    return raw.decode("utf-8", errors="replace")


def fields(text):
    """`key=value` lines (the head of a line before a space) into a dict; `finished` and the like into True."""
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        key, _, value = line.partition("=")
        result.setdefault(key, []).append(value if _ else True)
    return {k: v[0] if len(v) == 1 else v for k, v in result.items()}


def copy_target(target, run):
    # the same path every run: the cache of semantic tokens of the plugin is keyed by the file, `--cache warm` needs it to be the same
    destination = os.path.join(WORK, "runs", target["name"])
    # the server of the closed project lets its files go a moment after it stops
    for _ in range(30):
        shutil.rmtree(destination, ignore_errors=True)
        if not os.path.exists(destination):
            break
        time.sleep(1)
    else:
        raise SystemExit("cannot delete %s: what still holds it?" % destination)
    shutil.copytree(target["source"], destination, ignore=shutil.ignore_patterns(".idea", "bin", "obj", ".vs"))
    restore = subprocess.run(["dotnet", "restore", os.path.join(destination, target["solution"])], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if restore.returncode != 0:
        errors = [line.strip() for line in restore.stdout.splitlines() if "error" in line]
        print("  dotnet restore failed, the server will try itself: %s" % (errors[:1] or "?"), flush=True)
    return destination.replace("\\", "/")


def resident_mb(pids):
    """Working set (resident) and private bytes of processes, MB, through PowerShell: {pid: (ws, private)}."""
    if not pids:
        return {}
    command = "Get-Process -Id %s -ErrorAction SilentlyContinue | ForEach-Object { \"$($_.Id) $($_.WorkingSet64) $($_.PrivateMemorySize64)\" }" % ",".join(map(str, pids))
    out = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 3:
            result[int(parts[0])] = (int(parts[1]) / 1048576, int(parts[2]) / 1048576)
    return result


def machine_state():
    """CPU, memory and what else runs (Gradle and IDE JVMs, dotnet): the numbers mean little without it."""
    command = ("$c = Get-CimInstance Win32_Processor | Select-Object -First 1; $o = Get-CimInstance Win32_OperatingSystem; "
               "$load = [math]::Round((Get-Counter '\\Processor(_Total)\\% Processor Time' -SampleInterval 2 -MaxSamples 1).CounterSamples[0].CookedValue); "
               "$java = @(Get-Process java -ErrorAction SilentlyContinue).Count; $dotnet = @(Get-Process dotnet -ErrorAction SilentlyContinue).Count; "
               "\"$($c.Name.Trim()), $($c.NumberOfLogicalProcessors) logical CPUs; RAM $([math]::Round($o.TotalVisibleMemorySize/1MB,1)) GB, free $([math]::Round($o.FreePhysicalMemory/1MB,1)) GB; "
               "CPU load $load%; java processes $java, dotnet processes $dotnet\"")
    return subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout.strip()


def memory():
    info = fields(js("baseline_memory.js"))
    servers = info.get("serverPid", [])
    servers = [servers] if isinstance(servers, str) else servers
    server_pids = [int(s.split()[0]) for s in servers]
    ide_pid = int(info["idePid"])
    rss = resident_mb([ide_pid] + server_pids)
    # on Windows the platform starts the .cmd / apphost of the tool; the server proper is the largest of the tree
    tree = [rss[p][0] for p in server_pids if p in rss]
    return {
        "heapUsedMb": float(info["heapUsedMb"]),
        "ideRssMb": rss.get(ide_pid, (0, 0))[0],
        "serverRssMb": max(tree) if tree else 0.0,
        "serverTreeRssMb": sum(tree),
        "serverProcesses": [s.split(" ", 1)[-1] for s in servers],
    }


def completion(file, target, kind, reps, wait):
    prefix, char, expect = target[kind]
    rows = []
    # the robot waits for a script at most a few minutes: several short scripts
    while len(rows) < reps:
        chunk = min(5, reps - len(rows))
        out = js("baseline_complete.js", file=file, marker="TYPE:measure-" + kind, prefix=prefix, char=char, expect=expect, reps=chunk, wait=wait)
        for line in out.splitlines():
            row = fields(line.replace(" ", "\n"))
            if "rep" in row:
                rows.append({"first": int(row["first"]), "expected": int(row["expected"]), "items": int(row["items"])})
    return rows


def dismiss_dialogs(notes):
    """A modal dialog stops the daemon of the project behind it: the "quick tour" of the first start of a sandbox, and IDE Internal Errors
    (other plugins of the sandbox fail now and then). Closed at once; the run notes it, its times may be late by up to a second."""
    for xpath, what in (("//div[@class='MyDialog']//div[@text='Skip']", "quick tour"), ("//div[@class='MyDialog' and .//div[@text='IDE Internal Errors']]//div[@text='Close']", "IDE Internal Errors")):
        try:
            if robot.components(xpath):
                robot.call("/%s/js/execute" % robot.first(xpath)["id"], robot.script_body("robot.click(component)"))
                notes.append("closed a modal dialog (%s) at %s" % (what, time.strftime("%H:%M:%S")))
                print("  " + notes[-1], flush=True)
        except (SystemExit, OSError, ValueError) as e:
            print("  cannot close %s: %s" % (what, e), flush=True)
    try:
        # any other modal dialog stalls the run the same way: say so, the times of this run are not to be trusted
        for dialog in robot.components("//div[@class='MyDialog']"):
            texts = robot.call("/%s/data" % dialog["id"], {}).get("componentData", {}).get("textDataList", [])
            note = "a modal dialog is open: %s" % " | ".join(t["text"] for t in texts)[:120]
            if note not in notes:
                notes.append(note)
                print("  " + note, flush=True)
    except (SystemExit, OSError, ValueError):
        pass


def one_run(target, run, args):
    directory = copy_target(target, run)
    try:
        return measure(target, run, args, directory)
    finally:
        # a run that failed half-way must not leave its project (and its server) in the sandbox for the next one
        print("  " + js("baseline_close.js", dir=directory).strip(), flush=True)
        time.sleep(5)
        if not args.keep:
            shutil.rmtree(directory, ignore_errors=True)


def measure(target, run, args, directory):
    file = directory + "/" + target["open"]
    anchors = directory + "/" + target["anchors"]
    print("run %d: %s" % (run, directory), flush=True)
    print("  " + js("baseline_start.js", dir=directory, file=file, cold="yes" if args.cache == "cold" else "no", timeout=args.timeout).strip(), flush=True)
    deadline = time.time() + args.timeout + 30
    status = {}
    result_notes = []
    while time.time() < deadline:
        time.sleep(1)
        dismiss_dialogs(result_notes)
        status = fields(js("baseline_status.js"))
        if "finished" in status or "error" in status:
            break
    if "error" in status:
        print("  error: %s" % status["error"])
    stages = {k: int(v) for k, v in status.items() if k not in ("daemon", "colors", "problems", "finished", "error")}
    daemon = [int(x) for x in str(status.get("daemon", "")).split(",") if x]
    changes = lambda key: [tuple(int(n) for n in x.split(":")) for x in str(status.get(key, "")).split(",") if ":" in x]
    result = {"dir": directory, "stages": stages, "daemon": daemon, "colors": changes("colors"), "problems": changes("problems"), "notes": result_notes}
    print("  stages: %s\n  daemon passes after the file was opened: %s\n  colored highlights (ms:count): %s\n  problems (ms:count): %s"
          % (stages, daemon[:12], result["colors"][:12], result["problems"][:12]), flush=True)
    result["memoryReady"] = memory()
    print("  memory after ready: %s" % result["memoryReady"], flush=True)
    for kind in ("member", "prefix"):
        result[kind] = completion(anchors, target, kind, args.reps, args.wait)
        print("  completion %s: %s" % (kind, [(r["first"], r["expected"]) for r in result[kind]]), flush=True)
    result["edit"] = js("baseline_edit.js", file=anchors, marker="TYPE:measure-edit", text=target["edit"], times=args.edit_times, delay=args.delay, settle=5000).strip()
    print("  editing session: " + result["edit"], flush=True)
    result["memoryEdited"] = memory()
    print("  memory after editing: %s" % result["memoryEdited"], flush=True)
    return result


def since_file(run, key):
    stages = run["stages"]
    return stages[key] - stages["fileOpenStart"] if key in stages and "fileOpenStart" in stages else None


def shown_after(run, key, what):
    """When the editor first changed [what] (colors, problems) after the answer [key] came: the answer on screen. None: no change
    (the server agreed with what was shown, or nothing came)."""
    at = since_file(run, key)
    # colors drop to 0 for a moment when the heuristics step aside and on each refresh of the tokens: wait for the ones that come
    return next((ms for ms, n in run.get(what, []) if at is not None and ms >= at and (n > 0 or what != "colors")), None)


def percentile(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))] if values else None


def table(runs, args):
    rows = [
        ("project open, ms (from the open command)", [r["stages"].get("projectOpen") for r in runs]),
        ("lexer highlighting: editor opened, ms", [since_file(r, "fileOpenEnd") for r in runs]),
        ("first daemon pass on the file (heuristics), ms", [r["daemon"][0] if r["daemon"] else None for r in runs]),
        ("identifier colors first on screen (heuristics, or the server if ready first), ms", [next((ms for ms, n in r.get("colors", []) if n > 0), None) for r in runs]),
        ("semantic tokens from the plugin's cache, ms", [since_file(r, "tokensCache") for r in runs]),
        ("semantic tokens from the server: answer, ms", [since_file(r, "tokensServer") for r in runs]),
        ("semantic tokens from the server: colors on screen after ready, ms", [shown_after(r, "ready", "colors") for r in runs]),
        ("diagnostics from the server: answer, ms", [since_file(r, "diagnostics") for r in runs]),
        ("diagnostics from the server: on screen, ms", [shown_after(r, "diagnostics", "problems") for r in runs]),
        ("identifier colors dropping to none after the file was colored (flicker), times", [sum(1 for i, (_, n) in enumerate(r.get("colors", [])) if n == 0 and i > 0 and any(c > 0 for _, c in r["colors"][:i])) for r in runs]),
        ("server process started, ms (from project open command)", [r["stages"].get("serverProcess") for r in runs]),
        ("server ready (workspace loaded), ms (from project open command)", [r["stages"].get("ready") for r in runs]),
        ("IDE heap used after GC, ready, MB", [r["memoryReady"]["heapUsedMb"] for r in runs]),
        ("IDE RSS, ready, MB", [r["memoryReady"]["ideRssMb"] for r in runs]),
        ("server RSS, ready, MB", [r["memoryReady"]["serverRssMb"] for r in runs]),
        ("server + child processes RSS, ready, MB", [r["memoryReady"]["serverTreeRssMb"] for r in runs]),
        ("IDE heap used after GC, after editing, MB", [r["memoryEdited"]["heapUsedMb"] for r in runs]),
        ("IDE RSS, after editing, MB", [r["memoryEdited"]["ideRssMb"] for r in runs]),
        ("server RSS, after editing, MB", [r["memoryEdited"]["serverRssMb"] for r in runs]),
        ("server + child processes RSS, after editing, MB", [r["memoryEdited"]["serverTreeRssMb"] for r in runs]),
    ]
    for kind, label in (("member", "`.` member"), ("prefix", "identifier prefix")):
        for what, name in (("first", "first items"), ("expected", "expected item")):
            values = [x[what] for r in runs for x in r[kind] if x[what] >= 0]
            missed = sum(1 for r in runs for x in r[kind] if x[what] < 0)
            note = "" if not missed else " (%d of %d not reached)" % (missed, sum(len(r[kind]) for r in runs))
            rows.append(("completion %s, %s: median / p90, ms%s" % (label, name, note), ["%s / %s" % (percentile(values, 50), percentile(values, 90))] if values else ["-"]))
    print()
    print("| %s | median of runs | runs |" % "measurement")
    print("|---|---|---|")
    for name, values in rows:
        if values and isinstance(values[0], str):
            print("| %s | %s | all %d runs × %d reps |" % (name, values[0], len(runs), args.reps))
            continue
        present = [v for v in values if v is not None]
        median = statistics.median(present) if present else None
        fmt = lambda v: "-" if v is None else ("%.0f" % v)
        print("| %s | %s | %s |" % (name, fmt(median), ", ".join(fmt(v) for v in values)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", default="playground", choices=sorted(TARGETS))
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--reps", type=int, default=10, help="completion repetitions per kind and run")
    parser.add_argument("--cache", default="cold", choices=["cold", "warm"])
    parser.add_argument("--timeout", type=int, default=600, help="seconds for a run to get the server ready")
    parser.add_argument("--wait", type=int, default=5000, help="ms to wait for one completion")
    parser.add_argument("--edit-times", type=int, default=4, help="how many times the edit text is typed")
    parser.add_argument("--delay", type=int, default=60, help="ms between typed characters")
    parser.add_argument("--keep", action="store_true", help="keep the copies of the target in build/baseline/runs")
    parser.add_argument("--table", metavar="JSON", help="only print the table of the raw data of an earlier measurement")
    args = parser.parse_args()
    if args.table:
        with open(args.table, encoding="utf-8") as f:
            data = json.load(f)
        args.reps = data["args"]["reps"]
        print("%s, %s, cache %s; machine: %s" % (data["target"], data["started"], data["args"]["cache"], data.get("machine")))
        return table(data["runs"], args)
    target = dict(TARGETS[args.target], name=args.target)
    if not os.path.isdir(target["source"]):
        raise SystemExit("no %s: for aspnetcore run tools/ui-robot/make-aspnetcore-sample.py first" % target["source"])
    os.environ.setdefault("NO_PROXY", "127.0.0.1")
    os.makedirs(WORK, exist_ok=True)
    started = time.strftime("%Y-%m-%d %H:%M")
    machine = machine_state()
    print("machine: " + machine, flush=True)
    # projects of an interrupted measurement: openOrImport would ask whether to open the next one in their window
    print("leftovers: " + js("baseline_close.js", dir=os.path.join(WORK, "runs").replace("\\", "/")).strip(), flush=True)
    runs = [one_run(target, run, args) for run in range(1, args.runs + 1)]
    out = os.path.join(WORK, "%s-%s.json" % (args.target, time.strftime("%Y%m%d-%H%M%S")))
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"target": args.target, "started": started, "args": vars(args), "runs": runs}, f, indent=1)
    print("\n%s, %s, cache %s, %d runs; raw data: %s" % (args.target, started, args.cache, len(runs), out))
    table(runs, args)


if __name__ == "__main__":
    main()
