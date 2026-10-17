package io.github.dotnetsupport.razor.lang.codegen

import io.github.dotnetsupport.csharp.lang.CSharpSyntaxFacts
import io.github.dotnetsupport.razor.lang.intermediate.BaseTypeWithModel
import io.github.dotnetsupport.razor.lang.intermediate.IntermediateToken
import io.github.dotnetsupport.razor.lang.intermediate.MethodParameter
import io.github.dotnetsupport.razor.lang.intermediate.TypeParameter
import io.github.dotnetsupport.razor.lang.syntax.SourceSpan

// `CodeWriterExtensions` (`CodeGeneration/CodeWriterExtensions.cs`): the C# constructs the node writers write, as extension functions of
// CodeWriter. The compiler's interpolated-string writes are ported as `writeParts` (each piece its own write, see CodeWriter).

fun CodeWriter.isAtBeginningOfLine(): Boolean = lastChar == '\n'

fun CodeWriter.ensureNewLine() {
    if (!isAtBeginningOfLine()) writeLine()
}

/**
 * `WritePadding`: indents to the column of [span] in the Razor file (a tab counts as one space, so the character indices match) less
 * [offset]; nothing for a node of the imports.
 */
fun CodeWriter.writePadding(offset: Int, span: SourceSpan?, context: CodeRenderingContext): CodeWriter {
    if (span == null) return this
    val documentPath = context.sourceDocument.filePath
    if (documentPath != null && !documentPath.equals(span.filePath, ignoreCase = true)) return this

    var spaceCount = 0
    val text = context.sourceDocument.text
    var i = span.absoluteIndex - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '\n' || c == '\r') break
        spaceCount++
        i--
    }

    indent(maxOf(spaceCount - offset, 0))
    return this
}

fun CodeWriter.writeVariableDeclaration(type: String, name: String, value: String?): CodeWriter {
    write(type).write(" ").write(name)
    if (!value.isNullOrEmpty()) write(" = ").write(value) else write(" = null")
    writeLine(";")
    return this
}

fun CodeWriter.writeBooleanLiteral(value: Boolean): CodeWriter = write(if (value) "true" else "false")

/** `WriteIntegerLiteral`: the compiler writes the digits from a table in groups of three; the text and the location are those of the decimal. */
fun CodeWriter.writeIntegerLiteral(value: Int): CodeWriter {
    if (value == 0) return write("0")
    if (value < 0) write("-")
    return write(if (value < 0) (-value.toLong()).toString() else value.toString())
}

fun CodeWriter.writeStartAssignment(left: String): CodeWriter = write(left).write(" = ")

/** `WriteStartAssignment(ref WriteInterpolatedStringHandler)`. */
fun CodeWriter.writeStartAssignmentParts(vararg left: Any?): CodeWriter = writeParts(*left).write(" = ")

fun CodeWriter.writeParameterSeparator(): CodeWriter = write(", ")

fun CodeWriter.writeStartNewObject(typeName: String): CodeWriter = write("new ").write(typeName).write("(")

/** `WriteStringLiteral`: verbatim (`@"…"`) for 256 to 1500 characters without `\0`, C-style otherwise; [utf8] adds `u8`. */
fun CodeWriter.writeStringLiteral(literal: String, utf8: Boolean = false): CodeWriter {
    if (literal.length in 256..1500 && literal.indexOf('\u0000') == -1) writeVerbatimStringLiteral(literal, utf8) else writeCStyleStringLiteral(literal, utf8)
    return this
}

fun CodeWriter.writeUsing(name: String): CodeWriter = writeUsing(name, endLine = true)

fun CodeWriter.writeUsing(name: String, endLine: Boolean): CodeWriter {
    write("using ")
    write(name)
    if (endLine) writeLine(";")
    return this
}

