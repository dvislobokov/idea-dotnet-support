"""Rows of the popup dumps of popups.sh: `python popup_rows.py DUMP` prints, per position and action, the rows of the first list (the
popup), without the preview texts; with `--trim OUT` also writes that compact form to OUT (the raw dumps carry whole files in previews)."""
import io
import re
import sys


def parse(path):
    text = io.open(path, encoding="utf-8", errors="replace").read()
    result = {}
    for block in text.split("######## ")[1:]:
        head = block.split("\n", 1)[0]
        match = re.match(r"(\S+) (\S+) — (.*?) — «(.*)» \((.*)\)", head)
        if not match:
            continue
        pid, action, what, anchor, file = match.groups()
        rows = []
        in_list = False
        for line in block.split("\n")[1:]:
            if line.startswith("  list "):
                if rows:
                    break
                in_list = True
                continue
            if in_list and line.startswith("    "):
                row = line.strip()
                row = re.sub(r"\s*\|\s*.*$", "", row) if not row.startswith("---") else row
                if row and not row.startswith("<"):
                    rows.append(row)
            elif in_list and not line.startswith("    "):
                in_list = False
        result[(pid, action)] = {"what": what, "anchor": anchor, "file": file, "rows": rows}
    return result


if __name__ == "__main__":
    data = parse(sys.argv[1])
    lines = []
    for (pid, action), v in data.items():
        lines.append("%s %s — %s — «%s» (%s)" % (pid, action, v["what"], v["anchor"], v["file"]))
        lines.extend("    " + r for r in v["rows"])
    out = "\n".join(lines) + "\n"
    if "--trim" in sys.argv:
        io.open(sys.argv[sys.argv.index("--trim") + 1], "w", encoding="utf-8").write(out)
    else:
        sys.stdout.write(out)
