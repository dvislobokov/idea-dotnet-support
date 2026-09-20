package io.github.dotnetsupport.endpoints

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.dotnetsupport.lang.CSharpLexer
import io.github.dotnetsupport.lang.CSharpTokenTypes

/** An HTTP endpoint found in the sources. [offset] points at the route (or at the action method when it has no template). */
data class Endpoint(val method: String, val route: String, val offset: Int, val handler: String?) {
    /** Route parameters without constraints and defaults: `{id:int}` and `{id?}` are `id`. */
    val parameters: List<String> get() = PARAMETER.findAll(route).map { it.groupValues[1] }.toList()

    companion object {
        internal val PARAMETER = Regex("""\{\*{0,2}([A-Za-z_][A-Za-z0-9_]*)[^{}]*}""")
    }
}

/**
 * Finds ASP.NET Core endpoints by tokens:
 * minimal APIs (`app.MapGet("/x", ...)`, including `MapGroup` prefixes kept in variables or chained) and
 * controller actions (`[Route]` on the class, `[HttpGet("{id}")]` on the methods, `[controller]` / `[action]` tokens).
 * There is no symbol resolution: routes built from constants or in other files than their group are seen as written.
 */
object EndpointScanner {
    private val MAP_METHODS = mapOf(
        "MapGet" to "GET", "MapPost" to "POST", "MapPut" to "PUT", "MapDelete" to "DELETE", "MapPatch" to "PATCH",
        "MapHealthChecks" to "GET", "MapMethods" to "*",
    )
    private val HTTP_ATTRIBUTES = mapOf(
        "HttpGet" to "GET", "HttpPost" to "POST", "HttpPut" to "PUT", "HttpDelete" to "DELETE", "HttpPatch" to "PATCH",
        "HttpHead" to "HEAD", "HttpOptions" to "OPTIONS",
    ).flatMap { (name, verb) -> listOf(name to verb, name + "Attribute" to verb) }.toMap()

    private class Token(val type: IElementType, val text: String, val start: Int)

    /** Cheap check before tokenizing: `.MapGet(` and friends, or an `[HttpGet]`-like attribute. */
    fun mayContainEndpoints(text: CharSequence): Boolean = text.contains(".Map") || text.contains("Http")

    fun scan(text: CharSequence): List<Endpoint> {
        if (!mayContainEndpoints(text)) return emptyList()
        val tokens = tokenize(text)
        return (minimalApis(tokens) + controllerActions(tokens)).sortedBy { it.offset }
    }

    // ---- minimal APIs ----

    private fun minimalApis(tokens: List<Token>): List<Endpoint> {
        val groups = HashMap<String, String>() // variable -> route prefix
        val result = ArrayList<Endpoint>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != CSharpTokenTypes.IDENTIFIER || tokens.getOrNull(i - 1)?.type != CSharpTokenTypes.DOT || tokens.getOrNull(i + 1)?.type != CSharpTokenTypes.LPAREN) continue
            val template = tokens.getOrNull(i + 2)?.takeIf { it.type == CSharpTokenTypes.STRING }

            if (token.text == "MapGroup" && template != null) {
                // var api = app.MapGroup("/api");   api = ...;   RouteGroupBuilder api = ...
                val start = receiverStart(tokens, i - 1)
                val assignment = tokens.getOrNull(start - 1)
                val variable = tokens.getOrNull(start - 2)
                if (assignment?.type == CSharpTokenTypes.OPERATOR && assignment.text == "=" && variable?.type == CSharpTokenTypes.IDENTIFIER) {
                    groups[variable.text] = join(receiverPrefix(tokens, i - 1, groups), stringValue(template.text))
                }
                continue
            }

