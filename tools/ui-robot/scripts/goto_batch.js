// Go to Declaration (Ctrl+B, action GotoDeclaration) over many places of one file, for a NATIVE / ROSLYN comparison of «Navigation and usages»
// (feature_source.js `NAVIGATION`). __FILE__ — forward slashes; __CASES__ — `line:name` or `line:name#n` separated by `;` (line from 1, the
// n-th occurrence of `name` on that line as a whole word, the first by default; the caret goes on its first character), so comments that
// mention the name do not matter. Per case prints who answers (NativeCSharpNavigation.serves), the targets of the built-in tree (`tree: none` —
// it leaves the name to the server), then where Ctrl+B took the caret (`to: file:line  text`), or the items of the list of targets that
// popped up (`list: …`, closed afterwards), or `to: (stayed)`. Waits for the server up to __WAIT__ ms per case. Then reopens __FILE__.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.openapi.fileEditor.OpenFileDescriptor)
importClass(com.intellij.openapi.vfs.LocalFileSystem)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.ui.popup.JBPopupFactory)
importClass(com.intellij.openapi.wm.WindowManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = PluginManagerCore.getPlugin(PluginId.getId("io.github.dotnetsupport")).getPluginClassLoader()
var navigationClass = loader.loadClass("io.github.dotnetsupport.lang.NativeCSharpNavigation")
var navigation = navigationClass.getField("INSTANCE").get(null)
var psiElementClass = loader.loadClass("com.intellij.psi.PsiElement")
var psiFileClass = loader.loadClass("com.intellij.psi.PsiFile")
var out = new java.lang.StringBuilder()
var app = ApplicationManager.getApplication()
var state = { editor: null, before: null, popup: null }
var vfile = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
function edt(body) { app.invokeAndWait(new java.lang.Runnable({ run: body }), ModalityState.nonModal()) }

function where(file, offset) {
    var document = FileDocumentManager.getInstance().getDocument(file)
    var line = document.getLineNumber(offset)
    var text = String(document.getText()).substring(document.getLineStartOffset(line), document.getLineEndOffset(line)).trim()
    return file.getName() + ":" + (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1) + "  " + text
}

function describe(item) {
    try {
        if (item instanceof com.intellij.psi.PsiElement && item.getContainingFile() != null && item.getContainingFile().getVirtualFile() != null)
            return where(item.getContainingFile().getVirtualFile(), item.getTextOffset())
        if (item instanceof com.intellij.navigation.NavigationItem && item.getPresentation() != null)
            return item.getPresentation().getPresentableText() + " " + item.getPresentation().getLocationString()
    } catch (error) { return "? " + error }
    return String(item)
}

function popups() {
    return JBPopupFactory.getInstance().getChildPopups(WindowManager.getInstance().getFrame(project).getRootPane())
}

var cases = "__CASES__".split(";")
for (var c = 0; c < cases.length; c++) {
    var spec = String(cases[c]).trim()
    if (spec.length == 0) continue
    var colon = spec.indexOf(":")
    var lineNo = java.lang.Integer.parseInt(spec.substring(0, colon)) - 1
    var name = spec.substring(colon + 1)
    var nth = 1
    if (name.indexOf("#") > 0) { nth = java.lang.Integer.parseInt(name.substring(name.indexOf("#") + 1)); name = name.substring(0, name.indexOf("#")) }
    state.editor = null
    state.popup = null
    out.append("== " + spec + "\n")
    edt(function () {
        var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile, 0), true)
        var document = editor.getDocument()
        var lineText = String(document.getText()).substring(document.getLineStartOffset(lineNo), document.getLineEndOffset(lineNo))
        var found = -1, from = 0, seen = 0
        while (true) {
            var at = lineText.indexOf(name, from)
            if (at < 0) break
            var before = at == 0 ? " " : lineText.charAt(at - 1)
            var after = at + name.length >= lineText.length ? " " : lineText.charAt(at + name.length)
            if (!/[A-Za-z0-9_@]/.test(before) && !/[A-Za-z0-9_]/.test(after)) { seen++; if (seen == nth) { found = at; break } }
            from = at + 1
        }
        if (found < 0) { out.append("  not found on the line\n"); return }
        editor.getCaretModel().moveToOffset(document.getLineStartOffset(lineNo) + found)
        state.editor = editor
        state.before = where(vfile, editor.getCaretModel().getOffset())
    })
    if (state.editor == null) continue
    out.append(ReadAction.compute(new com.intellij.openapi.util.ThrowableComputable({ compute: function () {
        var psi = PsiDocumentManager.getInstance(project).getPsiFile(state.editor.getDocument())
        var leaf = psi.findElementAt(state.editor.getCaretModel().getOffset())
        var serves = navigationClass.getMethod("serves", psiFileClass).invoke(navigation, psi)
        var text = "  answers: " + (String(serves) == "true" ? "built-in" : "server") + "\n"
        var targets = navigationClass.getMethod("targets", psiElementClass).invoke(navigation, leaf)
        if (targets == null) text += "  tree: none\n"
        else for (var i = 0; i < targets.size(); i++) text += "  tree: " + describe(targets.get(i)) + "\n"
        return text
    } })))
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        ActionUtil.invokeAction(ActionManager.getInstance().getAction("GotoDeclaration"), DataManager.getInstance().getDataContext(state.editor.getContentComponent()), "unknown", null, null)
    } }), ModalityState.nonModal()) // write-safe: the action may open a file
    var result = null
    for (var tick = 0; tick < __WAIT__ / 250 && result == null; tick++) {
        java.lang.Thread.sleep(250)
        edt(function () {
            var list = popups()
            for (var p = 0; p < list.size(); p++) {
                var lists = com.intellij.util.ui.UIUtil.findComponentsOfType(list.get(p).getContent(), javax.swing.JList)
                var text = ""
                for (var l = 0; l < lists.size(); l++) {
                    var model = lists.get(l).getModel()
                    for (var e = 0; e < model.getSize(); e++) text += "  list: " + describe(model.getElementAt(e)) + "\n"
                }
                list.get(p).cancel()
                result = text.length > 0 ? text : "  list: (a popup without a list)\n"
                return
            }
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
            if (editor == null) return
            var file = FileDocumentManager.getInstance().getFile(editor.getDocument())
            var now = where(file, editor.getCaretModel().getOffset())
            if (now != state.before) result = "  to: " + now + "\n"
        })
    }
    out.append(result == null ? "  to: (stayed)\n" : result)
}
edt(function () { FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vfile, 0), true) })
out.toString()
