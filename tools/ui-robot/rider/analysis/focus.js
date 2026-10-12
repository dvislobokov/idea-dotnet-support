var r = "@@@"
com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
  var kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
  r += "focusOwner " + kfm.getFocusOwner() + "\nactiveWindow " + (kfm.getActiveWindow() ? kfm.getActiveWindow().getClass() : null) + "\n"
  r += "appActive " + com.intellij.openapi.application.ApplicationManager.getApplication().isActive() + "\n"
} }), com.intellij.openapi.application.ModalityState.any())
r
