package io.github.dotnetsupport.lang

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.dotnetsupport.appsettings.AppSettingsSchemas
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.msbuild.DotNetProjects

/**
 * Configuration keys (task 3.9 of docs/COMPLETION_GAPS.md), as Rider completes them: in `configuration["…"]`, `GetSection("…")`,
 * `GetRequiredSection("…")`, `GetValue<T>("…")`, `GetConnectionString("…")` the keys of the `appsettings*.json` of the project the file
 * belongs to, nested ones joined by `:` (`Serilog:MinimumLevel:Default`), relative to the section a call is made on
 * (`GetSection("Serilog")["MinimumLevel"]`); `GetConnectionString` offers the names under `ConnectionStrings`.
 *
 * Which receiver is a configuration: by its type where the semantics knows it (`IConfiguration`, `IConfigurationRoot`,
 * `IConfigurationSection`, `ConfigurationManager`), else by its name (`configuration`, `_config`, `builder.Configuration`…).
 */
object CSharpConfigurationKeys {
    private val TYPES = setOf("IConfiguration", "IConfigurationRoot", "IConfigurationSection", "ConfigurationManager", "IConfigurationManager", "IConfigurationBuilder")
    private val NAME = Regex("""(?i)^_?(config|configuration|cfg|section)$|Configuration$|Section$""")
    private val SECTION_METHODS = setOf("GetSection", "GetRequiredSection")
    private val VALUE_METHODS = setOf("GetValue")

    /** A key place: the section the key is relative to (`Serilog:` or empty) and whether only connection strings fit. */
    class Place(val section: String, val connectionStrings: Boolean)

    /** [semantic] false: by the names only, for the EDT (the list opening by itself), where the resolver may not read the indexes. */
    fun placeOf(literal: CSharpLiteralExpression, semantic: Boolean = true): Place? {
        CSharpStringArguments.indexedBy(literal)?.let { access ->
            val receiver = access.expression ?: return null
            val section = sectionOf(receiver, semantic) ?: return null
            return Place(section, false)
        }
        val call = CSharpStringArguments.callOf(literal) ?: return null
        if (call.index != 0 && call.named != "key" && call.named != "name") return null
        if (call.named != null && call.named != "key" && call.named != "name") return null
        return when (call.method) {
            "GetConnectionString" -> Place("ConnectionStrings:", true)
            in SECTION_METHODS, in VALUE_METHODS -> {
                val receiver = call.receiver ?: return null
                sectionOf(receiver, semantic)?.let { Place(it, false) }
            }
            else -> null
        }
    }

    /** The section [receiver] stands for (`""` for the root, `A:B:` for `GetSection("A:B")`), or null when it is no configuration. */
    private fun sectionOf(receiver: CSharpExpression, semantic: Boolean): String? {
        if (receiver is CSharpInvocationExpression) {
            val callee = receiver.expression as? CSharpMemberAccessExpression
            val method = callee?.nameElement?.identifier?.text
            if (method in SECTION_METHODS) {
                val key = (receiver.argumentList?.arguments?.firstOrNull()?.expression as? CSharpLiteralExpression)?.token?.text
                    ?.let { CSharpStringLiterals.shape(it)?.let { shape -> it.substring(shape.contentStart, shape.contentEnd) } } ?: return null
                val outer = callee?.expression?.let { sectionOf(it, semantic) } ?: ""
                return "$outer$key:"
            }
        }
        if (isConfiguration(receiver, semantic)) return ""
        return null
    }

    private fun isConfiguration(receiver: CSharpExpression, semantic: Boolean): Boolean {
        val file = receiver.containingFile as? CSharpFile
        if (file != null && semantic) {
            val type = runCatching { io.github.dotnetsupport.lang.semantic.CSharpSemanticSession(file.project).resolver(file).typeOf(receiver) }.getOrNull()
            if (type != null && type !is io.github.dotnetsupport.lang.semantic.SemanticType.Parameter) return type.name in TYPES
        }
        return CSharpStringArguments.lastName(receiver)?.let(NAME::containsMatchIn) == true
    }

    /** The keys of the `appsettings*.json` of the project of [file], each with the value it has in the first file that sets it. */
    fun keysFor(file: VirtualFile): Map<String, String> {
        val projectFile = DotNetProjects.findOwningProject(file) ?: return emptyMap()
        val directory = projectFile.parent ?: return emptyMap()
        val settings = directory.children.filter { !it.isDirectory && AppSettingsSchemas.isAppSettings(it.name) }.sortedBy { it.name.length }
        val out = LinkedHashMap<String, String>()
        for (json in settings) {
            val text = FileDocumentManager.getInstance().getCachedDocument(json)?.text ?: runCatching { String(json.contentsToByteArray(), json.charset) }.getOrNull() ?: continue
            keys(text).forEach { (key, value) -> out.putIfAbsent(key, value) }
        }
        return out
    }

    /** The keys of a JSON text, sections too, joined by `:`, with the value shown for leaves (`{…}` for a section). Broken JSON gives nothing. */
    fun keys(text: String): Map<String, String> {
        val root = runCatching { JsonParser.parseString(text.removePrefix("﻿")) }.getOrNull() as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, element: JsonElement) {
            when (element) {
                is JsonObject -> for ((name, value) in element.entrySet()) {
                    val key = prefix + name
                    out.putIfAbsent(key, display(value))
                    walk("$key:", value)
                }
                is JsonArray -> element.forEachIndexed { index, value ->
                    val key = prefix + index
                    out.putIfAbsent(key, display(value))
                    walk("$key:", value)
                }
                else -> {}
            }
        }
        walk("", root)
        return out
    }

    private fun display(value: JsonElement): String = when (value) {
        is JsonObject -> "{…}"
        is JsonArray -> "[…]"
        is JsonPrimitive -> value.asString.let { if (it.length > 40) it.take(40) + "…" else it }
        else -> "null"
    }
}

/** COMPLETION of configuration keys ([CSharpConfigurationKeys]): every key under the section of the place; the typed text matches the whole key. */
class CSharpConfigurationKeyCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val original = parameters.originalFile as? CSharpFile ?: return
        if (!CSharpFeatures.native(CSharpFeature.COMPLETION, original.project)) return
        val leaf = parameters.position
        val literal = CSharpStringArguments.literalOf(leaf) ?: return
        val place = CSharpConfigurationKeys.placeOf(literal) ?: return
        val virtualFile = original.viewProvider.virtualFile
        val typed = CSharpStringArguments.typedBefore(leaf, parameters.offset, parameters.editor.document.charsSequence) ?: return
        val keys = CSharpConfigurationKeys.keysFor(virtualFile)
        val set = result.withPrefixMatcher(typed)
        val offered = keys.filterKeys { it.startsWith(place.section) && it.length > place.section.length }
            .filterKeys { !place.connectionStrings || ':' !in it.substring(place.section.length) }
        for ((key, value) in offered) {
            val relative = key.substring(place.section.length)
            val depth = relative.count { it == ':' }
            val element = LookupElementBuilder.create(relative).withIcon(if (value == "{…}" || value == "[…]") AllIcons.Json.Object else AllIcons.Nodes.Property)
                .withTypeText(value, true)
            set.addElement(PrioritizedLookupElement.withPriority(element, -depth.toDouble()))
        }
        result.stopHere()
    }
}
