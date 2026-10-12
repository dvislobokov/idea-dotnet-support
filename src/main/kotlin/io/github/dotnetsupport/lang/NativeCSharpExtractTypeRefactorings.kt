package io.github.dotnetsupport.lang

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.SemanticType
import javax.swing.JComponent

/*
 * Refactor This on a type, as Rider lists them (Jellyfin № 52): "Extract Interface", "Extract Superclass", "Extract Class". A dialog
 * ([NativeCSharpExtractTypeDialog]) asks the name, the file and the members; [NativeCSharpExtractTypeEdits] turns the choice into the
 * texts, testable without the dialog ([NativeCSharpExtractTypeEdits.answer]).
 */

/** The edits of the three extractions: pure functions of the file's text and the tree. */
object NativeCSharpExtractTypeEdits {
    enum class Kind(val title: String) { INTERFACE("Extract Interface"), SUPERCLASS("Extract Superclass"), CLASS("Extract Class") }

    /** A member the dialog lists: [abstractable] — Extract Superclass may leave it in the class as an override of an abstract one. */
    class Member(val element: CSharpMemberDeclaration, val name: String, val label: String, val abstractable: Boolean)

    /** What the user chose: the new type's [name], the [members] to extract, those to make [abstract], in a [newFile] or after the type. */
    class Choice(val name: String, val members: List<CSharpMemberDeclaration>, val abstract: Set<CSharpMemberDeclaration> = emptySet(), val newFile: Boolean = true)

    /** The class's file after the extraction, the new type's declaration (at the type's indent) and, for a new file, its name and text. */
    class Result(val sourceText: String, val declaration: String, val fileName: String?, val newFileText: String?)

    /** Tests: the dialog's answer for a kind, the members listed and the suggested name (null: the dialog; returning null cancels). */
    @Volatile var answer: ((Kind, List<Member>, String) -> Choice?)? = null

    private class Edit(val start: Int, val end: Int, val text: String)

    // ---- where

    /** The top-level class / struct / record whose header or member holds [offset] (not a nested type's member). */
    fun ownerAt(file: CSharpFile, offset: Int): CSharpTypeDeclaration? {
        for (candidate in listOf(offset, offset - 1)) {
            val leaf = file.findElementAt(candidate.coerceAtLeast(0)) ?: continue
            val type = PsiTreeUtil.getParentOfType(leaf, CSharpBaseTypeDeclaration::class.java, false) as? CSharpTypeDeclaration ?: continue
            if (type.parent !is CSharpBaseNamespaceDeclaration && type.parent !is CSharpCompilationUnit) return null
            val keyword = type.keyword?.text ?: return null
            if (keyword != "class" && keyword != "struct" && keyword != "record") return null
            if (type.identifier?.text.isNullOrEmpty()) return null
            return type
        }
        return null
    }

    fun available(kind: Kind, owner: CSharpTypeDeclaration): Boolean {
        val keyword = owner.keyword?.text
        val static = owner.modifiers.any { it.text == "static" }
        return when (kind) {
            Kind.INTERFACE -> !static
            Kind.SUPERCLASS, Kind.CLASS -> keyword == "class" && !static && (owner.typeParameterList == null || kind == Kind.SUPERCLASS)
        } && candidates(kind, owner).isNotEmpty()
    }

    fun suggestedName(kind: Kind, owner: CSharpTypeDeclaration): String {
        val name = owner.identifier!!.text
        return when (kind) {
            Kind.INTERFACE -> "I$name"
            Kind.SUPERCLASS -> "${name}Base"
            Kind.CLASS -> "${name}Data"
        }
    }

    private fun modifiers(member: CSharpMemberDeclaration): List<String> = member.modifiers.map { it.text }
    private fun isStatic(member: CSharpMemberDeclaration) = "static" in modifiers(member) || "const" in modifiers(member)
    private fun isPublic(member: CSharpMemberDeclaration) = "public" in modifiers(member)
    private fun isPrivate(member: CSharpMemberDeclaration) = modifiers(member).none { it == "public" || it == "protected" || it == "internal" }

