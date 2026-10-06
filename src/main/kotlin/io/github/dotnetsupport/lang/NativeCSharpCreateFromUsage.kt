package io.github.dotnetsupport.lang

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.SyntaxKind
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.lang.semantic.CSharpNameResolver
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpTypeDisplay
import io.github.dotnetsupport.lang.semantic.SemanticType

/** What a quick fix of [CSharpCreateFromUsage] creates; [word] as Rider names it: "Create local variable 'x'". */
enum class CSharpCreateKind(val word: String) {
    CLASS("class"), INTERFACE("interface"), STRUCT("struct"), ENUM("enum"), RECORD("record"),
    FIELD("field"), PROPERTY("property"), METHOD("method"), LOCAL("local variable"), PARAMETER("parameter");

    val isType: Boolean get() = this == CLASS || this == INTERFACE || this == STRUCT || this == ENUM || this == RECORD
}

/**
 * Rider's «Create …» quick fixes on a name that does not resolve (DEV_JOURNEY 4.2, 0.1.100): Alt+Enter on CS0246 / CS0103 of a type offers
 * the types (`new Foo()` a class, a record, a struct; `Foo.Bar` a class or an enum with `Bar`; `: IFoo` an interface), on CS0103 of a value a
 * local variable, a parameter, a field and a property, on a call a method, on CS1061 / CS0117 (`x.Missing`, `Type.Missing`) a member of
 * that type of the solution. A type goes to a new file next to this one, in its namespace (as Rider), named after it; the types of members
 * and parameters come from the usage: the other side of an assignment, the declared type of the variable, the arguments of the call.
 *
 * [kinds] decides by the syntax only (the annotator asks it for every such error), the rest is done when the fix is chosen.
 */
object CSharpCreateFromUsage {
    val CODES = setOf("CS0246", "CS0103", "CS0117", "CS1061")

    /** The simple name whose identifier starts at [offset]. */
    fun nameAt(file: CSharpFile, offset: Int): CSharpIdentifierName? {
        val leaf = file.findElementAt(offset) ?: return null
        val name = leaf.parent as? CSharpIdentifierName ?: return null
        return name.takeIf { it.identifier === leaf }
    }

    fun kinds(name: CSharpIdentifierName, code: String): List<CSharpCreateKind> {
        val text = name.identifier?.text ?: return emptyList()
        val parent = name.parent
        return when (code) {
            "CS0246" -> {
                val base = parent as? CSharpBaseType
                when {
                    base != null -> {
                        val list = base.parent as? CSharpBaseList
                        val owner = list?.parent as? CSharpTypeDeclaration
                        val first = list?.types?.firstOrNull() === base
                        if (first && owner != null && owner.keyword?.text in setOf("class", "record")) interfaceFirst(text, listOf(CSharpCreateKind.CLASS, CSharpCreateKind.INTERFACE))
                        else listOf(CSharpCreateKind.INTERFACE)
                    }
                    parent is CSharpObjectCreationExpression && parent.type === name -> listOf(CSharpCreateKind.CLASS, CSharpCreateKind.RECORD, CSharpCreateKind.STRUCT)
                    else -> interfaceFirst(text, listOf(CSharpCreateKind.CLASS, CSharpCreateKind.INTERFACE, CSharpCreateKind.STRUCT, CSharpCreateKind.ENUM, CSharpCreateKind.RECORD))
                }
            }
            "CS0103" -> when {
                parent is CSharpMemberAccessExpression && parent.expression === name ->
                    if (text.first().isUpperCase()) listOf(CSharpCreateKind.CLASS, CSharpCreateKind.ENUM) else values(name, text)
                parent is CSharpInvocationExpression && parent.expression === name -> if (enclosingType(name) != null) listOf(CSharpCreateKind.METHOD) else emptyList()
                else -> values(name, text)
            }
            "CS0117", "CS1061" -> {
                val access = parent as? CSharpMemberAccessExpression ?: return emptyList()
                if (access.nameElement !== name) return emptyList()
                val call = access.parent as? CSharpInvocationExpression
                if (call != null && call.expression === access) listOf(CSharpCreateKind.METHOD)
                else if (text.first().isUpperCase()) listOf(CSharpCreateKind.PROPERTY, CSharpCreateKind.FIELD)
                else listOf(CSharpCreateKind.FIELD, CSharpCreateKind.PROPERTY)
            }
            else -> emptyList()
        }
    }

