// Evaluates __EXPRESSION__ in the current frame and tells what the "View" of the value gives: whether there is a link (full value evaluator),
// the text behind it, whether the hover shows text (XValueTextProvider) and which tabs the platform's popup would have (JSON, XML, HTML, JWT...).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.util.Disposer)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.xdebugger.evaluation.XDebuggerEvaluator)
importClass(com.intellij.xdebugger.frame.XValueNode)
importClass(com.intellij.xdebugger.frame.XFullValueEvaluator)
importClass(com.intellij.xdebugger.frame.XValuePlace)
importClass(com.intellij.xdebugger.impl.ui.visualizedtext.VisualizedTextPopupUtil)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const frame = XDebuggerManager.getInstance(project).getCurrentSession().getCurrentStackFrame()
const EXPRESSION = "__EXPRESSION__"

const result = new CompletableFuture()
frame.getEvaluator().evaluate(EXPRESSION, new XDebuggerEvaluator.XEvaluationCallback({
    evaluated: function (value) { result.complete(value) },
    errorOccurred: function (message) { result.complete("error: " + message) },
}), null)
const value = result.get(60, TimeUnit.SECONDS)
let text = EXPRESSION + ": "
if (typeof value == "string" || value instanceof java.lang.String) text += value
else {
    const evaluator = new CompletableFuture()
    value.computePresentation(new XValueNode({
        setPresentation: function () {},
        setFullValueEvaluator: function (e) { evaluator.complete(e) },
        isObsolete: function () { return false },
    }), XValuePlace.TREE)
    let full = null
    try { full = evaluator.get(3, TimeUnit.SECONDS) } catch (e) {}
    text += "link=" + (full != null)
    if (full != null) {
        const got = new CompletableFuture()
        full.startEvaluation(new XFullValueEvaluator.XFullValueEvaluationCallback({
            evaluated: function (t) { got.complete(t) },
            errorOccurred: function (m) { got.complete("<error " + m + ">") },
            isObsolete: function () { return false },
        }))
        const shown = String(got.get(10, TimeUnit.SECONDS))
        text += " text=" + JSON.stringify(shown.length > 80 ? shown.substring(0, 80) + "…" : shown)
        // the visualizers build editors: off the EDT they give nothing at all
        const names = []
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
            const disposable = Disposer.newDisposable()
            const tabs = VisualizedTextPopupUtil.INSTANCE.collectVisualizedTabs(project, shown, disposable)
            for (var i = 0; i < tabs.size(); i++) names.push(tabs.get(i).getFirst().getName())
            Disposer.dispose(disposable)
        } }))
        text += " tabs=[" + names.join(", ") + "]"
    }
    text += " hoverText=" + (typeof value.shouldShowTextValue == "function" ? value.shouldShowTextValue() : "n/a")
}
text
