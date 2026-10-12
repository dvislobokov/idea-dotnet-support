"""
Records what roslyn-language-server really answers: one request of every kind the client of the plugin can send, against
debug-playground, each saved as {method, params, result | error, ms}. The point is to stop guessing the shapes of Roslyn: the two
silent breakages of the first day (URIs with %3A, the Visual Studio numbers in `tags`) were both visible in plain traffic.

    python tools/roslyn-lsp/capture.py                       # -> tools/roslyn-lsp/out/capture/*.json (not committed)
    python tools/roslyn-lsp/capture.py --fixtures src/test/resources/roslyn/capture-5.12

`--fixtures DIR` also writes a trimmed copy for the tests: paths of this machine replaced with file:///c:/playground, long lists cut
(the cut is marked with "_truncated"). The capabilities sent in `initialize` are the ones of the IntelliJ LSP client when
`client-capabilities.json` lies next to this script (dumped from a running IDE with tools/ui-robot/scripts/lsp_capabilities.js):
what a server answers depends on what the client says it supports.

Nothing on disk is changed: the scenario lives in an unsaved version of Console/Scenarios.cs.
"""
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from probe import Server, find_server  # noqa: E402

SCENARIO = '''
public interface ICaptureShape { double Area(); }
public abstract class CaptureShapeBase : ICaptureShape { public abstract double Area(); }
public class CaptureSquare(double side) : CaptureShapeBase { public override double Area() => side * side; }
public class CaptureCircle(double radius) : CaptureShapeBase { public override double Area() => 3.14 * radius * radius; }

static class LspCapture
{
    /// <summary>Doc of <see cref="Target"/>.</summary>
    /// <param name="value">The value.</param>
    static int Target(int value) => value + 1;

    static void Use()
    {
        var person = new Person("Ada", 36);
        int unused = 1;
        var broken = MissingType.Value;
        System.Console.WriteLine(Target(2));
        person.ToString();
        ICaptureShape shape = new CaptureSquare(2);
        shape.Area();
        var   messy=Target( 3 )  ;
    }
}
'''

FALLBACK_CAPABILITIES = {
    "workspace": {"configuration": True, "workspaceFolders": True, "applyEdit": True,
                  "workspaceEdit": {"documentChanges": True, "resourceOperations": ["create", "rename", "delete"]}},
    "textDocument": {
        "diagnostic": {"dynamicRegistration": True},
        "publishDiagnostics": {"tagSupport": {"valueSet": [1, 2]}},
        "completion": {"completionItem": {"snippetSupport": True, "resolveSupport": {"properties": ["documentation", "detail", "additionalTextEdits"]},
                                          "labelDetailsSupport": True, "insertReplaceSupport": True},
                       "completionList": {"itemDefaults": ["commitCharacters", "editRange", "insertTextFormat", "data"]}},
        "codeAction": {"codeActionLiteralSupport": {"codeActionKind": {"valueSet": ["", "quickfix", "refactor", "source"]}},
                       "resolveSupport": {"properties": ["edit", "command"]}, "dataSupport": True},
        "semanticTokens": {"requests": {"full": True, "range": True}, "tokenTypes": [], "tokenModifiers": [], "formats": ["relative"]},
        "hover": {"contentFormat": ["markdown", "plaintext"]},
        "signatureHelp": {"signatureInformation": {"documentationFormat": ["markdown"], "parameterInformation": {"labelOffsetSupport": True}}},
        "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
        "rename": {"prepareSupport": True},
        "foldingRange": {}, "inlayHint": {"resolveSupport": {"properties": ["tooltip", "textEdits"]}},
        "callHierarchy": {}, "typeHierarchy": {}, "codeLens": {},
    },
    "window": {"workDoneProgress": True},
}


def option(name, default=None):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