    private fun interfaceFirst(text: String, kinds: List<CSharpCreateKind>): List<CSharpCreateKind> =
        if (looksLikeInterface(text)) listOf(CSharpCreateKind.INTERFACE) + kinds.filter { it != CSharpCreateKind.INTERFACE } else kinds

    private fun looksLikeInterface(text: String) = text.length > 1 && text[0] == 'I' && text[1].isUpperCase()

    /** A name in an expression: a local and a parameter inside a member, a field and a property inside a type; the likely one first. */
    private fun values(name: CSharpIdentifierName, text: String): List<CSharpCreateKind> {
        val all = ArrayList<CSharpCreateKind>()
        if (statementOf(name) != null) all += CSharpCreateKind.LOCAL
        if (parameterListOf(name) != null) all += CSharpCreateKind.PARAMETER
        if (enclosingType(name) != null) { all += CSharpCreateKind.FIELD; all += CSharpCreateKind.PROPERTY }
        val order = when {
            text.startsWith("_") -> listOf(CSharpCreateKind.FIELD, CSharpCreateKind.PROPERTY, CSharpCreateKind.LOCAL, CSharpCreateKind.PARAMETER)
            text.first().isUpperCase() -> listOf(CSharpCreateKind.PROPERTY, CSharpCreateKind.FIELD, CSharpCreateKind.LOCAL, CSharpCreateKind.PARAMETER)
            else -> listOf(CSharpCreateKind.LOCAL, CSharpCreateKind.PARAMETER, CSharpCreateKind.FIELD, CSharpCreateKind.PROPERTY)
        }
        return order.filter { it in all }
    }

    // ---- where things are

    internal fun enclosingType(at: PsiElement): CSharpTypeDeclaration? =
        PsiTreeUtil.getParentOfType(at, CSharpTypeDeclaration::class.java)?.takeIf { it.openBraceToken != null && it.closeBraceToken != null }

    /** The member of [type] that [at] is in. */
    private fun memberOf(at: PsiElement, type: CSharpTypeDeclaration): CSharpMemberDeclaration? {
        var current: PsiElement? = at
        while (current != null && current.parent !== type) current = current.parent
        return current as? CSharpMemberDeclaration
    }

    /** The statement of a block (or a top-level one) that [at] is in. */
    private fun statementOf(at: PsiElement): CSharpStatement? {
        var current: PsiElement? = at
        while (current != null && current !is CSharpFile) {
            if (current is CSharpStatement && current !is CSharpBlock && (current.parent is CSharpBlock || current.parent is CSharpGlobalStatement)) return current
            if (current is CSharpMemberDeclaration && current !is CSharpGlobalStatement) return null
            current = current.parent
        }
        return null
    }

    private fun parameterListOf(at: PsiElement): CSharpParameterList? {
        val owner = PsiTreeUtil.getParentOfType(at, CSharpBaseMethodDeclaration::class.java, CSharpLocalFunctionStatement::class.java) ?: return null
        val list = (owner as? CSharpBaseMethodDeclaration)?.parameterList ?: (owner as? CSharpLocalFunctionStatement)?.parameterList
        return list?.takeIf { it.closeParenToken != null && !PsiTreeUtil.isAncestor(it, at, false) }
    }

    private fun isStatic(at: PsiElement): Boolean {
        val type = enclosingType(at) ?: return false
        val member = memberOf(at, type) ?: return false
        return member.modifiers.any { it.text == "static" } || type.modifiers.any { it.text == "static" }
    }

    // ---- types from the usage

    private fun display(type: SemanticType?): String? = type?.let { CSharpTypeDisplay.display(it, qualified = false) }?.takeIf { it.isNotEmpty() }