    /** The members [kind] may take, with the names and the labels the dialog shows. */
    fun candidates(kind: Kind, owner: CSharpTypeDeclaration): List<Member> = owner.members.flatMap { member ->
        when (member) {
            is CSharpMethodDeclaration -> {
                val name = member.identifier?.text ?: return@flatMap emptyList()
                if (member.explicitInterfaceSpecifier != null || "partial" in modifiers(member)) return@flatMap emptyList()
                val ok = when (kind) {
                    Kind.INTERFACE -> isPublic(member) && !isStatic(member)
                    Kind.SUPERCLASS -> true
                    Kind.CLASS -> !isStatic(member) && "override" !in modifiers(member) && "virtual" !in modifiers(member) && "abstract" !in modifiers(member)
                }
                if (!ok) emptyList() else listOf(Member(member, name, "$name${member.parameterList?.text ?: "()"}",
                    kind == Kind.SUPERCLASS && !isStatic(member) && !isPrivate(member) && "abstract" !in modifiers(member)))
            }
            is CSharpPropertyDeclaration -> {
                val name = member.identifier?.text ?: return@flatMap emptyList()
                if (member.explicitInterfaceSpecifier != null) return@flatMap emptyList()
                val ok = when (kind) {
                    Kind.INTERFACE -> isPublic(member) && !isStatic(member)
                    Kind.SUPERCLASS -> true
                    Kind.CLASS -> !isStatic(member) && "override" !in modifiers(member) && "virtual" !in modifiers(member) && "abstract" !in modifiers(member)
                }
                if (!ok) emptyList() else listOf(Member(member, name, "$name : ${member.type?.text}",
                    kind == Kind.SUPERCLASS && !isStatic(member) && !isPrivate(member) && "abstract" !in modifiers(member)))
            }
            is CSharpIndexerDeclaration -> if (kind == Kind.INTERFACE && isPublic(member) && member.explicitInterfaceSpecifier == null)
                listOf(Member(member, "this", "this${member.parameterList?.text}", false)) else emptyList()
            is CSharpEventDeclaration -> {
                val name = member.identifier?.text ?: return@flatMap emptyList()
                if (member.explicitInterfaceSpecifier != null || kind == Kind.CLASS) return@flatMap emptyList()
                if (kind == Kind.INTERFACE && (!isPublic(member) || isStatic(member))) return@flatMap emptyList()
                listOf(Member(member, name, "event $name", false))
            }
            is CSharpEventFieldDeclaration -> {
                if (kind == Kind.CLASS || kind == Kind.INTERFACE && (!isPublic(member) || isStatic(member))) return@flatMap emptyList()
                val names = member.declaration?.variables?.mapNotNull { it.identifier?.text }.orEmpty()
                if (names.size != 1) emptyList() else listOf(Member(member, names.single(), "event ${names.single()}", false))
            }
            is CSharpFieldDeclaration -> {
                if (kind == Kind.INTERFACE) return@flatMap emptyList()
                val names = member.declaration?.variables?.mapNotNull { it.identifier?.text }.orEmpty()
                if (names.size != 1 || kind == Kind.CLASS && isStatic(member)) emptyList()
                else listOf(Member(member, names.single(), "${names.single()} : ${member.declaration?.type?.text}", false))
            }
            else -> emptyList()
        }
    }

    // ---- texts

    private fun lineStart(text: CharSequence, offset: Int) = NativeCSharpMoveTypeEdits.lineStart(text, offset)

    /** The `///` lines right above [start] (a line start). */
    private fun docStart(text: CharSequence, start: Int): Int {
        var current = start
        while (current > 0) {
            val previous = lineStart(text, current - 1)
            if (!text.subSequence(previous, current).trim().startsWith("///")) break
            current = previous
        }
        return current
    }

