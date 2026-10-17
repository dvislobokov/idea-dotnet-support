package io.github.dotnetsupport.lang

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.dotnetsupport.csharp.lang.CSharpLanguageLevel
import io.github.dotnetsupport.csharp.lang.psi.*
import io.github.dotnetsupport.index.IndexedTypeKind
import io.github.dotnetsupport.lang.semantic.CSharpSemanticSession
import io.github.dotnetsupport.lang.semantic.CSharpSolutionSearch
import io.github.dotnetsupport.lang.semantic.CSharpSymbol

/**
 * The context actions on members Rider offers at Jellyfin p01, p16, p17, p18 (0.1.173): an auto-property "To property with backing field",
 * "To property with 'field' keyword" (C# 14), "To computed property" and back "To auto-property"; "Encapsulate field"; "To factory method"
 * on a constructor; "To explicit implementation" of an interface member and back "To implicit implementation". Pure text edits of one
 * file: what they change in other files is not changed (a factory's call sites, usages of an encapsulated non-private field), so where
 * another file could break the action keeps what it would otherwise change (see each function).
 */
object NativeCSharpMemberEdits {
    private val ACCESS = setOf("public", "private", "protected", "internal", "file")

    private fun present(token: PsiElement?): Boolean = (token?.textLength ?: 0) > 0

    private fun lineStart(text: CharSequence, offset: Int): Int = if (offset == 0) 0 else text.lastIndexOf('\n', offset - 1) + 1

    private fun lineIndent(text: CharSequence, offset: Int): String =
        text.subSequence(lineStart(text, offset), offset).takeWhile { it == ' ' || it == '\t' }.toString()

    /** A change of the text: [start] … [end] becomes [text]. */
    private class Change(val start: Int, val end: Int, val text: String)

    /** [changes] as one edit over their span; the caret at [caret] (an offset of the original text, or of the start). */
    private fun edit(changes: List<Change>, text: CharSequence, caret: Int? = null): CSharpTextEdit? {
        if (changes.isEmpty()) return null
        val sorted = changes.sortedWith(compareBy<Change> { it.start }.thenBy { it.end })
        val from = sorted.first().start
        val to = sorted.maxOf { it.end }
        val out = StringBuilder()
        var at = from
        for (change in sorted) {
            if (change.start < at) return null
            out.append(text, at, change.start).append(change.text)
            at = change.end
        }
        out.append(text, at, to)
        // the caret where [caret] of the original text went: past the changes before it, by their difference in length
        val target = caret ?: from
        val shift = sorted.filter { it.end <= target }.sumOf { it.text.length - (it.end - it.start) }
        return CSharpTextEdit(TextRange(from, to), out.toString(), caret = (target - from + shift).coerceIn(0, out.length))
    }

    /** `_name` of `Name`, `name` of `_name` / `m_name`: the names of a property and of its field. */
    fun fieldName(property: String): String = "_" + property.replaceFirstChar { it.lowercase() }

    fun propertyName(field: String): String = field.removePrefix("m_").removePrefix("s_").trimStart('_').replaceFirstChar { it.uppercase() }

    private fun memberNames(type: CSharpTypeDeclaration): Set<String> = type.members.flatMap { member ->
        when (member) {
            is CSharpBaseFieldDeclaration -> member.declaration?.variables.orEmpty().mapNotNull { it.identifier?.text }
            else -> listOfNotNull(CSharpDeclarationNames.name(member))
        }
    }.toSet()

    // ---- auto-properties

    private val AUTO_KEYWORDS = setOf("get", "set", "init")

    /** The property of [offset] — anywhere in its declaration but in a body of an accessor — in a class, struct or record. */
    fun propertyAt(file: PsiFile, offset: Int): CSharpPropertyDeclaration? {
        for (leaf in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            val property = PsiTreeUtil.getParentOfType(leaf, CSharpPropertyDeclaration::class.java, false) ?: continue
            val accessor = PsiTreeUtil.getParentOfType(leaf, CSharpAccessorDeclaration::class.java, false)
            if (accessor != null && (accessor.body?.textRange?.containsOffset(offset) == true || accessor.expressionBody?.textRange?.containsOffset(offset) == true)) continue
            if (property.expressionBody?.textRange?.containsOffset(offset) == true) continue
            val type = property.parent as? CSharpTypeDeclaration ?: continue
            if (type.keyword?.text == "interface" || type is CSharpInterfaceDeclaration) continue
            return property
        }
        return null
    }

