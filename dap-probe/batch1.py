"""Typical IDE scenarios."""
import sys
import traceback

from dap import MARKERS, Session, short


def variables():
    s = Session("variables")
    s.launch("variables", ["variables"])
    stop = s.stop(expect="variables")
    if stop:
        tid = stop["threadId"]
        frames = s.top(tid)
        fid = frames[0]["id"]
        scopes, v = s.locals(fid)
        print("   scopes: %s" % [(x["name"], x.get("presentationHint"), x.get("expensive")) for x in scopes])
        for name in ["numbers", "grid", "jagged", "list", "dict", "set", "point", "tuple", "person", "anon", "maybe", "date", "asBase", "derived", "lazy", "twice", "task", "ex", "dyn", "boxed", "perm"]:
            if name in v and v[name].get("variablesReference"):
                s.children(v[name])
        if "numbers" in v:
            s.children(v["numbers"], filter="indexed", start=100, count=5)
            s.children(v["numbers"], filter="indexed", start=245, count=50)
        if "dict" in v:
            kids = s.children(v["dict"], show=False)
            for k in kids[:1]:
                if k.get("variablesReference"):
                    s.children(k)
        if "longText" in v:
            print("   longText value length reported: %d" % len(v["longText"].get("value", "")))
        for e in ["i + 1", "i / 0", "d * 2", "m + 1.5m", "s.Length", "s.ToUpper()", "s[0]", "nothing?.Length", "nothing ?? \"fallback\"", "nothing.Length",
                  "flag ? 1 : 2", "perm", "perm.HasFlag(Perm.Write)", "(int)perm", "day", "point.X + point.Y", "point", "tuple.Name", "person.Name", "person",
                  "numbers[10]", "numbers.Length", "numbers[999]", "grid[1,2]", "jagged[1][0]", "list[1]", "list.Count", "dict[\"two\"]", "dict[\"missing\"]", "set.Contains(2)",
                  "date.Year", "date.AddDays(1)", "guid", "span.TotalMinutes", "(int)boxed + 1", "boxed is int", "asBase as Derived", "asBase.Kind", "((Derived)asBase).Own",
                  "derived.Greet(\"bob\")", "derived.Greet(\"bob\", 2)", "derived[3]", "Derived.Twice(21)", "Derived.Counter", "derived.SideEffect", "Derived.Counter", "derived.Throws",
                  "derived._secret", "derived.baseField", "Math.Max(i, 100)", "string.Join(\",\", list)", "System.IO.Path.Combine(\"a\", \"b\")", "new System.Text.StringBuilder(\"x\").Append(1).ToString()",
                  "new Person(\"Zed\", 1)", "typeof(Derived)", "typeof(Derived).Name", "nameof(derived)", "$\"{i}-{s.Length}\"", "i == 42 && flag", "i > 10 || nothing == null",
                  "twice(4)", "task.Result", "lazy.Count()", "numbers.Sum()", "list.Select(x => x + \"!\").First()", "ex.Message", "dyn", "dyn.Length", "c + 1", "b + 1", "big + 1", "f * 2",
                  "undefinedName", "i +", "", "i = 100", "i", "s = \"changed\"", "s", "default(int)", "sizeof(int)", "checked(big + 1)", "this"]:
            s.eval(e, fid)
        s.eval("s", fid, context="hover")
        s.eval("s", fid, context="repl")
        s.eval("s", fid, context="clipboard")
        s.eval("longText", fid, context="clipboard", n=60)
        s.eval("s,nq", fid)
        s.eval("i,h", fid)
        s.eval("numbers,5", fid)
        # set values
        ref = scopes[0]["variablesReference"]
        for name, value in [("i", "7"), ("i", "i * 2"), ("s", "\"new text\""), ("flag", "false"), ("d", "not a number"), ("maybe", "null"), ("perm", "Perm.Exec"), ("point", "new Point(9, 9)"), ("nothing", "\"now set\"")]:
            r = s.request("setVariable", {"variablesReference": ref, "name": name, "value": value}, show=False)
            print("   setVariable %s := %s -> %s" % (name, value, short(r.get("body") if r.get("success") else "ERROR " + str(r.get("message")), 120)))
        r = s.request("setExpression", {"expression": "derived.Own", "value": "55", "frameId": fid}, show=False)
        print("   setExpression derived.Own := 55 -> %s" % short(r.get("body") if r.get("success") else "ERROR " + str(r.get("message"))))
        s.eval("derived.Own", fid)
        s.request("next", {"threadId": tid}, show=False)
        s.stop()
        s.drain(0.5)
        s.finish(tid)
        print("   program output tail: %r" % s.stdout_text()[-120:])
    s.close()


