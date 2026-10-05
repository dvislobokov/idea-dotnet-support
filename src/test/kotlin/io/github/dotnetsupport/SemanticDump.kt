package io.github.dotnetsupport

import java.io.BufferedReader
import java.io.File
import java.io.Reader

/**
 * The output of `roslyndump semantics` (format: tools/csharp-psi/roslyndump/README.md, section "semantics"): Roslyn's binding of every
 * identifier, the type of every expression and the diagnostics of the files of one compilation. The oracle of the semantic gate
 * ([CSharpSemanticGate], CSHARP_PSI_MIGRATION.md step 11, task C0).
 */
class SemanticDump(val header: Header, val files: List<FileRecord>) {
    /** `S` records. [sources]: every file the symbols can be declared in (the dumped project and the projects it references). */
    class Header(
        val input: String,
        val assembly: String,
        val languageVersion: String,
        val defines: List<String>,
        val references: List<String>,
        val sources: List<Source>,
        val generated: List<String>,
        /** The full paths of the referenced assemblies (dumps since 2026-10-05): what the gate indexes for the resolver. */
        val referencePaths: List<String> = emptyList(),
        /** `<Nullable>` of the compilation (`Enable`...): the nullable context of the warnings (D2). */
        val nullable: String? = null,
    )

    class Source(val path: String, val languageVersion: String, val defines: List<String>)

    class FileRecord(val path: String, val names: List<Name>, val expressions: List<Expression>, val diagnostics: List<Diagnostic>)

    /**
     * `N`: an identifier at [offset]. [declares] — its name declares the symbol (Roslyn's `GetDeclaredSymbol`); otherwise it refers to one.
     * [symbols]: one bound symbol, or the candidates when Roslyn bound none ([candidateReason]), or nothing.
     */
    class Name(val offset: Int, val text: String, val declares: Boolean, val symbols: List<Symbol>, val flags: Set<String>) {
        val candidateReason: String? get() = flags.firstOrNull { it.startsWith("cand=") }?.substringAfter('=')
        val isBound: Boolean get() = symbols.isNotEmpty() && candidateReason == null
        fun has(flag: String): Boolean = flag in flags
    }

    /**
     * A symbol: its kind (`Local`, `Parameter`, `NamedType.Class`, `Method.Ordinary` ...), its id (a documentation comment id, or
     * `<Kind>:<name>` for a local-like symbol), where it is declared: `path:offset` of its name in a source, `gen:<generated file>:offset`,
     * `asm:<assembly>`, `ns` for a namespace.
     */
    class Symbol(val kind: String, val id: String, val declarations: List<String>) {
        val isFromAssembly: Boolean get() = declarations.isNotEmpty() && declarations.all { it.startsWith("asm:") }
        val sourceDeclarations: List<String> get() = declarations.filter { it != "ns" && !it.startsWith("asm:") && !it.startsWith("gen:") }
    }

    /** `X`: an expression node, its type and converted type (null: none, e.g. a namespace or a method group; `?Name`: an error type). */
    class Expression(val start: Int, val end: Int, val kind: String, val type: String?, val convertedType: String?)

    class Diagnostic(val start: Int, val end: Int, val code: String, val isError: Boolean)

    companion object {
        const val FORMAT_VERSION = 1

        fun read(file: File): SemanticDump = file.bufferedReader(Charsets.UTF_8).use(::read)

        fun read(reader: Reader): SemanticDump {
            val settings = HashMap<String, String>()
            val references = ArrayList<String>()
            val referencePaths = ArrayList<String>()
            val options = HashMap<String, Pair<String, List<String>>>()
            val sources = ArrayList<Source>()
            val generated = ArrayList<String>()
            val files = ArrayList<FileRecord>()
            var path: String? = null
            var names = ArrayList<Name>()
            var expressions = ArrayList<Expression>()
            var diagnostics = ArrayList<Diagnostic>()
            fun flush() {
                path?.let { files += FileRecord(it, names, expressions, diagnostics) }
                names = ArrayList(); expressions = ArrayList(); diagnostics = ArrayList()
            }
            val lines = if (reader is BufferedReader) reader else reader.buffered()
            var lineNo = 0
            for (line in lines.lineSequence()) {
                lineNo++
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (lineNo == 1) {
                        val version = line.substringAfter("semantics ", "").trim().toIntOrNull()
                        require(version == FORMAT_VERSION) { "semantic dump format $version, expected $FORMAT_VERSION: rebuild roslyndump" }
                    }
                    continue
                }
                val f = line.split('\t')
                try {
                    when (f[0]) {
                        "S" -> when (f[1]) {
                            "reference" -> {
                                references += f[2]
                                f.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let { referencePaths += it }
                            }
                            "options" -> options[f[2]] = f[3] to defines(f.getOrElse(4) { "" })
                            "src" -> options.getValue(f[3]).let { (version, symbols) -> sources += Source(f[2], version, symbols) }
                            "generated" -> generated += f[2]
                            else -> settings[f[1]] = f.getOrElse(2) { "" }
                        }
                        "F" -> { flush(); path = f[1] }
                        "N" -> names += Name(f[1].toInt(), f[2], f[3] == "decl", symbols(f[4], f[5], f[6]), if (f[7] == "-") emptySet() else f[7].split(',').toSet())
                        "X" -> expressions += Expression(f[1].toInt(), f[2].toInt(), f[3], f[4].takeIf { it != "-" }, when (f[5]) { "=" -> f[4].takeIf { it != "-" }; "-" -> null; else -> f[5] })
                        "D" -> diagnostics += Diagnostic(f[1].toInt(), f[2].toInt(), f[3], f[4] == "error")
                        else -> error("unknown record")
                    }
                } catch (e: RuntimeException) {
                    throw IllegalArgumentException("semantic dump, line $lineNo: ${e.message}: $line", e)
                }
            }
            flush()
            val header = Header(
                settings["input"].orEmpty(), settings["assembly"].orEmpty(), settings["langversion"].orEmpty(), defines(settings["define"].orEmpty()),
                references, sources, generated, referencePaths, settings["nullable"],
            )
            return SemanticDump(header, files)
        }

        private fun defines(text: String): List<String> = text.split(';').filter { it.isNotEmpty() }

        // Candidates are separated by `|` in all three fields, the declarations of one symbol by `,`.
        private fun symbols(kinds: String, ids: String, declarations: String): List<Symbol> {
            if (kinds == "-") return emptyList()
            val k = kinds.split('|')
            val i = ids.split('|')
            val d = declarations.split('|')
            return k.indices.map { n -> Symbol(k[n], i[n], d.getOrElse(n) { "-" }.let { if (it == "-") emptyList() else it.split(',').map(::unescape) }) }
        }

        private fun unescape(place: String): String =
            if ('%' !in place) place else place.replace("%2C", ",").replace("%7C", "|").replace("%09", "\t").replace("%25", "%")
    }
}