    /** `{ get; set; }`, `{ get; }`, `{ get; init; }` with accessor modifiers, not abstract / extern / partial, not explicit. */
    fun isAuto(property: CSharpPropertyDeclaration): Boolean {
        val list = property.accessorList ?: return false
        if (property.modifiers.any { it.text == "abstract" || it.text == "extern" || it.text == "partial" }) return false
        if (property.explicitInterfaceSpecifier != null) return false
        val accessors = list.accessors
        if (accessors.isEmpty() || accessors.none { it.keyword?.text == "get" }) return false
        return accessors.all { it.keyword?.text in AUTO_KEYWORDS && it.body == null && it.expressionBody == null && present(it.semicolonToken) }
    }

    /** `public string Name` of the property: from its start (attributes included) to its accessor list, trimmed. */
    private fun header(property: CSharpPropertyDeclaration, text: CharSequence): String? {
        val list = property.accessorList ?: return null
        return text.subSequence(property.textRange.startOffset, list.textRange.startOffset).toString().trimEnd()
    }

    /** `private set` of an accessor: its modifiers before the keyword. */
    private fun accessorHead(accessor: CSharpAccessorDeclaration): String = (accessor.modifiers.map { it.text } + listOfNotNull(accessor.keyword?.text)).joinToString(" ")

    /** The accessors as a block of lines: `get => x;`, `set => x = value;`. */
    private fun accessorBlock(property: CSharpPropertyDeclaration, value: String, indent: String, unit: String): String {
        val lines = property.accessorList!!.accessors.map { accessor ->
            if (accessor.keyword?.text == "get") "${accessorHead(accessor)} => $value;" else "${accessorHead(accessor)} => $value = value;"
        }
        return "\n$indent{\n" + lines.joinToString("") { "$indent$unit$it\n" } + "$indent}"
    }

    /** The end of the declaration with its initializer: after `;` of `{ get; set; } = x;`, else after the accessor list. */
    private fun declarationEnd(property: CSharpPropertyDeclaration): Int =
        property.semicolonToken?.takeIf(::present)?.textRange?.endOffset ?: property.textRange.endOffset

    /**
     * `private string _name;` before the property and `get => _name; set => _name = value;` (Rider: "To property with backing field"); the
     * field `readonly` when no `set` writes it, `static` with the property, with the initializer of the property.
     */
    fun toBackingField(property: CSharpPropertyDeclaration, text: CharSequence, unit: String): CSharpTextEdit? {
        if (!isAuto(property)) return null
        val type = property.parent as? CSharpTypeDeclaration ?: return null
        val name = property.identifier?.text ?: return null
        val typeText = property.type?.text ?: return null
        val field = fieldName(name)
        if (field in memberNames(type)) return null
        val head = header(property, text) ?: return null
        val indent = lineIndent(text, property.textRange.startOffset)
        val readonly = property.accessorList!!.accessors.none { it.keyword?.text == "set" }
        val modifiers = listOfNotNull("private", "static".takeIf { property.modifiers.any { it.text == "static" } }, "readonly".takeIf { readonly }).joinToString(" ")
        val initializer = property.initializer?.value?.text?.let { " = $it" }.orEmpty()
        val start = lineStart(text, property.textRange.startOffset)
        val fieldLine = "$indent$modifiers $typeText $field$initializer;\n\n"
        val newProperty = head + accessorBlock(property, field, indent, unit)
        return edit(listOf(Change(start, start, fieldLine), Change(property.textRange.startOffset, declarationEnd(property), newProperty)), text, start)
    }