class Capture:
    def __init__(self, server, out):
        self.server, self.out, self.count, self.summary = server, out, 0, []
        out.mkdir(parents=True, exist_ok=True)
        for old in out.glob("*.json"):
            old.unlink()

    def request(self, method, params, label=None, timeout=90):
        started = time.perf_counter()
        try:
            response = self.server.request(method, params, timeout=timeout)
        except (TimeoutError, OSError):
            response = {"error": {"message": "no answer in %d s" % timeout}}
        record = {"method": method, "params": params, "ms": round((time.perf_counter() - started) * 1000, 1)}
        record.update({key: response[key] for key in ("result", "error") if key in response})
        self.save(label or method, record)
        return response.get("result")

    def save(self, label, record):
        self.count += 1
        name = "%02d-%s.json" % (self.count, re.sub(r"[^A-Za-z0-9_]+", "_", label).strip("_"))
        (self.out / name).write_text(json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8")
        answer = record.get("error", {}).get("message") if "error" in record else describe(record.get("result"))
        self.summary.append("%-46s %7s ms  %s" % (label, record.get("ms", ""), ("ERROR " + str(answer)[:90]) if "error" in record else answer))


def describe(result):
    if result is None:
        return "null"
    if isinstance(result, list):
        return "list of %d" % len(result)
    if isinstance(result, dict):
        return "{" + ", ".join(sorted(result)[:8]) + "}"
    return repr(result)[:60]


def trim(value, limit=25):
    """Long lists are cut for the fixtures: the shape of an element is what the tests are after. Numbers (semantic tokens) are kept longer."""
    if isinstance(value, list):
        if value and all(isinstance(item, int) for item in value):
            return value[:500]
        cut = [trim(item, limit) for item in value[:limit]]
        return cut + [{"_truncated": len(value) - limit}] if len(value) > limit else cut
    if isinstance(value, dict):
        return {key: trim(item, limit) for key, item in value.items()}
    return value


def cache_facts(capture, server, document, text):
    """
    What a cache of answers may rely on (LSP_PLAN.md, phase 3), each as a record of its own:
    - completion after a space and after the first letter of the next word: the same list? (then the letter needs no request);
    - semantic tokens: the same text gives the same data (a cache keyed by the content is right), and what a big file costs.
    The text of the document is changed with didChange and put back at the end.
    """
    version = [100]

    def change(new_text):
        version[0] += 1
        server.notify("textDocument/didChange", {"textDocument": {"uri": document["uri"], "version": version[0]}, "contentChanges": [{"text": new_text}]})

    def position(source, marker):
        offset = source.rindex(marker) + len(marker)
        line = source.count("\n", 0, offset)
        return {"line": line, "character": offset - (source.rfind("\n", 0, offset) + 1)}

    # completion in a class body: `public |`, then `public c|`
    anchor = "static class LspCapture\n{\n"
    for typed, context in (("public ", {"triggerKind": 2, "triggerCharacter": " "}), ("public c", {"triggerKind": 1})):
        source = text.replace(anchor, anchor + "    " + typed + "\n", 1)
        change(source)
        capture.request("textDocument/completion", {"textDocument": document, "position": position(source, "    " + typed), "context": context},
                        "cache completion after %r" % typed)

    # semantic tokens: twice the same text, then a big file, first and warm
    change(text)
    first = capture.request("textDocument/semanticTokens/full", {"textDocument": document}, "cache tokens same text 1")
    second = capture.request("textDocument/semanticTokens/full", {"textDocument": document}, "cache tokens same text 2")
    capture.save("cache tokens are deterministic", {"method": "(note)", "result": {"equal": first == second, "ints": len((first or {}).get("data", []))}})

    scenario_start = text.index("public interface ICaptureShape")
    body = text[scenario_start:].replace("ICaptureShape", "ICaptureShape{0}").replace("CaptureShapeBase", "CaptureShapeBase{0}") \
        .replace("CaptureSquare", "CaptureSquare{0}").replace("CaptureCircle", "CaptureCircle{0}").replace("LspCapture", "LspCapture{0}")
    big = text[:scenario_start] + "".join(body.replace("{0}", str(n)) for n in range(170))
    change(big)
    timings = []
    for attempt in range(6):
        started = time.perf_counter()
        response = server.request("textDocument/semanticTokens/full", {"textDocument": document}, timeout=120)
        timings.append(round((time.perf_counter() - started) * 1000, 1))
    capture.save("cache tokens big file", {"method": "(note)", "result": {"lines": big.count("\n") + 1, "ms": timings,
                                                                         "ints": len((response.get("result") or {}).get("data", [])),
                                                                         "bytes": response.get("_bytes")}})
    change(text)


def phase7_facts(capture, server, where, metadata, root):
    """
    What phase 7 of LSP_PLAN.md stands on:
    - Go to Implementation from a call of an interface method and from the method in the interface;
    - the decompiled file a definition in the framework leads to, opened as the IDE would open it: symbols, diagnostics, navigation;
    - rename of a type named as its file (Console/Scenarios.cs): does the server rename the file itself?
    """
    capture.request("textDocument/implementation", where("shape.Area()", len("shape.")), "phase7 implementation of a call of an interface method")
    capture.request("textDocument/implementation", where("public interface ICaptureShape { double Area", len("public interface ICaptureShape { double ")),
                    "phase7 implementation of the method in the interface")

    target = (metadata or [{}])[0] if isinstance(metadata, list) else (metadata or {})
    target_uri = target.get("uri") or target.get("targetUri")
    if target_uri:
        path = Path(re.sub(r"^file:///", "", target_uri).replace("%3A", ":"))
        decompiled = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
        capture.save("phase7 decompiled file head", {"method": "(note)", "result": {"uri": target_uri, "lines": decompiled.count("\n") + 1,
                                                                                    "head": decompiled.split("\n")[:12]}})
        server.notify("textDocument/didOpen", {"textDocument": {"uri": target_uri, "languageId": "csharp", "version": 1, "text": decompiled}})
        decompiled_document = {"uri": target_uri}

        def inside(marker, offset_in=0):
            offset = decompiled.index(marker) + offset_in
            line = decompiled.count("\n", 0, offset)
            return {"textDocument": decompiled_document, "position": {"line": line, "character": offset - (decompiled.rfind("\n", 0, offset) + 1)}}

        capture.request("textDocument/documentSymbol", {"textDocument": decompiled_document}, "phase7 decompiled documentSymbol")
        capture.request("textDocument/diagnostic", {"textDocument": decompiled_document}, "phase7 decompiled diagnostic")
        capture.request("textDocument/semanticTokens/full", {"textDocument": decompiled_document}, "phase7 decompiled semanticTokens")
        # a type of another assembly inside the decompiled code: `TextWriter` in `public static TextWriter Out` (not in a comment)
        if "static TextWriter Out" in decompiled:
            capture.request("textDocument/hover", inside("static TextWriter Out", len("static ") + 2), "phase7 decompiled hover")
            capture.request("textDocument/definition", inside("static TextWriter Out", len("static ") + 2), "phase7 decompiled definition")
            capture.request("textDocument/documentHighlight", inside("static TextWriter Out", len("static TextWriter ") + 1), "phase7 decompiled highlight")
        server.notify("textDocument/didClose", {"textDocument": decompiled_document})

    # rename of the class Scenarios, declared in Console/Scenarios.cs (the file on disk, the document is not open in the server)
    scenarios = root / "Console" / "Scenarios.cs"
    scenarios_text = scenarios.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
    scenarios_document = {"uri": scenarios.as_uri()}
    offset = scenarios_text.index("class Scenarios") + len("class ")
    line = scenarios_text.count("\n", 0, offset)
    position = {"line": line, "character": offset - (scenarios_text.rfind("\n", 0, offset) + 1)}
    capture.request("textDocument/prepareRename", {"textDocument": scenarios_document, "position": position}, "phase7 prepareRename of a type named as its file")
    capture.request("textDocument/rename", {"textDocument": scenarios_document, "position": position, "newName": "Playbook"}, "phase7 rename of a type named as its file")


def main():
    solution = Path(option("--solution", "debug-playground/DebugPlayground.sln")).resolve()
    root = solution.parent
    out = Path(option("--out", str(Path(__file__).parent / "out" / "capture")))
    capabilities_file = Path(__file__).parent / "client-capabilities.json"
    capabilities = json.loads(capabilities_file.read_text(encoding="utf-8")) if capabilities_file.exists() else FALLBACK_CAPABILITIES
    print("client capabilities:", capabilities_file.name if capabilities_file.exists() else "fallback of this script")

    server = Server(find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs")])
    capture = Capture(server, out)
    capture.request("initialize", {"processId": os.getpid(), "rootUri": root.as_uri(), "locale": "en",
                                   "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}], "capabilities": capabilities})
    server.notify("initialized", {})
    server.notify("solution/open", {"solution": solution.as_uri()})
    deadline = time.time() + 180
    while time.time() < deadline:
        with server.lock:
            if any(m["method"] == "workspace/projectInitializationComplete" for m in server.notifications):
                break
            server.lock.wait(0.2)

    file = root / "Console" / "Scenarios.cs"
    text = file.read_text(encoding="utf-8-sig").replace("\r\n", "\n") + SCENARIO
    uri = file.as_uri()
    document = {"uri": uri}
    server.notify("textDocument/didOpen", {"textDocument": {"uri": uri, "languageId": "csharp", "version": 1, "text": text}})

    def at(marker, inside=0):
        """The position `inside` characters into the LAST occurrence of marker (the scenario is appended at the end)."""
        offset = text.rindex(marker) + inside
        line = text.count("\n", 0, offset)
        return {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}

    def where(marker, inside=0):
        return {"textDocument": document, "position": at(marker, inside)}

    def span(marker):
        return {"start": at(marker), "end": at(marker, len(marker))}

    whole = {"start": {"line": 0, "character": 0}, "end": {"line": text.count("\n"), "character": 0}}
    scenario = {"start": at("public interface ICaptureShape"), "end": whole["end"]}

    diagnostics = capture.request("textDocument/diagnostic", {"textDocument": document}) or {}
    capture.request("textDocument/diagnostic", {"textDocument": document, "identifier": "DocumentCompilerSemantic"}, "textDocument/diagnostic identifier")

    # Whether the first completion of a name is whole depends on what the server did before. As the very first work after the load,
    # server 5.12 answers with a part of the list (154 of 630, no `Console`) and still says isIncomplete: false, so a client filters
    # that part locally (seen with a probe that asks nothing else first). After a diagnostic pull, as here and as in the IDE, where the
    # platform always pulls the diagnostics of the open file first, it is whole: this record.
    names = [capture.request("textDocument/completion", dict(where("person.ToString", 0), context={"triggerKind": 1}),
                             "textDocument/completion bare name, %s of the server" % order) for order in ("first", "second")]
    capture.save("completion first of the server is partial", {"method": "(note)", "result": [
        {"items": len((answer or {}).get("items", [])), "isIncomplete": (answer or {}).get("isIncomplete"),
         "hasConsole": any(item.get("label") == "Console" for item in (answer or {}).get("items", []))} for answer in names]})
    member = capture.request("textDocument/completion", dict(where("person.ToString", len("person.")), context={"triggerKind": 2, "triggerCharacter": "."}), "textDocument/completion member")
    items = member.get("items", []) if isinstance(member, dict) else (member or [])
    if items:
        # an item may carry no `data` of its own: then the one of itemDefaults is what resolve needs (without it: see the end of main)
        defaults = (member.get("itemDefaults") or {}) if isinstance(member, dict) else {}
        capture.request("completionItem/resolve", dict(items[0], data=items[0].get("data", defaults.get("data"))), "completionItem/resolve")

    capture.request("textDocument/hover", where("Target(2)", 2))
    capture.request("textDocument/hover", where("Console.WriteLine(Target", 2), "textDocument/hover framework type")
    capture.request("textDocument/signatureHelp", dict(where("Target(2)", len("Target(")), context={"triggerKind": 2, "triggerCharacter": "(", "isRetrigger": False}))

    capture.request("textDocument/definition", where("Target(2)", 2))
    capture.request("textDocument/definition", where("new Person(\"Ada\", 36);", 6), "textDocument/definition other project")
    metadata = capture.request("textDocument/definition", where("Console.WriteLine(Target", 2), "textDocument/definition framework type (metadata as source)")
    capture.request("textDocument/typeDefinition", where("person.ToString", 2))
    capture.request("textDocument/implementation", where("ICaptureShape shape", 3))
    capture.request("textDocument/references", dict(where("Target(2)", 2), context={"includeDeclaration": True}))
    capture.request("textDocument/documentHighlight", where("Target(2)", 2))

    capture.request("textDocument/documentSymbol", {"textDocument": document})
    capture.request("workspace/symbol", {"query": "Person"})
    capture.request("textDocument/foldingRange", {"textDocument": document})
    capture.request("textDocument/selectionRange", {"textDocument": document, "positions": [at("Target(2)", 2)]})
    capture.request("textDocument/semanticTokens/full", {"textDocument": document})
    capture.request("textDocument/semanticTokens/range", {"textDocument": document, "range": scenario})
    hints = capture.request("textDocument/inlayHint", {"textDocument": document, "range": scenario})
    if hints:
        capture.request("inlayHint/resolve", hints[0])
    lenses = capture.request("textDocument/codeLens", {"textDocument": document})
    if lenses:
        capture.request("codeLens/resolve", lenses[-1])

    # code actions: the diagnostics go back to the server exactly as they came, numbers of Visual Studio in `tags` included
    raw = diagnostics.get("items", []) if isinstance(diagnostics, dict) else []
    for label, marker in (("error", "MissingType"), ("warning", "unused = 1")):
        position = at(marker)
        here = [d for d in raw if d["range"]["start"]["line"] == position["line"]]
        actions = capture.request("textDocument/codeAction", {"textDocument": document, "range": span(marker), "context": {"diagnostics": here, "triggerKind": 1}}, "textDocument/codeAction at " + label)
        if label == "warning":
            nulled = [dict(d, tags=[None for _ in d.get("tags", [])]) for d in here]
            capture.request("textDocument/codeAction", {"textDocument": document, "range": span(marker), "context": {"diagnostics": nulled}}, "textDocument/codeAction with null tags (what lsp4j sends back)")
            def named(action, command):
                return (action.get("command") or {}).get("command") == command

            simple = next((a for a in actions or [] if "data" in a and not a.get("command")), None)
            if simple:
                capture.request("codeAction/resolve", simple, "codeAction/resolve plain action")
            # commands of the CLIENT: the server only describes them, a client that forwards them to workspace/executeCommand gets nothing
            nested = next((a for a in actions or [] if named(a, "roslyn.client.nestedCodeAction")), None)
            if nested:
                capture.request("codeAction/resolve", nested["command"]["arguments"][0]["NestedCodeActions"][0], "codeAction/resolve nested action")
            fix_all = next((a for a in actions or [] if named(a, "roslyn.client.fixAllCodeAction")), None)
            if fix_all:
                capture.request("codeAction/resolveFixAll", {"title": fix_all["title"], "data": fix_all["data"], "scope": "Document"}, "codeAction/resolveFixAll document")

    capture.request("textDocument/prepareRename", where("unused = 1", 2))
    capture.request("textDocument/rename", dict(where("Target(2)", 2), newName="Renamed"))
    options = {"tabSize": 4, "insertSpaces": True}
    capture.request("textDocument/formatting", {"textDocument": document, "options": options})
    capture.request("textDocument/rangeFormatting", {"textDocument": document, "range": span("var   messy=Target( 3 )  ;"), "options": options})
    capture.request("textDocument/onTypeFormatting", dict(where("var   messy=Target( 3 )  ;", len("var   messy=Target( 3 )  ;")), ch=";", options=options))

    calls = capture.request("textDocument/prepareCallHierarchy", where("static int Target", len("static int T")))
    if calls:
        capture.request("callHierarchy/incomingCalls", {"item": calls[0]})
        capture.request("callHierarchy/outgoingCalls", {"item": calls[0]})
    types = capture.request("textDocument/prepareTypeHierarchy", where("class CaptureShapeBase", len("class C")))
    if types:
        capture.request("typeHierarchy/supertypes", {"item": types[0]})
        capture.request("typeHierarchy/subtypes", {"item": types[0]})

    # what a client has to cope with when it follows a definition into a framework type
    target = (metadata or [{}])[0] if isinstance(metadata, list) else (metadata or {})
    target_uri = target.get("uri") or target.get("targetUri")
    if target_uri:
        capture.save("definition target uri", {"method": "(note)", "result": {"uri": target_uri, "isFile": target_uri.startswith("file:"), "existsOnDisk": target_uri.startswith("file:") and Path(re.sub(r"^file:///", "", target_uri).replace("%3A", ":")).exists()}})

    cache_facts(capture, server, document, text)
    phase7_facts(capture, server, where, metadata, root)

    with server.lock:
        capture.save("server to client requests", {"method": "(requests of the server)", "result": [{k: v for k, v in m.items() if k != "_bytes"} for m in server.requests if m["method"] != "workspace/configuration"][:40]})
        capture.save("server to client notifications", {"method": "(notifications of the server)", "result": sorted({m["method"] for m in server.notifications})})

    # LAST, because server 5.12 does not survive it: resolve of an item without `data` cancels the request and shuts the server down
    if items and "data" not in items[0]:
        capture.request("completionItem/resolve", dict(items[0]), "completionItem/resolve without data (kills the server)", timeout=20)
        capture.request("textDocument/hover", where("Target(2)", 2), "textDocument/hover after the resolve without data", timeout=20)

    print("\n".join(capture.summary))
    print("\n%d records in %s" % (capture.count, out))

    fixtures = option("--fixtures")
    if fixtures:
        target_dir = Path(fixtures)
        target_dir.mkdir(parents=True, exist_ok=True)
        for old in target_dir.glob("*.json"):
            old.unlink()
        home = [root.as_uri(), root.as_uri().replace("file:///C:", "file:///c:"), str(root).replace("\\", "\\\\"), str(root).replace("\\", "/"), str(root)]
        for source in sorted(out.glob("*.json")):
            record = trim(json.loads(source.read_text(encoding="utf-8")))
            if record.get("method") == "textDocument/didOpen":
                continue
            body = json.dumps(record, ensure_ascii=False, indent=1)
            for index, prefix in enumerate(home):
                body = body.replace(prefix, "file:///c:/playground" if index < 2 else "c:/playground")
            body = re.sub(re.escape(str(Path.home()).replace("\\", "\\\\")), "c:/home", body)
            body = body.replace(str(Path.home()).replace("\\", "/"), "c:/home").replace(Path.home().as_uri(), "file:///c:/home")
            (target_dir / source.name).write_text(body, encoding="utf-8")
        print("fixtures:", target_dir)
    server.process.kill()
    sys.stdout.flush()  # os._exit drops what is still buffered when the output goes to a pipe
    os._exit(0)


if __name__ == "__main__":
    main()
