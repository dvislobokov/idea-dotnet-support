"""How fast roslyn-language-server answers, and how much of an answer stays valid while the user types.

    python bench.py [SOLUTION.sln] [--file Console/Scenarios.cs] [--line 60] [--out out/bench.json]

Opens the solution (`solution/open`), waits for `workspace/projectInitializationComplete`, opens a document and measures, in
milliseconds from request to parsed response: completion after `.` and at a bare name, the same while typing one letter at a time
(didChange + completion), completionItem/resolve, hover, signature help, semantic tokens (full and range), pull diagnostics, document
symbols. The file on disk is not touched: edits go to the server only. Nothing here belongs to the build of the plugin.
"""
import json
import os
import statistics
import sys
import time
from pathlib import Path

from probe import Server, find_server

INDENT = "        "


def option(name, default):
    return sys.argv[sys.argv.index(name) + 1] if name in sys.argv else default


class Bench:
    def __init__(self, server, uri, text):
        self.server = server
        self.uri = uri
        self.version = 1
        self.text = text
        self.rows = []

    def timed(self, label, method, params, repeat=1):
        times, last = [], None
        for _ in range(repeat):
            started = time.perf_counter()
            last = self.server.request(method, params, timeout=120)
            times.append((time.perf_counter() - started) * 1000)
        row = {"what": label, "first_ms": round(times[0], 2), "bytes": last.get("_bytes")}
        if repeat > 1:
            rest = times[1:]
            row.update(median_ms=round(statistics.median(rest), 2), min_ms=round(min(rest), 2), max_ms=round(max(rest), 2))
        if "error" in last:
            row["error"] = last["error"].get("message", "")[:200]
        self.rows.append(row)
        return last.get("result"), row

    def change(self, line, start, end, text):
        self.version += 1
        self.server.notify("textDocument/didChange", {
            "textDocument": {"uri": self.uri, "version": self.version},
            "contentChanges": [{"range": {"start": {"line": line, "character": start}, "end": {"line": line, "character": end}}, "text": text}],
        })

    def position(self, line, character):
        return {"textDocument": {"uri": self.uri}, "position": {"line": line, "character": character}}


def items_of(result):
    if result is None:
        return [], False, None
    if isinstance(result, list):
        return result, False, None
    return result.get("items", []), bool(result.get("isIncomplete")), result.get("itemDefaults")


