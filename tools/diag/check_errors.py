"""Compare the `// ERROR CSxxxx` marks of a file with what the compiler and the IDE report.

Every line of a file in debug-playground/Broken/Errors that must have an error ends with `// ERROR CS0128` (several codes: `// ERROR CS0128 CS0103`);
every other line must have no error at all. Two judges:

  uv run --no-project python tools/diag/check_errors.py roslyn debug-playground/Broken/Errors/CS0128.cs [...]
      builds each file alone in a temporary class library (the SDK of the machine, nullable and implicit usings on, as Broken) and compares
      the errors of `dotnet build` with the marks: the marks have to be what Roslyn says, not what we think it says;
  uv run --no-project python tools/diag/check_errors.py ide debug-playground/Broken/Errors/CS0128.cs [...]
      asks the sandbox IDE of the UI robot (127.0.0.1:8583) for the ERROR highlights of each file
      (tools/ui-robot/scripts/errors_at.js) and compares them with the marks. Highlighting needs a moment after an open: `--retries N`.
      `--source roslyn` first switches «Errors and warnings» to roslyn-language-server (tools/ui-robot/scripts/feature_source.js) and back to
      the plugin's own pass at the end: the server, in the same project and IDE, is the reference the plugin's highlights are compared with
      (it is turned on for the run and off again; give it `--retries 20`, it answers later than the plugin).
  `--host DIR` (both modes): the files are checked as copies in DIR/_ErrorsCheck, deleted after. DIR has to be a project of the solution open
      in the sandbox (`--host debug-playground/ShopApi` when ShopApi.sln is open): neither the plugin nor the server checks a file outside
      the projects (Broken/Errors is excluded from compiling), and the copies see the project's implicit usings.

Exit code 0 when every file agrees. Output: `OK file` or `MISMATCH file` with `missing line N CSxxxx` / `extra line N CSxxxx: message`.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MARK = re.compile(r"//\s*ERROR\s+((?:CS\d{4}\s*)+)")
ROSLYN_ERROR = re.compile(r"^(?P<path>.+?)\((?P<line>\d+),(?P<col>\d+)\): error (?P<code>CS\d{4}): (?P<message>.*?)(?: \[.*\])?$")
IDE_ERROR = re.compile(r"^(?P<line>\d+):\d+-\d+:\d+ \[ERROR\] .*? \| (?P<code>CS\d{4}): (?P<message>.*)$")

CSPROJ = """<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <TargetFramework>net{major}.0</TargetFramework>
    <OutputType>Library</OutputType>
    <ImplicitUsings>enable</ImplicitUsings>
    <Nullable>enable</Nullable>
    <LangVersion>latest</LangVersion>
    <TreatWarningsAsErrors>false</TreatWarningsAsErrors>
    <EnableNETAnalyzers>false</EnableNETAnalyzers>
  </PropertyGroup>
