// The inlay hints of __FILE__ after it is opened and the daemon has run (__WAIT__ ms): inline and after-line-end ones with the line, the
// column and the text of the renderer; block ones (Code Vision) with the line and the text. Parameter names, types of `var`, lambda
// parameters, usages and so on, as the editor shows them.
importPackage(com.intellij.openapi.project);
importPackage(com.intellij.openapi.application);
importPackage(com.intellij.openapi.fileEditor);
importPackage(com.intellij.openapi.vfs);
var result = "";
var project = ProjectManager.getInstance().getOpenProjects()[0];
var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__");
var editor = null;
ApplicationManager.getApplication().invokeAndWait(function () { editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true); });
java.lang.Thread.sleep(__WAIT__);
ApplicationManager.getApplication().invokeAndWait(function () {
    var doc = editor.getDocument();
    var model = editor.getInlayModel();
    var out = [];
    function text(inlay) {
        var r = inlay.getRenderer();
        try { if (r.getPresentationList) { var es = r.getPresentationList().getEntries(); var parts = []; for (var q = 0; q < es.length; q++) parts.push(es[q].getText ? es[q].getText() : String(es[q])); return parts.join(""); } } catch (e) { }
        try { if (r.getText) return String(r.getText()); } catch (e2) { }
        try { var f = r.getClass().getDeclaredField("state"); f.setAccessible(true); var st = f.get(r); if (st != null && st.getEntries) { var ent = st.getEntries(); var ps = []; for (var z = 0; z < ent.size(); z++) ps.push(String(ent.get(z).getText ? ent.get(z).getText() : ent.get(z))); return ps.join(" | "); } } catch (e3) { }
        return r.getClass().getSimpleName();
    }
    var inline = model.getInlineElementsInRange(0, doc.getTextLength());
    for (var i = 0; i < inline.size(); i++) { var in1 = inline.get(i); var l = doc.getLineNumber(in1.getOffset()); out.push("inline " + (l + 1) + ":" + (in1.getOffset() - doc.getLineStartOffset(l) + 1) + " [" + text(in1) + "]"); }
    var after = model.getAfterLineEndElementsInRange(0, doc.getTextLength());
    for (var j = 0; j < after.size(); j++) { var a = after.get(j); out.push("after " + (doc.getLineNumber(a.getOffset()) + 1) + " [" + text(a) + "]"); }
    var block = model.getBlockElementsInRange(0, doc.getTextLength());
    for (var k = 0; k < block.size(); k++) { var b = block.get(k); out.push("block " + (doc.getLineNumber(b.getOffset()) + 1) + " [" + text(b) + "]"); }
    result = "inlays: " + out.length + "\n" + out.join("\n");
});
result;
