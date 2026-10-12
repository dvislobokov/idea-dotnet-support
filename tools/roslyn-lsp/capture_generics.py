"""
How roslyn-language-server presents generic methods and types in completion: the label (`AddSingleton<>`?), what is inserted, and the
signature in the documentation of the resolved item. The question behind it: can the client put `<>` itself when such an item is
chosen, and tell a method whose type arguments are inferred (`Select`) from one that needs them written (`AddSingleton<TService>()`).

    python tools/roslyn-lsp/capture_generics.py            # -> tools/roslyn-lsp/out/capture-generics/*.json, a summary on stdout
    python tools/roslyn-lsp/capture_generics.py --fixtures src/test/resources/roslyn/capture-5.12-generics   # the summary only

The playground is tools/roslyn-lsp/out/razor-playground (see capture_razor.py): an ASP.NET project, so the DI extensions are there.
The scenario lives in an unsaved version of Blazor/Program.cs. Nothing on disk is changed.
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

SCENARIO = '''
static class GenericProbe
{
    static T Make<T>() where T : new() => new T();
    static T Same<T>(T value) => value;
    static TResult Convert<TSource, TResult>(TSource value) => default!;
    static void Register<TService>() { }

    static void Use(Microsoft.Extensions.DependencyInjection.IServiceCollection services, System.IServiceProvider provider,
        System.Collections.Generic.List<int> list)
    {
        services.AddSing/*1*/;
        provider.GetReq/*2*/;
        list.Sel/*3*/;
        list.OfTy/*4*/;
        System.Array.Emp/*5*/;
        GenericProbe.Ma/*6*/;
        GenericProbe.Sa/*7*/;
        GenericProbe.Conv/*8*/;
        GenericProbe.Regi/*9*/;
        var made = new System.Collections.Generic.Dicti/*10*/;
        System.Collections.Generic.Li/*11*/ declared;
        System.Threading.Tasks.Ta/*12*/ task;
    }
}
'''

# marker -> the labels to look at (a name may come twice: with and without type parameters)
WANTED = {
    1: "AddSingleton", 2: "GetRequiredService", 3: "Select", 4: "OfType", 5: "Empty", 6: "Make", 7: "Same", 8: "Convert", 9: "Register",
    10: "Dictionary", 11: "List", 12: "Task",
}


def main():
    root = Path(option("--playground", str(Path(__file__).parent / "out" / "razor-playground"))).resolve()
    solution = next(root.glob("*.sln*"))
    out = Path(option("--out", str(Path(__file__).parent / "out" / "capture-generics")))
    capabilities_file = Path(__file__).parent / "client-capabilities.json"
    capabilities = json.loads(capabilities_file.read_text(encoding="utf-8")) if capabilities_file.exists() else FALLBACK_CAPABILITIES

    server = Server(find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs-generics")])
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

    file = root / "Blazor" / "Program.cs"
    text = file.read_text(encoding="utf-8-sig").replace("\r\n", "\n") + SCENARIO
    document = {"uri": file.as_uri()}
    server.notify("textDocument/didOpen", {"textDocument": {"uri": file.as_uri(), "languageId": "csharp", "version": 1, "text": text}})
    # the first completion of a server is partial (see capture.py): a diagnostic pull first, as the IDE does
    capture.request("textDocument/diagnostic", {"textDocument": document}, timeout=180)

    summary = []
    for marker, name in WANTED.items():
        offset = text.index("/*%d*/" % marker)
        line = text.count("\n", 0, offset)
        position = {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}
        answer = capture.request("textDocument/completion", {"textDocument": document, "position": position, "context": {"triggerKind": 1}},
                                 "textDocument/completion %d %s" % (marker, name)) or {}
        items = answer.get("items", []) if isinstance(answer, dict) else answer
        defaults = (answer.get("itemDefaults") or {}) if isinstance(answer, dict) else {}
        for item in items:
            if not isinstance(item, dict):
                continue
            label = item.get("label", "")
            if re.sub(r"<.*$", "", label) != name:
                continue
            resolved = capture.request("completionItem/resolve", dict(item, data=item.get("data", defaults.get("data"))),
                                       "completionItem/resolve %d %s" % (marker, re.sub(r"\W+", "_", label))) or {}
            documentation = resolved.get("documentation")
            markdown = documentation.get("value") if isinstance(documentation, dict) else documentation
            first = (re.search(r"```[a-z]*\s*\n(.+?)\n", markdown or "") or [None, None])[1]
            overloads = (re.search(r"\+\s*(\d+)\s+(?:generic\s+)?overload", (markdown or "").replace("\\", "")) or [None, None])[1]
            summary.append({"marker": marker, "label": label, "kind": item.get("kind"), "insertText": item.get("insertText"),
                            "textEditText": item.get("textEditText"), "filterText": item.get("filterText"), "labelDetails": item.get("labelDetails"),
                            "insertTextFormat": item.get("insertTextFormat"), "signature": first, "overloads": overloads,
                            "detail": resolved.get("detail"), "resolvedTextEdit": resolved.get("textEdit"), "documentation": markdown})
    capture.save("summary of generic items", {"method": "(summary)", "result": summary})
    server.notify("exit", {})

    for row in summary:
        print("%-3s %-26s kind=%-3s insert=%-22s %s%s" % (row["marker"], row["label"], row["kind"], row["textEditText"] or row["insertText"],
                                                       row["signature"], "  (+%s)" % row["overloads"] if row["overloads"] else ""))

    fixtures = option("--fixtures")
    if fixtures:
        target = Path(fixtures)
        target.mkdir(parents=True, exist_ok=True)
        for old in target.glob("*.json"):
            old.unlink()
        # the summary alone: the lists themselves are long, and cut to their first items they would lose the ones in question
        for record in sorted(out.glob("*summary*.json")):
            content = json.dumps(json.loads(record.read_text(encoding="utf-8")), ensure_ascii=False, indent=1)
            content = content.replace(root.as_uri(), "file:///c:/playground").replace(str(root).replace("\\", "/"), "c:/playground").replace(json.dumps(str(root))[1:-1], "c:/playground")
            content = re.sub(r'(?i)c(:|%3a)([/\\]|%5c)users\2[^/\\%"]+', lambda m: "c" + m.group(1) + m.group(2) + "users" + m.group(2) + "me", content)
            (target / record.name).write_text(content, encoding="utf-8")
        print("fixtures ->", target)


if __name__ == "__main__":
    main()
