// Quick Documentation of the element under __AT__ (the first occurrence after __AFTER__, or the first in the file) of __FILE__: opens the
// file, asks the documentation providers of the language (what Ctrl+Q shows, without the popup) and prints the text without HTML tags.
importPackage(com.intellij.openapi.project);
importPackage(com.intellij.openapi.application);
importPackage(com.intellij.openapi.fileEditor);
importPackage(com.intellij.openapi.vfs);
importPackage(com.intellij.psi);
importClass(com.intellij.lang.documentation.DocumentationProvider);
importClass(com.intellij.lang.LanguageDocumentation);
var result = "";
ApplicationManager.getApplication().invokeAndWait(function () {
    var project = ProjectManager.getInstance().getOpenProjects()[0];
    var vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__");
    if (vf == null) { result = "no file"; return; }
    var editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);
    var text = editor.getDocument().getText();
    var from = "__AFTER__" == "" ? 0 : text.indexOf("__AFTER__");
    var at = text.indexOf("__AT__", from < 0 ? 0 : from);
    if (at < 0) { result = "anchor not found"; return; }
    editor.getCaretModel().moveToOffset(at);
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    var element = psi.findElementAt(at);
    var ref = psi.findReferenceAt(at);
    var target = ref == null ? null : ref.resolve();
    var provider = LanguageDocumentation.INSTANCE.forLanguage(psi.getLanguage());
    var doc = null;
    if (provider != null) {
        var custom = provider.getDocumentationElementForLookupItem ? null : null;
        var el = target != null ? target : (provider.getCustomDocumentationElement ? provider.getCustomDocumentationElement(editor, psi, element, at) : null);
        if (el == null) el = element;
        doc = provider.generateDoc(el, element);
        if (doc == null) doc = provider.getQuickNavigateInfo(el, element);
    }
    result = "at " + editor.getDocument().getLineNumber(at) + 1 + " token [" + (element == null ? "?" : element.getText()) + "] provider " + (provider == null ? "none" : provider.getClass().getSimpleName()) + " target " + (target == null ? "none" : target.getClass().getSimpleName()) + "\n" +
        (doc == null ? "no documentation" : String(doc).replace(/<[^>]+>/g, " ").replace(/&nbsp;/g, " ").replace(/\s+/g, " ").trim().substring(0, 700));
});
result;