    /** The doc comment of [member] at [indent], or "". */
    private fun docComment(text: CharSequence, member: PsiElement, indent: String): String {
        val start = lineStart(text, member.textRange.startOffset)
        val doc = docStart(text, start)
        if (doc == start) return ""
        return text.subSequence(doc, start).toString().lines().filter { it.isNotBlank() }.joinToString("") { indent + it.trim() + "\n" }
    }

    /** [member] with its doc comment and attributes, [edits] applied, re-indented from its own indent to [indent]. */
    private fun memberText(text: CharSequence, member: PsiElement, indent: String, edits: List<Edit> = emptyList()): String {
        val start = NativeCSharpMoveTypeEdits.leadingComments(text, lineStart(text, member.textRange.startOffset))
        var piece = text.subSequence(start, member.textRange.endOffset).toString()
        for (edit in edits.filter { it.start >= start && it.end <= member.textRange.endOffset }.sortedByDescending { it.start }) {
            piece = piece.substring(0, edit.start - start) + edit.text + piece.substring(edit.end - start)
        }
        val own = CSharpExpressions.indentAt(text, member.textRange.startOffset)
        return piece.lines().joinToString("\n") { line -> if (line.isBlank()) "" else indent + line.removePrefix(own) }
    }

    /** The range [member] takes with its doc comment and attributes, its line end and the blank lines after it (before `}` the ones before it). */
    private fun removal(text: CharSequence, member: PsiElement): Edit {
        var start = NativeCSharpMoveTypeEdits.leadingComments(text, lineStart(text, member.textRange.startOffset))
        var end = member.textRange.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t' || text[end] == '\r')) end++
        if (end < text.length && text[end] == '\n') end++
        while (true) {
            val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
            if (end >= text.length || text.subSequence(end, lineEnd).isNotBlank()) break
            end = if (lineEnd < text.length) lineEnd + 1 else lineEnd
        }
        if (text.subSequence(end, text.length).trimStart().startsWith("}")) {
            while (start > 0) {
                val previous = lineStart(text, start - 1)
                if (text.subSequence(previous, start).isNotBlank()) break
                start = previous
            }
        }
        return Edit(start, end, "")
    }

    private fun apply(text: CharSequence, edits: List<Edit>): String {
        var result = text.toString()
        for (edit in edits.sortedWith(compareByDescending<Edit> { it.start }.thenByDescending { it.end })) result = result.substring(0, edit.start) + edit.text + result.substring(edit.end)
        return result
    }

    private fun words(text: String): Set<String> = Regex("""[A-Za-z_]\w*""").findAll(text).map { it.value }.toSet()

    /** The accessibility the new type takes: the class's (`public` / `internal`), nothing when it has none. */
    private fun access(owner: CSharpTypeDeclaration): String = owner.modifiers.map { it.text }.filter { it == "public" || it == "internal" }.joinToString(" ")

    /** Where an accessibility goes in [member]: before its first modifier, after its attributes. */
    private fun accessOffset(text: CharSequence, member: CSharpMemberDeclaration): Int {
        member.modifiers.firstOrNull()?.let { return it.textRange.startOffset }
        var at = member.attributeLists.lastOrNull()?.textRange?.endOffset ?: return member.textRange.startOffset
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }

    /** `private` → `protected` (or `protected` added when the member has no accessibility). */
    private fun toProtected(text: CharSequence, member: CSharpMemberDeclaration): Edit? {
        val private = member.modifiers.firstOrNull { it.text == "private" }
        if (private != null) {
            if (member.modifiers.any { it.text == "protected" }) return null
            return Edit(private.textRange.startOffset, private.textRange.endOffset, "protected")
        }
        if (member.modifiers.any { it.text == "public" || it.text == "protected" || it.text == "internal" }) return null
        val at = accessOffset(text, member)
        return Edit(at, at, "protected ")
    }

    /** `private` → `public` (or `public` added when the member has no accessibility). */
    private fun toPublic(text: CharSequence, member: CSharpMemberDeclaration): Edit? {
        val access = member.modifiers.filter { it.text == "private" || it.text == "protected" || it.text == "internal" }
        if (member.modifiers.any { it.text == "public" }) return null
        if (access.isNotEmpty()) return Edit(access.first().textRange.startOffset, access.last().textRange.endOffset, "public")
        val at = accessOffset(text, member)
        return Edit(at, at, "public ")
    }

