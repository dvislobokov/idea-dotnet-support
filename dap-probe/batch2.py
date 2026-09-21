"""Unusual code and hostile debuggees."""
import sys
import time
import traceback

from dap import Session, short


def walk(name, scenario, bps, evals=(), expand=(), filters=(), launch_extra=None, max_stops=8, stop_timeout=15, per_stop=None, finish_timeout=15):
    s = Session(name)
    s.launch(scenario, bps, filters=filters, launch_extra=launch_extra)
    for index in range(max_stops):
        stop = s.stop(timeout=stop_timeout)
        if not stop:
            break
        tid = stop["threadId"]
        print("   STOP #%d %s %s | %s" % (index + 1, stop.get("reason"), short(stop.get("description") or "", 80), s.where(tid)))
        frames = s.top(tid, 5)
        if frames:
            fid = next((f["id"] for f in frames if f.get("source")), frames[0]["id"])
            _, v = s.locals(fid)
            for n in expand:
                if n in v and v[n].get("variablesReference"):
                    s.children(v[n])
            for e in evals:
                s.eval(e, fid, timeout=40)
            if per_stop:
                per_stop(s, stop, fid, v, index)
        s.request("continue", {"threadId": tid}, show=False)
    s.finish(timeout=finish_timeout)
    s.close()
    return s


def spans():
    walk("spans", "spans", ["spans"], evals=["stack[1]", "stack.Length", "chars.Length", "chars.ToString()", "second", "window.Length", "*pointer", "pointer", "native + 1", "memory.Length", "memory.Span[0]"],
         expand=["stack", "chars", "window", "memory"])


def debugattrs():
    walk("debugattrs", "debugattrs", ["debugattrs", "attrs-visible"], evals=["order", "bag", "order.Hidden"], expand=["order", "bag"], max_stops=5)
    s = Session("debugattrs-step")
    s.launch("debugattrs", ["attrs-step"])
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        for command in ["stepIn", "stepOut", "stepIn", "stepOut", "stepIn", "stepOut", "next"]:
            s.request(command, {"threadId": tid}, show=False)
            st = s.stop(timeout=15, expect=command)
            if not st:
                break
            print("   %-8s -> %-9s %s" % (command, st.get("reason"), s.where(st["threadId"])))
            s.top(tid, 4)
    s.close()


def debugger_break():
    walk("break", "break", ["after-break"], max_stops=3)


def deep():
    def check(s, stop, fid, v, index):
        tid = stop["threadId"]
        for start, levels in [(0, 20), (2990, 20), (0, 0), (5000, 10)]:
            t0 = time.time()
            r = s.request("stackTrace", {"threadId": tid, "startFrame": start, "levels": levels}, show=False, timeout=60)
            body = r.get("body") or {}
            print("   stackTrace start=%d levels=%d -> %s frames, total=%s, %.0fms %s" % (start, levels, len(body.get("stackFrames", [])), body.get("totalFrames"), (time.time() - t0) * 1000, "" if r.get("success") else r.get("message")))
    walk("deep", "deep", ["deep-bottom"], per_stop=check, max_stops=1)


def huge():
    def check(s, stop, fid, v, index):
        for name in ["million", "bigDict", "bytes"]:
            if name in v:
                for paging in ({"filter": "indexed", "start": 0, "count": 100}, {"filter": "indexed", "start": 999_900, "count": 100}, {}):
                    t0 = time.time()
                    r = s.request("variables", dict({"variablesReference": v[name]["variablesReference"]}, **paging), show=False, timeout=120)
                    items = (r.get("body") or {}).get("variables", [])
                    print("   %s %s -> %d items in %.0fms %s first=%s" % (name, paging or "(no paging)", len(items), (time.time() - t0) * 1000, "" if r.get("success") else "FAILED " + short(r.get("message")), short(items[0]["name"] if items else None)))
        if "hugeString" in v:
            print("   hugeString value chars in locals: %d, type=%s" % (len(v["hugeString"].get("value", "")), v["hugeString"].get("type")))
        if "cycle" in v:
            node = v["cycle"]
            for depth in range(4):
                kids = s.children(node)
                nxt = [k for k in kids if k["name"] == "Next"]
                if not nxt or not nxt[0].get("variablesReference"):
                    break
                node = nxt[0]
    walk("huge", "huge", ["huge"], evals=["million.Count", "million[999999]", "hugeString.Length", "hugeString", "bigDict[\"key99999\"]", "cycle.Next.Next.Next.Name"], per_stop=check, max_stops=1, stop_timeout=60)


