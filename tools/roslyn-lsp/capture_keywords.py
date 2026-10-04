"""
What roslyn-language-server answers where a keyword is as likely as a type: `pub` in a class body (the keyword `public` and the type
`PublicKey` of an unimported namespace), `public s` and `public str` (the predefined types `string`, `short`, the modifiers `static`,
`sealed`, against `String`, `SByte`, `Stream`). The question: which items are keywords, which come from unimported namespaces, and what
the server says of their order (`sortText`, `preselect`), so that the client can rank them (0.1.44).

    python tools/roslyn-lsp/capture_keywords.py            # -> tools/roslyn-lsp/out/capture-keywords/*.json, a summary on stdout
    python tools/roslyn-lsp/capture_keywords.py --fixtures src/test/resources/roslyn/capture-5.12-keywords

The scenario lives in an unsaved version of Console/Scenarios.cs of debug-playground. Nothing on disk is changed.
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
class KeywordProbePublic
{
    pub/*1*/
}

class KeywordProbeS
{
    public s/*2*/
}

class KeywordProbeStr
{
    public str/*3*/
}
'''

PREFIXES = {1: "pub", 2: "s", 3: "str"}
# the first items of the list as the server orders them, and these wherever they are
WATCHED = {"public", "PublicKey", "string", "static", "sealed", "short", "struct", "String", "SByte", "Stream", "StringComparer"}


def main():
    solution = Path(option("--solution", "debug-playground/DebugPlayground.sln")).resolve()
    root = solution.parent
    out = Path(option("--out", str(Path(__file__).parent / "out" / "capture-keywords")))
    capabilities_file = Path(__file__).parent / "client-capabilities.json"
    capabilities = json.loads(capabilities_file.read_text(encoding="utf-8")) if capabilities_file.exists() else FALLBACK_CAPABILITIES

    server = Server(find_server() + ["--stdio", "--logLevel", "Information", "--extensionLogDirectory", str(out.parent.resolve() / "roslyn-logs-keywords")])
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

    file = root / "Console" / "Scenarios.cs"
    text = file.read_text(encoding="utf-8-sig").replace("\r\n", "\n") + SCENARIO
    document = {"uri": file.as_uri()}
    server.notify("textDocument/didOpen", {"textDocument": {"uri": file.as_uri(), "languageId": "csharp", "version": 1, "text": text}})
    # the first completion of a server is partial (see capture.py): a diagnostic pull first, as the IDE does
    capture.request("textDocument/diagnostic", {"textDocument": document}, timeout=180)

    # the items of unimported namespaces come once the server has built its cache of them, in the background after the first list:
    # a first round to start it, a pause, the round that is recorded
    for marker in PREFIXES:
        offset = text.index("/*%d*/" % marker)
        position = {"line": text.count("\n", 0, offset), "character": offset - (text.rfind("\n", 0, offset) + 1)}
        capture.request("textDocument/completion", {"textDocument": document, "position": position, "context": {"triggerKind": 1}}, "warm-up %d" % marker)
    time.sleep(float(option("--pause", "15")))

    summary = []
    for marker, prefix in PREFIXES.items():
        offset = text.index("/*%d*/" % marker)
        line = text.count("\n", 0, offset)
        position = {"line": line, "character": offset - (text.rfind("\n", 0, offset) + 1)}
        answer = capture.request("textDocument/completion", {"textDocument": document, "position": position, "context": {"triggerKind": 1}},
                                 "textDocument/completion %d %s" % (marker, prefix)) or {}
        items = answer.get("items", []) if isinstance(answer, dict) else answer
        defaults = (answer.get("itemDefaults") or {}) if isinstance(answer, dict) else {}
        matching = [item for item in items if isinstance(item, dict) and (item.get("filterText") or item["label"]).lower().startswith(prefix.lower())]
        ordered = sorted(matching, key=lambda item: item.get("sortText") or item["label"])
        rows = []
        for rank, item in enumerate(ordered):
            if rank >= 25 and item["label"] not in WATCHED and not item.get("labelDetails"):
                continue
            data = item.get("data", defaults.get("data"))
            rows.append({"rank": rank, "label": item["label"], "kind": item.get("kind"), "sortText": item.get("sortText"),
                         "filterText": item.get("filterText"), "preselect": item.get("preselect"), "labelDetails": item.get("labelDetails"),
                         "insertText": item.get("insertText"), "textEditText": item.get("textEditText"),
                         "commitCharacters": item.get("commitCharacters") is not None, "tags": item.get("tags"),
                         "data": data if isinstance(data, (dict, list)) and len(json.dumps(data)) < 400 else None})
        summary.append({"marker": marker, "prefix": prefix, "isIncomplete": answer.get("isIncomplete") if isinstance(answer, dict) else None,
                        "items": len(items), "matching": len(matching), "rows": rows})
    capture.save("summary of keyword positions", {"method": "(summary)", "result": summary})
    server.notify("exit", {})

    for block in summary:
        print("== %s (%d of %d match)" % (block["prefix"], block["matching"], block["items"]))
        for row in block["rows"][:15] + [r for r in block["rows"][15:] if r["label"] in WATCHED]:
            print("  %3d %-24s kind=%-3s sort=%-28s pre=%-5s %s" % (row["rank"], row["label"], row["kind"], row["sortText"], row["preselect"],
                                                              json.dumps(row["labelDetails"]) if row["labelDetails"] else ""))

    fixtures = option("--fixtures")
    if fixtures:
        target = Path(fixtures)
        target.mkdir(parents=True, exist_ok=True)
        for old in target.glob("*.json"):
            old.unlink()
        for record in sorted(out.glob("*summary*.json")):
            content = json.dumps(json.loads(record.read_text(encoding="utf-8")), ensure_ascii=False, indent=1)
            content = content.replace(root.as_uri(), "file:///c:/playground").replace(str(root).replace("\\", "/"), "c:/playground").replace(json.dumps(str(root))[1:-1], "c:/playground")
            content = re.sub(r'(?i)c(:|%3a)([/\\]|%5c)users\2[^/\\%"]+', lambda m: "c" + m.group(1) + m.group(2) + "users" + m.group(2) + "me", content)
            (target / record.name).write_text(content, encoding="utf-8", newline="\n")
        print("fixtures ->", target)


if __name__ == "__main__":
    main()