    /** The signature of [member] in an interface or as an abstract member: no modifiers, no body. */
    private fun signature(text: CharSequence, member: CSharpMemberDeclaration): String? = when (member) {
        is CSharpMethodDeclaration -> {
            val start = member.returnType?.textRange?.startOffset ?: return null
            val end = member.constraintClauses.lastOrNull()?.textRange?.endOffset ?: member.parameterList?.textRange?.endOffset ?: return null
            text.subSequence(start, end).toString().replace(Regex("""\s*\n\s*"""), " ") + ";"
        }
        is CSharpPropertyDeclaration -> "${member.type?.text} ${member.identifier?.text} ${accessors(member.accessorList, member.expressionBody != null)}"
        is CSharpIndexerDeclaration -> "${member.type?.text} this${member.parameterList?.text} ${accessors(member.accessorList, member.expressionBody != null)}"
        is CSharpEventDeclaration -> "event ${member.type?.text} ${member.identifier?.text};"
        is CSharpEventFieldDeclaration -> "event ${member.declaration?.type?.text} ${member.declaration?.variables?.singleOrNull()?.identifier?.text};"
        else -> null
    }

    /** `{ get; set; }` of the accessors not hidden by an accessibility of their own; `{ get; }` for an expression body. */
    private fun accessors(list: CSharpAccessorList?, expressionBody: Boolean): String {
        if (list == null) return if (expressionBody) "{ get; }" else "{ get; }"
        val visible = list.accessors.filter { accessor -> accessor.modifiers.none { it.text == "private" || it.text == "protected" || it.text == "internal" } }
        return "{ " + visible.joinToString(" ") { "${it.keyword?.text};" } + " }"
    }

    /** The type parameters of [owner] the texts use: `<T>` and its `where` clauses, or empty. */
    private fun typeParameters(owner: CSharpTypeDeclaration, texts: List<String>): Pair<String, String> {
        val all = owner.typeParameterList?.parameters?.mapNotNull { it.identifier?.text }.orEmpty()
        val used = words(texts.joinToString("\n"))
        val kept = all.filter { it in used }
        if (kept.isEmpty()) return "" to ""
        val clauses = owner.constraintClauses.filter { clause -> words(clause.text).let { w -> kept.any { it in w } } && clause.text.substringAfter("where").trim().substringBefore(' ').substringBefore(':') in kept }
        return "<${kept.joinToString(", ")}>" to clauses.joinToString("") { " " + it.text }
    }

    /** The declaration of the new type at the class's indent: `access kind Name<T> : bases where … { members }`. */
    private fun declaration(indent: String, head: String, members: List<String>): String = buildString {
        append(indent).append(head).append('\n').append(indent).append("{\n")
        append(members.joinToString("\n\n"))
        if (members.isNotEmpty()) append('\n')
        append(indent).append("}\n")
    }

    /** The new file: what is before the class (usings, namespace) and [declaration]. */
    private fun newFile(text: CharSequence, owner: CSharpTypeDeclaration, declaration: String): String {
        val namespace = owner.parent as? CSharpBaseNamespaceDeclaration
        val headerEnd = lineStart(text, NativeCSharpMoveTypeEdits.leadingComments(text, lineStart(text, owner.textRange.startOffset)))
        return when (namespace) {
            null -> text.subSequence(0, headerEnd).toString().trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" } + declaration
            is CSharpNamespaceDeclaration -> text.subSequence(0, namespace.openBraceToken!!.textRange.endOffset).toString() + "\n" + declaration + "}\n"
            else -> {
                val scoped = namespace as CSharpFileScopedNamespaceDeclaration
                text.subSequence(0, scoped.textRange.startOffset).toString().trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" } +
                    text.subSequence(scoped.textRange.startOffset, scoped.semicolonToken!!.textRange.endOffset) + "\n\n" + declaration
            }
        }
    }