def stepping():
    s = Session("stepping")
    s.launch("stepping", ["step-start"])
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        print("   at " + s.where(tid))
        plan = ["stepIn", "next", "next", "stepOut", "next", "stepIn", "stepOut", "next", "stepIn", "stepOut", "next", "stepIn", "next", "next", "next", "next", "stepIn", "next", "next", "next", "next", "next", "next", "next"]
        for command in plan:
            r = s.request(command, {"threadId": tid}, show=False)
            st = s.stop(timeout=15, expect=command)
            if st is None:
                break
            tid = st["threadId"]
            print("   %-8s -> %-9s %s%s" % (command, st.get("reason"), s.where(tid), "" if r.get("success") else "  (request failed: %s)" % r.get("message")))
        s.finish(tid)
    s.close()

    s = Session("stepping-nojmc")
    s.launch("stepping", ["step-lambda"], launch_extra={"justMyCode": False, "enableStepFiltering": False})
    stop = s.stop()
    if stop:
        tid = stop["threadId"]
        for command in ["stepIn", "stepIn", "stepOut", "stepOut"]:
            s.request(command, {"threadId": tid}, show=False)
            st = s.stop(timeout=20, expect=command)
            if st is None:
                break
            print("   %-8s -> %-9s %s" % (command, st.get("reason"), s.where(st["threadId"])))
        s.top(tid, 5)
    s.close()


def async_code():
    s = Session("async")
    s.launch("async", ["async-start", "async-inner"])
    stop = s.stop()
    seen = 0
    while stop and seen < 12:
        seen += 1
        tid = stop["threadId"]
        frames = s.top(tid, 8)
        if seen == 1:
            for command in ["next", "next"]:
                s.request(command, {"threadId": tid}, show=False)
                st = s.stop(timeout=15, expect="next over await")
                if st:
                    tid = st["threadId"]
                    print("   %s -> %s %s (thread %s)" % (command, st.get("reason"), s.where(tid), tid))
        if seen == 3 and frames:
            s.locals(frames[0]["id"])
            s.request("stepOut", {"threadId": tid}, show=False)
            st = s.stop(timeout=15, expect="stepOut of async")
            if st:
                print("   stepOut of async -> %s %s" % (st.get("reason"), s.where(st["threadId"])))
                tid = st["threadId"]
        s.request("continue", {"threadId": tid}, show=False)
        stop = s.stop(timeout=8, expect="next breakpoint or end")
    s.finish()
    s.close()


def threads():
    s = Session("threads", quiet_events=("module", "loadedSource"))
    s.launch("threads", ["threads-worker", "threads-main"])
    stop = s.stop()
    if stop:
        print("   stopped: %s" % short(stop))
        r = s.request("threads", show=True, n=500)
        for t in (r.get("body") or {}).get("threads", []):
            frames = s.top(t["id"], 3, show=False)
            print("   thread %s %-16s: %s" % (t["id"], t["name"], " <- ".join(f["name"] for f in frames)))
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
        second = s.stop()
        print("   second stop: %s" % short(second))
        if second:
            s.request("continue", {"threadId": second["threadId"]}, show=False)
    # pause a running program
    s.drain(0.3)
    s.finish(timeout=10)
    s.close()

    s = Session("pause")
    s.launch("sleeper")
    s.drain(1.0)
    r = s.request("pause", {"threadId": 0})
    stop = s.stop(timeout=10, expect="pause")
    if stop:
        print("   paused: %s" % short(stop))
        r = s.request("threads", show=False)
        for t in (r.get("body") or {}).get("threads", []):
            frames = s.top(t["id"], 4, show=False)
            print("   thread %s %-14s: %s" % (t["id"], t["name"], " <- ".join("%s:%s" % (f["name"], f.get("line")) for f in frames)))
        main = [t for t in (r.get("body") or {}).get("threads", []) if "Main" in t["name"]]
        if main:
            frames = s.top(main[0]["id"], 6)
            user = [f for f in frames if f.get("source")]
            if user:
                s.locals(user[0]["id"])
                s.eval("i", user[0]["id"])
        s.request("pause", {"threadId": 0})
        s.request("continue", {"threadId": stop["threadId"]})
        s.drain(0.6)
        r = s.request("terminate", {})
        ex, term = s.finish(timeout=10)
    s.close()


