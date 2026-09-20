"""Compares two runs of the probe session by session: python compare.py <dir-or-files of run A> -- <dir-or-files of run B>."""
import difflib
import glob
import os
import re
import sys


def sessions(paths):
    result = {}
    for path in paths:
        files = sorted(glob.glob(os.path.join(path, "*.txt"))) if os.path.isdir(path) else [path]
        for file in files:
            name = None
            for line in open(file, encoding="utf-8", errors="replace"):
                m = re.match(r"===== (.+) =====", line)
                if m:
                    name = m.group(1)
                    result[name] = []
                elif name:
                    result[name].append(normalize(line.rstrip("\n")))
    return result


def normalize(line):
    line = re.sub(r"\(\d+ms\)|\| \d+ms|\d+ms|took [\d.]+s|in \d+ms", "<t>", line)
    line = re.sub(r'"threadId": \d+|thread \d+|\(thread \d+\)', "<thread>", line)
    line = re.sub(r'"systemProcessId": \d+|pid \d+', "<pid>", line)
    line = re.sub(r"0x[0-9A-Fa-f]{6,}", "<addr>", line)
    line = re.sub(r"(<dap>|/probe)[\\/]+", "<root>/", line)
    line = re.sub(r"[A-Za-z]:\\\\?[^\"' ]*scratchpad[^\"' ]*dap\\\\?", "<root>/", line)
    line = line.replace("\\\\", "/").replace("\\", "/").replace("\r", "")
    line = re.sub(r"/r/n|\\r\\n", "/n", line)
    line = re.sub(r'"id": \d+', '"id": N', line)
    line = re.sub(r"ref=\d+", "ref=N", line)
    return line


NOISE = re.compile(r"~ (module|thread|process|initialized|breakpoint)|adapter stderr|_methodPtr|child\.Id|_threadId|hugeString value|RESULT exit code")

if __name__ == "__main__":
    split = sys.argv.index("--")
    a, b = sessions(sys.argv[1:split]), sessions(sys.argv[split + 1:])
    same, different = [], []
    for name in sorted(set(a) | set(b)):
        if name not in a or name not in b:
            print("##### %s: only in %s" % (name, "A" if name in a else "B"))
            continue
        left = [l for l in a[name] if not NOISE.search(l)]
        right = [l for l in b[name] if not NOISE.search(l)]
        if left == right:
            same.append(name)
            continue
        different.append(name)
        print("##### %s" % name)
        for line in difflib.unified_diff(left, right, "A", "B", lineterm="", n=0):
            if not line.startswith(("---", "+++", "@@")):
                print("   " + line[:330])
    print("\nSAME (%d): %s" % (len(same), ", ".join(same)))
    print("DIFFERENT (%d): %s" % (len(different), ", ".join(different)))