    private fun result(text: CharSequence, owner: CSharpTypeDeclaration, edits: MutableList<Edit>, name: String, declaration: String, newFile: Boolean): Result {
        if (newFile) return Result(apply(text, edits), declaration, "$name.cs", newFile(text, owner, declaration))
        edits += Edit(owner.textRange.endOffset, owner.textRange.endOffset, "\n\n" + declaration.trimEnd('\n'))
        return Result(apply(text, edits), declaration, null, null)
    }

    /** ` : Name` / `, Name` at the end of the class's base list ([first]: before its other bases). */
    private fun addBase(owner: CSharpTypeDeclaration, name: String, first: Boolean): Edit {
        val types = owner.baseList?.types.orEmpty()
        if (types.isNotEmpty()) {
            return if (first) Edit(types.first().textRange.startOffset, types.first().textRange.startOffset, "$name, ")
            else Edit(types.last().textRange.endOffset, types.last().textRange.endOffset, ", $name")
        }
        val anchor = owner.parameterList ?: owner.typeParameterList ?: owner.identifier!!
        return Edit(anchor.textRange.endOffset, anchor.textRange.endOffset, " : $name")
    }

    // ---- Extract Interface

    /**
     * An interface of the chosen members' signatures (with their doc comments, as Rider copies them) and the class's type parameters the
     * signatures use; the class gets the interface at the end of its base list.
     */
    fun extractInterface(text: CharSequence, owner: CSharpTypeDeclaration, choice: Choice, unit: String): Result? {
        val indent = CSharpExpressions.indentAt(text, owner.textRange.startOffset)
        val members = choice.members.sortedBy { it.textRange.startOffset }.map { member ->
            val signature = signature(text, member) ?: return null
            docComment(text, member, indent + unit) + indent + unit + signature
        }
        val (parameters, clauses) = typeParameters(owner, members)
        val head = listOf(access(owner), "interface", choice.name + parameters).filter { it.isNotEmpty() }.joinToString(" ") + clauses
        val edits = mutableListOf(addBase(owner, choice.name + parameters, first = false))
        return result(text, owner, edits, choice.name, declaration(indent, head, members), choice.newFile)
    }

    // ---- Extract Superclass