def exceptions():
    for filters in (["all"], ["user-unhandled"], []):
        s = Session("exceptions-" + ("-".join(filters) or "none"))
        s.launch("exceptions", ["ex-done"], filters=filters)
        for _ in range(8):
            stop = s.stop(timeout=10)
            if not stop:
                break
            tid = stop["threadId"]
            print("   stop: %s | %s" % (short(stop, 200), s.where(tid)))
            if stop.get("reason") == "exception":
                s.request("exceptionInfo", {"threadId": tid}, n=700)
                frames = s.top(tid, 3, show=False)
                if frames:
                    s.eval("$exception", frames[0]["id"])
                    s.eval("$exception.Message", frames[0]["id"])
            s.request("continue", {"threadId": tid}, show=False)
        s.finish(timeout=10)
        s.close()

    for scenario in ("unhandled", "asyncunhandled"):
        for filters in (["user-unhandled"], []):
            s = Session("%s-%s" % (scenario, "-".join(filters) or "none"))
            s.launch(scenario, [], filters=filters)
            stop = s.stop(timeout=10, expect="unhandled exception")
            if stop:
                print("   stop: %s | %s" % (short(stop, 240), s.where(stop["threadId"])))
                s.request("exceptionInfo", {"threadId": stop["threadId"]}, n=600)
                s.top(stop["threadId"], 5)
                s.request("continue", {"threadId": stop["threadId"]}, show=False)
            s.finish(timeout=15)
            print("   stderr tail: %r" % "".join(t for c, t in s.output if c == "stderr")[-160:])
            s.close()

    # conditions on exception filters
    s = Session("exceptions-conditions")
    s.initialize()
    seq = s.send("launch", {"program": __import__("dap").APP_DLL, "args": ["exceptions"], "justMyCode": True})
    s.wait_event("initialized", 20)
    s.request("setExceptionBreakpoints", {"filters": [], "filterOptions": [{"filterId": "all", "condition": "DebugTarget.ShopException, System.Format*"}]})
    s.request("configurationDone", show=False)
    s.wait_response(seq, 30)
    for _ in range(5):
        stop = s.stop(timeout=8)
        if not stop:
            break
        print("   stop: %s" % short(stop, 200))
        s.request("continue", {"threadId": stop["threadId"]}, show=False)
    s.finish(timeout=10)
    s.close()


def closures():
    s = Session("closures")
    s.launch("closures", ["lambda-body", "local-function", "generic-method", "closures-end", "lib-add"])
    for _ in range(6):
        stop = s.stop(timeout=10)
        if not stop:
            break
        tid = stop["threadId"]
        frames = s.top(tid, 4)
        _, v = s.locals(frames[0]["id"])
        for e in ["factor", "prefix", "n * factor", "this", "Value", "StaticReadonly", "Closures.StaticReadonly"]:
            s.eval(e, frames[0]["id"])
        s.request("continue", {"threadId": tid}, show=False)
    s.finish(timeout=10)
    s.close()


def output():
    s = Session("output")
    s.launch("output", ["output-done"])
    stop = s.stop(timeout=15)
    cats = {}
    for c, t in s.output:
        cats.setdefault(c, []).append(t)
    for c, chunks in cats.items():
        text = "".join(chunks)
        print("   category %-8s: %d events, %d chars; head=%r tail=%r" % (c, len(chunks), len(text), text[:70], text[-70:]))
    text = s.stdout_text()
    print("   bulk lines present: %d of 200; cyrillic ok: %s; 'no newline' ok: %s" % (sum(1 for i in range(200) if ("bulk %d\n" % i) in text.replace("\r\n", "\n")), "Кириллица и emoji 🙂 €" in text, "no newline" in text))
    if stop:
        s.finish(stop["threadId"])
    s.close()

    s = Session("stdin")
    s.launch("stdin", ["stdin-read"])
    stop = s.stop(timeout=8, expect="ReadLine returning")
    print("   stdin: stop=%s output=%r" % (short(stop), s.stdout_text()))
    if stop:
        frames = s.top(stop["threadId"], 2)
        s.eval("line", frames[0]["id"])
        s.finish(stop["threadId"])
    s.close()

    for name, extra, args in [("env", {"env": {"MY_VAR": "from-dap", "PATH": None}, "cwd": __import__("os").path.dirname(MARKERS["entry"][0])}, ["a b", "quo\"te", "ünï"]),
                              ("exitcode", {}, [])]:
        s = Session("launch-" + name)
        s.launch(name, [], launch_extra=extra, args_extra=args)
        s.finish(timeout=15)
        print("   output: %r" % s.stdout_text())
        s.close()


ALL = {f.__name__: f for f in (variables, stepping, async_code, threads, exceptions, closures, output)}

if __name__ == "__main__":
    for name in (sys.argv[1:] or ALL):
        try:
            ALL[name]()
        except Exception:
            print("!!! harness error in %s:\n%s" % (name, traceback.format_exc()))
