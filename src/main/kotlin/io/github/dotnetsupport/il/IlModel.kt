package io.github.dotnetsupport.il

/**
 * The IL at a place of the source: the built [assembly] (its PDB next to it or embedded), the source [file] and the caret [line] (1-based).
 * [typeName] (metadata name, nested types with `+`) and [memberName] come from the declaration scanner of the plugin: the helper falls back on
 * them when the PDB says nothing about the line (no PDB, a field, a type header, an abstract member).
 */
data class IlRequest(val assembly: String, val file: String, val line: Int, val typeName: String? = null, val memberName: String? = null)

/**
 * What the helper disassembled: the bodies whose code is on the line (a method, the `MoveNext` of its async state machine, a lambda, a local
 * function), else the member or the type found by name. [assemblyModified] is the time of the assembly file (ms), to tell a stale build.
 */
data class IlAnswer(val assembly: String, val assemblyModified: Long, val pdb: String?, val bodies: List<IlBody>, val warning: String? = null)

/** One disassembled member in the ildasm format; [atCaret] for the innermost body that holds the line. */
data class IlBody(val name: String, val kind: Kind, val text: String, val atCaret: Boolean, val mapping: List<IlLineMapping>) {
    enum class Kind { METHOD, STATE_MACHINE, LAMBDA, LOCAL_FUNCTION, TYPE, FIELD }
}

/**
 * Line [textLine] (0-based) of [IlBody.text] is the instruction at IL [offset] that starts a sequence point compiled from the source range
 * [startLine]:[startColumn]–[endLine]:[endColumn] (1-based, as in the PDB). Hidden sequence points are not here.
 */
data class IlLineMapping(val textLine: Int, val offset: Int, val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int)

/** Where the IL comes from: the .NET helper in the IDE ([IlHelperSource]), a fake in tests. Blocking, not for the EDT; throws HelperException. */
interface IlSource {
    fun il(request: IlRequest): IlAnswer
}