            val verb = MAP_METHODS[token.text] ?: continue
            if (template == null) continue
            val route = join(receiverPrefix(tokens, i - 1, groups), stringValue(template.text))
            val verbs = if (verb == "*") explicitVerbs(tokens, i + 3).ifEmpty { listOf("*") } else listOf(verb)
            verbs.mapTo(result) { Endpoint(it, route, template.start, handlerName(tokens, i + 3)) }
        }
        return result
    }

    /** Index of the first token of the expression that ends right before the dot at [dot]: `app`, `app.MapGroup("/a")`, `builder.Services.X()`. */
    private fun receiverStart(tokens: List<Token>, dot: Int): Int {
        var i = dot - 1
        while (i >= 0) {
            when (tokens[i].type) {
                CSharpTokenTypes.RPAREN -> i = matchingOpen(tokens, i) - 1 // the name of the call
                CSharpTokenTypes.IDENTIFIER -> {}
                else -> return i + 1
            }
            if (i < 0) return 0
            // `a.b`: keep going through the dot; anything else ends the chain
            if (tokens.getOrNull(i - 1)?.type == CSharpTokenTypes.DOT) i -= 2 else return i
        }
        return 0
    }

    /** Route prefix contributed by the receiver of the call at [dot]: group variables and chained `MapGroup("...")` calls. */
    private fun receiverPrefix(tokens: List<Token>, dot: Int, groups: Map<String, String>): String {
        val last = tokens.getOrNull(dot - 1) ?: return ""
        if (last.type == CSharpTokenTypes.IDENTIFIER) {
            // `app` or `this.app`: a variable; only a plain variable can be a known group
            return if (tokens.getOrNull(dot - 2)?.type == CSharpTokenTypes.DOT) "" else groups[last.text].orEmpty()
        }
        if (last.type != CSharpTokenTypes.RPAREN) return ""
        val open = matchingOpen(tokens, dot - 1)
        val name = tokens.getOrNull(open - 1) ?: return ""
        val before = if (tokens.getOrNull(open - 2)?.type == CSharpTokenTypes.DOT) receiverPrefix(tokens, open - 2, groups) else ""
        // .WithTags("x"), .RequireAuthorization() and the like keep the prefix of what they are called on
        val segment = tokens.getOrNull(open + 1)?.takeIf { name.text == "MapGroup" && it.type == CSharpTokenTypes.STRING }?.let { stringValue(it.text) }
        return if (segment == null) before else join(before, segment)
    }

    private fun matchingOpen(tokens: List<Token>, close: Int): Int {
        var depth = 0
        for (i in close downTo 0) {
            if (tokens[i].type == CSharpTokenTypes.RPAREN) depth++
            if (tokens[i].type == CSharpTokenTypes.LPAREN && --depth == 0) return i
        }
        return 0
    }

    /** `MapMethods("/x", new[] { "GET", "HEAD" }, handler)`: the strings of the second argument. */
    private fun explicitVerbs(tokens: List<Token>, afterTemplate: Int): List<String> {
        val verbs = ArrayList<String>()
        var i = afterTemplate
        if (tokens.getOrNull(i)?.type != CSharpTokenTypes.COMMA) return verbs
        i++
        var depth = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when (token.type) {
                CSharpTokenTypes.LBRACE, CSharpTokenTypes.LBRACKET -> depth++
                CSharpTokenTypes.RBRACE, CSharpTokenTypes.RBRACKET -> if (--depth <= 0 && verbs.isNotEmpty()) return verbs
                CSharpTokenTypes.STRING -> verbs += stringValue(token.text).uppercase()
                CSharpTokenTypes.COMMA -> if (depth == 0) return verbs
                CSharpTokenTypes.LPAREN, CSharpTokenTypes.RPAREN -> return verbs
            }
            i++
        }
        return verbs
    }

    /** `MapGet("/x", GetAll)` or `MapGet("/x", Handlers.GetAll)`: the method group; null for a lambda. */
    private fun handlerName(tokens: List<Token>, afterTemplate: Int): String? {
        if (tokens.getOrNull(afterTemplate)?.type != CSharpTokenTypes.COMMA) return null
        var i = afterTemplate + 1
        var name: String? = null
        while (tokens.getOrNull(i)?.type == CSharpTokenTypes.IDENTIFIER) {
            name = tokens[i].text
            if (tokens.getOrNull(i + 1)?.type != CSharpTokenTypes.DOT) break
            i += 2
        }
        return name?.takeIf { tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.RPAREN }
    }

    // ---- controllers ----

    private class Attribute(val name: String, val template: String?, val offset: Int)

    private fun controllerActions(tokens: List<Token>): List<Endpoint> {
        val result = ArrayList<Endpoint>()
        class TypeScope(val name: String, val depth: Int, val routes: List<String>)
        val scopes = ArrayList<TypeScope>()
        var pendingType: TypeScope? = null
        var attributes = ArrayList<Attribute>()
        var depth = 0
        var parentheses = 0

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val scope = scopes.lastOrNull()
            val inTypeBody = scope != null && depth == scope.depth + 1
            when {
                token.type == CSharpTokenTypes.LPAREN -> parentheses++
                token.type == CSharpTokenTypes.RPAREN -> parentheses--
                token.type == CSharpTokenTypes.LBRACE -> {
                    pendingType?.let { scopes += it }
                    pendingType = null
                    attributes = ArrayList()
                    depth++
                }
                token.type == CSharpTokenTypes.RBRACE -> {
                    depth--
                    while (scopes.isNotEmpty() && scopes.last().depth >= depth) scopes.removeAt(scopes.lastIndex)
                    attributes = ArrayList()
                }
                token.type == CSharpTokenTypes.SEMICOLON && parentheses == 0 -> {
                    pendingType = null
                    attributes = ArrayList()
                }
                // attributes of a type or of a member; an indexer `a[i]` never starts a declaration
                token.type == CSharpTokenTypes.LBRACKET && parentheses == 0 && pendingType == null && (scope == null || inTypeBody || depth == scope.depth) &&
                    tokens.getOrNull(i - 1)?.type.let { it != CSharpTokenTypes.IDENTIFIER && it != CSharpTokenTypes.RPAREN } -> {
                    i = readAttributes(tokens, i, attributes)
                }
                token.type == CSharpTokenTypes.KEYWORD && token.text == "class" && tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.IDENTIFIER -> {
                    val name = tokens[++i].text
                    pendingType = TypeScope(name, depth, attributes.filter { it.name == "Route" || it.name == "RouteAttribute" }.mapNotNull { it.template })
                    attributes = ArrayList()
                }
                inTypeBody && parentheses == 0 && token.type == CSharpTokenTypes.IDENTIFIER && tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.LPAREN && attributes.isNotEmpty() -> {
                    val verbs = attributes.filter { it.name in HTTP_ATTRIBUTES }
                    val methodRoutes = attributes.filter { it.name == "Route" || it.name == "RouteAttribute" }.mapNotNull { it.template }
                    for (verb in verbs) {
                        val templates = if (verb.template != null) listOf(verb.template) else methodRoutes.ifEmpty { listOf("") }
                        for (template in templates) for (prefix in scope!!.routes.ifEmpty { listOf("") }) {
                            val route = if (template.startsWith("/") || template.startsWith("~/")) join("", template.removePrefix("~")) else join(prefix, template)
                            result += Endpoint(
                                HTTP_ATTRIBUTES.getValue(verb.name),
                                route.replace("[controller]", scope.name.removeSuffix("Controller"), ignoreCase = true).replace("[action]", token.text, ignoreCase = true),
                                if (verb.template != null) verb.offset else token.start,
                                handler = "${scope.name}.${token.text}",
                            )
                        }
                    }
                    attributes = ArrayList()
                }
            }
            i++
        }
        return result
    }

    /** Reads `[A, B("x")]` starting at [open]; returns the index of the closing bracket. */
    private fun readAttributes(tokens: List<Token>, open: Int, into: MutableList<Attribute>): Int {
        var nesting = 0
        var i = open
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.type == CSharpTokenTypes.LBRACKET || token.type == CSharpTokenTypes.LPAREN) nesting++
            if (token.type == CSharpTokenTypes.RBRACKET || token.type == CSharpTokenTypes.RPAREN) nesting--
            if (nesting == 0) return i
            // the name is the last identifier of `Microsoft.AspNetCore.Mvc.HttpGet`
            if (nesting == 1 && token.type == CSharpTokenTypes.IDENTIFIER && tokens.getOrNull(i + 1)?.type != CSharpTokenTypes.DOT) {
                val argument = tokens.getOrNull(i + 2)?.takeIf { tokens.getOrNull(i + 1)?.type == CSharpTokenTypes.LPAREN && it.type == CSharpTokenTypes.STRING }
                into += Attribute(token.text, argument?.let { stringValue(it.text) }, argument?.start ?: token.start)
            }
            i++
        }
        return i
    }

    // ---- helpers ----

    /** `/api` + `todos/{id}` -> `/api/todos/{id}`; the root is `/`. */
    fun join(prefix: String, template: String): String {
        val parts = (prefix.split('/') + template.split('/')).filter { it.isNotEmpty() }
        return "/" + parts.joinToString("/")
    }

    /** The content of a regular, verbatim or (as written) interpolated string literal. */
    fun stringValue(literal: String): String {
        val verbatim = literal.takeWhile { it == '@' || it == '$' }.contains('@')
        val body = literal.dropWhile { it == '@' || it == '$' }.trim('"')
        return if (verbatim) body.replace("\"\"", "\"") else body.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\\/", "/").replace("\u0000", "\\")
    }

    private fun tokenize(text: CharSequence): List<Token> {
        val tokens = ArrayList<Token>()
        val lexer = CSharpLexer()
        lexer.start(text)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in CSharpTokenTypes.COMMENTS && type != CSharpTokenTypes.PREPROCESSOR) {
                tokens += Token(type, lexer.tokenText, lexer.tokenStart)
            }
            lexer.advance()
        }
        return tokens
    }
}
