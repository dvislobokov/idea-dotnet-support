importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.frame.XCompositeNode)
importClass(com.intellij.xdebugger.frame.XValueModifier)
importClass(com.intellij.xdebugger.impl.breakpoints.XExpressionImpl)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const session = XDebuggerManager.getInstance(project).getCurrentSession()
const frame = session.getCurrentStackFrame()
const NAME = "__NAME__", VALUE = "__VALUE__"

function children(container) {
    const done = new CompletableFuture()
    const found = new java.util.ArrayList()
    container.computeChildren(new XCompositeNode({
        addChildren: function (list, last) {
            for (let i = 0; i < list.size(); i++) found.add([list.getName(i), list.getValue(i)])
            const groups = new java.util.ArrayList(list.getTopGroups()); groups.addAll(list.getBottomGroups())
            for (let g = 0; g < groups.size(); g++) found.add(["<group>", groups.get(g)])
            if (last) done.complete(found)
        },
        tooManyChildren: function (remaining) { done.complete(found) },
        setAlreadySorted: function (sorted) {},
        setErrorMessage: function (message) { done.completeExceptionally(new java.lang.RuntimeException(message)) },
        setMessage: function () {},
        isObsolete: function () { return false },
    }))
    return done.get(30, TimeUnit.SECONDS)
}

// NAME is a path: person/Age
let container = frame, value = null, names = ""
const path = NAME.split("/")
for (let p = 0; p < path.length; p++) {
    let list = children(container)
    // locals come inside a "Locals" group
    if (p == 0) { const top = new java.util.ArrayList(list); list = new java.util.ArrayList(); for (let g = 0; g < top.size(); g++) { if (top.get(g)[0] == "<group>") list.addAll(children(top.get(g)[1])); else list.add(top.get(g)) } }
    value = null; names = ""
    for (let i = 0; i < list.size(); i++) { names += list.get(i)[0] + " "; if (list.get(i)[0] == path[p]) value = list.get(i)[1] }
    if (value == null) throw new java.lang.IllegalStateException("no " + path[p] + " among: " + names)
    container = value
}
const modifier = value.getModifier()
if (modifier == null) throw new java.lang.IllegalStateException(NAME + " has no modifier (class " + value.getClass().getName() + ")")
const result = new CompletableFuture()
modifier.setValue(XExpressionImpl.fromText(VALUE), new XValueModifier.XModificationCallback({
    valueModified: function () { result.complete("modified") },
    errorOccurred: function (message) { result.complete("error: " + message) },
}))
NAME + " := " + VALUE + " -> " + result.get(30, TimeUnit.SECONDS)
