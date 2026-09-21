// Evaluates __EXPRESSION__ in the current frame and prints how the value is presented, how long it took, and its first children.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.evaluation.XDebuggerEvaluator)
importClass(com.intellij.xdebugger.frame.XValueNode)
importClass(com.intellij.xdebugger.frame.XCompositeNode)
importClass(com.intellij.xdebugger.frame.XValuePlace)
importClass(com.intellij.xdebugger.frame.presentation.XValuePresentation)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const session = XDebuggerManager.getInstance(project).getCurrentSession()
const frame = session.getCurrentStackFrame()
const EXPRESSION = "__EXPRESSION__", CHILDREN = __CHILDREN__

function present(value) {
    const done = new CompletableFuture()
    function render(presentation) {
        let text = ""
        const add = function (v) { text += v }
        presentation.renderValue(new XValuePresentation.XValueTextRenderer({
            renderValue: add, renderStringValue: add, renderNumericValue: add, renderKeywordValue: add, renderComment: add, renderSpecialSymbol: add,
            renderError: function (v) { text += "<error: " + v + ">" },
        }))
        return (presentation.getType() == null ? "" : "{" + presentation.getType() + "} ") + text
    }
    value.computePresentation(new XValueNode({
        setPresentation: function (icon, a, b, c) { done.complete(c === undefined ? render(a) + (b ? " [+]" : "") : "{" + a + "} " + b + (c ? " [+]" : "")) },
        setFullValueEvaluator: function (e) {},
        isObsolete: function () { return false },
    }), XValuePlace.TREE)
    return done.get(60, TimeUnit.SECONDS)
}

function children(container) {
    const done = new CompletableFuture()
    const found = new java.util.ArrayList()
    container.computeChildren(new XCompositeNode({
        addChildren: function (list, last) { for (let i = 0; i < list.size(); i++) found.add([list.getName(i), list.getValue(i)]); if (last) done.complete(found) },
        tooManyChildren: function (remaining) { found.add(["<more: " + remaining + ">", null]); done.complete(found) },
        setAlreadySorted: function (sorted) {},
        setErrorMessage: function (message) { found.add(["<error: " + message + ">", null]); done.complete(found) },
        setMessage: function () {},
        isObsolete: function () { return false },
    }))
    return done.get(120, TimeUnit.SECONDS)
}

const started = java.lang.System.currentTimeMillis()
const result = new CompletableFuture()
frame.getEvaluator().evaluate(EXPRESSION, new XDebuggerEvaluator.XEvaluationCallback({
    evaluated: function (value) { result.complete(value) },
    errorOccurred: function (message) { result.complete("error: " + message) },
}), null)
const value = result.get(120, TimeUnit.SECONDS)
let text = EXPRESSION + " = "
if (typeof value == "string" || value instanceof java.lang.String) text += value
else {
    const shown = String(present(value))
    text += (shown.length > 160 ? shown.substring(0, 160) + "... (" + shown.length + " chars)" : shown)
    if (CHILDREN > 0) {
        const list = children(value)
        text += "\n  children: " + list.size()
        for (let i = 0; i < Math.min(list.size(), CHILDREN); i++) text += "\n    " + list.get(i)[0] + (list.get(i)[1] == null ? "" : " = " + String(present(list.get(i)[1])).substring(0, 80))
        if (list.size() > CHILDREN) text += "\n    ... last: " + list.get(list.size() - 1)[0]
    }
}
text + "\n  (" + (java.lang.System.currentTimeMillis() - started) + " ms)"