/** `WriteEnhancedLineNumberDirective`: `#line (l,c)-(l,c) offset "path"`, 1-based; an offset of zero is left out. */
fun CodeWriter.writeEnhancedLineNumberDirective(span: SourceSpan, characterOffset: Int, ensurePathBackslashes: Boolean): CodeWriter {
    write("#line (")
        .writeIntegerLiteral(span.lineIndex + 1)
        .write(",")
        .writeIntegerLiteral(span.characterIndex + 1)
        .write(")-(")
        .writeIntegerLiteral(span.lineIndex + 1 + span.lineCount)
        .write(",")
        .writeIntegerLiteral(span.endCharacterIndex + 1)
        .write(") ")

    if (characterOffset != 0) writeIntegerLiteral(characterOffset).write(" ")

    return write("\"").writeFilePath(span.filePath, ensurePathBackslashes).writeLine("\"")
}

fun CodeWriter.writeLineNumberDirective(span: SourceSpan, ensurePathBackslashes: Boolean): CodeWriter {
    if (length >= newLine.length && !isAtBeginningOfLine()) writeLine()

    return write("#line ")
        .writeIntegerLiteral(span.lineIndex + 1)
        .write(" \"")
        .writeFilePath(span.filePath, ensurePathBackslashes)
        .writeLine("\"")
}

/**
 * `WriteFilePath`: a URI-shaped path with its quotes and line breaks percent-encoded; otherwise as is, or with `/` turned into `\` when
 * [ensurePathBackslashes] (the tooling's forward-slash paths in the design-time documents, dotnet/razor#9108).
 */
fun CodeWriter.writeFilePath(filePath: String?, ensurePathBackslashes: Boolean): CodeWriter {
    // The compiler's `filePath.AsMemory()` of null is empty.
    val path = filePath ?: ""
    if (isUriShapedFilePath(path)) return writeEscapedFilePath(path)
    if (!ensurePathBackslashes) return write(path)

    var rest = path
    var forwardSlashIndex = rest.indexOf('/')
    while (forwardSlashIndex >= 0) {
        write(rest.substring(0, forwardSlashIndex))
        write("\\")
        rest = rest.substring(forwardSlashIndex + 1)
        forwardSlashIndex = rest.indexOf('/')
    }
    write(rest)
    return this
}

/** `FileUtilities.IsUriShapedFilePath`: a scheme of two or more characters without slashes, or `…:/`. */
fun isUriShapedFilePath(filePath: String): Boolean {
    val schemeEnd = filePath.indexOf(':')
    if (schemeEnd <= 1) return false
    val scheme = filePath.substring(0, schemeEnd)
    return (scheme.indexOf('/') < 0 && scheme.indexOf('\\') < 0) || filePath.startsWith(":/", schemeEnd)
}

private fun CodeWriter.writeEscapedFilePath(filePath: String): CodeWriter {
    // #line file names take no C# escapes: quotes and line breaks are percent-encoded, the rest of the URI left as it is.
    var rest = filePath
    while (true) {
        val index = rest.indexOfAny(charArrayOf('"', '\r', '\n', '\u0085', ' ', ' '))
        if (index < 0) break
        write(rest.substring(0, index))
        write(
            when (rest[index]) {
                '"' -> "%22"
                '\r' -> "%0D"
                '\n' -> "%0A"
                '\u0085' -> "%C2%85"
                ' ' -> "%E2%80%A8"
                ' ' -> "%E2%80%A9"
                else -> throw IllegalStateException()
            },
        )
        rest = rest.substring(index + 1)
    }
    return write(rest)
}

fun CodeWriter.writeStartMethodInvocation(methodName: String): CodeWriter {
    write(methodName)
    return write("(")
}

/** `WriteStartMethodInvocation(ref WriteInterpolatedStringHandler)`. */
fun CodeWriter.writeStartMethodInvocationParts(vararg methodName: Any?): CodeWriter {
    writeParts(*methodName)
    return write("(")
}

fun CodeWriter.writeEndMethodInvocation(): CodeWriter = writeEndMethodInvocation(endLine = true)

