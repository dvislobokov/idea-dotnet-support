// Screen coordinates (for xdotool on the Xvfb screen) of the first __AT__ after __AFTER__ in __FILE__: the point relative to the IDE frame,
// which fills the virtual screen from (0, 0) — `getLocationOnScreen` of the editor is unreliable without a window manager.
importPackage(com.intellij.openapi.project);
importPackage(com.intellij.openapi.application);
importPackage(com.intellij.openapi.fileEditor);
importPackage(com.intellij.openapi.vfs);
importPackage(com.intellij.openapi.wm);
importClass(com.intellij.openapi.editor.ScrollType);
var out = "";
ApplicationManager.getApplication().invokeAndWait(function () {
    var project = ProjectManager.getInstance().getOpenProjects()[0];
    var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__");
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);
    var text = editor.getDocument().getText();
    var from = "__AFTER__" == "" ? 0 : text.indexOf("__AFTER__");
    var at = text.indexOf("__AT__", from < 0 ? 0 : from);
    if (at < 0) { out = "anchor not found"; return; }
    editor.getCaretModel().moveToOffset(at + 2);
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    editor.getScrollingModel().disableAnimation();
    var p = editor.offsetToXY(at + 2);
    var frame = WindowManager.getInstance().getFrame(project);
    var rel = javax.swing.SwingUtilities.convertPoint(editor.getContentComponent(), p.x, p.y, frame.getRootPane());
    out = "at " + rel.x + " " + (rel.y + editor.getLineHeight() / 2) + " " + (editor.getDocument().getLineNumber(at) + 1);
});
out;
