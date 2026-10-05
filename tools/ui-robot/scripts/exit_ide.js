// Closes the sandbox IDE without dialogs (force, no confirmation, no restart): the end of a robot session; `action Exit` asks first.
// Later on the EDT, so the robot gets its answer before the IDE goes away.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    ApplicationManager.getApplication().exit(true, true, false)
} }), ModalityState.any())
"exiting"
