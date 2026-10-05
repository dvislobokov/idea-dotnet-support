package io.github.dotnetsupport.lang.semantic

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.lexer.CSharpDirectiveTokenType
import io.github.dotnetsupport.msbuild.CompilationModel
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * What decides whether the compiler reports a warning at a place (task D2): the nullable context (`<Nullable>` of the project and the
 * `#nullable` directives before the place), `#pragma warning disable` / `restore`, and the warnings the project turns off — `<NoWarn>` of
 * the project file and the `Directory.Build.props` above it, `<WarningLevel>0`, `dotnet_diagnostic.CSxxxx.severity = none` of an
 * `.editorconfig`. Read statically, without evaluating MSBuild: a warning is shown only where nothing of these turns it off.
 */
object CSharpWarningContext {
    /** The nullable context at a place: whether nullable annotations (`string?`) and nullable warnings are on. */
    data class Nullable(val annotations: Boolean, val warnings: Boolean)

    @Volatile private var testNullable: String? = null
    @Volatile private var testSet = false

    /** The `<Nullable>` of the gate's and the tests' compilation (`enable`, `disable`, `warnings`, `annotations`). */
    @TestOnly
    fun setNullableForTests(value: String?, set: Boolean = true) {
        testNullable = value
        testSet = set
    }

    private fun projectNullable(file: PsiFile): String? {
        if (testSet) return testNullable
        val project = CSharpSemanticEnvironment.projectOf(file) ?: return null
        return CompilationModel.getInstance(file.project).options(project).nullable
    }

    private fun parse(value: String?): Nullable = when (value?.trim()?.lowercase()) {
        "enable", "true" -> Nullable(annotations = true, warnings = true)
        "warnings" -> Nullable(annotations = false, warnings = true)
        "annotations" -> Nullable(annotations = true, warnings = false)
        else -> Nullable(annotations = false, warnings = false)
    }

    /** The nullable context at [offset] of [file]: the project's, changed by the `#nullable` directives before it. */
    fun nullableAt(file: PsiFile, offset: Int): Nullable {
        val project = parse(projectNullable(file))
        var annotations = project.annotations
        var warnings = project.warnings
        for (directive in directives(file)) {
            if (directive.offset >= offset) break
            if (directive.words.firstOrNull() != "nullable") continue
            val setting = directive.words.getOrNull(1) ?: continue
            val target = directive.words.getOrNull(2)
            // `restore`: back to the project's
            fun value(ofProject: Boolean): Boolean = when (setting) {
                "enable" -> true
                "disable" -> false
                else -> ofProject
            }
            if (target == null || target == "annotations") annotations = value(project.annotations)
            if (target == null || target == "warnings") warnings = value(project.warnings)
        }
        return Nullable(annotations, warnings)
    }

    /** Whether the warning [code] at [offset] of [file] is turned off: by `#pragma warning disable` before it or by the project. */
    fun isSuppressed(file: PsiFile, code: String, offset: Int): Boolean {
        if (code in projectSuppressed(file)) return true
        if (ALL in projectSuppressed(file)) return true
        if (code.startsWith("CS86") && NULLABLE in projectSuppressed(file)) return true
        var disabled = false
        for (directive in directives(file)) {
            if (directive.offset >= offset) break
            val words = directive.words
            if (words.size < 3 || words[0] != "pragma" || words[1] != "warning") continue
            val codes = words.drop(3).filter { it != "," }.map(::normalize)
            if (codes.isNotEmpty() && code !in codes && !(code.startsWith("CS86") && NULLABLE in codes)) continue
            disabled = words[2] == "disable"
        }
        return disabled
    }

    /** A directive: where its `#` is and the tokens after it (`nullable`, `enable`; `pragma`, `warning`, `disable`, `CS0162`). */
    private class Directive(val offset: Int, val words: List<String>)