def main():
    arguments = [a for i, a in enumerate(sys.argv[1:], 1) if not a.startswith("--") and not sys.argv[i - 1].startswith("--")]
    solution = Path(arguments[0] if arguments else "debug-playground/DebugPlayground.sln").resolve()
    file = (solution.parent / option("--file", "Console/Scenarios.cs")).resolve()
    line = int(option("--line", "60"))  # zero-based line to type at; a new line is inserted there
    out = Path(option("--out", str(Path(__file__).parent / "out" / "bench.json")))
    out.parent.mkdir(parents=True, exist_ok=True)

    server = Server(find_server() + ["--stdio", "--logLevel", "Warning", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs")])
    started = time.perf_counter()
    initialize = server.request("initialize", {
        "processId": os.getpid(),
        "rootUri": solution.parent.as_uri(),
        "workspaceFolders": [{"uri": solution.parent.as_uri(), "name": solution.parent.name}],
        "capabilities": {
            "workspace": {"configuration": True, "workspaceFolders": True},
            "textDocument": {
                "diagnostic": {"dynamicRegistration": True},
                "completion": {"completionItem": {"snippetSupport": True, "resolveSupport": {"properties": ["documentation", "detail", "additionalTextEdits"]},
                                                  "labelDetailsSupport": True, "insertReplaceSupport": True},
                               "completionList": {"itemDefaults": ["commitCharacters", "editRange", "insertTextFormat", "data"]}},
                "semanticTokens": {"requests": {"full": {"delta": True}, "range": True}, "tokenTypes": [], "tokenModifiers": [], "formats": ["relative"]},
                "hover": {"contentFormat": ["markdown", "plaintext"]},
                "signatureHelp": {"signatureInformation": {"documentationFormat": ["markdown"]}},
                "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
            },
            "window": {"workDoneProgress": True},
        },
    })
    initialize_ms = (time.perf_counter() - started) * 1000
    server.notify("initialized", {})
    server.notify("solution/open", {"solution": solution.as_uri()})
    loaded = None
    deadline = time.time() + 180
    while time.time() < deadline and loaded is None:
        with server.lock:
            if any(m["method"] == "workspace/projectInitializationComplete" for m in server.notifications):
                loaded = (time.perf_counter() - started) * 1000
            else:
                server.lock.wait(0.2)

    text = file.read_text(encoding="utf-8-sig")
    bench = Bench(server, file.as_uri(), text)
    server.notify("textDocument/didOpen", {"textDocument": {"uri": bench.uri, "languageId": "csharp", "version": 1, "text": text}})
    document = {"textDocument": {"uri": bench.uri}}
    lines = text.count("\n") + 1

    bench.timed("diagnostic (pull), first after open", "textDocument/diagnostic", dict(document, identifier="DocumentCompilerSemantic"))
    bench.timed("semanticTokens/full", "textDocument/semanticTokens/full", document, repeat=6)
    bench.timed("semanticTokens/range, 50 lines", "textDocument/semanticTokens/range",
                dict(document, range={"start": {"line": 40, "character": 0}, "end": {"line": 90, "character": 0}}), repeat=6)
    bench.timed("documentSymbol", "textDocument/documentSymbol", document, repeat=6)
    bench.timed("foldingRange", "textDocument/foldingRange", document, repeat=6)

    # a member list: `person.`
    bench.change(line, 0, 0, INDENT + "person.\n")
    at = bench.position(line, len(INDENT) + len("person."))
    result, row = bench.timed("completion after `person.`", "textDocument/completion", dict(at, context={"triggerKind": 2, "triggerCharacter": "."}), repeat=11)
    members, incomplete, defaults = items_of(result)
    row.update(items=len(members), isIncomplete=incomplete, itemDefaults=sorted(defaults) if defaults else None)
    if members:
        first = dict(members[0])
        if defaults and "data" in defaults and "data" not in first:
            first["data"] = defaults["data"]
        bench.timed("completionItem/resolve (member)", "completionItem/resolve", first, repeat=6)
        bench.rows[-1]["item"] = members[0].get("label")
    bench.timed("hover on `person`", "textDocument/hover", bench.position(line, len(INDENT) + 2), repeat=11)

    # a bare name, typed one letter at a time: what the user does most of the time
    bench.change(line, 0, len(INDENT) + len("person."), INDENT)
    typed = ""
    typing = []
    for letter in "Console":
        bench.change(line, len(INDENT) + len(typed), len(INDENT) + len(typed), letter)
        typed += letter
        result, row = bench.timed("completion while typing `%s`" % typed, "textDocument/completion",
                                  dict(bench.position(line, len(INDENT) + len(typed)), context={"triggerKind": 1}))
        items, incomplete, defaults = items_of(result)
        labels = {i.get("label") for i in items}
        row.update(items=len(items), isIncomplete=incomplete)
        typing.append((typed, labels))
    # would the first list, filtered by the client, have given what the server gave later?
    base_typed, base = typing[0]
    for typed, labels in typing[1:]:
        missing = sorted(labels - base)
        bench.rows.append({"what": "reuse of the list for `%s` at `%s`" % (base_typed, typed), "server_items": len(labels),
                           "not_in_first_list": len(missing), "examples": missing[:5]})

    bench.change(line, len(INDENT) + len(typed), len(INDENT) + len(typed), ".WriteLine(")
    typed += ".WriteLine("
    bench.timed("signatureHelp after `(`", "textDocument/signatureHelp",
                dict(bench.position(line, len(INDENT) + len(typed)), context={"triggerKind": 2, "triggerCharacter": "(", "isRetrigger": False}), repeat=6)
    bench.timed("diagnostic (pull) after edits", "textDocument/diagnostic", dict(document, identifier="DocumentCompilerSemantic"), repeat=4)
    bench.timed("semanticTokens/full after edits", "textDocument/semanticTokens/full", document, repeat=4)

    report = {"server": initialize.get("result", {}).get("serverInfo"), "solution": str(solution), "file": str(file), "fileLines": lines,
              "initialize_ms": round(initialize_ms), "solutionLoaded_ms": round(loaded) if loaded else None, "rows": bench.rows}
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print("initialize %d ms, solution loaded %s ms" % (initialize_ms, round(loaded) if loaded else "never"))
    for row in bench.rows:
        print(json.dumps(row, ensure_ascii=False))
    try:
        server.request("shutdown", None, timeout=15)
        server.notify("exit", None)
    except Exception:
        pass
    server.process.kill()


if __name__ == "__main__":
    main()