    private fun typeOf(resolver: CSharpNameResolver, expression: CSharpExpression?): String? {
        if (expression == null) return null
        if (expression.node.elementType == SyntaxKind.NullLiteralExpression || expression.node.elementType == SyntaxKind.DefaultLiteralExpression) return null
        return runCatching { display(resolver.typeOf(expression)) }.getOrNull()
    }

    /** The type the place of [expression] wants: the other side of an assignment, the declared type of a variable, `bool` of a condition. */
    internal fun expectedType(resolver: CSharpNameResolver, expression: CSharpExpression): String? {
        val parent = expression.parent
        return when {
            parent is CSharpAssignmentExpression && parent.left === expression -> typeOf(resolver, parent.right)
            parent is CSharpAssignmentExpression && parent.right === expression -> typeOf(resolver, parent.left)
            parent is CSharpEqualsValueClause -> ((parent.parent as? CSharpVariableDeclarator)?.parent as? CSharpVariableDeclaration)?.type?.text?.takeIf { it != "var" }
            parent is CSharpReturnStatement -> returnTypeAt(parent)
            parent is CSharpIfStatement && parent.condition === expression -> "bool"
            parent is CSharpWhileStatement && parent.condition === expression -> "bool"
            parent is CSharpConditionalExpression && parent.condition === expression -> "bool"
            parent is CSharpPrefixUnaryExpression && parent.operatorToken?.text == "!" -> "bool"
            parent is CSharpBinaryExpression && parent.operatorToken?.text.let { it == "&&" || it == "||" } -> "bool"
            parent is CSharpBinaryExpression && parent.operatorToken?.text.let { it == "+" || it == "-" || it == "*" || it == "/" || it == "<" || it == ">" || it == "==" || it == "!=" } ->
                typeOf(resolver, if (parent.left === expression) parent.right else parent.left)
            else -> null
        }
    }

    /** The return type of the method [at] is in; `T` of `Task<T>` of an async one. */
    private fun returnTypeAt(at: PsiElement): String? {
        val method = PsiTreeUtil.getParentOfType(at, CSharpMethodDeclaration::class.java, CSharpLocalFunctionStatement::class.java) ?: return null
        val modifiers = (method as? CSharpMethodDeclaration)?.modifiers ?: (method as CSharpLocalFunctionStatement).modifiers
        val type = ((method as? CSharpMethodDeclaration)?.returnType ?: (method as? CSharpLocalFunctionStatement)?.returnType)?.text ?: return null
        if (modifiers.none { it.text == "async" }) return type.takeIf { it != "void" }
        return Regex("""^(?:System\.Threading\.Tasks\.)?(?:Task|ValueTask)<(.+)>$""").find(type)?.groupValues?.get(1)
    }

    /** What a method created for [call] returns: `void` as a statement, `Task` / `Task<T>` when awaited, else what the place wants. */
    private fun returnTypeOf(resolver: CSharpNameResolver, call: CSharpInvocationExpression): String {
        val parent = call.parent
        if (parent is CSharpExpressionStatement) return "void"
        if (parent is CSharpAwaitExpression) return expectedType(resolver, parent)?.let { "Task<$it>" } ?: if (parent.parent is CSharpExpressionStatement) "Task" else "Task<object>"
        return expectedType(resolver, call) ?: "object"
    }

    /** `(int id, string name)` of the arguments of a call: their types, their names when they are names. */
    private fun parametersOf(resolver: CSharpNameResolver, arguments: List<CSharpArgument>): List<String> {
        val taken = HashSet<String>()
        return arguments.map { argument ->
            val expression = argument.expression
            val type = typeOf(resolver, expression) ?: "object"
            val written = argument.nameColon?.nameElement?.identifier?.text ?: when (expression) {
                is CSharpIdentifierName -> expression.identifier?.text?.trimStart('_')
                is CSharpMemberAccessExpression -> expression.nameElement?.identifier?.text
                else -> null
            }
            val base = (written ?: CSharpVariableNames.forType(type).firstOrNull() ?: "arg").let { n -> n.replaceFirstChar { it.lowercase() } }.ifEmpty { "arg" }
            val name = CSharpVariableNames.unique(if (base.first().isLetter() || base.first() == '_' || base.first() == '@') base else "arg", taken)
            taken += name
            listOfNotNull(argument.refKindKeyword?.text, type, name).joinToString(" ")
        }
    }