    /** The directives of [file] in order: tokens of the lexer's directive mode, a `#` starts one (disabled `#if` text has none). */
    private fun directives(file: PsiFile): List<Directive> = com.intellij.psi.util.CachedValuesManager.getCachedValue(file) {
        com.intellij.psi.util.CachedValueProvider.Result.create(scan(file), file)
    }

    private fun scan(file: PsiFile): List<Directive> {
        if (!file.textContains('#')) return emptyList()
        val found = ArrayList<Directive>()
        var words: MutableList<String>? = null
        var start = 0
        fun flush() {
            words?.let { found += Directive(start, it) }
            words = null
        }
        PsiTreeUtil.processElements(file) { e ->
            if (e.firstChild == null) {
                val type = e.elementType
                if (type is CSharpDirectiveTokenType) {
                    if (type.roslynKind === SyntaxKind.HashToken) {
                        flush()
                        words = ArrayList()
                        start = e.textRange.startOffset
                    } else if (type.roslynKind !== SyntaxKind.EndOfDirectiveToken && e.textLength > 0) words?.add(e.text)
                } else if (e.textLength > 0 && e !is com.intellij.psi.PsiWhiteSpace && e !is com.intellij.psi.PsiComment) flush()
            }
            true
        }
        flush()
        return found
    }

    private class Cached(val stamps: List<Long>, val codes: Set<String>)

    private val cache = ConcurrentHashMap<VirtualFile, Cached>()

    /** The codes the project of [file] turns off ([ALL]: every warning, [NULLABLE]: the nullable ones). */
    private fun projectSuppressed(file: PsiFile): Set<String> {
        if (testSet) return emptySet()
        val project = CSharpSemanticEnvironment.projectOf(file) ?: return emptySet()
        val sources = ArrayList<VirtualFile>()
        sources += project
        var dir = project.parent
        while (dir != null) {
            dir.findChild("Directory.Build.props")?.let(sources::add)
            dir.findChild(".editorconfig")?.let(sources::add)
            dir.findChild(".globalconfig")?.let(sources::add)
            dir = dir.parent
        }
        val stamps = sources.map { it.modificationStamp }
        cache[project]?.takeIf { it.stamps == stamps }?.let { return it.codes }
        val codes = HashSet<String>()
        for (source in sources) {
            val text = try { VfsUtilCore.loadText(source) } catch (_: Exception) { continue }
            if (source.name.endsWith("config")) {
                for (m in SEVERITY.findAll(text)) if (m.groupValues[2].lowercase() in setOf("none", "silent", "suggestion")) codes += normalize(m.groupValues[1])
                continue
            }
            for (m in NO_WARN.findAll(text)) m.groupValues[1].split(';', ',', ' ', '\n', '\r', '\t').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$(") }.mapTo(codes, ::normalize)
            if (WARNING_LEVEL_0.containsMatchIn(text)) codes += ALL
        }
        cache[project] = Cached(stamps, codes)
        return codes
    }

    /** `CS0168`, `0168`, `168` → `CS0168`; `nullable` stays itself. */
    private fun normalize(code: String): String {
        val text = code.trim()
        if (text.equals("nullable", ignoreCase = true)) return NULLABLE
        val digits = text.removePrefix("CS").removePrefix("cs")
        return if (digits.isNotEmpty() && digits.all(Char::isDigit)) "CS" + digits.padStart(4, '0') else text.uppercase()
    }

    private const val ALL = "*"
    private const val NULLABLE = "nullable"
    private val NO_WARN = Regex("<NoWarn[^>]*>([^<]*)</NoWarn>", RegexOption.IGNORE_CASE)
    private val WARNING_LEVEL_0 = Regex("<WarningLevel[^>]*>\\s*0\\s*</WarningLevel>", RegexOption.IGNORE_CASE)
    private val SEVERITY = Regex("dotnet_diagnostic\\.(CS\\d+)\\.severity\\s*=\\s*(\\w+)", RegexOption.IGNORE_CASE)
}