def intellij_paging():
    """What the DAP client of the JetBrains platform does to a big collection: filter "named" first (no start/count),
    then pages of filter "indexed". The named answer must not contain (or cost) the elements. Also: threads sorted by id."""
    import json
    def check(s, stop, fid, v, index):
        for name in ["million", "bigDict", "bytes"]:
            if name not in v:
                continue
            print("   %s indexedVariables=%s namedVariables=%s" % (name, v[name].get("indexedVariables"), v[name].get("namedVariables")))
            for paging in ({"filter": "named"}, {"filter": "indexed", "start": 0, "count": 100}, {"filter": "indexed", "start": 100, "count": 100}):
                t0 = time.time()
                r = s.request("variables", dict({"variablesReference": v[name]["variablesReference"]}, **paging), show=False, timeout=120)
                items = (r.get("body") or {}).get("variables", [])
                indexed = [i for i in items if i["name"].startswith("[")]
                verdict = ""
                if paging["filter"] == "named" and indexed:
                    verdict = " <-- BUG: %d indexed children in a 'named' answer" % len(indexed)
                if paging["filter"] == "indexed" and len(indexed) != len(items):
                    verdict = " <-- BUG: named children in an 'indexed' answer"
                print("   %s %s -> %d items, %d bytes, %.0fms %s names=%s%s" % (name, paging, len(items), len(json.dumps(r)), (time.time() - t0) * 1000,
                      "" if r.get("success") else "FAILED " + short(r.get("message")), short([i["name"] for i in items[:3]]), verdict))
        ids = [t["id"] for t in (s.request("threads", show=False).get("body") or {}).get("threads", [])]
        print("   threads %s sorted=%s" % (ids, ids == sorted(ids)))
    walk("intellij-paging", "huge", ["huge"], per_stop=check, max_stops=1, stop_timeout=60)


def evil():
    def check(s, stop, fid, v, index):
        if index > 0:
            return
        for name in ["throwing", "hanging", "evil"]:
            if name in v:
                print("   locals display %s = %s" % (name, short(v[name].get("value"), 160)))
        t0 = time.time()
        if "evil" in v:
            s.children(v["evil"])
            print("   expanding 'evil' took %.1fs" % (time.time() - t0))
        for e in ["evil.Fine", "evil.Slow", "evil.Hangs", "evil.Fine", "evil.Deadlocks", "evil.Fine", "hanging.ToString()", "throwing.ToString()", "hanging.Value"]:
            t0 = time.time()
            s.eval(e, fid, timeout=90)
        r = s.request("threads", show=False)
        print("   threads after evals: %s" % short([t["name"] for t in (r.get("body") or {}).get("threads", [])], 300))
    walk("evil", "evil", ["evil", "evil-after"], per_stop=check, max_stops=3, stop_timeout=40)


def unicode_names():
    walk("unicode", "unicode", ["unicode"], evals=["переменная + 1", "@class", "class", "変数", "ünïcödé.Length"], max_stops=1)


def patterns():
    walk("patterns", "patterns", ["pattern-arm", "patterns-end"], evals=["input", "input is int", "input is int n && n > 3", "required.Name", "changed", "changed.Amount"], max_stops=7)


def asynciter():
    walk("asynciter", "asynciter", ["asynciter-consume", "asynciter-produce", "asynciter-end"], evals=["total", "value", "i"], max_stops=8)


def parallel():
    s = Session("parallel")
    s.launch("parallel", ["parallel-body", "parallel-end"])
    hits = 0
    for _ in range(12):
        stop = s.stop(timeout=10)
        if not stop:
            break
        hits += 1
        print("   stop %d: %s | %s" % (hits, short(stop, 160), s.where(stop["threadId"])))
        if hits == 1:
            r = s.request("threads", show=False)
            at_bp = 0
            for t in (r.get("body") or {}).get("threads", []):
                frames = s.top(t["id"], 1, show=False)
                if frames and frames[0].get("line") == 0:
                    continue
                if frames:
                    at_bp += 1
            print("   threads with managed frames at first stop: %d" % at_bp)
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
    s.finish(timeout=10)
    s.close()


def deadlock():
    s = Session("deadlock")
    s.launch("deadlock", [])
    s.drain(1.5)
    s.request("pause", {"threadId": 0}, show=False)
    stop = s.stop(timeout=10, expect="pause")
    if stop:
        r = s.request("threads", show=False)
        for t in (r.get("body") or {}).get("threads", []):
            frames = s.top(t["id"], 5, show=False)
            print("   thread %-12s: %s" % (t["name"], " <- ".join("%s:%s" % (f["name"], f.get("line")) for f in frames)))
    s.close()


def staticctor():
    walk("staticctor", "staticctor", ["static-ctor", "staticctor-caught"], filters=["all"], evals=["$exception", "e.InnerException.Message", "GoodStatic.Value"], max_stops=5)


def filters():
    walk("filters", "filters", ["filter-body", "filters-catch", "filters-aggregate"], evals=["e", "e.Message", "e.InnerExceptions.Count", "e.InnerExceptions[0].Message"], max_stops=6)