    /**
     * A class [Choice.name] the chosen members move to (or are declared `abstract` in, the class's own ones becoming overrides), deriving
     * from the class's old base class; the class derives from it. Private members the moved ones use, or that use the moved ones, become
     * `protected`. Null when a constructor of the class calls `base(...)` or the old base takes primary-constructor arguments: the new
     * class would need those constructors.
     */
    fun extractSuperclass(text: CharSequence, owner: CSharpTypeDeclaration, choice: Choice, oldBaseIsClass: Boolean, unit: String): Result? {
        val indent = CSharpExpressions.indentAt(text, owner.textRange.startOffset)
        val types = owner.baseList?.types.orEmpty()
        val oldBase = types.firstOrNull()?.takeIf { oldBaseIsClass }
        if (oldBase is CSharpPrimaryConstructorBaseType) return null
        if (oldBase != null && owner.members.any { (it as? CSharpConstructorDeclaration)?.initializer?.text?.contains("base") == true }) return null
        val moved = choice.members.filter { it !in choice.abstract }
        val remaining = owner.members.filter { it !in moved }
        val edits = mutableListOf<Edit>()
        // privates on one side used by the other side: protected
        val movedText = moved.joinToString("\n") { it.text }
        val remainingText = remaining.filter { it !in choice.abstract }.joinToString("\n") { it.text } + choice.abstract.joinToString("\n") { it.text }
        val movedEdits = HashMap<CSharpMemberDeclaration, MutableList<Edit>>()
        for (member in owner.members) {
            if (!isPrivate(member) || member is CSharpBaseTypeDeclaration || member is CSharpConstructorDeclaration) continue
            val name = candidateName(member) ?: continue
            val usedAcross = if (member in moved) Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(remainingText)
                else Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(movedText)
            if (!usedAcross) continue
            val edit = toProtected(text, member) ?: continue
            if (member in moved) movedEdits.getOrPut(member) { mutableListOf() } += edit else edits += edit
        }
        val bodies = ArrayList<Pair<Int, String>>()
        for (member in moved) {
            bodies += member.textRange.startOffset to memberText(text, member, indent + unit, movedEdits[member].orEmpty())
            edits += removal(text, member)
        }
        for (member in choice.abstract) {
            val signature = signature(text, member) ?: return null
            val access = modifiers(member).filter { it == "public" || it == "protected" || it == "internal" }.joinToString(" ")
            bodies += member.textRange.startOffset to docComment(text, member, indent + unit) + indent + unit + "$access abstract $signature".trim()
            // the class's own member overrides the abstract one
            val virtual = member.modifiers.firstOrNull { it.text == "virtual" }
            if (virtual != null) edits += Edit(virtual.textRange.startOffset, virtual.textRange.endOffset, "override")
            else member.modifiers.lastOrNull { it.text == "public" || it.text == "protected" || it.text == "internal" }?.let { edits += Edit(it.textRange.endOffset, it.textRange.endOffset, " override") }
                ?: return null
        }
        val members = bodies.sortedBy { it.first }.map { it.second }
        val (parameters, clauses) = typeParameters(owner, members)
        val abstract = choice.abstract.isNotEmpty()
        val head = listOf(access(owner), if (abstract) "abstract" else "", "class", choice.name + parameters).filter { it.isNotEmpty() }.joinToString(" ") +
            (oldBase?.let { " : ${it.text}" } ?: "") + clauses
        val reference = choice.name + parameters
        if (oldBase != null) edits += Edit(oldBase.textRange.startOffset, oldBase.textRange.endOffset, reference) else edits += addBase(owner, reference, first = true)
        return result(text, owner, edits, choice.name, declaration(indent, head, members), choice.newFile)
    }

    private fun candidateName(member: CSharpMemberDeclaration): String? = when (member) {
        is CSharpMethodDeclaration -> member.identifier?.text
        is CSharpPropertyDeclaration -> member.identifier?.text
        is CSharpEventDeclaration -> member.identifier?.text
        is CSharpBaseFieldDeclaration -> member.declaration?.variables?.singleOrNull()?.identifier?.text
        else -> null
    }

    // ---- Extract Class

    /**
     * The chosen fields, properties and methods into a new class (made `public`); the class gets `private readonly Name _name = new();`
     * where the first of them was, and its other members reach them through it (`_count` → `_data._count`, `this.Save()` →
     * `_data.Save()`). Only when the moved members use nothing of the class but each other (no `this`, no `base`, no other instance
     * member): null otherwise.
     */
    fun extractClass(text: CharSequence, owner: CSharpTypeDeclaration, choice: Choice, unit: String): Result? {
        val indent = CSharpExpressions.indentAt(text, owner.textRange.startOffset)
        val moved = choice.members.sortedBy { it.textRange.startOffset }
        val movedNames = moved.mapNotNull(::candidateName).toSet()
        if (movedNames.size != moved.size) return null
        val remaining = owner.members.filter { it !in moved }
        val remainingNames = remaining.filter { !isStatic(it) }.mapNotNull(::candidateName).toSet() - movedNames
        for (member in moved) {
            val used = words(member.text) - setOfNotNull(candidateName(member))
            if ("this" in used || "base" in used || used.any { it in remainingNames }) return null
        }
        val field = "_" + choice.name.replaceFirstChar { it.lowercase() }
        if (owner.members.any { candidateName(it) == field }) return null
        val edits = mutableListOf<Edit>()
        val bodies = moved.map { member -> memberText(text, member, indent + unit, listOfNotNull(toPublic(text, member))) }
        moved.forEachIndexed { i, member ->
            val removal = removal(text, member)
            edits += if (i == 0) Edit(removal.start, removal.end, indent + unit + "private readonly ${choice.name} $field = new();\n\n".let { s ->
                if (text.subSequence(removal.end, text.length).trimStart().startsWith("}")) s.trimEnd() + "\n" else s
            }) else removal
        }
        // the other members reach the moved ones through the field
        for (member in remaining) {
            for (name in PsiTreeUtil.findChildrenOfType(member, CSharpIdentifierName::class.java)) {
                val identifier = name.identifier?.text ?: continue
                if (identifier !in movedNames) continue
                val parent = name.parent
                if (parent is CSharpMemberAccessExpression && parent.nameElement == name) {
                    if (parent.expression is CSharpThisExpression) edits += Edit(parent.textRange.startOffset, parent.textRange.endOffset, "$field.$identifier")
                    continue
                }
                if (parent is CSharpNameColon || parent is CSharpNameEquals) continue
                edits += Edit(name.textRange.startOffset, name.textRange.endOffset, "$field.$identifier")
            }
        }
        val head = listOf(access(owner), "class", choice.name).filter { it.isNotEmpty() }.joinToString(" ")
        return result(text, owner, edits, choice.name, declaration(indent, head, bodies), choice.newFile)
    }

