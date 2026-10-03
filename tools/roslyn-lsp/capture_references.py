"""
What roslyn-language-server tells about the usages of a symbol: `textDocument/references` with and without the declaration, on a field
that is read, written, passed by ref / out and named in `nameof`, on a method called from two projects, on a type and on an attribute
(debug-playground/Console/Editor/FindUsages.cs and Lib/UsageLog.cs). The questions behind it (Find Usages with grouping, as in Rider):
does an answer say what kind of usage each one is (read / write / nameof / attribute), does it tell the declaration from the usages,
what does `includeDeclaration` change. Also the same file with `documentHighlight` (whose kinds are Read / Write in plain LSP) and,
in a second run, `references` for a client that says it is Visual Studio (`_vs_supportsVisualStudioExtensions`): Roslyn answers such a
client with its own items, which carry a kind; the IntelliJ client cannot say that and could not read the answer.

    python tools/roslyn-lsp/capture_references.py            # -> tools/roslyn-lsp/out/capture-references/*.json, a summary on stdout
    python tools/roslyn-lsp/capture_references.py --fixtures src/test/resources/roslyn/capture-5.12-references

Nothing on disk is changed: the files are opened as they are.
"""
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from probe import Server, find_server  # noqa: E402
from capture import Capture, FALLBACK_CAPABILITIES, option  # noqa: E402


def start(root, solution, out, capabilities, logs):
    server = Server(find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / logs)])
    capture = Capture(server, out)
    capture.request("initialize", {"processId": os.getpid(), "rootUri": root.as_uri(), "locale": "en",
                                   "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}], "capabilities": capabilities})
    server.notify("initialized", {})
    server.notify("solution/open", {"solution": solution.as_uri()})
    deadline = time.time() + 240
    while time.time() < deadline:
        with server.lock:
            if any(m["method"] == "workspace/projectInitializationComplete" for m in server.notifications):
                break
            server.lock.wait(0.2)
    return server, capture


def main():
    solution = Path(option("--solution", "debug-playground/DebugPlayground.sln")).resolve()
    root = solution.parent
    out = Path(option("--out", str(Path(__file__).parent / "out" / "capture-references")))
    capabilities_file = Path(__file__).parent / "client-capabilities.json"
    capabilities = json.loads(capabilities_file.read_text(encoding="utf-8")) if capabilities_file.exists() else FALLBACK_CAPABILITIES

    file = root / "Console" / "Editor" / "FindUsages.cs"
    text = file.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
    document = {"uri": file.as_uri()}

    def at(marker, inside=0, last=False):
        offset = (text.rindex(marker) if last else text.index(marker)) + inside
        line = text.count("\n", 0, offset)
        return {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}

    targets = [
        ("field", "public int Counter;", len("public int ")),
        ("method", "UsageLog.Record(\"created\")", len("UsageLog.")),
        ("type", "public class UsageSample", len("public class ")),
        ("attribute", "class UsageNoteAttribute", len("class ")),
    ]

    server, capture = start(root, solution, out, capabilities, "roslyn-logs-references")
    server.notify("textDocument/didOpen", {"textDocument": {"uri": file.as_uri(), "languageId": "csharp", "version": 1, "text": text}})
    capture.request("textDocument/diagnostic", {"textDocument": document}, timeout=180)
    summary = []
    for name, marker, inside in targets:
        for declaration in (True, False):
            params = {"textDocument": document, "position": at(marker, inside), "context": {"includeDeclaration": declaration}}
            result = capture.request("textDocument/references", params, "textDocument/references %s%s" % (name, "" if declaration else " without declaration")) or []
            summary.append({"target": name, "includeDeclaration": declaration, "count": len(result),
                            "keys": sorted({key for item in result for key in item}),
                            "files": sorted({item["uri"].rsplit("/", 1)[-1] for item in result if "uri" in item})})
    highlights = capture.request("textDocument/documentHighlight", {"textDocument": document, "position": at(*targets[0][1:])}, "textDocument/documentHighlight field") or []
    summary.append({"target": "field", "documentHighlight kinds": sorted({item.get("kind") for item in highlights}), "count": len(highlights)})
    server.notify("exit", {})
    server.process.kill()

    # the same question from a client that says it is Visual Studio: its own record files, numbered on
    vs_capabilities = dict(capabilities, _vs_supportsVisualStudioExtensions=True)
    vs_out = out / "vs"
    server, capture = start(root, solution, vs_out, vs_capabilities, "roslyn-logs-references-vs")
    server.notify("textDocument/didOpen", {"textDocument": {"uri": file.as_uri(), "languageId": "csharp", "version": 1, "text": text}})
    capture.request("textDocument/diagnostic", {"textDocument": document}, timeout=180)
    params = {"textDocument": document, "position": at(*targets[0][1:]), "context": {"includeDeclaration": True}}
    vs = capture.request("textDocument/references", params, "textDocument/references field visual studio client") or []
    summary.append({"target": "field", "client": "visual studio", "count": len(vs), "keys": sorted({key for item in vs for key in item}),
                    "kinds": sorted({json.dumps(item.get("_vs_kind")) for item in vs})})
    server.notify("exit", {})
    server.process.kill()

    capture.save("summary of references", {"method": "(summary)", "result": summary})
    for row in summary:
        print(json.dumps(row, ensure_ascii=False))

    fixtures = option("--fixtures")
    if fixtures:
        target_dir = Path(fixtures)
        target_dir.mkdir(parents=True, exist_ok=True)
        for old in target_dir.glob("*.json"):
            old.unlink()
        home = [root.as_uri(), root.as_uri().replace("file:///C:", "file:///c:"), str(root).replace("\\", "\\\\"), str(root).replace("\\", "/"), str(root)]
        for source in sorted(out.glob("*.json")) + sorted(vs_out.glob("*.json")):
            # the pull of diagnostics only makes the server ready, as in the IDE: not a thing of the fixtures
            if "initialize" in source.name or "diagnostic" in source.name:
                continue
            body = json.dumps(json.loads(source.read_text(encoding="utf-8")), ensure_ascii=False, indent=1)
            for index, prefix in enumerate(home):
                body = body.replace(prefix, "file:///c:/playground" if index < 2 else "c:/playground")
            body = re.sub(r'(?i)c(:|%3a)([/\\]|%5c)users\2[^/\\%"]+', lambda m: "c" + m.group(1) + m.group(2) + "users" + m.group(2) + "me", body)
            name = ("vs-" if source.parent == vs_out else "") + source.name
            (target_dir / name).write_text(body, encoding="utf-8")
        # the sources the ranges point into, as they were: the tests classify the usages on the very text the server saw
        for source in (file, root / "Lib" / "UsageLog.cs"):
            (target_dir / (source.name + ".txt")).write_text(source.read_text(encoding="utf-8-sig").replace("\r\n", "\n"), encoding="utf-8")
        print("fixtures:", target_dir)
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
