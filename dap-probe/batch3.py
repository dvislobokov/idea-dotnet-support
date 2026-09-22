"""Breakpoint features, launch variants, attach, protocol abuse."""
import json
import os
import subprocess
import sys
import time
import traceback

import dap
from dap import APP_DLL, HERE, MARKERS, ROOT, WINDOWS, Session, kill_pid, pid_alive, short


def bp_features():
    s = Session("bp-features")
    s.initialize()
    seq = s.send("launch", {"program": APP_DLL, "args": ["sleeper"]})
    s.wait_event("initialized", 20)
    path, line = MARKERS["sleeper"]
    r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [
        {"line": line, "hitCondition": "3"},
    ]}, n=500)
    s.request("configurationDone", show=False)
    s.wait_response(seq, 30)
    stop = s.stop(timeout=10, expect="hitCondition 3")
    if stop:
        f = s.top(stop["threadId"], 1)[0]["id"]
        s.eval("i", f)
    for spec in ({"hitCondition": ">= 5"}, {"hitCondition": "% 4"}, {"hitCondition": "abc"}, {"condition": "i == 12"}, {"condition": "i =="}, {"condition": "i + 1"}, {"condition": "missing > 1"},
                 {"condition": "i / (i - i) > 0"}, {"logMessage": "i is {i} and twice is {i * 2} {{braces}} {missing}"}, {"condition": "i % 2 == 0", "logMessage": "even {i}"}):
        r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [dict({"line": line}, **spec)]}, show=False)
        bps = (r.get("body") or {}).get("breakpoints", [{}])
        print("   set %s -> ok=%s verified=%s message=%s" % (spec, r.get("success"), bps[0].get("verified"), short(bps[0].get("message") or r.get("message"))))
        if stop:
            s.request("continue", {"threadId": stop["threadId"]}, show=False)
        before = len(s.output)
        stop = s.stop(timeout=4, expect=str(spec))
        if stop:
            f = s.top(stop["threadId"], 1, show=False)[0]["id"]
            s.eval("i", f)
        logged = [t for c, t in s.output[before:] if c != "stdout"]
        if logged:
            print("   non-stdout output: %s" % short(logged, 300))
        if not stop:
            s.request("pause", {"threadId": 0}, show=False)
            stop = s.stop(timeout=5, expect="pause")
    # breakpoints while running: add, remove
    if stop:
        s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": []}, show=False)
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
    s.drain(0.5)
    r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": line}]}, n=200)
    stop = s.stop(timeout=5, expect="breakpoint added while running")
    print("   breakpoint added while running hit: %s" % bool(stop))
    # function breakpoints
    r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": []}, show=False)
    for names in (["DebugTarget.Output.Sleeper"], ["Console.WriteLine"], ["System.Console.WriteLine"], ["NoSuch.Method"], ["DebugTarget.Output.Sleeper(int)"]):
        r = s.request("setFunctionBreakpoints", {"breakpoints": [{"name": n} for n in names]}, n=300)
    s.request("setFunctionBreakpoints", {"breakpoints": [{"name": "System.Threading.Thread.Sleep"}]}, n=300)
    if stop:
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
    stop = s.stop(timeout=5, expect="function breakpoint on Thread.Sleep")
    if stop:
        print("   function bp stop: %s | %s" % (short(stop), s.where(stop["threadId"])))
    # nonsense locations
    r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": 1}, {"line": 99999}, {"line": 0}, {"line": -5}]}, n=900)
    r = s.request("setBreakpoints", {"source": {"path": os.path.join(ROOT, "App", "NoSuchFile.cs")}, "breakpoints": [{"line": 3}]}, n=300)
    r = s.request("setBreakpoints", {"source": {"path": path.replace("\\", "/")}, "breakpoints": [{"line": line}]}, n=260)
    r = s.request("setBreakpoints", {"source": {"path": path[0].lower() + path[1:]}, "breakpoints": [{"line": line}]}, n=260)
    r = s.request("setBreakpoints", {"source": {"path": path.upper()}, "breakpoints": [{"line": line}]}, n=260)
    r = s.request("setBreakpoints", {"source": {"name": "Rest.cs"}, "breakpoints": [{"line": line}]}, n=260)
    r = s.request("setBreakpoints", {"source": {"path": path}, "lines": [line]}, n=260)
    # goto
    if stop:
        r = s.request("gotoTargets", {"source": {"path": path}, "line": line - 1}, n=300)
        targets = (r.get("body") or {}).get("targets", [])
        if targets:
            s.request("goto", {"threadId": stop["threadId"], "targetId": targets[0]["id"]})
            st = s.stop(timeout=5, expect="goto")
            if st:
                print("   after goto: %s %s" % (st.get("reason"), s.where(st["threadId"])))
    s.close()


