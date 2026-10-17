// The checks of RazorTagHelperCompletionContributor one by one at the caret of the selected editor (original file, no completion copy): the leaf
// before the caret is XML_NAME, the Razor file of the view, the descriptors in scope, the parent tag and whether the leaf is its name, the tags
// that start with the typed prefix.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiUtilBase)
importClass(com.intellij.codeInsight.completion.CompletionContributor)
importClass(com.intellij.lang.html.HTMLLanguage)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var loader = null
var contributors = CompletionContributor.forLanguage(HTMLLanguage.INSTANCE)
for (var i = 0; i < contributors.size(); i++) if (String(contributors.get(i).getClass().getName()).indexOf("RazorTagHelperCompletionContributor") >= 0) loader = contributors.get(i).getClass().getClassLoader()
var setup = new java.util.concurrent.atomic.AtomicReference(null)
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    setup.set([editor, PsiUtilBase.getPsiFileInEditor(editor, project), editor.getCaretModel().getOffset()])
} }))
var file = setup.get()[1], offset = setup.get()[2]
var out = new java.util.concurrent.atomic.AtomicReference("")
ApplicationManager.getApplication().runReadAction(new java.lang.Runnable({ run: function () {
    var position = file.findElementAt(offset - 1)
    var text = "position " + position + " type " + position.getNode().getElementType() + " in " + file + "\n"
    var editorObject = loader.loadClass("io.github.dotnetsupport.frameworks.razor.RazorTagHelperEditor").getField("INSTANCE").get(null)
    var razor = editorObject.razorFile(file)
    text += "razor file: " + razor + "\n"
    var context = razor == null ? null : editorObject.context(razor)
    text += "context: " + (context == null ? "null" : context.getTagHelpers().size()) + "\n"
    var parent = position.getParent()
    text += "parent " + parent + " (" + parent.getClass().getName() + "), firstChild " + parent.getFirstChild() + ", its next " + parent.getFirstChild().getNextSibling() + ", is position: " + (parent.getFirstChild().getNextSibling() == position) + "\n"
    if (context != null) {
        var helpers = context.getTagHelpers()
        var tags = []
        for (var k = 0; k < helpers.size(); k++) {
            var rules = helpers.get(k).getTagMatchingRules()
            for (var r = 0; r < rules.size(); r++) { var name = String(rules.get(r).getTagName()); if (name.indexOf("Cou") == 0 || name.indexOf("Play") == 0) tags.push(name) }
        }
        text += "tags: " + tags.join(", ") + "\n"
    }
    out.set(text)
} }))
out.get()