    // ---- what is written

    /** The text of the type, at 4-space levels. */
    internal fun typeText(kind: CSharpCreateKind, name: String, usage: CSharpIdentifierName, resolver: CSharpNameResolver): String {
        val creation = (usage.parent as? CSharpObjectCreationExpression)?.takeIf { it.type === usage }
        val arguments = creation?.argumentList?.arguments.orEmpty()
        val parameters = if (arguments.isEmpty()) emptyList() else parametersOf(resolver, arguments)
        return when (kind) {
            CSharpCreateKind.RECORD -> if (parameters.isEmpty()) "public record $name;" else
                "public record $name(" + parameters.joinToString(", ") { p -> p.substringBeforeLast(' ') + " " + p.substringAfterLast(' ').replaceFirstChar { it.uppercase() } } + ");"
            CSharpCreateKind.ENUM -> {
                val member = (usage.parent as? CSharpMemberAccessExpression)?.takeIf { it.expression === usage }?.nameElement?.identifier?.text
                "public enum $name\n{\n" + (member?.let { "    $it\n" } ?: "") + "}"
            }
            CSharpCreateKind.CLASS, CSharpCreateKind.STRUCT -> {
                val keyword = if (kind == CSharpCreateKind.STRUCT) "struct" else "class"
                // `Foo.Bar` of a class: a static class, as Rider makes it for a static usage
                val static = kind == CSharpCreateKind.CLASS && (usage.parent as? CSharpMemberAccessExpression)?.expression === usage
                if (parameters.isEmpty()) "public ${if (static) "static " else ""}$keyword $name\n{\n}"
                else "public $keyword $name\n{\n    public $name(${parameters.joinToString(", ")})\n    {\n    }\n}"
            }
            else -> "public interface $name\n{\n}"
        }
    }

    /** Where a member of [kind] goes: the type and whether that is the type of the usage. */
    internal fun targetType(kind: CSharpCreateKind, usage: CSharpIdentifierName, resolver: CSharpNameResolver): Pair<CSharpTypeDeclaration, Boolean>? {
        val access = (usage.parent as? CSharpMemberAccessExpression)?.takeIf { it.nameElement === usage }
        if (access == null) return enclosingType(usage)?.let { it to true }
        val qualifier = access.expression?.let { runCatching { resolver.qualifier(it) }.getOrNull() } ?: return null
        val type = when (qualifier) {
            is CSharpNameResolver.Qualifier.Type -> qualifier.type
            is CSharpNameResolver.Qualifier.Value -> qualifier.type
            is CSharpNameResolver.Qualifier.ValueOrType -> qualifier.value
            else -> null
        } as? SemanticType.Source ?: return null
        val declaration = type.info.parts.mapNotNull { it.element() as? CSharpTypeDeclaration }.firstOrNull { it.containingFile?.virtualFile?.isWritable == true } ?: return null
        if (declaration.openBraceToken == null || declaration.closeBraceToken == null) return null
        return declaration to (declaration == enclosingType(usage))
    }

    private fun staticAccess(usage: CSharpIdentifierName, resolver: CSharpNameResolver): Boolean {
        val access = (usage.parent as? CSharpMemberAccessExpression)?.takeIf { it.nameElement === usage } ?: return isStatic(usage)
        return access.expression?.let { runCatching { resolver.qualifier(it) }.getOrNull() } is CSharpNameResolver.Qualifier.Type
    }

