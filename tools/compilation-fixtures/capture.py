"""Answers of the real MsBuildHost for CompilationOptionsTest (src/test/resources/msbuild/compilation).

    uv run --no-project python tools/compilation-fixtures/capture.py

Builds helpers/msbuildhost in a temporary directory with the SDK `dotnet` finds, evaluates the projects of
src/test/resources/msbuild/compilation/projects and debug-playground/MultiTarget with the requests CompilationModel makes, and writes
the answers next to the projects, their paths made `C:\\src\\...` (the playground: `C:\\src\\debug-playground\\...`). The imports are
cut down to the Directory.Build.props ones: the SDK ones are long and name the machine's SDK.
"""
import json
import os
import shutil
import subprocess
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO, "src", "test", "resources", "msbuild", "compilation")
PROJECTS = os.path.join(OUT, "projects")
PLAYGROUND = os.path.join(REPO, "debug-playground")

# CompilationOptions.PROPERTIES, ITEM_TYPES, TARGETS (and a few more to look at)
PROPS = ["DefineConstants", "LangVersion", "Nullable", "ImplicitUsings", "RootNamespace", "TargetFramework", "MaxSupportedLangVersion",
         "TargetFrameworkIdentifier", "TargetFrameworkVersion", "TargetPlatformIdentifier", "Configuration", "Platform", "UsingMicrosoftNETSdk"]
ITEMS = ["Compile", "Using"]
TARGETS = ["AddImplicitDefineConstants"]

CASES = [
    ("net10-debug.json", PROJECTS, "Net10/Net10.csproj", {"Configuration": "Debug"}),
    ("multi-outer.json", PROJECTS, "Multi/Multi.csproj", {"Configuration": "Debug"}),
    ("multi-net48.json", PROJECTS, "Multi/Multi.csproj", {"Configuration": "Debug", "TargetFramework": "net48"}),
    ("multi-net10.json", PROJECTS, "Multi/Multi.csproj", {"Configuration": "Debug", "TargetFramework": "net10.0"}),
    ("netstandard-release.json", PROJECTS, "Std/Std.csproj", {"Configuration": "Release"}),
    ("props-release.json", PROJECTS, "Props/WithProps/WithProps.csproj", {"Configuration": "Release"}),
    ("legacy-debug.json", PROJECTS, "Legacy/Legacy.csproj", {"Configuration": "Debug"}),
    ("legacy-release.json", PROJECTS, "Legacy/Legacy.csproj", {"Configuration": "Release"}),
    ("playground-multitarget-net9.json", PLAYGROUND, "MultiTarget/MultiTarget.csproj", {"Configuration": "Debug", "TargetFramework": "net9.0"}),
]


def main():
    work = tempfile.mkdtemp(prefix="compilation-fixtures-")
    try:
        for name in ("msbuildhost", "protocol"):
            shutil.copytree(os.path.join(REPO, "helpers", name), os.path.join(work, "helpers", name))
        subprocess.run(["dotnet", "build", "-c", "Release", "-p:HelperFramework=net10.0", "-o", os.path.join(work, "bin")],
                       cwd=os.path.join(work, "helpers", "msbuildhost"), check=True, stdout=subprocess.DEVNULL)
        host = subprocess.Popen(["dotnet", os.path.join(work, "bin", "MsBuildHost.dll"), "--serve"], cwd=PROJECTS,
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
        for number, (name, root, path, globals_) in enumerate(CASES, 1):
            params = {"projectPath": os.path.join(root, path), "globalProperties": globals_, "properties": PROPS, "itemTypes": ITEMS, "targets": TARGETS}
            host.stdin.write(json.dumps({"id": number, "method": "evaluate", "params": params}) + "\n")
            host.stdin.flush()
            while True:
                message = json.loads(host.stdout.readline())
                if message.get("id") == number:
                    break
            if "error" in message:
                print(name, "ERROR", message["error"])
                continue
            result = message["result"]
            result["imports"] = [p for p in result["imports"] if "Directory.Build" in p]
            result["milliseconds"] = 0
            text = json.dumps(result, indent=1, ensure_ascii=False)
            src = "C:\\\\src" if root == PROJECTS else "C:\\\\src\\\\debug-playground"
            text = text.replace(json.dumps(os.path.normpath(root))[1:-1], src)
            with open(os.path.join(OUT, name), "w", encoding="utf-8", newline="\n") as f:
                f.write(text + "\n")
            print(name, result["properties"]["DefineConstants"], "|", result["properties"]["LangVersion"])
        host.stdin.close()
        host.wait()
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