fun CodeWriter.writeEndMethodInvocation(endLine: Boolean): CodeWriter {
    write(")")
    if (endLine) writeLine(";")
    return this
}

fun CodeWriter.writeInstanceMethodInvocation(instanceName: String, methodName: String, vararg arguments: String): CodeWriter =
    writeInstanceMethodInvocation(instanceName, methodName, endLine = true, *arguments)

fun CodeWriter.writeInstanceMethodInvocation(instanceName: String, methodName: String, endLine: Boolean, vararg arguments: String): CodeWriter =
    writeStartMethodInvocationParts(instanceName, ".", methodName).writeCommaSeparatedList(arguments.asList()).writeEndMethodInvocation(endLine)

fun CodeWriter.writeStartInstanceMethodInvocation(instanceName: String, methodName: String): CodeWriter =
    writeStartMethodInvocationParts(instanceName, ".", methodName)

fun CodeWriter.writeFieldDeclaration(modifiers: List<String>, type: String, name: String, expression: String? = null): CodeWriter {
    val value = if (expression.isNullOrEmpty()) "null" else expression
    return writeModifierList(modifiers).writeLineParts(type, " ", name, " = ", value, ";")
}

private fun CodeWriter.writeModifierList(modifiers: List<String>?): CodeWriter {
    if (!modifiers.isNullOrEmpty()) for (modifier in modifiers) writeParts(modifier, " ")
    return this
}

fun CodeWriter.writeField(suppressWarnings: List<String>?, modifiers: List<String>?, type: String, name: String): CodeWriter {
    if (!suppressWarnings.isNullOrEmpty()) for (suppressWarning in suppressWarnings) writeLineParts("#pragma warning disable ", suppressWarning)

    writeModifierList(modifiers)
    writeLineParts(type, " ", name, ";")

    if (!suppressWarnings.isNullOrEmpty()) for (i in suppressWarnings.indices.reversed()) writeLineParts("#pragma warning restore ", suppressWarnings[i])
    return this
}

fun CodeWriter.writeMethodInvocation(methodName: String, vararg arguments: String): CodeWriter = writeMethodInvocation(methodName, endLine = true, *arguments)

fun CodeWriter.writeMethodInvocation(methodName: String, endLine: Boolean, vararg arguments: String): CodeWriter =
    writeStartMethodInvocation(methodName).writeCommaSeparatedList(arguments.asList()).writeEndMethodInvocation(endLine)

/** `WriteMethodInvocation(ref WriteInterpolatedStringHandler methodName, bool endLine, params ImmutableArray<string> arguments)`. */
fun CodeWriter.writeMethodInvocationParts(methodName: List<Any?>, endLine: Boolean = true, arguments: List<String> = emptyList()): CodeWriter =
    writeStartMethodInvocationParts(*methodName.toTypedArray()).writeCommaSeparatedList(arguments).writeEndMethodInvocation(endLine)

fun CodeWriter.writePropertyDeclaration(modifiers: List<String>?, type: IntermediateToken, name: String, expressionBody: String, context: CodeRenderingContext): CodeWriter {
    writePropertyDeclarationPreamble(this, modifiers, type.content, name, type.source, nameSpan = null, context)
    writeLineParts(" => ", expressionBody, ";")
    return this
}

fun CodeWriter.writeAutoPropertyDeclaration(
    modifiers: List<String>?, type: String, name: String, typeSpan: SourceSpan? = null, nameSpan: SourceSpan? = null,
    context: CodeRenderingContext? = null, privateSetter: Boolean = false, defaultValue: Boolean = false,
): CodeWriter {
    writePropertyDeclarationPreamble(this, modifiers, type, name, typeSpan, nameSpan, context)

    write(" { get;")
    if (privateSetter) write(" private")
    writeLine(" set; }")

    if (defaultValue && context != null && !context.options.suppressNullabilityEnforcement) writeLine(" = default!;")
    return this
}