def crashes():
    for scenario in ("threadcrash", "stackoverflow", "failfast", "envexit"):
        for filters in (["user-unhandled"],):
            s = Session("crash-" + scenario)
            s.launch(scenario, [], filters=filters)
            for _ in range(3):
                stop = s.stop(timeout=25, expect="crash stop or exit")
                if not stop:
                    break
                print("   STOP %s | %s" % (short(stop, 260), s.where(stop["threadId"])))
                s.request("exceptionInfo", {"threadId": stop["threadId"]}, n=300)
                s.top(stop["threadId"], 4)
                s.request("continue", {"threadId": stop["threadId"]}, show=False)
            s.finish(timeout=30)
            print("   stderr head: %r" % "".join(t for c, t in s.output if c == "stderr")[:200])
            s.close()


def dynamic_code():
    walk("dynamic", "dynamic", ["reflected", "dynamic"], evals=["expando", "expando.Name", "expando.Count + 1", "number + 1", "compiled(2)", "tree", "emitted", "fromIl", "x"], expand=["expando"], max_stops=3)
    s = Session("dynamic-stepin")
    s.launch("dynamic", ["dynamic"], launch_extra={"justMyCode": False})
    s.close()


def multiline():
    s = Session("multiline")
    s.launch("multiline", ["multi-statements", "multi-lambdas", "multi-for", "multi-continuation"])
    for _ in range(14):
        stop = s.stop(timeout=10)
        if not stop:
            break
        tid = stop["threadId"]
        print("   stop: %s | %s" % (stop.get("reason"), s.where(tid)))
        s.request("next", {"threadId": tid}, show=False)
        st = s.stop(timeout=10)
        if not st:
            break
        print("      next -> %s" % s.where(st["threadId"]))
        s.request("continue", {"threadId": tid}, show=False)
    s.finish(timeout=10)
    s.close()

    # column breakpoints on the line with two lambdas
    from dap import MARKERS
    path, line = MARKERS["multi-lambdas"]
    s = Session("column-breakpoints")
    s.initialize()
    seq = s.send("launch", {"program": __import__("dap").APP_DLL, "args": ["multiline"]})
    s.wait_event("initialized", 20)
    text = open(path, encoding="utf-8").read().splitlines()[line - 1]
    col_where = text.index("n % 2") + 1
    col_select = text.index("n * 10") + 1
    s.request("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": line, "column": col_where}, {"line": line, "column": col_select}]}, n=700)
    r = s.request("breakpointLocations", {"source": {"path": path}, "line": line})
    s.request("configurationDone", show=False)
    s.wait_response(seq, 30)
    for _ in range(10):
        stop = s.stop(timeout=8)
        if not stop:
            break
        print("   column bp stop: %s | %s" % (short(stop.get("hitBreakpointIds")), s.where(stop["threadId"])))
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
    s.finish(timeout=10)
    s.close()


def generics():
    walk("generics2", "generics2", ["nested-generic", "static-abstract", "generic-instantiations"], evals=["this", "First", "Second", "value", "typeof(T)", "shape.Area()", "T.Title"], max_stops=6)


def finalizer():
    walk("finalizer", "finalizer", ["finalizer", "finalizer-end"], evals=["this"], max_stops=3)


def childproc():
    walk("childproc", "childproc", ["childproc"], evals=["version", "child.Id", "child.HasExited"], max_stops=1, stop_timeout=40)


def refparams():
    walk("refparams", "refparams", ["refparams", "refparams-end"], evals=["value", "produced", "text", "value + produced"], max_stops=2)


def async_stepping():
    s = Session("async-stepping")
    s.launch("async", ["async-start"])
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        print("   at %s (thread %s)" % (s.where(tid), tid))
        for command in ["next", "stepIn", "next", "next", "next", "next", "stepOut", "next", "next", "next"]:
            s.request(command, {"threadId": tid}, show=False)
            st = s.stop(timeout=15, expect=command)
            if not st:
                break
            tid = st["threadId"]
            print("   %-8s -> %-9s %s (thread %s)" % (command, st.get("reason"), s.where(tid), tid))
        s.top(tid, 6)
    s.close()


ALL = {f.__name__: f for f in (spans, debugattrs, debugger_break, deep, huge, intellij_paging, evil, unicode_names, patterns, asynciter, parallel, deadlock, staticctor, filters, crashes,
                               dynamic_code, multiline, generics, finalizer, childproc, refparams, async_stepping)}

if __name__ == "__main__":
    for name in (sys.argv[1:] or ALL):
        try:
            ALL[name]()
        except Exception:
            print("!!! harness error in %s:\n%s" % (name, traceback.format_exc()))
