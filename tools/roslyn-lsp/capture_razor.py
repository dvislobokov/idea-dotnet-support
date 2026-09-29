"""
What roslyn-language-server answers for Razor documents (.razor of Blazor, .cshtml of Razor Pages / MVC) through its Razor cohost:
the server registers 16 handlers for the language `aspnetcorerazor` (see 63-server_to_client_requests.json), this records what they
really return, one request of every kind, each as {method, params, result | error, ms}. Also every request the server sends back
(`razor/*`: the client is asked for the HTML half in VS Code), to know what a client must serve.

    python tools/roslyn-lsp/capture_razor.py                      # -> tools/roslyn-lsp/out/capture-razor/*.json
    python tools/roslyn-lsp/capture_razor.py --language razor     # languageId to try instead of aspnetcorerazor
    python tools/roslyn-lsp/capture_razor.py --fixtures src/test/resources/roslyn/capture-5.12-razor

The playground is tools/roslyn-lsp/out/razor-playground (dotnet new sln + blazor + webapp, restored), created by hand; the scenario
lives in an unsaved version of Blazor/Components/Pages/Counter.razor. Nothing on disk is changed.
"""
import json
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from probe import Server, find_server  # noqa: E402
from capture import Capture, FALLBACK_CAPABILITIES, option, trim  # noqa: E402

SCENARIO = '''
<p>Doubled: @Doubled</p>
<button @onclick="Reset">Reset</button>

@code {
    private int Doubled => currentCount * 2;

    private void Reset()
    {
        currentCount = 0;
        int unused = 1;
        var broken = MissingType.Value;
        System.Console.WriteLine(currentCount.ToString());
    }
}
'''


