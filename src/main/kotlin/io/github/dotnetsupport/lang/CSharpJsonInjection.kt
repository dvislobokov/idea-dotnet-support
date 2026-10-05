package io.github.dotnetsupport.lang

import com.intellij.json.JsonLanguage
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import io.github.dotnetsupport.csharp.lang.psi.*

/**
 * Which string literals are JSON (task 3.7 of docs/COMPLETION_GAPS.md), as Rider and Roslyn's embedded languages decide: a literal after
 * `// lang=json` (`language=json`, before the literal or the statement), an argument of a method of the solution whose parameter is
 * `[StringSyntax(StringSyntaxAttribute.Json)]`, and the JSON of the libraries everybody calls: `JsonDocument.Parse`, `JsonNode.Parse`,
 * `JsonSerializer.Deserialize`, Newtonsoft's `JObject.Parse` / `JArray.Parse` / `JToken.Parse` / `JsonConvert.DeserializeObject`.
 */
object CSharpJsonPlaces {
    private val COMMENT = Regex("""(?i)\b(lang|language)\s*=\s*json\b""")
    private val PARSE = mapOf(
        "JsonDocument" to setOf("Parse"), "JsonNode" to setOf("Parse"), "JsonObject" to setOf("Parse"), "JsonArray" to setOf("Parse"),
        "JsonSerializer" to setOf("Deserialize", "DeserializeAsync"), "JObject" to setOf("Parse"), "JArray" to setOf("Parse"), "JToken" to setOf("Parse"),
        "JsonConvert" to setOf("DeserializeObject", "PopulateObject"),
    )

    fun isJson(literal: CSharpLiteralExpression): Boolean {
        if (CSharpRegexPlaces.commentSays(literal, COMMENT)) return true
        val call = CSharpStringArguments.callOf(literal) ?: return false
        val type = CSharpStringArguments.lastName(call.receiver)
        if (type != null && call.method in PARSE[type].orEmpty()) return if (call.named != null) call.named == "json" else call.index == 0
        return CSharpRegexPlaces.syntaxParameter(literal.project, call.method, call.index, call.named, "Json")
    }
}

/** Injects the platform's JSON into the literals [CSharpJsonPlaces] finds. Registered in META-INF/dotnet-json.xml, where the JSON plugin is. */
class CSharpJsonInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(CSharpStringLiteralLeaf::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? CSharpStringLiteralLeaf ?: return
        val shape = CSharpStringLiterals.shape(host.text) ?: return
        if (shape.contentEnd <= shape.contentStart) return
        val literal = CSharpStringArguments.literalOf(host) ?: return
        if (!CSharpJsonPlaces.isJson(literal)) return
        registrar.startInjecting(JsonLanguage.INSTANCE)
        for (range in shape.lines) registrar.addPlace(null, null, host, range)
        registrar.doneInjecting()
    }
}
