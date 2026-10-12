"""
What the server costs the machine: memory of its process (and of the MSBuild BuildHost it starts) after the solution is loaded and after
a round of the requests the IDE makes, and the time of the load, for the settings of the .NET garbage collector the plugin can pass in the
environment. The server ships with Server GC (`System.GC.Server: true` in its runtimeconfig): a heap per core.

    python tools/roslyn-lsp/memory.py [debug-playground/DebugPlayground.sln]

Windows only (the memory is read with PowerShell). Prints a table; nothing on disk is changed.
"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from probe import Server, find_server  # noqa: E402

VARIANTS = [
    ("as shipped (Server GC)", {}),
    ("Workstation GC", {"DOTNET_gcServer": "0"}),
    ("Workstation GC + conserve memory", {"DOTNET_gcServer": "0", "DOTNET_GCConserveMemory": "7"}),
    ("Server GC + dynamic adaptation", {"DOTNET_GCDynamicAdaptationMode": "1"}),
]


def memory(pid):
    """Working set and private bytes of the process and of the BuildHost processes it has started, in MB."""
    script = ("$p = Get-CimInstance Win32_Process; "
              "$ids = @(%d) + @($p | Where-Object { $_.ParentProcessId -eq %d } | ForEach-Object { $_.ProcessId }); "
              "$ids | ForEach-Object { $x = Get-Process -Id $_ -ErrorAction SilentlyContinue; if ($x) { '{0};{1};{2}' -f $x.ProcessName, $x.WorkingSet64, $x.PrivateMemorySize64 } }") % (pid, pid)
    out = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True).stdout
    rows = [line.split(";") for line in out.strip().splitlines() if line.count(";") == 2]
    return [(name, int(ws) // 2**20, int(private) // 2**20) for name, ws, private in rows]


def run(label, environment, solution):
    root = solution.parent
    capabilities = json.loads((Path(__file__).parent / "client-capabilities.json").read_text(encoding="utf-8"))
    env_before = {key: os.environ.get(key) for key in environment}
    os.environ.update(environment)
    try:
        server = Server(find_server() + ["--stdio", "--logLevel", "Warning", "--extensionLogDirectory", str(Path(__file__).parent / "out" / "roslyn-logs")])
    finally:
        for key, value in env_before.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    started = time.perf_counter()
    initialize = server.request("initialize", {"processId": os.getpid(), "rootUri": root.as_uri(), "locale": "en",
                                               "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}], "capabilities": capabilities})
    pid = initialize["result"]["_roslyn_processId"]
    server.notify("initialized", {})
    server.notify("solution/open", {"solution": solution.as_uri()})
    while time.perf_counter() - started < 180:
        with server.lock:
            if any(m["method"] == "workspace/projectInitializationComplete" for m in server.notifications):
                break
            server.lock.wait(0.2)
    loaded = time.perf_counter() - started
    after_load = memory(pid)

    # what the IDE does with an open file: diagnostics, tokens, code lens, completion, a few times over
    file = root / "Console" / "Scenarios.cs"
    uri = file.as_uri()
    text = file.read_text(encoding="utf-8-sig")
    server.notify("textDocument/didOpen", {"textDocument": {"uri": uri, "languageId": "csharp", "version": 1, "text": text}})
    document = {"textDocument": {"uri": uri}}
    diagnostic_started = time.perf_counter()
    server.request("textDocument/diagnostic", document, timeout=120)
    first_diagnostic = time.perf_counter() - diagnostic_started
    for version in range(2, 12):
        text += "\n// edit %d" % version
        server.notify("textDocument/didChange", {"textDocument": {"uri": uri, "version": version}, "contentChanges": [{"text": text}]})
        server.request("textDocument/diagnostic", document, timeout=120)
        server.request("textDocument/semanticTokens/full", document, timeout=120)
        server.request("textDocument/codeLens", document, timeout=120)
        server.request("textDocument/completion", {"textDocument": {"uri": uri}, "position": {"line": 60, "character": 8}, "context": {"triggerKind": 1}}, timeout=120)
    time.sleep(3)
    after_work = memory(pid)
    server.process.kill()
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
    return label, loaded, first_diagnostic, after_load, after_work


def main():
    solution = Path(sys.argv[1] if len(sys.argv) > 1 else "debug-playground/DebugPlayground.sln").resolve()
    print("cores:", os.cpu_count())
    for label, environment in VARIANTS:
        label, loaded, first_diagnostic, after_load, after_work = run(label, environment, solution)
        print("\n== %s: load %.1f s, first diagnostic pull %.2f s" % (label, loaded, first_diagnostic))
        for title, rows in (("after load", after_load), ("after 10 edits", after_work)):
            total_ws = sum(ws for _, ws, _ in rows)
            total_private = sum(private for _, _, private in rows)
            print("   %-15s total WS %5d MB, private %5d MB  |  %s" % (title, total_ws, total_private,
                                                                        ", ".join("%s %d/%d" % (name, ws, private) for name, ws, private in rows)))
        sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