</Project>
"""


def marks(path):
    found = set()
    with open(path, encoding="utf-8-sig") as f:
        for number, line in enumerate(f, 1):
            m = MARK.search(line)
            if m:
                for code in m.group(1).split():
                    found.add((number, code))
    return found


def compare(path, reported):
    expected = marks(path)
    got = {(line, code) for line, code, _ in reported}
    messages = {(line, code): message for line, code, message in reported}
    missing = sorted(expected - got)
    extra = sorted(got - expected)
    name = os.path.relpath(path, ROOT)
    if not missing and not extra:
        print(f"OK {name} ({len(expected)} marks)")
        return True
    print(f"MISMATCH {name}")
    for line, code in missing:
        print(f"  missing line {line} {code}")
    for line, code in extra:
        print(f"  extra line {line} {code}: {messages.get((line, code), '')}")
    return False


def sdk_major():
    version = subprocess.run(["dotnet", "--version"], capture_output=True, text=True).stdout.strip()
    return version.split(".")[0]


def roslyn(paths):
    major = sdk_major()
    ok = True
    for path in paths:
        work = tempfile.mkdtemp(prefix="diag-check-")
        try:
            with open(os.path.join(work, "Check.csproj"), "w", encoding="utf-8") as f:
                f.write(CSPROJ.format(major=major))
            shutil.copy(path, os.path.join(work, os.path.basename(path)))
            run = subprocess.run(["dotnet", "build", "-nologo", "-clp:NoSummary", "-v", "q", "-p:UseSharedCompilation=false"], cwd=work,
                                 capture_output=True, text=True, encoding="utf-8", errors="replace", env={**os.environ, "DOTNET_CLI_UI_LANGUAGE": "en"})
            reported = set()
            for line in (run.stdout + run.stderr).splitlines():
                m = ROSLYN_ERROR.match(line.strip())
                if m and os.path.basename(m.group("path")) == os.path.basename(path):
                    reported.add((int(m.group("line")), m.group("code"), m.group("message")))
            ok = compare(path, reported) and ok
        finally:
            shutil.rmtree(work, ignore_errors=True)
    return ok


def robot_js(robot, template, replacements):
    script = os.path.join(tempfile.gettempdir(), "diag_robot.js")
    for key, value in replacements.items():
        template = template.replace(key, value)
    with open(script, "w", encoding="utf-8") as f:
        f.write(template)
    run = subprocess.run([sys.executable, robot, "js", script], capture_output=True, text=True, encoding="utf-8", errors="replace",
                         env={**os.environ, "PYTHONIOENCODING": "utf-8"})
    if run.returncode != 0:
        # the sandbox is gone (or the script broke): retrying would only wait for nothing
        sys.exit("robot failed: " + (run.stderr.strip().splitlines() or ["?"])[-1])
    return run.stdout


HOST = None   # --host DIR: check copies in DIR/_ErrorsCheck


def hosted(paths):
    """Copies in debug-playground/Console (a project of the solution): the server checks only files of a loaded project, not Broken/Errors."""
    os.makedirs(HOST, exist_ok=True)
    copies = []
    for path in paths:
        copy = os.path.join(HOST, os.path.basename(path))
        shutil.copy(path, copy)
        copies.append(copy)
    return copies


def ide(paths, retries, source=None):
    robot = os.path.join(ROOT, "tools", "ui-robot", "robot.py")
    if HOST and not source:
        copies = hosted(paths)
        try:
            time.sleep(5)   # the IDE sees the new files of the project
            return check_ide(robot, copies, retries)
        finally:
            shutil.rmtree(HOST, ignore_errors=True)
    if source:
        scripts = os.path.join(ROOT, "tools", "ui-robot", "scripts")
        switch = open(os.path.join(scripts, "feature_source.js"), encoding="utf-8").read()
        server = open(os.path.join(scripts, "server_enabled.js"), encoding="utf-8").read()
        was = "server enabled true" in robot_js(robot, server, {"__ENABLED__": "keep"})
        if source.lower() == "roslyn":
            print(robot_js(robot, server, {"__ENABLED__": "true"}).strip())
            # the server loads the solution: wait until it answers (up to 5 minutes)
            for _ in range(60):
                status = robot_js(robot, server, {"__ENABLED__": "keep"})
                if "ready true" in status:
                    break
                time.sleep(5)
            print(status.strip())
        print(robot_js(robot, switch, {"__FEATURE__": "DIAGNOSTICS", "__SOURCE__": source.upper()}).strip())
        copies = hosted(paths) if HOST else paths
        try:
            if HOST:
                time.sleep(10)   # the server sees the new files of the project
            return check_ide(robot, copies, retries)
        finally:
            if HOST:
                shutil.rmtree(HOST, ignore_errors=True)
            print(robot_js(robot, switch, {"__FEATURE__": "DIAGNOSTICS", "__SOURCE__": "NATIVE"}).strip())
            if not was:
                print(robot_js(robot, server, {"__ENABLED__": "false"}).strip())
    return check_ide(robot, paths, retries)


def check_ide(robot, paths, retries):
    template = open(os.path.join(ROOT, "tools", "ui-robot", "scripts", "errors_at.js"), encoding="utf-8").read()
    ok = True
    for path in paths:
        absolute = os.path.abspath(path).replace("\\", "/")
        reported, previous = set(), None
        for _ in range(retries):
            out = robot_js(robot, template, {"__FILE__": absolute})
            reported = set()
            for line in out.splitlines():
                m = IDE_ERROR.match(line.strip())
                if m:
                    reported.add((int(m.group("line")), m.group("code"), m.group("message")))
            # settled: the same twice, and something (every file has marks; the server answers seconds after the plugin)
            if previous is not None and reported == previous and reported:
                break
            previous = reported
            time.sleep(3)
        ok = compare(path, reported) and ok
    return ok


def main():
    sys.stdout.reconfigure(line_buffering=True)   # a long run shows each file as it is done, also into a log file
    if len(sys.argv) < 3 or sys.argv[1] not in ("roslyn", "ide"):
        print(__doc__)
        return 2
    args = sys.argv[2:]
    retries = 5
    source = None
    if "--host" in args:
        i = args.index("--host")
        global HOST
        HOST = os.path.join(os.path.abspath(args[i + 1]), "_ErrorsCheck")
        del args[i:i + 2]
    if "--source" in args:
        i = args.index("--source")
        source = args[i + 1]
        del args[i:i + 2]
    if "--retries" in args:
        i = args.index("--retries")
        retries = int(args[i + 1])
        del args[i:i + 2]
    paths = []
    for a in args:
        paths += [os.path.join(a, n) for n in sorted(os.listdir(a)) if n.endswith(".cs")] if os.path.isdir(a) else [a]
    ok = roslyn(paths) if sys.argv[1] == "roslyn" else ide(paths, retries, source)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
