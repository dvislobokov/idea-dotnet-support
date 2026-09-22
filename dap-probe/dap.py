"""A small DAP client for probing dotnet-debugger: never raises on adapter misbehaviour, records everything."""
import glob
import json
import os
import queue
import re
import subprocess
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
WINDOWS = os.name == "nt"
# DAP_ADAPTER points at a local build; by default the tool installed with `--tool-path tools`
ADAPTER = os.environ.get("DAP_ADAPTER") or os.path.join(HERE, "tools", "dotnet-debugger.exe" if WINDOWS else "dotnet-debugger")
ROOT = os.path.join(HERE, "t")
APP_DLL = os.path.join(ROOT, "App", "bin", "Debug", "net10.0", "App.dll")


def markers():
    found = {}
    for path in glob.glob(os.path.join(ROOT, "**", "*.cs"), recursive=True):
        if os.sep + "obj" + os.sep in path or os.sep + "bin" + os.sep in path or "#1" in path:  # the copy made for the path test
            continue
        with open(path, encoding="utf-8") as f:
            for number, line in enumerate(f, 1):
                m = re.search(r"// BP:([\w-]+)", line)
                if m:
                    found[m.group(1)] = (path, number)
    return found


MARKERS = markers()


def pid_alive(pid):
    if WINDOWS:
        return str(pid) in subprocess.run(["tasklist", "/FI", "PID eq %s" % pid], capture_output=True, text=True).stdout
    try:
        # a zombie still has a /proc entry: look at its state
        with open("/proc/%s/stat" % pid) as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


def kill_pid(pid):
    if WINDOWS:
        subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
    else:
        subprocess.run(["kill", "-9", str(pid)], capture_output=True)


def short(o, n=300):
    s = o if isinstance(o, str) else json.dumps(o, ensure_ascii=False)
    s = s.replace(HERE, "<dap>").replace(HERE.replace("\\", "\\\\"), "<dap>")
    return s if len(s) <= n else s[:n] + "...(%d)" % len(s)