    /** The text of a field, a property or a method for [usage] in [target], at 4-space levels. */
    internal fun memberText(kind: CSharpCreateKind, name: String, usage: CSharpIdentifierName, target: CSharpTypeDeclaration, same: Boolean, resolver: CSharpNameResolver): String {
        val face = target.keyword?.text == "interface"
        val static = if (staticAccess(usage, resolver) && !face) "static " else ""
        val access = (usage.parent as? CSharpMemberAccessExpression)?.takeIf { it.nameElement === usage }
        val expression: CSharpExpression = access ?: usage
        return when (kind) {
            CSharpCreateKind.METHOD -> {
                val call = expression.parent as CSharpInvocationExpression
                val returns = returnTypeOf(resolver, call)
                val parameters = parametersOf(resolver, call.argumentList?.arguments.orEmpty()).joinToString(", ")
                if (face) "$returns $name($parameters);"
                else "${if (same) "private" else "public"} $static$returns $name($parameters)\n{\n    throw new NotImplementedException();\n}"
            }
            CSharpCreateKind.PROPERTY -> {
                val type = expectedType(resolver, expression) ?: "object"
                if (face) "$type $name { get; set; }" else "public $static$type $name { get; set; }"
            }
            else -> {
                val type = expectedType(resolver, expression) ?: "object"
                "${if (same) "private" else "public"} $static$type $name;"
            }
        }
    }

    // ---- the edits

    /** Inserts [member] (4-space levels) into [type] of [document]: a field after the fields (or first), the rest after [after] or last. */
    internal fun insertMember(document: Document, type: CSharpTypeDeclaration, member: String, field: Boolean, after: CSharpMemberDeclaration?, unit: String, property: Boolean = false): Int {
        val text = document.charsSequence
        val open = type.openBraceToken!!.textRange.endOffset
        val close = type.closeBraceToken!!.textRange.startOffset
        // the indent of the line the declaration starts on
        val lineStart = text.lastIndexOf('\n', type.textRange.startOffset - 1) + 1
        val typeIndent = text.subSequence(lineStart, text.length).takeWhile { it == ' ' || it == '\t' }.toString()
        val indented = NativeCSharpGenerateEdits.reindent(member, typeIndent + unit, unit)
        if (text.subSequence(open, close).isBlank()) {
            // `{ }` or an empty body on lines of its own: the member alone inside
            val insertion = "\n" + indented + "\n" + typeIndent
            document.replaceString(open, close, insertion)
            return open + 1 + typeIndent.length + unit.length
        }
        val fields = type.members.filter { it is CSharpFieldDeclaration }
        val properties = type.members.filter { it is CSharpPropertyDeclaration }
        val anchor: Int
        val insertion: String
        // the page of the server: a member of its own kind after the last of that kind, or everything at the end of the type
        val atEnd = CSharpGenerationOptions.atEnd
        when {
            atEnd && type.members.isNotEmpty() -> { anchor = type.members.last().textRange.endOffset; insertion = "\n\n" + indented }
            field && fields.isNotEmpty() -> { anchor = fields.last().textRange.endOffset; insertion = "\n" + indented }
            field -> { anchor = open; insertion = "\n" + indented + "\n" }
            property && properties.isNotEmpty() -> { anchor = properties.last().textRange.endOffset; insertion = "\n\n" + indented }
            after != null -> { anchor = after.textRange.endOffset; insertion = "\n\n" + indented }
            else -> {
                val last = type.members.lastOrNull()
                anchor = last?.textRange?.endOffset ?: open
                insertion = "\n\n" + indented
            }
        }
        document.insertString(anchor, insertion)
        return anchor + insertion.indexOf(indented) + typeIndent.length + unit.length
    }

    /** `using System;` for `NotImplementedException` when the file does not see it. */
    internal fun ensureSystem(document: Document, file: CSharpFile) {
        val text = document.charsSequence
        if (CSharpUsings.isVisible("System", text) || NativeCSharpGenerateEdits.globallyImported(file, "System")) return
        CSharpUsings.insertion(text, "System")?.let { document.insertString(it.offset, it.text) }
    }

