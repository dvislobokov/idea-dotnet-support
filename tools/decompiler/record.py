"""Records the answers of DotNetHelper `decompile` / `assemblyTypes` for the tests (src/test/resources/decompiler) and prints the timings.

From the root of the repository:  uv run --no-project python tools/decompiler/record.py [--timings]

Builds the helper (helpers/dotnethelper) and the fixture assembly of the index (tools/index-fixture) into a temporary folder, starts
`dotnet DotNetHelper.dll --serve` and asks it what the tests read; the temporary path of the fixture becomes `C:\\work\\fixture`.
With --timings it decompiles framework and NuGet types too (System.Console, System.String through System.Runtime, JsonSerializer,
Newtonsoft.Json when it is in the NuGet cache) and saves nothing of them: the first request of an assembly reads it, the next ones do not.
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
target = os.path.join(root, "src", "test", "resources", "decompiler")
work = tempfile.mkdtemp(prefix="decompiler-")
neutral = r"C:\work\fixture"


def build(project, out):
    subprocess.run(["dotnet", "build", project, "-c", "Release", "-o", out, "-nologo", "-v", "q"], check=True, cwd=root)


build("helpers/dotnethelper", os.path.join(work, "helper"))
build("tools/index-fixture", os.path.join(work, "fixture"))
fixture = os.path.join(work, "fixture", "IndexFixture.dll")
dotnet_root = os.path.dirname(os.path.realpath(shutil.which("dotnet")))
pack = sorted(glob.glob(os.path.join(dotnet_root, "packs", "Microsoft.NETCore.App.Ref", "10.0.*")))[-1]
pack_ref = os.path.join(pack, "ref", "net10.0")
shared = os.path.join(dotnet_root, "shared", "Microsoft.NETCore.App", os.path.basename(pack))

saved = [
    ("circle", "decompile", {"assembly": fixture, "typeName": "Fixture.Circle", "memberId": "P:Fixture.Circle.Radius", "referenceDirs": [pack_ref], "languageVersion": "latest"}),
    ("shape", "decompile", {"assembly": fixture, "typeName": "Fixture.Shape", "referenceDirs": [pack_ref]}),
    ("inner", "decompile", {"assembly": fixture, "typeName": "Fixture.Box`1+Inner`1", "referenceDirs": [pack_ref]}),
    ("types", "assemblyTypes", {"assembly": fixture}),
]
timed = [
    ("System.Console (implementation, first)", {"assembly": os.path.join(shared, "System.Console.dll"), "typeName": "System.Console", "xmlDoc": os.path.join(pack_ref, "System.Console.xml"), "referenceDirs": [pack_ref]}),
    ("System.ConsoleKeyInfo (same assembly)", {"assembly": os.path.join(shared, "System.Console.dll"), "typeName": "System.ConsoleKeyInfo", "referenceDirs": [pack_ref]}),
    ("System.String via System.Runtime", {"assembly": os.path.join(shared, "System.Runtime.dll"), "typeName": "System.String", "referenceDirs": [pack_ref]}),
    ("JsonSerializer", {"assembly": os.path.join(shared, "System.Text.Json.dll"), "typeName": "System.Text.Json.JsonSerializer", "xmlDoc": os.path.join(pack_ref, "System.Text.Json.xml"), "referenceDirs": [pack_ref]}),
]
newtonsoft = sorted(glob.glob(os.path.expanduser("~/.nuget/packages/newtonsoft.json/13.*/lib/net6.0/Newtonsoft.Json.dll")))
if newtonsoft:
    timed += [("JsonConvert (NuGet, first)", {"assembly": newtonsoft[-1], "typeName": "Newtonsoft.Json.JsonConvert", "referenceDirs": [pack_ref]}),
              ("JObject (same assembly)", {"assembly": newtonsoft[-1], "typeName": "Newtonsoft.Json.Linq.JObject", "referenceDirs": [pack_ref]})]

helper = subprocess.Popen(["dotnet", os.path.join(work, "helper", "DotNetHelper.dll"), "--serve"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8")
next_id = 0


def ask(method, params):
    global next_id
    next_id += 1
    helper.stdin.write(json.dumps({"id": next_id, "method": method, "params": params}) + "\n")
    helper.stdin.flush()
    while True:
        message = json.loads(helper.stdout.readline())
        if message.get("id") == next_id:
            if "error" in message: raise RuntimeError(message["error"]["message"])
            return message["result"]


os.makedirs(target, exist_ok=True)
def neutralize(answer):
    """The temporary folder of the fixture becomes `C:\\work\\fixture`; the offsets of the members after it move with the text."""
    folder = os.path.dirname(fixture)
    if isinstance(answer, dict) and "text" in answer:
        text = answer["text"]
        for member in answer["members"]:
            member["offset"] += text[:member["offset"]].count(folder) * (len(neutral) - len(folder))
        answer["text"] = text.replace(folder, neutral)
    return json.loads(json.dumps(answer).replace(json.dumps(folder)[1:-1], json.dumps(neutral)[1:-1]))


for name, method, params in saved:
    text = json.dumps(neutralize(ask(method, params)), indent=1, ensure_ascii=False)
    with open(os.path.join(target, name + ".json"), "w", encoding="utf-8", newline="\n") as f: f.write(text + "\n")
    print("saved", name)
if "--timings" in sys.argv:
    for name, params in timed:
        start = time.time()
        answer = ask("decompile", params)
        print(f"{name}: {int((time.time() - start) * 1000)} ms, {len(answer['text'])} chars, {len(answer['members'])} members, from {os.path.basename(answer['assembly'])}")
helper.stdin.close()
helper.wait()
shutil.rmtree(work, ignore_errors=True)