    /** `get => field; set => field = value;` (C# 14, Rider: "To property with 'field' keyword"); the initializer stays after the block. */
    fun toFieldKeyword(property: CSharpPropertyDeclaration, text: CharSequence, unit: String): CSharpTextEdit? {
        if (!isAuto(property)) return null
        val head = header(property, text) ?: return null
        val indent = lineIndent(text, property.textRange.startOffset)
        val initializer = property.initializer?.value?.text?.let { " = $it;" }.orEmpty()
        val newProperty = head + accessorBlock(property, "field", indent, unit) + initializer
        return edit(listOf(Change(property.textRange.startOffset, declarationEnd(property), newProperty)), text, property.textRange.startOffset)
    }

    /** `public string Name => value;` of the initializer, else `=> throw new NotImplementedException();` (Rider: "To computed property"). */
    fun toComputed(property: CSharpPropertyDeclaration, text: CharSequence): CSharpTextEdit? {
        if (!isAuto(property)) return null
        val head = header(property, text) ?: return null
        val value = property.initializer?.value?.text ?: "throw new ${exception(text)}()"
        return edit(listOf(Change(property.textRange.startOffset, declarationEnd(property), "$head => $value;")), text, property.textRange.startOffset)
    }

    private val USING_SYSTEM = Regex("""(?m)^\s*(?:global\s+)?using\s+System\s*;""")

    private fun exception(text: CharSequence): String = if (USING_SYSTEM.containsMatchIn(text)) "NotImplementedException" else "System.NotImplementedException"

    /** `get => _f;` / `get { return _f; }` / `get => this._f;`: the field read; `set => _f = value;` / `set { _f = value; }`: the field written. */
    private fun accessorField(accessor: CSharpAccessorDeclaration): String? {
        val keyword = accessor.keyword?.text ?: return null
        val body = accessor.expressionBody?.expression?.text ?: accessor.body?.statements?.singleOrNull()?.text ?: return null
        val name = """(?:this\.)?@?([A-Za-z_]\w*)"""
        val match = when (keyword) {
            "get" -> Regex("""^(?:return\s+)?$name\s*;?$""").matchEntire(body.trim())
            "set", "init" -> Regex("""^$name\s*=\s*value\s*;?$""").matchEntire(body.trim())
            else -> null
        } ?: return null
        return match.groupValues[1]
    }

    /**
     * A property whose accessors only read and write one private field of its type and static-ness, the field used nowhere else in the
     * file: `{ get; set; }` with the field's initializer, the field gone (Rider: "To auto-property"). A field used elsewhere is left to the
     * user (other files are not searched, so a non-private field is not touched either).
     */
    fun toAuto(property: CSharpPropertyDeclaration, text: CharSequence): CSharpTextEdit? {
        val list = property.accessorList ?: return null
        if (isAuto(property) || property.explicitInterfaceSpecifier != null) return null
        if (property.modifiers.any { it.text == "abstract" || it.text == "extern" || it.text == "partial" }) return null
        val accessors = list.accessors
        if (accessors.none { it.keyword?.text == "get" }) return null
        val names = accessors.map { accessorField(it) ?: return null }.distinct()
        val fieldName = names.singleOrNull() ?: return null
        val type = property.parent as? CSharpTypeDeclaration ?: return null
        val declaration = type.members.filterIsInstance<CSharpFieldDeclaration>().firstOrNull { d -> d.declaration?.variables.orEmpty().any { it.identifier?.text == fieldName } } ?: return null
        val declarator = declaration.declaration?.variables?.singleOrNull() ?: return null
        if (declaration.declaration?.type?.text?.replace(" ", "") != property.type?.text?.replace(" ", "")) return null
        val fieldModifiers = declaration.modifiers.map { it.text }
        if (fieldModifiers.any { it in ACCESS && it != "private" } || "volatile" in fieldModifiers || "const" in fieldModifiers) return null
        if (("static" in fieldModifiers) != property.modifiers.any { it.text == "static" }) return null
        // used elsewhere in the file: the declaration and the accessors are all the occurrences
        val word = Regex("""(?<![\w@])@?${Regex.escape(fieldName)}(?!\w)""")
        val inAccessors = accessors.sumOf { word.findAll(it.text).count() }
        if (word.findAll(text).count() != inAccessors + 1) return null
        val head = header(property, text) ?: return null
        val auto = accessors.joinToString(" ", prefix = " { ", postfix = " }") { "${accessorHead(it)};" }
        val initializer = declarator.initializer?.value?.text?.let { " = $it;" }.orEmpty()
        val changes = ArrayList<Change>()
        changes += Change(property.textRange.startOffset, declarationEnd(property), head + auto + initializer)
        // the field's lines, and one empty line after them when another follows
        val fieldStart = lineStart(text, declaration.textRange.startOffset)
        var fieldEnd = declaration.textRange.endOffset
        while (fieldEnd < text.length && text[fieldEnd] != '\n' && text[fieldEnd].isWhitespace()) fieldEnd++
        if (fieldEnd < text.length && text[fieldEnd] == '\n') fieldEnd++
        val nextLineEnd = text.indexOf('\n', fieldEnd).let { if (it < 0) text.length else it }
        if (fieldEnd < text.length && text.subSequence(fieldEnd, nextLineEnd).isBlank() && nextLineEnd < text.length) fieldEnd = nextLineEnd + 1
        changes += Change(fieldStart, fieldEnd, "")
        return edit(changes, text, property.textRange.startOffset)
    }