    /** The text of a new file with [type] in the namespace of [file] (file-scoped or a block, as that file has it). */
    internal fun newFileText(file: CSharpFile, type: String, unit: String, at: PsiElement): String {
        val namespace = PsiTreeUtil.getParentOfType(at, CSharpBaseNamespaceDeclaration::class.java)
            ?: file.compilationUnit?.members?.filterIsInstance<CSharpFileScopedNamespaceDeclaration>()?.firstOrNull()
        val name = namespace?.nameElement?.text?.filterNot { it.isWhitespace() }
        return when {
            name == null -> "$type\n"
            namespace is CSharpFileScopedNamespaceDeclaration -> "namespace $name;\n\n$type\n"
            else -> "namespace $name\n{\n" + NativeCSharpGenerateEdits.reindent(type, unit, unit) + "\n}\n"
        }
    }

    fun create(kind: CSharpCreateKind, project: Project, editor: Editor?, file: CSharpFile, usage: CSharpIdentifierName) {
        val name = usage.identifier?.text ?: return
        val resolver = CSharpSemanticSession(project).resolver(file)
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        val unit = NativeCSharpContextEdits.unit(file)
        when {
            kind.isType -> createType(kind, project, editor, file, usage, name, resolver, unit)
            kind == CSharpCreateKind.LOCAL -> {
                val statement = statementOf(usage) ?: return
                val type = expectedType(resolver, usage) ?: "object"
                WriteCommandAction.runWriteCommandAction(project, "Create Local Variable", null, {
                    val assignment = usage.parent as? CSharpAssignmentExpression
                    if (assignment != null && assignment.left === usage && assignment.parent === statement && assignment.operatorToken?.text == "=") {
                        document.insertString(usage.textRange.startOffset, "var ")
                    } else {
                        val start = statement.textRange.startOffset
                        val indent = NativeCSharpUsingEdits.indentOf(document.charsSequence, start).orEmpty()
                        document.insertString(start, "$type $name = default;\n$indent")
                    }
                    documents.commitDocument(document)
                }, file)
            }
            kind == CSharpCreateKind.PARAMETER -> {
                val list = parameterListOf(usage) ?: return
                val type = expectedType(resolver, usage) ?: "object"
                val parameters = list.parameters
                // before the first optional one: a required parameter cannot follow it
                val optional = parameters.firstOrNull { p -> p.default != null || p.modifiers.any { it.text == "params" } }
                WriteCommandAction.runWriteCommandAction(project, "Create Parameter", null, {
                    if (optional != null) document.insertString(optional.textRange.startOffset, "$type $name, ")
                    else document.insertString(list.closeParenToken!!.textRange.startOffset, (if (parameters.isEmpty()) "" else ", ") + "$type $name")
                    documents.commitDocument(document)
                }, file)
            }
            else -> {
                val (target, same) = targetType(kind, usage, resolver) ?: return
                val text = memberText(kind, name, usage, target, same, resolver)
                val targetFile = target.containingFile as? CSharpFile ?: return
                val targetDocument = documents.getDocument(targetFile) ?: return
                val after = if (same) memberOf(usage, target) else null
                var caret = -1
                WriteCommandAction.runWriteCommandAction(project, "Create ${kind.word.replaceFirstChar { it.uppercase() }}", null, {
                    caret = insertMember(targetDocument, target, text, kind == CSharpCreateKind.FIELD, after, NativeCSharpContextEdits.unit(targetFile), property = kind == CSharpCreateKind.PROPERTY)
                    if (text.contains("NotImplementedException")) {
                        val before = targetDocument.textLength
                        documents.commitDocument(targetDocument)
                        ensureSystem(targetDocument, targetFile)
                        caret += targetDocument.textLength - before
                    }
                    documents.commitDocument(targetDocument)
                }, file, targetFile)
                if (caret >= 0) navigate(project, editor, targetFile, caret, same)
            }
        }
    }

