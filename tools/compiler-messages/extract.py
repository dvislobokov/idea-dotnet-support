"""Extract the C# examples of dotnet/docs' compiler-messages pages into a corpus, one file per example.

Usage (from the repository root):
    uv run --no-project python tools/compiler-messages/extract.py <path to docs/csharp/language-reference/compiler-messages> [out dir]

Each ```csharp block of a page (and each `:::code source="..." id="..." :::` snippet) becomes `<out>/<page>-<n>.cs`, verbatim, with a
header comment naming the page and the codes the page documents (`f1_keywords`): what the coverage test (`CompilerMessagesCoverageTest`)
measures the plugin on. Blocks that are not whole files (a lone member, `...`) are kept too: Roslyn compiles them the same way for both
sides, the oracle (`oracle.py`) records whatever Roslyn says. Default out dir: src/test/resources/compilerMessages/cases.
"""
import re
import sys
from pathlib import Path

docs = Path(sys.argv[1])
out = Path(sys.argv[2] if len(sys.argv) > 2 else "src/test/resources/compilerMessages/cases")
out.mkdir(parents=True, exist_ok=True)
for old in out.glob("*.cs"):
    old.unlink()

BLOCK = re.compile(r"^```(?:csharp|cs|c#)[^\n]*\n(.*?)^```", re.M | re.S | re.I)
SNIPPET = re.compile(r':::code\s+language="csharp"\s+source="([^"]+)"(?:\s+id="([^"]+)")?\s*:::', re.I)
CODES = re.compile(r"\bCS\d{4}\b")


def front_matter_codes(text: str) -> list[str]:
    head = text.split("\n---", 2)[1] if text.startswith("---") else ""
    return sorted(set(CODES.findall(head)))


def snippet(page: Path, source: str, region: str | None) -> str | None:
    path = (page.parent / source).resolve()
    if not path.is_file():
        return None
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    if not region:
        return "\n".join(lines) + "\n"
    inside, taken = False, []
    for line in lines:
        s = line.strip()
        if s.startswith("// <") and s.endswith(">") and s[4:-1].strip() == region:
            inside = True
            continue
        if s.startswith("// </") and inside:
            break
        if inside:
            taken.append(line)
    return "\n".join(taken) + "\n" if taken else None


count = 0
for page in sorted(docs.glob("*.md")):
    if page.name == "index.md":
        continue
    text = page.read_text(encoding="utf-8")
    codes = front_matter_codes(text)
    examples: list[str] = [m.group(1) for m in BLOCK.finditer(text)]
    for m in SNIPPET.finditer(text):
        body = snippet(page, m.group(1), m.group(2))
        if body:
            examples.append(body)
    for n, body in enumerate(examples, 1):
        body = body.replace("\r\n", "\n")
        if not body.strip() or "..." == body.strip():
            continue
        header = f"// docs: {page.name} #{n}; codes: {' '.join(codes) or '-'}\n"
        (out / f"{page.stem}-{n}.cs").write_text(header + body, encoding="utf-8", newline="\n")
        count += 1
print(f"{count} examples -> {out}")
