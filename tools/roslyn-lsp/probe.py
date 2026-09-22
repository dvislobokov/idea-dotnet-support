"""Black-box probe of roslyn-language-server: what it is started with, what it can do, which settings it asks the client for.

    python probe.py [WORKSPACE_DIR] [--out probe.json]

Starts the server with --stdio, performs the LSP handshake, answers `workspace/configuration` with nulls (the server then uses
its defaults) and writes down: server info, capabilities, dynamic registrations, every configuration section requested, and the
notifications seen. Nothing here belongs to the build of the plugin.
"""
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path


def find_server():
    configured = os.environ.get("ROSLYN_LANGUAGE_SERVER")
    if configured:
        return [configured]
    name = "roslyn-language-server.cmd" if os.name == "nt" else "roslyn-language-server"
    tool = Path.home() / ".dotnet" / "tools" / name
    return ["cmd.exe", "/c", str(tool)] if os.name == "nt" else [str(tool)]


class Server:
    def __init__(self, command):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.responses = {}
        self.requests = []
        self.notifications = []
        self.configuration_sections = []
        self.lock = threading.Condition()
        self.next_id = 1
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for _ in self.process.stderr:
            pass

    def _read(self):
        stream = self.process.stdout
        while True:
            length = None
            while True:
                line = stream.readline()
                if not line:
                    return
                line = line.strip()
                if not line:
                    break
                if line.lower().startswith(b"content-length:"):
                    length = int(line.split(b":")[1])
            if length is None:
                continue
            message = json.loads(stream.read(length).decode("utf-8"))
            message["_bytes"] = length  # the size on the wire, for bench.py
            self._handle(message)

    def _handle(self, message):
        with self.lock:
            if "method" in message and "id" in message:
                self.requests.append(message)
                result = None
                if message["method"] == "workspace/configuration":
                    items = message["params"]["items"]
                    self.configuration_sections.extend(item.get("section") for item in items)
                    result = [None] * len(items)
                self._send({"jsonrpc": "2.0", "id": message["id"], "result": result})
            elif "method" in message:
                self.notifications.append(message)
            else:
                self.responses[message["id"]] = message
            self.lock.notify_all()

    def _send(self, message):
        body = json.dumps(message).encode("utf-8")
        self.process.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
        self.process.stdin.flush()

    def request(self, method, params, timeout=60):
        with self.lock:
            request_id = self.next_id
            self.next_id += 1
            self._send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})
            deadline = time.time() + timeout
            while request_id not in self.responses:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError(method)
                self.lock.wait(remaining)
            return self.responses.pop(request_id)

    def notify(self, method, params):
        with self.lock:
            self._send({"jsonrpc": "2.0", "method": method, "params": params})


def main():
    arguments = [a for a in sys.argv[1:] if not a.startswith("--")]
    workspace = Path(arguments[0]).resolve() if arguments else Path.cwd()
    out = Path(sys.argv[sys.argv.index("--out") + 1]) if "--out" in sys.argv else Path("probe.json")
    command = find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs")]
    server = Server(command)
    initialize = server.request("initialize", {
        "processId": os.getpid(),
        "rootUri": workspace.as_uri(),
        "workspaceFolders": [{"uri": workspace.as_uri(), "name": workspace.name}],
        "capabilities": {
            "workspace": {"configuration": True, "didChangeConfiguration": {"dynamicRegistration": True}, "workspaceFolders": True,
                          "didChangeWatchedFiles": {"dynamicRegistration": True}},
            "textDocument": {"publishDiagnostics": {}, "diagnostic": {"dynamicRegistration": True}, "inlayHint": {"dynamicRegistration": True},
                             "codeLens": {"dynamicRegistration": True}, "completion": {"completionItem": {"snippetSupport": True}}},
            "window": {"workDoneProgress": True},
        },
    })
    server.notify("initialized", {})
    # the server pulls its settings after this notification
    server.notify("workspace/didChangeConfiguration", {"settings": {}})
    time.sleep(8)
    result = initialize.get("result", {})
    report = {
        "command": command,
        "serverInfo": result.get("serverInfo"),
        "capabilities": result.get("capabilities"),
        "registrations": [r for m in server.requests if m["method"] == "client/registerCapability" for r in m["params"]["registrations"]],
        "requestsFromServer": sorted({m["method"] for m in server.requests}),
        "notificationsFromServer": sorted({m["method"] for m in server.notifications}),
        "configurationSections": server.configuration_sections,
    }
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    try:
        server.request("shutdown", None, timeout=15)
        server.notify("exit", None)
    except Exception:
        pass
    server.process.kill()
    print("sections:", len(server.configuration_sections), "->", out)


if __name__ == "__main__":
    main()
