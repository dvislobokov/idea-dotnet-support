// Completion as a person sees it: opens __FILE__, puts the caret at the end of line __LINE__ (1-based), types __TYPE__ character by
// character through the typed action (the auto-popup fires as from the keyboard), then polls the active lookup for up to __WAIT__ ms and
// prints its items (the first __LIMIT__) and the positions of __FIND__ (comma-separated names); __EXPLICIT__ = "yes" presses Ctrl+Space when
// no popup came by itself. The typed text is undone at the end (__UNDO__ = "yes").
importPackage(com.intellij.openapi.project);
importPackage(com.intellij.openapi.application);
importPackage(com.intellij.openapi.fileEditor);
importPackage(com.intellij.openapi.vfs);
importPackage(com.intellij.openapi.command);
importClass(com.intellij.openapi.editor.actionSystem.TypedAction);
importClass(com.intellij.openapi.actionSystem.DataContext);
importClass(com.intellij.openapi.editor.impl.EditorImpl);
importClass(com.intellij.codeInsight.lookup.LookupManager);
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation);
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase);
importClass(com.intellij.codeInsight.completion.CompletionType);

importClass(com.intellij.openapi.editor.ex.util.EditorUtil);
var project = ProjectManager.getInstance().getOpenProjects()[0];
var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__");
var editor = null;
var out = "";
ApplicationManager.getApplication().invokeAndWait(function () {
    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true);
    editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);
    var doc = editor.getDocument();
    editor.getCaretModel().moveToOffset(doc.getLineEndOffset(__LINE__ - 1));
    editor.getContentComponent().requestFocusInWindow();
});
var typed = "__TYPE__";
for (var i = 0; i < typed.length; i++) {
    var ch = typed.charAt(i);
    ApplicationManager.getApplication().invokeAndWait(function () {
        var ctx = EditorUtil.getEditorDataContext(editor);
        TypedAction.getInstance().actionPerformed(editor, ch, ctx);
    });
    java.lang.Thread.sleep(120);
}
function items() {
    var r = null;
    ApplicationManager.getApplication().invokeAndWait(function () {
        var lk = LookupManager.getActiveLookup(editor);
        if (lk != null) { var it = lk.getItems(); var names = []; for (var k = 0; k < it.size(); k++) var pr = LookupElementPresentation.renderElement(it.get(k)); names.push(String(it.get(k).getLookupString()) + (pr.getItemText() != null && String(pr.getItemText()) != String(it.get(k).getLookupString()) ? " [" + pr.getItemText() + "]" : "") + (pr.getTailText() != null ? " " + pr.getTailText() : "") + (pr.getTypeText() != null ? " : " + pr.getTypeText() : "")); r = names; }
    });
    return r;
}
var waited = 0, found = null;
while (waited < __WAIT__) { found = items(); if (found != null && found.length > 0) break; java.lang.Thread.sleep(200); waited += 200; }
var explicit = false;
if ((found == null || found.length == 0) && "__EXPLICIT__" == "yes") {
    explicit = true;
    ApplicationManager.getApplication().invokeAndWait(function () { new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor); });
    waited = 0;
    while (waited < __WAIT__) { found = items(); if (found != null && found.length > 0) break; java.lang.Thread.sleep(200); waited += 200; }
}
var lineText = "";
ApplicationManager.getApplication().invokeAndWait(function () { var d = editor.getDocument(); var l = __LINE__ - 1; lineText = d.getText(new com.intellij.openapi.util.TextRange(d.getLineStartOffset(l), d.getLineEndOffset(l))); });
out += "line: [" + lineText.trim() + "] popup: " + (found == null ? "none" : found.length + " items") + (explicit ? " (after Ctrl+Space)" : " (auto)") + " in " + waited + " ms\n";
if (found != null) {
    for (var m = 0; m < Math.min(__LIMIT__, found.length); m++) out += "  " + found[m] + "\n";
    var wanted = "__FIND__".split(",");
    for (var w = 0; w < wanted.length; w++) if (wanted[w] != "") out += "  position of " + wanted[w] + ": " + (found.indexOf(wanted[w]) + 1) + "\n";
}
ApplicationManager.getApplication().invokeAndWait(function () {
    var lk = LookupManager.getActiveLookup(editor); if (lk != null) lk.hideLookup(true);
    if ("__UNDO__" == "yes") WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
        var d = editor.getDocument(); var l = __LINE__ - 1; d.deleteString(d.getLineEndOffset(l) - typed.length, d.getLineEndOffset(l));
    } }));
});
out;