private fun writePropertyDeclarationPreamble(
    writer: CodeWriter, modifiers: List<String>?, type: String, name: String, typeSpan: SourceSpan?, nameSpan: SourceSpan?, context: CodeRenderingContext?,
) {
    fun writeToken(content: String, span: SourceSpan?) {
        if (span != null) context!!.buildEnhancedLinePragma(span).use { writer.write(content) } else writer.write(content)
    }

    writer.writeModifierList(modifiers)
    writeToken(type, typeSpan)
    writer.write(" ")
    writeToken(name, nameSpan)
}

/** `WriteIdentifierEscapeIfNeeded`: `@` before an identifier that is a C# keyword. */
fun CodeWriter.writeIdentifierEscapeIfNeeded(identifier: String): CodeWriter {
    if (identifier.identifierRequiresEscaping()) write("@")
    return this
}

/** `IdentifierRequiresEscaping`: a reserved or a contextual keyword (`SyntaxFacts.GetKeywordKind` / `GetContextualKeywordKind`). */
fun String.identifierRequiresEscaping(): Boolean = CSharpSyntaxFacts.getKeywordKind(this) != null || CSharpSyntaxFacts.getContextualKeywordKind(this) != null

fun CodeWriter.buildScope(): CSharpCodeWritingScope = CSharpCodeWritingScope(this)

fun CodeWriter.buildLambda(): CSharpCodeWritingScope = writeLambdaHeader().buildScope()

fun CodeWriter.buildLambda(parameterName: String): CSharpCodeWritingScope = writeLambdaHeader(parameterName).buildScope()

fun CodeWriter.buildLambda(parameterName: IWriteableValue): CSharpCodeWritingScope = writeLambdaHeader(parameterName).buildScope()

fun CodeWriter.buildAsyncLambda(): CSharpCodeWritingScope = writeAsyncLambdaHeader().buildScope()

fun CodeWriter.buildAsyncLambda(parameterName: String): CSharpCodeWritingScope = writeAsyncLambdaHeader(parameterName).buildScope()

fun CodeWriter.buildAsyncLambda(parameterName: IWriteableValue): CSharpCodeWritingScope = writeAsyncLambdaHeader(parameterName).buildScope()

fun CodeWriter.writeLambdaHeader(): CodeWriter = write("() => ")

fun CodeWriter.writeLambdaHeader(parameterName: String): CodeWriter = writeParts("(", parameterName, ") => ")

fun CodeWriter.writeLambdaHeader(parameterName: IWriteableValue): CodeWriter {
    write("(")
    parameterName.writeTo(this)
    write(") => ")
    return this
}

fun CodeWriter.writeAsyncLambdaHeader(): CodeWriter = write("async() => ")

fun CodeWriter.writeAsyncLambdaHeader(parameterName: String): CodeWriter = writeParts("async(", parameterName, ") => ")

fun CodeWriter.writeAsyncLambdaHeader(parameterName: IWriteableValue): CodeWriter {
    write("async(")
    parameterName.writeTo(this)
    write(") => ")
    return this
}

/** `BuildNamespace`: no namespace for an empty name (a scope without braces); the name under a line pragma when it has a span. */
fun CodeWriter.buildNamespace(name: String?, span: SourceSpan?, context: CodeRenderingContext): CSharpCodeWritingScope {
    if (name.isNullOrEmpty()) return CSharpCodeWritingScope(this, writeBraces = false)

    write("namespace ")
    if (span == null) {
        writeLine(name)
    } else {
        writeLine()
        context.buildEnhancedLinePragma(span).use { writeLine(name) }
    }
    return CSharpCodeWritingScope(this)
}

/**
 * `BuildClassDeclaration`: `class Name<T,…> : Base<TModel>, I1, I2` with every token that has a source under its own line pragma, then the
 * constraints of the type parameters; `#nullable restore` / `#nullable disable` around it for a nullable context.
 */
