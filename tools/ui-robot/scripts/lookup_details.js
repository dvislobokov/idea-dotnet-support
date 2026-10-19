// The items of the open completion popup with what they are: the lookup string, the tail and type texts of their presentation, the
// class of the item and of its object, the file of the PSI element it stands for. __LIMIT__ items; __NAME__: only those named so ("" — all).
// For a list with the same name several times (are they one declaration seen twice, or different types?).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder()
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var lookup = LookupManager.getInstance(project).getActiveLookup()
    if (lookup == null) { out.append("no lookup"); return }
    var items = lookup.getItems()
    var shown = 0
    for (var i = 0; i < items.size() && shown < __LIMIT__; i++) {
        var item = items.get(i)
        if ("__NAME__" != "" && item.getLookupString() != "__NAME__") continue
        shown++
        var p = new LookupElementPresentation()
        item.renderElement(p)
        var psi = item.getPsiElement()
        var file = psi == null ? null : psi.getContainingFile()
        out.append(item.getLookupString() + " | tail=" + p.getTailText() + " | type=" + p.getTypeText() + " | " + item.getClass().getSimpleName() +
            " | object " + (item.getObject() == null ? "null" : item.getObject().getClass().getSimpleName()) +
            " | file " + (file == null ? "-" : file.getName()) + "\n")
    }
} }), ModalityState.any())
out.toString()
