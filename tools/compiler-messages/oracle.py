"""Roslyn's diagnostics of every file of the compiler-messages corpus, one compilation per file (the examples repeat type names).

Usage (from the repository root; needs the built oracle, see tools/csharp-psi/roslyndump/README.md):
    uv run --no-project python tools/compiler-messages/oracle.py [cases dir] [out file] [--jobs=N]

Runs `roslyndump semantics <file>` (files mode: the newest Microsoft.NETCore.App.Ref pack, nullable enabled, langversion preview) for each
`.cs` of the cases dir, in parallel, and writes `<out>` as tab-separated lines `file<TAB>start<TAB>end<TAB>code<TAB>severity`, sorted:
the oracle `CompilerMessagesCoverageTest` compares the plugin with. Default: src/test/resources/compilerMessages/cases -> .../cases.roslyn.txt.
"""
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

args = [a for a in sys.argv[1:] if not a.startswith("--")]
jobs = next((int(a.split("=", 1)[1]) for a in sys.argv[1:] if a.startswith("--jobs=")), 8)
cases = Path(args[0] if args else "src/test/resources/compilerMessages/cases")
out = Path(args[1] if len(args) > 1 else cases.parent / "cases.roslyn.txt")
dll = Path("tools/csharp-psi/roslyndump/bin/Release/net10.0/RoslynDump.dll")
if not dll.is_file():
    sys.exit(f"oracle not built: {dll} (dotnet build -c Release tools/csharp-psi/roslyndump)")


def run(file: Path) -> list[str]:
    with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as tmp:
        dump = Path(tmp.name)
    try:
        r = subprocess.run(["dotnet", str(dll), "semantics", str(file), "--root", str(cases), "--out", str(dump)], capture_output=True, text=True)
        if r.returncode != 0:
            print(f"{file.name}: exit {r.returncode}\n{r.stderr.strip()}", file=sys.stderr)
            return []
        lines = []
        for line in dump.read_text(encoding="utf-8").splitlines():
            if line.startswith("D\t"):
                _, start, end, code, severity = line.split("\t")
                lines.append(f"{file.name}\t{start}\t{end}\t{code}\t{severity}")
        return lines
    finally:
        dump.unlink(missing_ok=True)


files = sorted(cases.glob("*.cs"))
with ThreadPoolExecutor(max_workers=jobs) as pool:
    results = list(pool.map(run, files))
rows = sorted({row for rows in results for row in rows}, key=lambda r: (r.split("\t")[0], int(r.split("\t")[1]), r.split("\t")[3]))
out.write_text("\n".join(rows) + "\n", encoding="utf-8", newline="\n")
errors = sum(1 for r in rows if r.endswith("\terror"))
print(f"{len(files)} files, {len(rows)} diagnostics ({errors} errors) -> {out}")