    // ---- Encapsulate field

    /** The field of [offset] (one declarator, not `const`, in a class / struct / record) and its type. */
    fun fieldAt(file: PsiFile, offset: Int): Pair<CSharpFieldDeclaration, CSharpTypeDeclaration>? {
        for (leaf in listOfNotNull(file.findElementAt(offset), if (offset > 0) file.findElementAt(offset - 1) else null)) {
            val declaration = PsiTreeUtil.getParentOfType(leaf, CSharpFieldDeclaration::class.java, false) ?: continue
            if (declaration.declaration?.variables?.singleOrNull()?.initializer?.textRange?.containsOffset(offset) == true) continue
            val type = declaration.parent as? CSharpTypeDeclaration ?: continue
            if (type.keyword?.text == "interface") continue
            if (declaration.modifiers.any { it.text == "const" }) continue
            if (declaration.declaration?.variables?.size != 1) continue
            return declaration to type
        }
        return null
    }

    /**
     * A property over the field after it (Rider: "Encapsulate field"): `public ILogger<T> Logger => _logger;` for a `readonly` field, else
     * `{ get => _f; set => _f = value; }`; the field `private`. A field named as its property would be (`Count`) is renamed `_count` and the
     * usages of the type then read the property, as Rider's. A non-private field whose property gets another name keeps its accessibility:
     * other files may use it, and they are not searched.
     */
    fun encapsulate(declaration: CSharpFieldDeclaration, type: CSharpTypeDeclaration, text: CharSequence, unit: String): CSharpTextEdit? {
        val declarator = declaration.declaration?.variables?.singleOrNull() ?: return null
        val identifier = declarator.identifier ?: return null
        val name = identifier.text
        val typeText = declaration.declaration?.type?.text ?: return null
        val property = propertyName(name).takeIf { it.isNotEmpty() && it[0].isLetter() } ?: return null
        val rename = property == name
        val field = if (rename) fieldName(name) else name
        val names = memberNames(type)
        if (!rename && property in names) return null
        if (rename && field in names) return null
        val modifiers = declaration.modifiers
        val written = modifiers.map { it.text }.filter { it in ACCESS }
        val wasPrivate = written.isEmpty() || written == listOf("private")
        val propertyAccess = if (wasPrivate) "public" else written.joinToString(" ")
        val static = modifiers.any { it.text == "static" }
        val readonly = modifiers.any { it.text == "readonly" }
        val changes = ArrayList<Change>()
        if (!wasPrivate && rename) {
            val access = modifiers.filter { it.text in ACCESS }
            changes += Change(access.first().textRange.startOffset, access.last().textRange.endOffset, "private")
        }
        if (rename) changes += Change(identifier.textRange.startOffset, identifier.textRange.endOffset, field)
        val indent = lineIndent(text, declaration.textRange.startOffset)
        val head = listOfNotNull(propertyAccess, "static".takeIf { static }, typeText, property).joinToString(" ")
        val body = if (readonly) " => $field;" else "\n$indent{\n$indent${unit}get => $field;\n$indent${unit}set => $field = value;\n$indent}"
        val at = declaration.textRange.endOffset
        changes += Change(at, at, "\n\n$indent$head$body")
        return edit(changes, text, at)
    }