def process_control():
    # restart
    s = Session("restart")
    s.launch("sleeper", ["sleeper"])
    stop = s.stop()
    r = s.request("restart", {"arguments": {"program": APP_DLL, "args": ["sleeper"]}}, timeout=40)
    stop = s.stop(timeout=20, expect="breakpoint after restart")
    print("   after restart stop=%s, process events=%d" % (short(stop), len([e for e in s.all_events if e["event"] == "process"])))
    s.close()

    # disconnect without terminating: does the debuggee survive?
    s = Session("detach-launch")
    s.launch("sleeper", [])
    s.drain(1)
    pid = next((e["body"].get("systemProcessId") for e in s.all_events if e["event"] == "process"), None)
    s.close(terminate=False)
    time.sleep(1)
    print("   debuggee pid %s after disconnect(terminateDebuggee=false): %s" % (pid, "ALIVE" if pid_alive(pid) else "gone"))
    kill_pid(pid)

    # kill the debuggee from outside
    s = Session("external-kill")
    s.launch("sleeper", [])
    s.drain(1)
    pid = next((e["body"].get("systemProcessId") for e in s.all_events if e["event"] == "process"), None)
    kill_pid(pid)
    s.finish(timeout=10)
    s.close()

    # adapter killed: is the debuggee left behind?
    s = Session("adapter-killed")
    s.launch("sleeper", [])
    s.drain(1)
    pid = next((e["body"].get("systemProcessId") for e in s.all_events if e["event"] == "process"), None)
    s.proc.kill()
    time.sleep(2)
    print("   debuggee pid %s after the adapter was killed: %s" % (pid, "ALIVE (orphan)" if pid_alive(pid) else "gone"))
    kill_pid(pid)


