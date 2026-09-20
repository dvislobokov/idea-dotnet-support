import subprocess
import time

from dap import APP_DLL, MARKERS, Session, short

# 1. what the process really returns after an unhandled exception, without any debugger
for scenario in ("unhandled", "threadcrash"):
    p = subprocess.run(["dotnet", APP_DLL, scenario], capture_output=True)
    print("RESULT exit code of '%s' without a debugger: %s (0x%08X)" % (scenario, p.returncode, p.returncode & 0xFFFFFFFF))

# 2. step into an async method; step over the end of an async method
s = Session("v-async-stepin")
s.launch("async", ["async-second"])
stop = s.stop()
if stop:
    tid = stop["threadId"]
    print("RESULT at %s" % s.where(tid))
    s.request("stepIn", {"threadId": tid}, show=False)
    st = s.stop(timeout=10)
    print("RESULT stepIn on 'await Compute(first)' -> %s" % (s.where(st["threadId"]) if st else None))
    if st:
        s.top(st["threadId"], 6)
s.close()

s = Session("v-async-inner-stepout")
s.launch("async", ["async-inner"])
stop = s.stop()
if stop:
    tid = stop["threadId"]
    print("RESULT at %s" % s.where(tid))
    s.request("setBreakpoints", {"source": {"path": MARKERS["async-inner"][0]}, "breakpoints": []}, show=False)
    s.request("stepOut", {"threadId": tid}, show=False)
    st = s.stop(timeout=10)
    print("RESULT stepOut of the async method Compute -> %s" % (s.where(st["threadId"]) if st else "NO STOP (ran to the end)"))
s.close()

s = Session("v-async-step-past-end")
s.launch("async", ["async-inner"])
stop = s.stop()
if stop:
    tid = stop["threadId"]
    s.request("setBreakpoints", {"source": {"path": MARKERS["async-inner"][0]}, "breakpoints": []}, show=False)
    for i in range(5):
        s.request("next", {"threadId": tid}, show=False)
        st = s.stop(timeout=10)
        print("RESULT next #%d inside Compute -> %s" % (i + 1, s.where(st["threadId"]) if st else "NO STOP (ran to the end)"))
        if not st:
            break
        tid = st["threadId"]
s.close()

# 3. step out while an exception is thrown and caught inside the frame that is being left
s = Session("v-stepout-exception")
s.launch("stepping", ["step-prop"])
stop = s.stop()
if stop:
    tid = stop["threadId"]
    print("RESULT at %s" % s.where(tid))
    s.request("stepOut", {"threadId": tid}, show=False)
    st = s.stop(timeout=10)
    print("RESULT stepOut of Stepping.Run (it throws and catches inside) -> %s" % (s.where(st["threadId"]) if st else None))
s.close()

# 4. goto
s = Session("v-goto")
s.launch("sleeper", ["sleeper"])
stop = s.stop()
if stop:
    path, line = MARKERS["sleeper"]
    r = s.request("gotoTargets", {"source": {"path": path}, "line": line + 1}, n=300)
    targets = (r.get("body") or {}).get("targets", [])
    if targets:
        r = s.request("goto", {"threadId": stop["threadId"], "targetId": targets[0]["id"]})
        st = s.stop(timeout=5)
        print("RESULT after goto: %s" % (short(st) + " | " + s.where(st["threadId"]) if st else "no stopped event"))
    r = s.request("gotoTargets", {"source": {"path": MARKERS["lib-add"][0]}, "line": MARKERS["lib-add"][1]}, n=300)
    r = s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": 0}, {"line": -5}, {"line": 99999}]}, n=900)
s.close()

# 5. strings of different lengths
s = Session("v-strings")
s.launch("variables", ["variables"])
stop = s.stop()
if stop:
    fid = s.top(stop["threadId"], 1, show=False)[0]["id"]
    for n in (100, 1000, 1023, 1024, 1025, 4095, 4096, 4097, 5000):
        r = s.request("evaluate", {"expression": "new string('q', %d)" % n, "frameId": fid, "context": "watch"}, show=False)
        body = r.get("body") or {}
        value = body.get("result", r.get("message", ""))
        print("RESULT string of %5d chars -> %d chars returned, type=%s, head=%s" % (n, len(value), body.get("type"), short(value, 70)))
    r = s.request("evaluate", {"expression": "longText", "frameId": fid, "context": "clipboard"}, show=False)
    print("RESULT clipboard context for the 5000-char string: %s" % short((r.get("body") or {}).get("result", r.get("message")), 120))
s.close()

# 6. does listing locals call ToString() of user types? (HangingToString never returns)
s = Session("v-tostring")
s.launch("evil", ["evil"])
stop = s.stop(timeout=30)
if stop:
    fid = s.top(stop["threadId"], 1, show=False)[0]["id"]
    t0 = time.time()
    scopes = s.request("scopes", {"frameId": fid}, show=False)["body"]["scopes"]
    r = s.request("variables", {"variablesReference": scopes[0]["variablesReference"]}, show=False, timeout=120)
    print("RESULT listing locals with a hanging ToString() took %.1fs: %s" % (time.time() - t0, short({v["name"]: v["value"] for v in (r.get("body") or {}).get("variables", [])}, 400)))
s.close()

# 7. terminate: exit code and events
s = Session("v-terminate")
s.launch("sleeper", [])
s.drain(1)
s.request("terminate", {})
s.finish(timeout=10)
s.close()