    // ---- To factory method

    /** The constructor whose header holds [offset]: an instance constructor of a non-abstract, non-generic class, struct or record. */
    fun constructorAt(file: PsiFile, offset: Int): CSharpConstructorDeclaration? {
        val constructor = NativeCSharpModifierEdits.headerAt(file, offset) as? CSharpConstructorDeclaration ?: return null
        if (constructor.modifiers.any { it.text == "static" }) return null
        val type = constructor.parent as? CSharpTypeDeclaration ?: return null
        if (type.typeParameterList != null || type.modifiers.any { it.text == "abstract" || it.text == "static" } || type.keyword?.text == "interface") return null
        return constructor
    }

    /**
     * `public static T Create(…) => new T(…);` after the constructor, the constructor `private`, and `new T(…)` of this file that can only
     * mean this constructor (no other one takes as many arguments, no initializer) written `T.Create(…)` (Rider: "To factory method").
     * Call sites in other files keep `new T(…)`, which a private constructor no longer allows — the user is told by the errors there.
     */
    fun toFactory(file: PsiFile, constructor: CSharpConstructorDeclaration, text: CharSequence): CSharpTextEdit? {
        val type = constructor.parent as? CSharpTypeDeclaration ?: return null
        val typeName = type.identifier?.text ?: return null
        val list = constructor.parameterList ?: return null
        val parameters = list.parameters
        val count = parameters.size
        val factory = "Create"
        val sameArity = type.members.filterIsInstance<CSharpMethodDeclaration>().any { m -> m.identifier?.text == factory && (m.parameterList?.parameters?.size ?: 0) == count }
        if (sameArity) return null
        val open = list.openParenToken?.takeIf(::present) ?: return null
        val close = list.closeParenToken?.takeIf(::present) ?: return null
        val written = text.subSequence(open.textRange.endOffset, close.textRange.startOffset).toString()
        if (parameters.any { it.identifier?.text.isNullOrEmpty() }) return null
        val arguments = parameters.joinToString(", ") { p ->
            val ref = p.modifiers.map { it.text }.firstOrNull { it == "ref" || it == "out" || it == "in" }
            listOfNotNull(ref, p.identifier?.text).joinToString(" ")
        }
        val access = constructor.modifiers.filter { it.text in ACCESS }
        val factoryAccess = access.joinToString(" ") { it.text }.ifEmpty { "private" }.let { if (it == "private") "public" else it }
        val changes = ArrayList<Change>()
        if (access.isEmpty()) {
            val start = constructor.identifier?.textRange?.startOffset ?: return null
            changes += Change(start, start, "private ")
        } else if (access.map { it.text } != listOf("private")) {
            changes += Change(access.first().textRange.startOffset, access.last().textRange.endOffset, "private")
        }
        val indent = lineIndent(text, constructor.textRange.startOffset)
        val at = constructor.textRange.endOffset
        changes += Change(at, at, "\n\n$indent$factoryAccess static $typeName $factory($written) => new $typeName($arguments);")
        val others = type.members.filterIsInstance<CSharpConstructorDeclaration>().filter { it != constructor && it.modifiers.none { m -> m.text == "static" } }
        if (others.none { (it.parameterList?.parameters?.size ?: 0) == count }) {
            for (creation in PsiTreeUtil.findChildrenOfType(file, CSharpObjectCreationExpression::class.java)) {
                if (creation.initializer != null) continue
                if (creation.type?.text != typeName) continue
                val args = creation.argumentList ?: continue
                if (args.arguments.size != count) continue
                val newKeyword = creation.newKeyword?.takeIf(::present) ?: continue
                val typeElement = creation.type ?: continue
                changes += Change(newKeyword.textRange.startOffset, typeElement.textRange.endOffset, "$typeName.$factory")
            }
        }
        return edit(changes, text, at)
    }