    /** Whether the first base of [owner] is a class (not an interface), by the resolver; by the `I` + capital convention when unknown. */
    fun firstBaseIsClass(owner: CSharpTypeDeclaration): Boolean {
        val type = owner.baseList?.types?.firstOrNull()?.type ?: return false
        val file = owner.containingFile as? CSharpFile
        val resolved = if (file == null || DumbService.isDumb(owner.project)) null else runCatching { CSharpSemanticSession(owner.project).resolver(file).resolveType(type) }.getOrNull()
        return when (resolved) {
            is SemanticType.Source -> resolved.info.kind == TypeKind.CLASS || resolved.info.kind == TypeKind.RECORD
            is SemanticType.Library -> resolved.type.kind == IndexedTypeKind.CLASS
            else -> !Regex("""^I[A-Z]""").containsMatchIn(type.text.substringAfterLast('.'))
        }
    }
}

/** The dialog of the three extractions: the name, a new file or the same one, the members (and which become abstract). */
class NativeCSharpExtractTypeDialog(
    project: Project, private val kind: NativeCSharpExtractTypeEdits.Kind, private val members: List<NativeCSharpExtractTypeEdits.Member>, suggested: String,
) : DialogWrapper(project) {
    private val name = JBTextField(suggested, 30)
    private val newFile = JBCheckBox("Put in a new file", true)
    private val checks = members.map { JBCheckBox(it.label, kind == NativeCSharpExtractTypeEdits.Kind.INTERFACE) }
    private val abstracts = members.map { JBCheckBox("Make abstract", false).apply { isEnabled = it.abstractable } }

    init {
        title = kind.title
        init()
    }

    override fun createCenterPanel(): JComponent {
        val list = panel {
            members.indices.forEach { i ->
                row {
                    cell(checks[i])
                    if (kind == NativeCSharpExtractTypeEdits.Kind.SUPERCLASS) cell(abstracts[i])
                }
            }
        }
        return panel {
            row("Name:") { cell(name) }
            row { cell(newFile) }
            row { cell(JBScrollPane(list).apply { preferredSize = java.awt.Dimension(480, 260) }) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = name

    fun choice(): NativeCSharpExtractTypeEdits.Choice? {
        val chosen = members.indices.filter { checks[it].isSelected }
        val typed = name.text.trim()
        if (chosen.isEmpty() || typed.isEmpty() || !typed.all { it.isLetterOrDigit() || it == '_' } || !(typed[0].isLetter() || typed[0] == '_')) return null
        val abstract = chosen.filter { abstracts[it].isSelected && members[it].abstractable }.map { members[it].element }.toSet()
        return NativeCSharpExtractTypeEdits.Choice(typed, chosen.map { members[it].element }, abstract, newFile.isSelected)
    }
}

/** One of the three extractions as a row of Refactor This (Rider does not list them in Alt+Enter). */
abstract class NativeCSharpExtractTypeRow(private val kind: NativeCSharpExtractTypeEdits.Kind) : NativeCSharpRefactorRow(kind.title) {
    private fun owner(editor: Editor?, file: PsiFile?): CSharpTypeDeclaration? {
        if (editor == null || file !is CSharpFile || file.compilationUnit == null) return null
        return NativeCSharpExtractTypeEdits.ownerAt(file, editor.caretModel.offset)
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        owner(editor, file)?.let { NativeCSharpExtractTypeEdits.available(kind, it) } == true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val source = file as? CSharpFile ?: return
        val owner = owner(editor, source) ?: return
        val virtualFile = source.virtualFile ?: return
        val members = NativeCSharpExtractTypeEdits.candidates(kind, owner)
        val suggested = NativeCSharpExtractTypeEdits.suggestedName(kind, owner)
        val choice = NativeCSharpExtractTypeEdits.answer?.invoke(kind, members, suggested) ?: run {
            if (NativeCSharpExtractTypeEdits.answer != null) return
            val dialog = NativeCSharpExtractTypeDialog(project, kind, members, suggested)
            if (!dialog.showAndGet()) return
            dialog.choice() ?: return Messages.showErrorDialog(project, "Choose a valid name and at least one member", kind.title)
        }
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
        val text = document.immutableCharSequence
        val unit = NativeCSharpContextEdits.unit(source)
        val result = when (kind) {
            NativeCSharpExtractTypeEdits.Kind.INTERFACE -> NativeCSharpExtractTypeEdits.extractInterface(text, owner, choice, unit)
            NativeCSharpExtractTypeEdits.Kind.SUPERCLASS -> NativeCSharpExtractTypeEdits.extractSuperclass(text, owner, choice, NativeCSharpExtractTypeEdits.firstBaseIsClass(owner), unit)
            NativeCSharpExtractTypeEdits.Kind.CLASS -> NativeCSharpExtractTypeEdits.extractClass(text, owner, choice, unit)
        } ?: return Messages.showErrorDialog(project, cannot(kind), kind.title)
        val fileName = result.fileName
        if (fileName != null && virtualFile.parent.findChild(fileName) != null) return Messages.showErrorDialog(project, "$fileName already exists", kind.title)
        val created = WriteCommandAction.writeCommandAction(project).withName(kind.title).compute<VirtualFile?, RuntimeException> {
            document.replaceString(0, document.textLength, result.sourceText)
            FileDocumentManager.getInstance().saveDocument(document)
            if (fileName == null) null else virtualFile.parent.createChildData(this, fileName).also { VfsUtil.saveText(it, result.newFileText!!) }
        }
        if (created != null) {
            MoveTypeToFileIntention.removeUnusedUsings(project, created)
            OpenFileDescriptor(project, created).navigate(true)
        }
    }

    private fun cannot(kind: NativeCSharpExtractTypeEdits.Kind): String = when (kind) {
        NativeCSharpExtractTypeEdits.Kind.SUPERCLASS -> "Cannot extract a superclass here: a constructor calls base(...), or the base class takes primary-constructor arguments"
        NativeCSharpExtractTypeEdits.Kind.CLASS -> "Cannot extract a class: the members use other members of the class, 'this' or 'base'"
        NativeCSharpExtractTypeEdits.Kind.INTERFACE -> "Cannot extract an interface from these members"
    }
}

/** Refactor This: "Extract Interface" (Jellyfin № 52, as Rider). */
class NativeCSharpExtractInterfaceRow : NativeCSharpExtractTypeRow(NativeCSharpExtractTypeEdits.Kind.INTERFACE)

/** Refactor This: "Extract Superclass" (Jellyfin № 52, as Rider). */
class NativeCSharpExtractSuperclassRow : NativeCSharpExtractTypeRow(NativeCSharpExtractTypeEdits.Kind.SUPERCLASS)

/** Refactor This: "Extract Class" (Jellyfin № 52, as Rider). */
class NativeCSharpExtractClassRow : NativeCSharpExtractTypeRow(NativeCSharpExtractTypeEdits.Kind.CLASS)