class Session:
    def __init__(self, name, adapter_args=(), quiet_events=("module", "thread", "loadedSource")):
        self.name = name
        self.quiet_events = set(quiet_events)
        self.log_path = os.path.join(HERE, "logs", name + ".adapter.log")
        os.makedirs(os.path.dirname(self.log_path), exist_ok=True)
        self.transcript = open(os.path.join(HERE, "logs", name + ".transcript.jsonl"), "w", encoding="utf-8")
        self.proc = subprocess.Popen([ADAPTER, "--log=" + self.log_path, *adapter_args], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.messages = queue.Queue()
        self.seq = 0
        self.backlog = []
        self.responses = {}
        self.all_events = []
        self.output = []
        self.stderr_text = []
        threading.Thread(target=self._reader, daemon=True).start()
        threading.Thread(target=self._stderr, daemon=True).start()
        print("\n===== %s =====" % name)

    # --- plumbing
    def _stderr(self):
        for line in self.proc.stderr:
            self.stderr_text.append(line.decode("utf-8", errors="replace"))

    def _reader(self):
        out = self.proc.stdout
        try:
            while True:
                header = b""
                while not header.endswith(b"\r\n\r\n"):
                    c = out.read(1)
                    if not c:
                        self.messages.put(None)
                        return
                    header += c
                length = int(re.search(rb"content-length:\s*(\d+)", header, re.I).group(1))
                body = b""
                while len(body) < length:
                    chunk = out.read(length - len(body))
                    if not chunk:
                        self.messages.put(None)
                        return
                    body += chunk
                message = json.loads(body.decode("utf-8"))
                self.transcript.write(json.dumps({"dir": "in", "t": time.time(), "m": message}, ensure_ascii=False) + "\n")
                self.messages.put(message)
        except Exception as e:  # a malformed frame is a finding in itself
            self.messages.put({"type": "reader-error", "error": repr(e)})
            self.messages.put(None)

    def send_raw(self, data: bytes):
        try:
            self.proc.stdin.write(data)
            self.proc.stdin.flush()
        except OSError as e:
            print("   ! cannot write to adapter: %r" % e)

    def send(self, command, arguments=None):
        self.seq += 1
        message = {"seq": self.seq, "type": "request", "command": command}
        if arguments is not None:
            message["arguments"] = arguments
        self.transcript.write(json.dumps({"dir": "out", "t": time.time(), "m": message}, ensure_ascii=False) + "\n")
        body = json.dumps(message).encode("utf-8")
        self.send_raw(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        return self.seq

    def _pump(self, timeout):
        try:
            m = self.messages.get(timeout=timeout)
        except queue.Empty:
            return "empty"
        if m is None:
            return None
        if m.get("type") == "event":
            self.all_events.append(m)
            self.backlog.append(m)
            if m["event"] == "output":
                self.output.append((m["body"].get("category"), m["body"].get("output", "")))
            elif m["event"] not in self.quiet_events:
                print("   ~ %s %s" % (m["event"], short(m.get("body"), 260)))
        elif m.get("type") == "response":
            self.responses[m.get("request_seq")] = m
        elif m.get("type") == "request":
            print("   <- reverse request %s %s" % (m.get("command"), short(m.get("arguments"))))
            self.backlog.append(m)
        elif m.get("type") == "reader-error":
            print("   ! reader error", m["error"])
        return m

    def wait_response(self, request_seq, timeout=30):
        # responses may arrive while another one is awaited (attach answers before configurationDone): keep them all
        end = time.time() + timeout
        while True:
            if request_seq in self.responses:
                return self.responses.pop(request_seq)
            if time.time() >= end:
                return {"success": False, "message": "TIMEOUT after %ss" % timeout}
            if self._pump(0.5) is None:
                if request_seq in self.responses:
                    return self.responses.pop(request_seq)
                return {"success": False, "message": "ADAPTER EXITED (code %s)" % self.proc.poll()}

    def request(self, command, arguments=None, timeout=30, show=True, n=300):
        started = time.time()
        response = self.wait_response(self.send(command, arguments), timeout)
        took = (time.time() - started) * 1000
        if show:
            payload = response.get("body") if response.get("success") else response.get("message")
            print("-> %s %s | %.0fms ok=%s %s" % (command, short(arguments, 140) if arguments else "", took, response.get("success"), short(payload, n)))
        return response

    def wait_event(self, name, timeout=30, pred=None):
        def take():
            for e in self.backlog:
                if e.get("type") == "event" and e["event"] == name and (pred is None or pred(e)):
                    self.backlog.remove(e)
                    return e
            return None
        end = time.time() + timeout
        while True:
            e = take()
            if e:
                return e
            if time.time() >= end:
                return None
            if self._pump(0.5) is None:
                return take()

    def drain(self, seconds=1.0):
        end = time.time() + seconds
        while time.time() < end:
            if self._pump(0.2) is None:
                return

    # --- typical flows
    def initialize(self, **extra):
        args = {"clientID": "probe", "adapterID": "coreclr", "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path",
                "supportsVariableType": True, "supportsVariablePaging": True, "supportsRunInTerminalRequest": False}
        args.update(extra)
        return self.request("initialize", args, show=False)

    def set_breakpoints(self, names_or_specs, show=True):
        """names: marker names or (marker, {extra}) tuples; grouped per file as DAP requires."""
        by_file = {}
        for item in names_or_specs:
            name, extra = (item, {}) if isinstance(item, str) else item
            path, line = MARKERS[name]
            by_file.setdefault(path, []).append(dict({"line": line}, **extra))
        results = {}
        for path, bps in by_file.items():
            r = self.request("setBreakpoints", {"source": {"path": path}, "breakpoints": bps}, show=show)
            results[path] = r
        return results

    def launch(self, scenario, bps=(), filters=(), launch_extra=None, args_extra=(), program=APP_DLL, wait=True):
        self.initialize()
        arguments = {"program": program, "args": [scenario, *args_extra], "cwd": os.path.dirname(program), "justMyCode": True}
        arguments.update(launch_extra or {})
        launch_seq = self.send("launch", arguments)
        if not self.wait_event("initialized", 30):
            print("   ! no initialized event")
            return self.wait_response(launch_seq, 5)
        self.set_breakpoints(bps, show=False)
        self.request("setExceptionBreakpoints", {"filters": list(filters)}, show=False)
        self.request("configurationDone", show=False)
        response = self.wait_response(launch_seq, 60)
        if not response.get("success"):
            print("   ! launch failed: %s" % short(response.get("message")))
        return response

    def stop(self, timeout=30, expect=None):
        e = self.wait_event("stopped", timeout)
        if e is None:
            print("   ! NO stopped event within %ss (expected %s)" % (timeout, expect))
            return None
        return e["body"]

    def top(self, thread_id, levels=6, show=True):
        r = self.request("stackTrace", {"threadId": thread_id, "startFrame": 0, "levels": levels}, show=False)
        frames = (r.get("body") or {}).get("stackFrames", [])
        if show:
            print("   stack: " + " <- ".join("%s:%s" % (f["name"], f.get("line")) for f in frames) + "  total=%s" % (r.get("body") or {}).get("totalFrames"))
        return frames

    def where(self, thread_id):
        frames = self.top(thread_id, 1, show=False)
        if not frames:
            return "?"
        f = frames[0]
        return "%s @ %s:%s:%s" % (f["name"], os.path.basename((f.get("source") or {}).get("path", "?")), f.get("line"), f.get("column"))

    def locals(self, frame_id, show=True, n=110):
        result = {}
        scopes = (self.request("scopes", {"frameId": frame_id}, show=False).get("body") or {}).get("scopes", [])
        for scope in scopes:
            r = self.request("variables", {"variablesReference": scope["variablesReference"]}, show=False)
            for v in (r.get("body") or {}).get("variables", []):
                result[v["name"]] = v
                if show:
                    print("   %s.%s = %s  : %s  ref=%s%s" % (scope["name"], v["name"], short(v.get("value"), n), v.get("type"), v.get("variablesReference"),
                                                         "".join(" %s=%s" % (k, v[k]) for k in ("indexedVariables", "namedVariables", "presentationHint") if k in v)))
        return scopes, result

    def children(self, variable, show=True, n=110, **paging):
        args = {"variablesReference": variable["variablesReference"]}
        args.update(paging)
        started = time.time()
        r = self.request("variables", args, show=False, timeout=60)
        items = (r.get("body") or {}).get("variables", [])
        if show:
            print("   children of %s (%d, %.0fms)%s: %s" % (variable.get("name"), len(items), (time.time() - started) * 1000, "" if r.get("success") else " FAILED " + short(r.get("message")),
                                                         "; ".join("%s=%s" % (c["name"], short(c.get("value"), 60)) for c in items[:14]) + (" ..." if len(items) > 14 else "")))
        return items

    def eval(self, expression, frame_id, context="watch", timeout=30, n=200):
        started = time.time()
        r = self.request("evaluate", {"expression": expression, "frameId": frame_id, "context": context}, show=False, timeout=timeout)
        took = (time.time() - started) * 1000
        body = r.get("body") or {}
        print("   eval[%s] %-46s -> %s%s  (%.0fms)" % (context, expression, (short(body.get("result"), n) + " : " + str(body.get("type"))) if r.get("success") else "ERROR " + short(r.get("message"), n),
                                                     " ref=%s" % body.get("variablesReference") if body.get("variablesReference") else "", took))
        return r

    def finish(self, thread_id=None, timeout=20):
        if thread_id is not None:
            self.request("continue", {"threadId": thread_id}, show=False)
        exited = self.wait_event("exited", timeout)
        terminated = self.wait_event("terminated", 5)
        print("   end: exited=%s terminated=%s" % (exited["body"] if exited else None, terminated is not None))
        return exited, terminated

    def close(self, terminate=True):
        if self.proc.poll() is None:
            self.request("disconnect", {"terminateDebuggee": terminate}, show=False, timeout=10)
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                print("   ! adapter still alive 5s after disconnect; killing")
                self.proc.kill()
        err = "".join(self.stderr_text).strip()
        if err:
            print("   adapter stderr: " + short(err, 600))
        print("   adapter exit code: %s" % self.proc.poll())
        self.transcript.close()

    def stdout_text(self):
        return "".join(text for category, text in self.output if category in ("stdout", None))