    private fun createType(kind: CSharpCreateKind, project: Project, editor: Editor?, file: CSharpFile, usage: CSharpIdentifierName, name: String, resolver: CSharpNameResolver, unit: String) {
        val text = typeText(kind, name, usage, resolver)
        val directory = file.originalFile.containingDirectory
        val fileName = "$name.cs"
        val documents = PsiDocumentManager.getInstance(project)
        if (directory != null && directory.findFile(fileName) == null && directory.isWritable) {
            var created: PsiFile? = null
            WriteCommandAction.runWriteCommandAction(project, "Create ${kind.word.replaceFirstChar { it.uppercase() }}", null, {
                created = directory.createFile(fileName)
                val document = created?.let(documents::getDocument) ?: return@runWriteCommandAction
                document.setText(newFileText(file, text, unit, usage))
                documents.commitDocument(document)
            }, file)
            val createdFile = created ?: return
            val content = documents.getDocument(createdFile)?.text ?: return
            // the caret inside the body, as after New | Class
            val body = content.indexOf("{\n", content.indexOf(" $name")).takeIf { it >= 0 }?.let { it + 2 } ?: content.length
            navigate(project, editor, createdFile, body, same = false)
            return
        }
        // a file of that name is there already: the type goes after the type of the usage in this file
        val document = documents.getDocument(file) ?: return
        val top = PsiTreeUtil.getParentOfType(usage, CSharpBaseTypeDeclaration::class.java)?.let { t -> generateSequence(t as PsiElement) { it.parent }.filterIsInstance<CSharpBaseTypeDeclaration>().last() }
        WriteCommandAction.runWriteCommandAction(project, "Create ${kind.word.replaceFirstChar { it.uppercase() }}", null, {
            val anchor = top?.textRange?.endOffset ?: document.textLength
            val indent = top?.let { NativeCSharpUsingEdits.indentOf(document.charsSequence, it.textRange.startOffset) }.orEmpty()
            document.insertString(anchor, (if (top == null && !document.charsSequence.endsWith("\n")) "\n" else "") + "\n" + (if (top == null) "" else "\n") +
                NativeCSharpGenerateEdits.reindent(text, indent, unit) + if (top == null) "\n" else "")
            documents.commitDocument(document)
        }, file)
    }

    private fun navigate(project: Project, editor: Editor?, file: PsiFile, offset: Int, same: Boolean) {
        if (same && editor != null) {
            editor.caretModel.moveToOffset(offset.coerceIn(0, editor.document.textLength))
            return
        }
        val virtualFile = file.virtualFile ?: return
        if (ApplicationManager.getApplication().isUnitTestMode && editor == null) return
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, virtualFile, offset), true)
    }

    /** The fixes for a semantic error at [range] with [code]; [imported]: «Import type» is offered there and stays first. */
    fun fixes(file: CSharpFile, code: String, range: TextRange, imported: Boolean = false): List<IntentionAction> {
        if (code !in CODES) return emptyList()
        val name = nameAt(file, range.startOffset) ?: return emptyList()
        val text = name.identifier?.text ?: return emptyList()
        val pointer = SmartPointerManager.createPointer<PsiElement>(name)
        return kinds(name, code).mapIndexed { i, kind -> CSharpCreateFromUsageFix(kind, text, pointer, i == 0 && !imported) }
    }
}

/** «Create class 'Foo'», «Create field '_orders'», … ([CSharpCreateFromUsage]). */
class CSharpCreateFromUsageFix(
    private val kind: CSharpCreateKind, private val name: String, private val at: SmartPsiElementPointer<PsiElement>, private val first: Boolean,
) : IntentionAction, PriorityAction {
    override fun getText(): String = "Create ${kind.word} '$name'"
    override fun getFamilyName(): String = "Create from usage"
    override fun startInWriteAction(): Boolean = false
    override fun getPriority(): PriorityAction.Priority = if (first) PriorityAction.Priority.HIGH else PriorityAction.Priority.NORMAL

    private fun usage(): CSharpIdentifierName? = at.element as? CSharpIdentifierName

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (file !is CSharpFile || DumbService.isDumb(project)) return false
        val usage = usage() ?: return false
        if (usage.identifier?.text != name) return false
        if (kind.isType || kind == CSharpCreateKind.LOCAL || kind == CSharpCreateKind.PARAMETER) return true
        return CSharpCreateFromUsage.targetType(kind, usage, CSharpSemanticSession(project).resolver(file)) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (file !is CSharpFile) return
        CSharpCreateFromUsage.create(kind, project, editor, file, usage() ?: return)
    }
}
