// Sets the boolean registry key __KEY__ of the sandbox IDE to __ON__ (true / false) and prints it back: `dotnet.debugger.protocol.trace`
// writes every DAP message of the next debug session to ~/idea-dotnet-logs/dotnet-debugger/protocol/.
importClass(com.intellij.openapi.util.registry.Registry)
var key = Registry.get("__KEY__")
key.setValue(__ON__)
"__KEY__ = " + key.asBoolean()
