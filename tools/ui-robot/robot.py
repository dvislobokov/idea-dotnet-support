"""Drives the sandbox IDE started with `./gradlew runIdeForUiTests` through the Remote Robot server (http://127.0.0.1:8583).

    python robot.py wait                         wait until the server answers
    python robot.py windows                      frames and dialogs of the IDE
    python robot.py shot OUT.png [XPATH]         a picture of one component, by default the main frame (or the welcome screen)
    python robot.py find XPATH                   components matching the XPath, with their texts
    python robot.py click XPATH                  click the centre of the first match
    python robot.py clicktext XPATH TEXT         click a text inside the first match (a row of a tree or a list, a tab); add `double` for a double click
    python robot.py open PROJECT_DIR             open a project in the sandbox
    python robot.py action ACTION_ID             invoke an action of the IDE (StepOver, Resume, Stop, ...)
    python robot.py openfile FILE [LINE]         open a file of the project in the editor, the caret on LINE (1-based)
    python robot.py breakpoint FILE LINE         toggle a line breakpoint (LINE is 1-based)
    python robot.py run CONFIGURATION [Debug]    start a run configuration by name, with the Run or the Debug executor
    python robot.py js FILE.js [--edt]           run JavaScript inside the IDE, print what it returns
    python robot.py tree OUT.html                the component tree with XPaths (what http://127.0.0.1:8583 shows)

Pictures are always of a component of the IDE, painted by the component itself: the `/screenshot` of the server captures the
whole desktop with whatever else is on it, and is deliberately not used.
"""
import base64
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8583"
MAIN_WINDOWS = ["//div[@class='IdeFrameImpl']", "//div[@class='FlatWelcomeFrame']"]
DIALOGS = "//div[@class='MyDialog']"