    // ---- To explicit / implicit implementation

    /** The method or property whose header holds [offset]. */
    private fun implementationAt(file: PsiFile, offset: Int): CSharpMemberDeclaration? =
        NativeCSharpModifierEdits.headerAt(file, offset)?.takeIf { it is CSharpMethodDeclaration || it is CSharpPropertyDeclaration }

    private fun explicitSpecifier(member: CSharpMemberDeclaration): CSharpExplicitInterfaceSpecifier? = when (member) {
        is CSharpMethodDeclaration -> member.explicitInterfaceSpecifier
        is CSharpPropertyDeclaration -> member.explicitInterfaceSpecifier
        else -> null
    }

    private fun identifierOf(member: CSharpMemberDeclaration): PsiElement? = when (member) {
        is CSharpMethodDeclaration -> member.identifier
        is CSharpPropertyDeclaration -> member.identifier
        else -> null
    }

    /** A public method or property of a class / struct / record that may become an explicit implementation (its modifiers allow it). */
    fun implicitAt(file: PsiFile, offset: Int): CSharpMemberDeclaration? {
        val member = implementationAt(file, offset) ?: return null
        if (explicitSpecifier(member) != null) return null
        val modifiers = member.modifiers.map { it.text }
        if ("public" !in modifiers || modifiers.any { it in setOf("static", "virtual", "abstract", "override", "sealed", "new", "partial", "extern") }) return null
        val type = member.parent as? CSharpTypeDeclaration ?: return null
        if (type.keyword?.text == "interface") return null
        return member
    }

    /**
     * The interface as the type writes it in its base list (`IComparable<User>`) for the one interface member [member] implements; null when
     * none, several, or a generic interface the base list does not name.
     */
    fun interfaceOf(member: CSharpMemberDeclaration, session: CSharpSemanticSession): String? {
        val owners = CSharpSolutionSearch.baseMembers(member, session).mapNotNull { base ->
            when (base) {
                is PsiElement -> (CSharpSolutionSearch.ownerType(base) as? CSharpTypeDeclaration)?.takeIf { it.keyword?.text == "interface" }?.let { CSharpDeclarationNames.name(it) to (it.typeParameterList != null) }
                is CSharpSymbol.LibraryMember -> base.member.type.takeIf { it.kind == IndexedTypeKind.INTERFACE }?.let { it.name.substringBefore('`').substringAfterLast('.') to it.name.contains('`') }
                else -> null
            }
        }.distinctBy { it.first }
        val (name, generic) = owners.singleOrNull() ?: return null
        name ?: return null
        val type = member.parent as? CSharpTypeDeclaration ?: return null
        val written = type.baseList?.types.orEmpty().mapNotNull { it.type?.text?.replace(Regex("""\s+"""), "") }
            .firstOrNull { it.substringBefore('<').substringAfterLast('.') == name }
        return written ?: name.takeIf { !generic }
    }

    /** `void IRunner.Run()` of `public void Run()` (Rider: "To explicit implementation"): the accessibility goes, the interface's name before the name. */
    fun toExplicit(member: CSharpMemberDeclaration, iface: String, text: CharSequence): CSharpTextEdit? {
        val identifier = identifierOf(member) ?: return null
        val public = member.modifiers.firstOrNull { it.text == "public" } ?: return null
        var end = public.textRange.endOffset
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        val changes = listOf(Change(public.textRange.startOffset, end, ""), Change(identifier.textRange.startOffset, identifier.textRange.startOffset, "$iface."))
        return edit(changes, text, identifier.textRange.startOffset)
    }

    /** An explicit implementation whose name no other member of the type has. */
    fun explicitAt(file: PsiFile, offset: Int): CSharpMemberDeclaration? {
        val member = implementationAt(file, offset) ?: return null
        explicitSpecifier(member) ?: return null
        val type = member.parent as? CSharpTypeDeclaration ?: return null
        val name = identifierOf(member)?.text ?: return null
        if (type.members.any { it != member && CSharpDeclarationNames.name(it) == name && explicitSpecifier(it) == null }) return null
        return member
    }