fun CodeWriter.buildClassDeclaration(
    modifiers: List<String>?, name: String?, baseType: BaseTypeWithModel?, interfaces: List<IntermediateToken>?, typeParameters: List<TypeParameter>?,
    context: CodeRenderingContext?, useNullableContext: Boolean = false,
): CSharpCodeWritingScope {
    val writer = this

    fun writeToken(token: IntermediateToken, addLineBreak: Boolean = false) {
        val source = token.source
        if (source != null) {
            // `WriteWithPragma` writes to the context's writer, which is this one.
            val contextWriter = context!!.codeWriter
            context.buildEnhancedLinePragma(source).use { contextWriter.write(token.content) }
        } else {
            writer.write(token.content)
            if (addLineBreak) writer.writeLine()
        }
    }

    fun writeOptionalToken(token: IntermediateToken?, addLineBreak: Boolean = false) {
        if (token != null) writeToken(token, addLineBreak)
    }

    if (useNullableContext) writeLine("#nullable restore")

    writeModifierList(modifiers)
    write("class ")
    write(name ?: "")

    if (!typeParameters.isNullOrEmpty()) {
        write("<")
        for (i in typeParameters.indices) {
            writeToken(typeParameters[i].name)
            // ',' between the parameters, not after them.
            if (i < typeParameters.size - 1) write(",")
        }
        write(">")
    }

    val hasBaseType = !baseType?.baseType?.content.isNullOrDotNetWhiteSpace()
    val hasInterfaces = !interfaces.isNullOrEmpty()

    if (hasBaseType || hasInterfaces) {
        write(" : ")

        if (hasBaseType) {
            writeToken(baseType!!.baseType)
            writeOptionalToken(baseType.greaterThan)
            writeOptionalToken(baseType.modelType)
            writeOptionalToken(baseType.lessThan)
            if (hasInterfaces) writeParameterSeparator()
        }

        if (hasInterfaces) {
            writeToken(interfaces!![0])
            for (i in 1 until interfaces.size) {
                write(", ")
                writeToken(interfaces[i])
            }
        }
    }

    writeLine()

    if (!typeParameters.isNullOrEmpty()) for (typeParameter in typeParameters) writeOptionalToken(typeParameter.constraints, addLineBreak = true)

    if (useNullableContext) writeLine("#nullable disable")

    return CSharpCodeWritingScope(this)
}

fun CodeWriter.buildConstructorDeclaration(modifiers: List<String>?, typeName: String, parameters: List<MethodParameter>): CSharpCodeWritingScope {
    writeModifierList(modifiers)
        .write(typeName)
        .write("(")
        .writeCommaSeparatedList(parameters) { w, v -> w.writeModifierList(v.modifiers).writeParts(v.type, " ", v.name) }
        .writeLine(")")
    return CSharpCodeWritingScope(this)
}

fun CodeWriter.buildMethodDeclaration(modifiers: List<String>?, returnType: String?, name: String?, parameters: List<MethodParameter>): CSharpCodeWritingScope {
    writeModifierList(modifiers)
        .write(returnType ?: "")
        .write(" ")
        .write(name ?: "")
        .write("(")
        .writeCommaSeparatedList(parameters) { w, v -> w.writeModifierList(v.modifiers).writeParts(v.type, " ", v.name) }
        .writeLine(")")
    return CSharpCodeWritingScope(this)
}

fun CodeWriter.writeCommaSeparatedList(items: List<String>): CodeWriter = writeSeparatedList(", ", items)

fun <T> CodeWriter.writeCommaSeparatedList(items: List<T>, elementWriter: (CodeWriter, T) -> Unit): CodeWriter = writeSeparatedList(", ", items, elementWriter)

fun CodeWriter.writeSeparatedList(separator: String, items: List<String>): CodeWriter {
    var first = true
    for (item in items) {
        if (!first) write(separator) else first = false
        write(item)
    }
    return this
}

fun <T> CodeWriter.writeSeparatedList(separator: String, items: List<T>, elementWriter: (CodeWriter, T) -> Unit): CodeWriter {
    var first = true
    for (item in items) {
        if (!first) write(separator) else first = false
        elementWriter(this, item)
    }
    return this
}