def request(path, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()


def call(path, body=None, timeout=60):
    result = json.loads(request(path, body, timeout))
    if result.get("status") != "SUCCESS":
        raise SystemExit("robot: %s\n%s" % (result.get("message") or result.get("status"), result.get("log", "")))
    return result


def components(xpath):
    return call("/xpath/components", {"xpath": xpath}).get("elementList") or []


def first(xpath):
    found = components(xpath)
    if not found:
        raise SystemExit("robot: nothing matches %s" % xpath)
    return found[0]


def main_window():
    for xpath in MAIN_WINDOWS:
        found = components(xpath)
        if found:
            return found[0]
    raise SystemExit("robot: the IDE has no window yet")


def image_bytes(result):
    raw = result["bytes"]
    return base64.b64decode(raw) if isinstance(raw, str) else bytes((value + 256) % 256 for value in raw)


def script_body(script, edt=False):
    return {"script": script, "runInEdt": edt}


def js(script, edt=False, timeout=120):
    result = call("/js/retrieveAny", script_body(script, edt), timeout)
    raw = result.get("bytes")
    if raw is None:
        return None
    # a Java-serialized object; strings and numbers are readable enough once the header is cut off
    data = image_bytes(result)
    return data.decode("utf-8", errors="replace")


def js_string(value):
    return json.dumps(value)


def command_wait(_):
    for _ in range(120):
        try:
            request("/", timeout=3)
            print("robot is up")
            return
        except (urllib.error.URLError, OSError):
            time.sleep(2)
    raise SystemExit("robot: no answer from %s" % BASE)


def command_windows(_):
    for xpath in MAIN_WINDOWS + [DIALOGS]:
        for component in components(xpath):
            print(xpath, component["className"].split(".")[-1], "%sx%s" % (component["width"], component["height"]), component["id"])


def command_shot(args):
    component = first(args[1]) if len(args) > 1 else main_window()
    result = call("/%s/screenshot?isPaintingMode=true" % component["id"])
    with open(args[0], "wb") as out:
        out.write(image_bytes(result))
    print(args[0], "%sx%s" % (component["width"], component["height"]))


def command_find(args):
    for component in components(args[0]):
        texts = call("/%s/data" % component["id"], {}).get("componentData", {}).get("textDataList", [])  # POST: a GET is answered 404
        print(component["className"].split(".")[-1], "%sx%s" % (component["width"], component["height"]), component["id"], "|", " ".join(t["text"] for t in texts)[:200])


def command_click(args):
    component = first(args[0])
    call("/%s/js/execute" % component["id"], script_body("robot.click(component)"))  # without /js it is the route for serialized lambdas
    print("clicked", component["className"].split(".")[-1])


def command_clicktext(args):
    component = first(args[0])
    texts = call("/%s/data" % component["id"], {}).get("componentData", {}).get("textDataList", [])
    match = next((t for t in texts if t["text"] == args[1]), None) or next((t for t in texts if args[1] in t["text"]), None)
    if match is None:
        raise SystemExit("robot: no text %r in %s; there is: %s" % (args[1], args[0], " | ".join(t["text"] for t in texts)[:400]))
    point = "new java.awt.Point(%d, %d)" % (match["point"]["x"], match["point"]["y"])
    click = "robot.doubleClick(component, %s)" % point if "double" in args[2:] else "robot.click(component, %s)" % point
    call("/%s/js/execute" % component["id"], script_body(click))
    print("clicked", repr(match["text"]))


def command_open(args):
    script = """
        importClass(com.intellij.ide.impl.ProjectUtil)
        importClass(com.intellij.openapi.application.ApplicationManager)
        const path = %s
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { ProjectUtil.openOrImport(path, null, true) } }))
    """ % js_string(args[0].replace("\\", "/"))
    call("/js/execute", script_body(script))
    print("opening", args[0])


def command_action(args):
    script = """
        importClass(com.intellij.openapi.actionSystem.ActionManager)
        importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
        importClass(com.intellij.openapi.actionSystem.ActionPlaces)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.wm.IdeFocusManager)
        importClass(com.intellij.ide.DataManager)
        const id = %s
        ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
            const action = ActionManager.getInstance().getAction(id)
            if (action == null) throw new java.lang.IllegalArgumentException("No action " + id)
            const focus = IdeFocusManager.getGlobalInstance().getFocusOwner()
            ActionManager.getInstance().tryToExecute(action, null, focus, ActionPlaces.UNKNOWN, true)
        } }))
    """ % js_string(args[0])
    call("/js/execute", script_body(script))
    print("invoked", args[0])


IN_PROJECT = """
    importClass(com.intellij.openapi.project.ProjectManager)
    importClass(com.intellij.openapi.application.ApplicationManager)
    importClass(com.intellij.openapi.vfs.LocalFileSystem)
    const projects = ProjectManager.getInstance().getOpenProjects()
    if (projects.length == 0) throw new java.lang.IllegalStateException("No project is open")
    const project = projects[projects.length - 1]
    function later(body) { ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: body })) }
    function file(path) {
        const found = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        if (found == null) throw new java.lang.IllegalArgumentException("No file " + path)
        return found
    }
"""


def command_openfile(args):
    line = int(args[1]) - 1 if len(args) > 1 else 0
    script = IN_PROJECT + """
        importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
        const target = file(%s)
        later(function () { new OpenFileDescriptor(project, target, %d, 0).navigate(true) })
    """ % (js_string(args[0].replace("\\", "/")), line)
    call("/js/execute", script_body(script))
    print("opened", args[0])


def command_breakpoint(args):
    script = IN_PROJECT + """
        importClass(com.intellij.xdebugger.XDebuggerUtil)
        const target = file(%s)
        later(function () { XDebuggerUtil.getInstance().toggleLineBreakpoint(project, target, %d) })
    """ % (js_string(args[0].replace("\\", "/")), int(args[1]) - 1)
    call("/js/execute", script_body(script))
    print("toggled a breakpoint at", args[0], args[1])


def command_run(args):
    debug = len(args) > 1 and args[1].lower() == "debug"
    script = IN_PROJECT + """
        importClass(com.intellij.execution.RunManager)
        importClass(com.intellij.execution.ProgramRunnerUtil)
        importClass(com.intellij.execution.executors.DefaultDebugExecutor)
        importClass(com.intellij.execution.executors.DefaultRunExecutor)
        const name = %s
        const settings = RunManager.getInstance(project).getAllSettings().stream().filter(function (s) { return s.getName() == name }).findFirst().orElse(null)
        if (settings == null) throw new java.lang.IllegalArgumentException("No run configuration " + name)
        const executor = %s
        later(function () { ProgramRunnerUtil.executeConfiguration(settings, executor) })
    """ % (js_string(args[0]), "DefaultDebugExecutor.getDebugExecutorInstance()" if debug else "DefaultRunExecutor.getRunExecutorInstance()")
    call("/js/execute", script_body(script))
    print("started", args[0], "with", "Debug" if debug else "Run")


def command_js(args):
    print(js(open(args[0], encoding="utf-8").read(), edt="--edt" in args))


def command_tree(args):
    with open(args[0], "wb") as out:
        out.write(request("/hierarchy", timeout=120))
    print(args[0])


COMMANDS = {
    "wait": command_wait, "windows": command_windows, "shot": command_shot, "find": command_find, "click": command_click,
    "open": command_open, "action": command_action, "js": command_js, "tree": command_tree,
    "clicktext": command_clicktext, "openfile": command_openfile, "breakpoint": command_breakpoint, "run": command_run,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        raise SystemExit(__doc__)
    COMMANDS[sys.argv[1]](sys.argv[2:])