def attach():
    target = subprocess.Popen(["dotnet", APP_DLL, "sleeper"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    try:
        s = Session("attach")
        s.initialize()
        seq = s.send("attach", {"processId": target.pid, "justMyCode": True})
        print("   initialized event: %s" % bool(s.wait_event("initialized", 20)))
        s.set_breakpoints(["sleeper"], show=True)
        s.request("configurationDone", show=False)
        print("   attach response: %s" % short(s.wait_response(seq, 30)))
        stop = s.stop(timeout=10, expect="breakpoint after attach")
        if stop:
            f = s.top(stop["threadId"], 3)
            s.locals(f[0]["id"])
        # second adapter attaching to the same process
        s2 = Session("attach-second")
        s2.initialize()
        seq2 = s2.send("attach", {"processId": target.pid})
        s2.wait_event("initialized", 5)
        s2.request("configurationDone", show=False)
        print("   second attach response: %s" % short(s2.wait_response(seq2, 20)))
        s2.close(terminate=False)
        s.close(terminate=False)
        time.sleep(1)
        print("   target after detach: %s" % ("ALIVE" if target.poll() is None else "exited %s" % target.poll()))
    finally:
        target.kill()

    for name, args in (("attach-nonexistent", {"processId": 999999}), ("attach-native", {"processId": os.getpid()}), ("attach-string-pid", {"processId": "abc"}), ("attach-no-pid", {})):
        s = Session(name)
        s.initialize()
        seq = s.send("attach", args)
        s.wait_event("initialized", 3)
        s.request("configurationDone", show=False, timeout=5)
        print("   response: %s" % short(s.wait_response(seq, 20), 400))
        s.close(terminate=False)


def launch_variants():
    cases = [
        ("launch-missing-program", {"program": os.path.join(ROOT, "nope", "missing.dll")}),
        ("launch-no-program", {}),
        ("launch-exe-apphost", {"program": APP_DLL.replace(".dll", ".exe" if WINDOWS else ""), "args": ["exitcode"]}),
        ("launch-native-exe", {"program": r"C:\Windows\System32\whoami.exe" if WINDOWS else "/usr/bin/whoami"}),
        ("launch-bad-cwd", {"program": APP_DLL, "args": ["exitcode"], "cwd": os.path.join(ROOT, "no-such-dir")}),
        ("launch-stop-at-entry", {"program": APP_DLL, "args": ["exitcode"], "stopAtEntry": True}),
        ("launch-project-build", {"project": os.path.join(ROOT, "App"), "build": True, "args": ["exitcode"]}),
        ("launch-project-file", {"project": os.path.join(ROOT, "App", "App.csproj"), "args": ["exitcode"]}),
        ("launch-integrated-terminal-unsupported", {"program": APP_DLL, "args": ["exitcode"], "console": "integratedTerminal"}),
        ("launch-bad-types", {"program": APP_DLL, "args": "not-a-list", "env": ["not", "a", "map"], "justMyCode": "yes"}),
    ]
    for name, arguments in cases:
        s = Session(name)
        s.initialize()
        seq = s.send("launch", arguments)
        got_init = s.wait_event("initialized", 60 if "project" in arguments else 8)
        if got_init:
            s.request("configurationDone", show=False)
        response = s.wait_response(seq, 90)
        print("   launch ok=%s %s" % (response.get("success"), short(response.get("message") or response.get("body"), 400)))
        stop = s.stop(timeout=4, expect="(entry stop only for stopAtEntry)") if arguments.get("stopAtEntry") else None
        if stop:
            print("   entry stop: %s | %s" % (short(stop), s.where(stop["threadId"])))
            s.request("continue", {"threadId": stop["threadId"]}, show=False)
        s.finish(timeout=20)
        print("   output: %s" % short(s.stdout_text(), 160))
        s.close()

    # a client that supports runInTerminal
    s = Session("launch-run-in-terminal")
    s.initialize(supportsRunInTerminalRequest=True)
    seq = s.send("launch", {"program": APP_DLL, "args": ["exitcode"], "console": "integratedTerminal"})
    s.wait_event("initialized", 8)
    s.request("configurationDone", show=False)
    s.drain(3)
    reverse = [m for m in s.backlog if m.get("type") == "request"]
    print("   reverse requests: %s" % short(reverse, 700))
    s.close()


def protocol_abuse():
    s = Session("protocol-before-initialize")
    for command, arguments in (("threads", None), ("launch", {"program": APP_DLL}), ("evaluate", {"expression": "1+1"}), ("noSuchCommand", {"x": 1})):
        s.request(command, arguments, timeout=8)
    s.close()

    s = Session("protocol-garbage")
    s.initialize()
    s.send_raw(b"Content-Length: 5\r\n\r\nhello")
    s.send_raw(b"Content-Length: 17\r\n\r\n{\"seq\": \"x\", 1234}")
    s.send_raw(b"Garbage-Header: 1\r\n\r\n")
    body = json.dumps({"seq": 900, "type": "request"}).encode()
    s.send_raw(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    body = json.dumps({"seq": 901, "type": "response", "request_seq": 1, "success": True, "command": "zzz"}).encode()
    s.send_raw(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    s.drain(1.5)
    r = s.request("threads", timeout=8)
    print("   adapter alive after garbage: %s (exit code %s)" % (s.proc.poll() is None, s.proc.poll()))
    s.close()

    s = Session("protocol-wrong-state")
    s.launch("sleeper", ["sleeper"])
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        frames = s.top(tid, 2)
        fid = frames[0]["id"]
        scopes, v = s.locals(fid, show=False)
        ref = scopes[0]["variablesReference"]
        s.request("continue", {"threadId": tid}, show=False)
        s.request("setBreakpoints", {"source": {"path": MARKERS["sleeper"][0]}, "breakpoints": []}, show=False)
        s.drain(0.3)
        print("   -- while running:")
        s.request("stackTrace", {"threadId": tid}, timeout=8)
        s.request("scopes", {"frameId": fid}, timeout=8)
        s.request("variables", {"variablesReference": ref}, timeout=8)
        s.request("evaluate", {"expression": "i", "frameId": fid}, timeout=8)
        s.request("evaluate", {"expression": "1 + 1"}, timeout=8)
        s.request("next", {"threadId": tid}, timeout=8)
        s.request("continue", {"threadId": tid}, timeout=8)
        s.request("pause", {"threadId": 123456}, timeout=8)
        st = s.stop(timeout=5)
        print("   -- while stopped again (%s):" % short(st))
        s.request("variables", {"variablesReference": ref}, timeout=8)
        s.request("variables", {"variablesReference": 987654}, timeout=8)
        s.request("scopes", {"frameId": 987654}, timeout=8)
        s.request("stackTrace", {"threadId": 987654}, timeout=8)
        s.request("stackTrace", {}, timeout=8)
        s.request("evaluate", {"frameId": fid}, timeout=8)
        s.request("continue", {"threadId": 987654}, timeout=8)
        st2 = s.stop(timeout=3, expect="(should NOT resume on a wrong thread id?)")
        s.request("source", {"sourceReference": 0, "source": {"path": MARKERS["sleeper"][0]}}, timeout=8, n=120)
        s.request("source", {"sourceReference": 42}, timeout=8)
        s.request("completions", {"text": "Conso", "column": 6}, timeout=8)
        s.request("stepInTargets", {"frameId": fid}, timeout=8)
        s.request("dataBreakpointInfo", {"name": "i"}, timeout=8)
        s.request("readMemory", {"memoryReference": "0x0", "count": 8}, timeout=8)
        s.request("disassemble", {"memoryReference": "0x0", "instructionCount": 4}, timeout=8)
        s.request("configurationDone", timeout=8)
        s.request("launch", {"program": APP_DLL}, timeout=8)
        s.request("initialize", {"adapterID": "again"}, timeout=8, n=80)
    s.close()

    # spam: ten steps without waiting, then cancel
    s = Session("protocol-spam")
    s.launch("sleeper", ["sleeper"])
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        seqs = [s.send("next", {"threadId": tid}) for _ in range(10)]
        results = [s.wait_response(q, 10) for q in seqs]
        print("   10 x next without waiting: ok=%d failed=%d first failure=%s" % (sum(1 for r in results if r.get("success")), sum(1 for r in results if not r.get("success")),
                                                                                short(next((r.get("message") for r in results if not r.get("success")), None))))
        s.drain(3)
        print("   stopped events seen: %d" % len([e for e in s.all_events if e["event"] == "stopped"]))
        r = s.request("cancel", {"requestId": 1}, timeout=8)
        r = s.request("cancel", {"requestId": 99999}, timeout=8)
    s.close()


def paths_and_builds():
    import shutil
    weird = os.path.join(ROOT, "Путь с пробелом #1 & (x)")
    if not os.path.isdir(weird):
        shutil.copytree(os.path.join(ROOT, "App"), os.path.join(weird, "App"), ignore=shutil.ignore_patterns("bin", "obj"))
        shutil.copytree(os.path.join(ROOT, "Lib"), os.path.join(weird, "Lib"), ignore=shutil.ignore_patterns("bin", "obj"))
    subprocess.run(["dotnet", "build", os.path.join(weird, "App", "App.csproj"), "-nologo", "-v", "q"], capture_output=True)
    dll = os.path.join(weird, "App", "bin", "Debug", "net10.0", "App.dll")
    source = os.path.join(weird, "App", "Rest.cs")
    line = MARKERS["sleeper"][1]
    s = Session("weird-path")
    s.initialize()
    seq = s.send("launch", {"program": dll, "args": ["sleeper"], "cwd": os.path.dirname(dll)})
    s.wait_event("initialized", 20)
    s.request("setBreakpoints", {"source": {"path": source}, "breakpoints": [{"line": line}]}, n=200)
    s.request("configurationDone", show=False)
    print("   launch: %s" % short(s.wait_response(seq, 30)))
    stop = s.stop(timeout=10, expect="breakpoint in a path with spaces, cyrillic, # and &")
    if stop:
        print("   hit at %s" % s.where(stop["threadId"]))
    s.close()

    # Release (optimized) build
    subprocess.run(["dotnet", "build", os.path.join(ROOT, "App", "App.csproj"), "-c", "Release", "-nologo", "-v", "q"], capture_output=True)
    release = APP_DLL.replace(os.sep + "Debug" + os.sep, os.sep + "Release" + os.sep)
    for jmc in (True, False):
        s = Session("release-jmc-%s" % jmc)
        s.launch("variables", ["variables"], program=release, launch_extra={"justMyCode": jmc})
        stop = s.stop(timeout=10, expect="breakpoint in optimized code")
        if stop:
            frames = s.top(stop["threadId"], 3)
            _, v = s.locals(frames[0]["id"], show=False)
            print("   locals in optimized frame: %d; sample: %s" % (len(v), short({k: x.get("value") for k, x in list(v.items())[:6]}, 300)))
        s.close()

    # source edited after the build: the PDB checksum no longer matches
    path = os.path.join(weird, "App", "Rest.cs")
    original = open(path, encoding="utf-8").read()
    open(path, "w", encoding="utf-8").write("// an extra line added after the build\n// and another\n" + original)
    s = Session("stale-source")
    s.initialize()
    seq = s.send("launch", {"program": dll, "args": ["sleeper"]})
    s.wait_event("initialized", 20)
    s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": line + 2}]}, n=300)
    s.request("configurationDone", show=False)
    s.wait_response(seq, 30)
    stop = s.stop(timeout=8, expect="breakpoint in a modified file")
    if stop:
        print("   hit at %s (file has 2 extra lines; breakpoint asked for line %d)" % (s.where(stop["threadId"]), line + 2))
    for e in [e for e in s.all_events if e["event"] == "breakpoint"][:2]:
        print("   breakpoint event: %s" % short(e["body"], 400))
    s.close()
    open(path, "w", encoding="utf-8").write(original)


def server_mode():
    import socket
    proc = subprocess.Popen([dap.ADAPTER, "--server=4711"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(2)
    try:
        def talk(tag):
            c = socket.create_connection(("127.0.0.1", 4711), timeout=5)
            body = json.dumps({"seq": 1, "type": "request", "command": "initialize", "arguments": {"adapterID": "coreclr"}}).encode()
            c.sendall(b"Content-Length: %d\r\n\r\n" % len(body) + body)
            c.settimeout(5)
            data = c.recv(65536)
            print("   %s: %d bytes, starts %r" % (tag, len(data), data[:60]))
            return c
        first = talk("first client")
        try:
            second = talk("second client while the first is connected")
            second.close()
        except Exception as e:
            print("   second client: %r" % e)
        first.close()
        time.sleep(1)
        print("   adapter after the client left: %s" % ("still running" if proc.poll() is None else "exited %s" % proc.poll()))
        try:
            third = talk("new client after the first left")
            third.close()
        except Exception as e:
            print("   new client after the first left: %r" % e)
        if WINDOWS:
            out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
            print("   listening on: %s" % [l.split()[1] for l in out.splitlines() if ":4711" in l and "LISTENING" in l])
    finally:
        proc.kill()


ALL = {f.__name__: f for f in (bp_features, process_control, attach, launch_variants, protocol_abuse, paths_and_builds, server_mode)}

if __name__ == "__main__":
    for name in (sys.argv[1:] or ALL):
        try:
            ALL[name]()
        except Exception:
            print("!!! harness error in %s:\n%s" % (name, traceback.format_exc()))