def main():
    root = Path(option("--playground", str(Path(__file__).parent / "out" / "razor-playground"))).resolve()
    solution = next(root.glob("*.sln*"))
    language = option("--language", "aspnetcorerazor")
    out = Path(option("--out", str(Path(__file__).parent / "out" / "capture-razor")))
    capabilities_file = Path(__file__).parent / "client-capabilities.json"
    capabilities = json.loads(capabilities_file.read_text(encoding="utf-8")) if capabilities_file.exists() else FALLBACK_CAPABILITIES

    server = Server(find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs-razor")])
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
    capture.save("registrations for razor", {"method": "(registrations)", "result": [
        {"method": r["method"], "selector": r.get("registerOptions", {}).get("documentSelector")}
        for m in server.requests if m["method"] == "client/registerCapability" for r in m["params"]["registrations"]
        if "razor" in json.dumps(r.get("registerOptions", {})).lower()]})

    file = root / "Blazor" / "Components" / "Pages" / "Counter.razor"
    text = file.read_text(encoding="utf-8-sig").replace("\r\n", "\n") + SCENARIO
    uri = file.as_uri()
    document = {"uri": uri}
    server.notify("textDocument/didOpen", {"textDocument": {"uri": uri, "languageId": language, "version": 1, "text": text}})

    def at(marker, inside=0):
        offset = text.rindex(marker) + inside
        line = text.count("\n", 0, offset)
        return {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}

    def where(marker, inside=0):
        return {"textDocument": document, "position": at(marker, inside)}

    capture.request("textDocument/diagnostic", {"textDocument": document}, timeout=180)
    capture.request("textDocument/diagnostic", {"textDocument": document}, "textDocument/diagnostic second")
    capture.request("textDocument/completion", dict(where("currentCount.ToString", len("currentCount.")), context={"triggerKind": 2, "triggerCharacter": "."}), "textDocument/completion member in @code")
    capture.request("textDocument/completion", dict(where("<p>Doubled: @Doubled", len("<p>Doubled: @")), context={"triggerKind": 2, "triggerCharacter": "@"}), "textDocument/completion after @ in markup")
    capture.request("textDocument/completion", dict(where("<button @onclick", 1), context={"triggerKind": 2, "triggerCharacter": "<"}), "textDocument/completion tag")
    capture.request("textDocument/completion", dict(where("<button @onclick", len("<button @")), context={"triggerKind": 2, "triggerCharacter": "@"}), "textDocument/completion directive attribute")
    capture.request("textDocument/hover", where("currentCount * 2", 3))
    capture.request("textDocument/hover", where("<PageTitle>", 3), "textDocument/hover component tag")
    capture.request("textDocument/signatureHelp", dict(where("WriteLine(currentCount", len("WriteLine(")), context={"triggerKind": 2, "triggerCharacter": "(", "isRetrigger": False}))
    capture.request("textDocument/definition", where("@onclick=\"Reset\"", len("@onclick=\"R")))
    capture.request("textDocument/definition", where("<PageTitle>", 3), "textDocument/definition component")
    capture.request("textDocument/references", dict(where("private void Reset", len("private void R")), context={"includeDeclaration": True}))
    capture.request("textDocument/documentHighlight", where("currentCount * 2", 3))
    capture.request("textDocument/documentSymbol", {"textDocument": document})
    capture.request("textDocument/foldingRange", {"textDocument": document})
    capture.request("textDocument/semanticTokens/full", {"textDocument": document})
    capture.request("textDocument/inlayHint", {"textDocument": document, "range": {"start": {"line": 0, "character": 0}, "end": {"line": text.count("\n"), "character": 0}}})
    capture.request("textDocument/codeLens", {"textDocument": document})
    capture.request("textDocument/codeAction", {"textDocument": document, "range": {"start": at("MissingType"), "end": at("MissingType", len("MissingType"))},
                                                "context": {"diagnostics": [], "triggerKind": 1}})
    capture.request("textDocument/codeAction", {"textDocument": document, "range": {"start": at("int unused"), "end": at("int unused", len("int unused"))},
                                                "context": {"diagnostics": [], "triggerKind": 1}}, "textDocument/codeAction unused")
    capture.request("textDocument/prepareRename", where("private void Reset", len("private void R")))
    capture.request("textDocument/rename", dict(where("private void Reset", len("private void R")), newName="ResetCount"))
    capture.request("textDocument/formatting", {"textDocument": document, "options": {"tabSize": 4, "insertSpaces": True}})
    capture.request("textDocument/onTypeFormatting", dict(where("currentCount = 0;", len("currentCount = 0;")), ch=";", options={"tabSize": 4, "insertSpaces": True}))
    capture.request("textDocument/prepareCallHierarchy", where("private void Reset", len("private void R")))
    capture.request("textDocument/prepareTypeHierarchy", where("private int Doubled", 3))
    capture.request("textDocument/selectionRange", {"textDocument": document, "positions": [at("currentCount * 2", 3)]})
    capture.request("textDocument/documentColor", {"textDocument": document})

    # a change: the diagnostics must follow the unsaved text
    changed = text.replace("var broken = MissingType.Value;", "var fixedNow = 1;")
    server.notify("textDocument/didChange", {"textDocument": {"uri": uri, "version": 2}, "contentChanges": [{"text": changed}]})
    capture.request("textDocument/diagnostic", {"textDocument": document}, "textDocument/diagnostic after change")

    # Razor Pages: a .cshtml with its PageModel
    cshtml = root / "Pages" / "Pages" / "Index.cshtml"
    ctext = cshtml.read_text(encoding="utf-8-sig").replace("\r\n", "\n") + "\n<p>@Model.Missing @ViewData[\"Title\"]</p>\n@{ var x = 1; x.ToString(); }\n"
    cdoc = {"uri": cshtml.as_uri()}
    server.notify("textDocument/didOpen", {"textDocument": {"uri": cshtml.as_uri(), "languageId": language, "version": 1, "text": ctext}})
    coffset = ctext.rindex("@Model.Missing") + len("@Model.")
    cline = ctext.count("\n", 0, coffset)
    cpos = {"line": cline, "character": coffset - (ctext.rfind("\n", 0, coffset) + 1)}
    capture.request("textDocument/diagnostic", {"textDocument": cdoc}, "textDocument/diagnostic cshtml", timeout=180)
    capture.request("textDocument/completion", {"textDocument": cdoc, "position": cpos, "context": {"triggerKind": 2, "triggerCharacter": "."}}, "textDocument/completion cshtml Model.")
    capture.request("textDocument/hover", {"textDocument": cdoc, "position": {"line": cline, "character": cpos["character"] - 3}}, "textDocument/hover cshtml Model")
    capture.request("textDocument/documentSymbol", {"textDocument": cdoc}, "textDocument/documentSymbol cshtml")
    capture.request("textDocument/semanticTokens/full", {"textDocument": cdoc}, "textDocument/semanticTokens/full cshtml")

    # the generated C# behind the component, if the server exposes it
    capture.request("razor/provideSemanticTokensRange", {"textDocument": document, "ranges": [], "requiredHostDocumentVersion": 1, "correlationId": "0"}, "razor/provideSemanticTokensRange (does the server serve razor/*?)")

    with server.lock:
        capture.save("requests of the server", {"method": "(requests of the server)", "result": sorted({m["method"] for m in server.requests}),
                                                "razor": [m for m in server.requests if m["method"].startswith("razor/")][:5],
                                                "toClient": [m for m in server.requests if m["method"] not in ("workspace/configuration", "client/registerCapability", "window/workDoneProgress/create")][:40]})
        capture.save("notifications of the server", {"method": "(notifications)", "result": sorted({m["method"] for m in server.notifications}),
                                                     "razorNotifications": [m for m in server.notifications if "razor" in m["method"].lower()][:5],
                                                     "publishDiagnosticsFor": sorted({m["params"]["uri"] for m in server.notifications if m["method"] == "textDocument/publishDiagnostics"})})
        capture.save("configuration sections", {"method": "(configuration)", "result": sorted({s for s in server.configuration_sections if s and ("razor" in s or "html" in s)})})
    server.notify("exit", {})
    print("\n".join(capture.summary))

    fixtures = option("--fixtures")
    if fixtures:
        target = Path(fixtures)
        target.mkdir(parents=True, exist_ok=True)
        for old in target.glob("*.json"):
            old.unlink()
        for record in sorted(out.glob("*.json")):
            content = json.dumps(trim(json.loads(record.read_text(encoding="utf-8"))), ensure_ascii=False, indent=1)
            content = content.replace(root.as_uri(), "file:///c:/playground").replace(str(root).replace("\\", "/"), "c:/playground").replace(json.dumps(str(root))[1:-1], "c:/playground")
            # the user name of this machine: the assemblyPath inside roslyn-source-generated URIs, the MetadataAsSource folder in %TEMP%
            content = re.sub(r'(?i)c(:|%3a)([/\\]|%5c)users\2[^/\\%"]+', lambda m: "c" + m.group(1) + m.group(2) + "users" + m.group(2) + "me", content)
            (target / record.name).write_text(content, encoding="utf-8")
        print("fixtures ->", target)


if __name__ == "__main__":
    main()