/** `WriteVerbatimStringLiteral`: `@"…"` with `""` for a quote, written at indent 0 (its line breaks are part of the string). */
fun CodeWriter.writeVerbatimStringLiteral(literal: String, utf8: Boolean = false) {
    write("@\"")

    val oldIndent = currentIndent
    currentIndent = 0

    var rest = literal
    var index = rest.indexOf('"')
    while (index >= 0) {
        write(rest.substring(0, index))
        write("\"\"")
        rest = rest.substring(index + 1)
        index = rest.indexOf('"')
    }
    write(rest)

    write("\"")
    if (utf8) write("u8")

    currentIndent = oldIndent
}

private val C_STYLE_STRING_LITERAL_ESCAPE_CHARS = charArrayOf('\r', '\t', '"', '\'', '\\', '\u0000', '\n', ' ', ' ')

/** `WriteCStyleStringLiteral` (CodeDOM's `QuoteSnippetStringCStyle`). */
private fun CodeWriter.writeCStyleStringLiteral(literal: String, utf8: Boolean = false) {
    write("\"")

    var rest = literal
    while (true) {
        val index = rest.indexOfAny(C_STYLE_STRING_LITERAL_ESCAPE_CHARS)
        if (index < 0) break
        write(rest.substring(0, index))
        when (rest[index]) {
            '\r' -> write("\\r")
            '\t' -> write("\\t")
            '"' -> write("\\\"")
            '\'' -> write("\\'")
            '\\' -> write("\\\\")
            // As the compiler: a backslash and the NUL character itself.
            '\u0000' -> write("\\\u0000")
            '\n' -> write("\\n")
            ' ' -> write("\\u2028")
            ' ' -> write("\\u2029")
        }
        rest = rest.substring(index + 1)
    }
    write(rest)

    write("\"")
    if (utf8) write("u8")
}

/**
 * `CSharpCodeWritingScope`: `{` and one indent level on creation, `}` on [close] (a `using` block in the compiler, `use {}` here). With
 * [autoSpace] a space before `{` and a line break before `}` when the last character is not white space; the indent is restored only when
 * the body left it as it found it.
 */
class CSharpCodeWritingScope(private val writer: CodeWriter, private val autoSpace: Boolean = true, private val writeBraces: Boolean = true) : AutoCloseable {
    private val tabSize: Int = writer.tabSize
    private var startIndent: Int = -1

    init {
        writeStartScope()
    }

    override fun close() = writeEndScope()

    private fun writeStartScope() {
        tryAutoSpace(" ")
        if (writeBraces) writer.writeLine("{") else writer.writeLine()
        writer.currentIndent += tabSize
        startIndent = writer.currentIndent
    }

    private fun writeEndScope() {
        tryAutoSpace(writer.newLine)

        // Only when the scope was not modified.
        if (writer.currentIndent == startIndent) writer.currentIndent -= tabSize

        if (writeBraces) writer.writeLine("}") else writer.writeLine()
    }

    private fun tryAutoSpace(spaceCharacter: String) {
        val ch = writer.lastChar
        if (autoSpace && ch != null && !ch.isDotNetWhiteSpace()) writer.write(spaceCharacter)
    }
}

/** `char.IsWhiteSpace` of .NET: Latin-1 `\t`–`\r`, space, `\u0085`, ` `; beyond Latin-1 the space, line and paragraph separators. */
fun Char.isDotNetWhiteSpace(): Boolean {
    if (code <= 0xFF) return this == ' ' || this in '\t'..'\r' || this == '\u0085' || this == ' '
    val type = Character.getType(this).toByte()
    return type == Character.SPACE_SEPARATOR || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
}

/** `string.IsNullOrWhiteSpace` of .NET. */
fun String?.isNullOrDotNetWhiteSpace(): Boolean = this == null || all { it.isDotNetWhiteSpace() }
