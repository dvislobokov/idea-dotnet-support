package io.github.dotnetsupport.msbuild

/** A `<Using>` item, a `global using` the SDK generates: `global using [static] [Alias =] Namespace;`. */
data class GlobalUsing(val namespace: String, val alias: String? = null, val isStatic: Boolean = false)

/**
 * What the C# compiler of a project is given for one configuration and target framework, the part a parser and later the semantics need:
 * `#if` symbols, the language version, nullable context, global usings, the root namespace and the source files. From MsBuildHost
 * ([fromEvaluation], [Source.EVALUATED]) or, until its answer comes or when it cannot give one, read from the project file and the
 * `Directory.Build.props` above it ([CompilationOptionsReader], [Source.STATIC]).
 */
data class CompilationOptions(
    /** The project file, `/` separators. */
    val projectPath: String,
    val configuration: String,
    /** The framework these options are for: the only one, or the active one of a multi-targeted project; null when none is known. */
    val targetFramework: String?,
    /** Every framework of the project. */
    val targetFrameworks: List<String>,
    /** `DefineConstants` after the SDK has added its implicit symbols, as `csc -define:` takes them: valid identifiers, in order, once each. */
    val defineConstants: List<String>,
    /** `LangVersion` as MSBuild passes it to the compiler (`7.3`, `14.0`, `latest`, `preview`); null: none, the compiler's default. */
    val langVersion: String?,
    /** `Nullable`: `enable`, `disable`, `warnings`, `annotations`; null when not set. */
    val nullable: String?,
    val implicitUsings: Boolean,
    /** The `Using` items (the implicit ones of the SDK among them): what `GlobalUsings.g.cs` declares. */
    val usings: List<GlobalUsing>,
    val rootNamespace: String?,
    /**
     * The `Compile` items, full paths with `/` (a pattern with wildcards for a project of the old format read statically); null when not
     * known: an SDK project read statically, whose default globs [ProjectContent] applies.
     */
    val compileFiles: List<String>?,
    val source: Source,
    /** `AssemblyName`: what other assemblies' `InternalsVisibleTo` name it by; null when not known (the file name of the project then). */
    val assemblyName: String? = null,
    /** The `InternalsVisibleTo` items: the SDK writes `[assembly: InternalsVisibleTo]` of them (their `Key` dropped: a name is compared). */
    val internalsVisibleTo: List<String> = emptyList(),
    /** `ProduceReferenceAssembly`: its references compile against a reference assembly, which drops internal members without a friend. */
    val produceReferenceAssembly: Boolean? = null,
    /** `SignAssembly`: an `InternalsVisibleTo` with a public key makes a friend of a signed assembly only. */
    val signAssembly: Boolean = false,
) {
    enum class Source { EVALUATED, STATIC }

    /** The `#if` symbols, for `CSharpPreprocessorSymbols.KEY` of csharp-psi. */
    val preprocessorSymbols: Set<String> get() = LinkedHashSet(defineConstants)

    /** The language version for `CSharpLanguageVersion.parse` of csharp-psi: `default` (the compiler's) when the project gives none. */
    val languageVersion: String get() = langVersion ?: "default"

    private val compileKeys: Set<String>? by lazy { compileFiles?.filter { !isPattern(it) }?.mapTo(HashSet()) { key(it) } }
    private val compilePatterns: List<MsBuildGlob> by lazy { compileFiles.orEmpty().filter(::isPattern).map(::MsBuildGlob) }

    /** Whether [path] (full) is a `Compile` item; null when the items are not known ([compileFiles] null). */
    fun compiles(path: String): Boolean? {
        val keys = compileKeys ?: return null
        val normalized = EvaluatedFiles.normalize(path)
        return key(normalized) in keys || compilePatterns.any { it.matches(normalized) }
    }

    companion object {
        /** The properties MsBuildHost is asked for. */
        val PROPERTIES: List<String> = listOf("DefineConstants", "LangVersion", "Nullable", "ImplicitUsings", "RootNamespace", "TargetFramework", "AssemblyName", "ProduceReferenceAssembly", "SignAssembly")
        val ITEM_TYPES: List<String> = listOf("Compile", "Using", "InternalsVisibleTo")

        /**
         * Run on the evaluation before it is read: the SDK adds the symbols of the framework (`NET10_0`, `NETFRAMEWORK`, `NET48_OR_GREATER`...)
         * to `DefineConstants` in this target, not in the evaluation. It only sets properties and items; a project of the old format has no such
         * target and keeps its `DefineConstants` as written.
         */
        val TARGETS: List<String> = listOf("AddImplicitDefineConstants")

        /** The options from an answer of MsBuildHost asked with [PROPERTIES], [ITEM_TYPES] and [TARGETS]. */
        fun fromEvaluation(projectPath: String, configuration: String, result: MsBuildEvaluationResult): CompilationOptions {
            // a project of the old format has no TargetFramework: the helper names it from TargetFrameworkVersion (`v4.7.2` -> `net472`)
            val framework = result.property("TargetFramework") ?: result.targetFrameworks.singleOrNull()
            return CompilationOptions(
                projectPath = EvaluatedFiles.normalize(projectPath),
                configuration = configuration,
                targetFramework = framework,
                targetFrameworks = result.targetFrameworks,
                defineConstants = symbols(result.property("DefineConstants")),
                langVersion = result.property("LangVersion"),
                nullable = result.property("Nullable"),
                implicitUsings = isTrue(result.property("ImplicitUsings")),
                usings = result.items["Using"].orEmpty().map { usingOf(it.include, it.metadata["Alias"], it.metadata["Static"]) },
                rootNamespace = result.property("RootNamespace"),
                compileFiles = result.items["Compile"]?.map { EvaluatedFiles.normalize(it.include) },
                source = Source.EVALUATED,
                assemblyName = result.property("AssemblyName"),
                internalsVisibleTo = result.items["InternalsVisibleTo"].orEmpty().map { it.include.trim() }.filter { it.isNotEmpty() },
                produceReferenceAssembly = result.property("ProduceReferenceAssembly")?.let(::isTrue),
                signAssembly = result.property("SignAssembly")?.let(::isTrue) == true,
            )
        }

        /**
         * `DefineConstants` as the compiler reads `-define:` (Roslyn's `ParseConditionalCompilationSymbols`): separated by `;` or `,`, trimmed,
         * what is not an identifier is dropped (the compiler warns and ignores it), the first of the same name kept.
         */
        fun symbols(defineConstants: String?): List<String> =
            defineConstants.orEmpty().split(';', ',').map { it.trim() }.filter { IDENTIFIER.matches(it) }.distinct()

        internal fun usingOf(include: String, alias: String?, static: String?): GlobalUsing =
            GlobalUsing(include.trim(), alias?.trim()?.takeIf { it.isNotEmpty() }, isTrue(static))

        internal fun isTrue(value: String?): Boolean = value?.trim()?.lowercase() in setOf("true", "enable")

        private val IDENTIFIER = Regex("""^[\p{L}_][\p{L}\p{Nd}_]*$""")

        private fun isPattern(path: String): Boolean = '*' in path || '?' in path

        private fun key(path: String): String = EvaluatedFiles.normalize(path).lowercase()
    }
}
