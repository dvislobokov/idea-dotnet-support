// The stop as the Debug tool window shows it: the threads (the active one marked), the frames of the active thread, the variables of the
// current frame with their values (the first level). For checking the debugger without reading pictures.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.ui.SimpleColoredText)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const session = XDebuggerManager.getInstance(project).getCurrentSession()
const report = new java.lang.StringBuilder()
const done = new java.util.concurrent.CountDownLatch(2)

function frameText(frame) {
    const text = new SimpleColoredText()
    frame.customizePresentation(text)
    return String(text.toString())
}

if (session == null || session.getSuspendContext() == null) {
    report.append("not suspended")
} else {
    const context = session.getSuspendContext()
    const active = context.getActiveExecutionStack()
    const stacks = context.getExecutionStacks()
    report.append("threads: " + stacks.length + "\n")
    for (let i = 0; i < stacks.length && i < 8; i++) report.append("  " + (stacks[i] === active ? "* " : "  ") + stacks[i].getDisplayName() + "\n")
    report.append("frames of " + active.getDisplayName() + ":\n")
    report.append("  " + frameText(active.getTopFrame()) + "\n")
    active.computeStackFrames(1, new com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer({
        addStackFrames: function (frames, last) {
            for (let i = 0; i < frames.size() && i < 6; i++) report.append("  " + frameText(frames.get(i)) + "\n")
            if (last) done.countDown()
        },
        errorOccurred: function (message) { report.append("  frames error: " + message + "\n"); done.countDown() },
        isObsolete: function () { return false },
    }))
    session.getCurrentStackFrame().computeChildren(new com.intellij.xdebugger.frame.XCompositeNode({
        addChildren: function (children, last) {
            report.append("variables:\n")
            // a function per row: Rhino keeps the first value of a `const` declared inside a loop
            for (let i = 0; i < children.size() && i < 16; i++) row(children.getName(i), children.getValue(i))
            if (last) done.countDown()
        },
        tooManyChildren: function (remaining) {},
        setAlreadySorted: function (sorted) {},
        setErrorMessage: function (message) { report.append("variables error: " + message + "\n"); done.countDown() },
        setMessage: function (message, icon, attributes, link) {},
        isObsolete: function () { return false },
    }))
    done.await(10, java.util.concurrent.TimeUnit.SECONDS)
}

function row(name, value) {
    value.computePresentation(new com.intellij.xdebugger.frame.XValueNode({
        setPresentation: function (icon, presentation, hasChildren) {
            report.append("  " + name + " : " + presentation.getType() + " = " + describe(presentation) + (hasChildren ? "  [+]" : "") + "\n")
        },
        setFullValueEvaluator: function (e) {},
        isObsolete: function () { return false },
    }), com.intellij.xdebugger.frame.XValuePlace.TREE)
}

function describe(presentation) {
    const out = new java.lang.StringBuilder()
    presentation.renderValue(new com.intellij.xdebugger.frame.presentation.XValuePresentation.XValueTextRenderer({
        renderValue: function (value, key) { out.append(value) },
        renderStringValue: function (value, chars, max) { out.append(value) },
        renderNumericValue: function (value) { out.append(value) },
        renderKeywordValue: function (value) { out.append(value) },
        renderComment: function (value) { out.append(value) },
        renderSpecialSymbol: function (value) { out.append(value) },
        renderError: function (value) { out.append("error: " + value) },
    }))
    return String(out.toString()).substring(0, 80)
}
report.toString()