    /** `public void Run()` of `void IRunner.Run()` (Rider: "To implicit implementation"). */
    fun toImplicit(member: CSharpMemberDeclaration, text: CharSequence): CSharpTextEdit? {
        val specifier = explicitSpecifier(member) ?: return null
        val start = member.modifiers.firstOrNull()?.textRange?.startOffset ?: when (member) {
            is CSharpMethodDeclaration -> member.returnType?.textRange?.startOffset
            is CSharpPropertyDeclaration -> member.type?.textRange?.startOffset
            else -> null
        } ?: return null
        val changes = listOf(Change(start, start, "public "), Change(specifier.textRange.startOffset, specifier.textRange.endOffset, ""))
        return edit(changes, text, start)
    }
}

/** Alt+Enter on an auto-property: a private field and accessors over it (Rider: "To property with backing field"). */
class NativeCSharpToBackingFieldIntention : NativeCSharpContextAction("To property with backing field") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpMemberEdits.propertyAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toBackingField(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
}

/** Alt+Enter on an auto-property, C# 14: accessors over `field` (Rider: "To property with 'field' keyword"). */
class NativeCSharpToFieldKeywordIntention : NativeCSharpContextAction("To property with 'field' keyword") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        if (!CSharpLanguageLevel.forFile(file).isFeatureEnabled(io.github.dotnetsupport.csharp.lang.CSharpFeature.FieldKeyword)) return null
        return NativeCSharpMemberEdits.propertyAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toFieldKeyword(it, editor.document.charsSequence, NativeCSharpContextEdits.unit(file)) }
    }
}

/** Alt+Enter on an auto-property: an expression-bodied get-only property (Rider: "To computed property"). */
class NativeCSharpToComputedPropertyIntention : NativeCSharpContextAction("To computed property") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpMemberEdits.propertyAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toComputed(it, editor.document.charsSequence) }
}

/** Alt+Enter on a property over a private field used nowhere else: an auto-property, the field gone (Rider: "To auto-property"). */
class NativeCSharpToAutoPropertyIntention : NativeCSharpContextAction("To auto-property") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpMemberEdits.propertyAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toAuto(it, editor.document.charsSequence) }
}

/** Alt+Enter on a field: a property over it (Rider: "Encapsulate field"). */
class NativeCSharpEncapsulateFieldIntention : NativeCSharpContextAction("Encapsulate field") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val (declaration, type) = NativeCSharpMemberEdits.fieldAt(file, editor.caretModel.offset) ?: return null
        return NativeCSharpMemberEdits.encapsulate(declaration, type, editor.document.charsSequence, NativeCSharpContextEdits.unit(file))
    }
}

/** Alt+Enter on a constructor: a static `Create` and the constructor private (Rider: "To factory method"). */
class NativeCSharpToFactoryMethodIntention : NativeCSharpContextAction("To factory method") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpMemberEdits.constructorAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toFactory(file, it, editor.document.charsSequence) }
}

/** Alt+Enter on a public member implementing one interface member: `void IRunner.Run()` (Rider: "To explicit implementation"). */
class NativeCSharpToExplicitImplementationIntention : NativeCSharpContextAction("To explicit implementation", needsTypes = true) {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? {
        val member = NativeCSharpMemberEdits.implicitAt(file, editor.caretModel.offset) ?: return null
        val iface = NativeCSharpMemberEdits.interfaceOf(member, CSharpSemanticSession(file.project)) ?: return null
        return NativeCSharpMemberEdits.toExplicit(member, iface, editor.document.charsSequence)
    }
}

/** Alt+Enter on an explicit implementation: `public void Run()` (Rider: "To implicit implementation"). */
class NativeCSharpToImplicitImplementationIntention : NativeCSharpContextAction("To implicit implementation") {
    override fun edit(file: CSharpFile, editor: Editor): CSharpTextEdit? =
        NativeCSharpMemberEdits.explicitAt(file, editor.caretModel.offset)?.let { NativeCSharpMemberEdits.toImplicit(it, editor.document.charsSequence) }
}
