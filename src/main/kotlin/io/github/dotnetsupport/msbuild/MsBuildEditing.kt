package io.github.dotnetsupport.msbuild

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.XmlAttributeInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlTokenType
import com.intellij.ui.JBColor
import com.intellij.xml.XmlTagNameProvider
import javax.swing.Icon

/** What the editor of an MSBuild file (`.csproj`, `.props`, `.targets`, ...) gets from [MsBuildSchema]. */
object MsBuildFiles {
    fun isMsBuild(file: PsiFile?): Boolean {
        val original = file?.originalFile ?: return false
        return original.fileType == MsBuildFileType || original.virtualFile?.extension?.lowercase() in MSBUILD_EXTENSIONS
    }

    /** Names of the tags around [tag], the parent first. */
    fun ancestors(tag: XmlTag): List<String> = generateSequence(tag.parentTag) { it.parentTag }.map { it.localName }.toList()

    /** The packages and the SDK of the file decide which fragments of the schema come first. */
    fun project(file: PsiFile): MsBuildProject = CachedValuesManager.getCachedValue(file.originalFile) {
        CachedValueProvider.Result.create(MsBuildProject.parse(file.originalFile.viewProvider.contents), file.originalFile)
    }

    fun lookup(entry: SchemaEntry, project: MsBuildProject, insertHandler: InsertHandler<LookupElement>?): LookupElement {
        val active = entry.fragment.isActiveFor(project)
        val needs = if (active) "" else "  needs " + (entry.fragment.packages.firstOrNull() ?: entry.fragment.sdks.firstOrNull().orEmpty())
        val builder = LookupElementBuilder.create(entry, entry.name)
            .withIcon(icon(entry.kind))
            .withTypeText(entry.fragment.title, true)
            .withTailText("  " + StringUtil.shortenTextWithEllipsis(entry.doc.substringBefore(". "), TAIL_LENGTH, 0) + needs, true)
            .withItemTextForeground(if (active) JBColor.foreground() else JBColor.GRAY)
            .withCaseSensitivity(false)
            .withInsertHandler(insertHandler)
        // what the project can use right now first; the tools it does not reference stay discoverable below
        return PrioritizedLookupElement.withPriority(builder, if (active) ACTIVE_PRIORITY else 0.0)
    }

    /**
     * A lookup item that matches in any case (`nulla` finds `Nullable`) is inserted by the platform in the case of what was
     * typed: `nullable`. MSBuild does not care, but people do: the name is put back the way the schema spells it.
     */
    fun restoreCase(context: InsertionContext, text: String) {
        val document = context.document
        val range = TextRange(context.startOffset, context.tailOffset)
        if (range.endOffset <= document.textLength && document.getText(range) != text && document.getText(range).equals(text, ignoreCase = true)) {
            document.replaceString(range.startOffset, range.endOffset, text)
        }
    }

    /** [restoreCase], then what [then] does. */
    fun spelledAsInSchema(then: InsertHandler<LookupElement>?): InsertHandler<LookupElement> = InsertHandler { context, item ->
        restoreCase(context, item.lookupString)
        context.commitDocument()
        then?.handleInsert(context, item)
    }

    private fun icon(kind: SchemaEntry.Kind): Icon = when (kind) {
        SchemaEntry.Kind.PROPERTY -> AllIcons.Nodes.Property
        SchemaEntry.Kind.ITEM -> AllIcons.Nodes.Tag
        SchemaEntry.Kind.METADATA -> AllIcons.Nodes.Parameter
        SchemaEntry.Kind.ELEMENT -> AllIcons.Nodes.Folder
        SchemaEntry.Kind.TASK -> AllIcons.Nodes.Function
    }

    private const val TAIL_LENGTH = 70
    private const val ACTIVE_PRIORITY = 100.0
}

/** Tag names: properties in a PropertyGroup, items in an ItemGroup, metadata in an item, tasks in a Target. */
class MsBuildTagNameProvider : XmlTagNameProvider {
    override fun addTagNameVariants(elements: MutableList<LookupElement>, tag: XmlTag, prefix: String?) {
        val file = tag.containingFile
        if (!MsBuildFiles.isMsBuild(file)) return
        val project = MsBuildFiles.project(file)
        val present = tag.parentTag?.subTags.orEmpty().filter { it !== tag }.mapTo(HashSet()) { it.localName.lowercase() }
        val place = MsBuildSchema.placeOf(MsBuildFiles.ancestors(tag))
        MsBuildSchema.childTags(MsBuildFiles.ancestors(tag))
            // a property or a metadata is set once; items and tasks repeat
            .filter { (place != MsBuildSchema.Place.PROPERTY_GROUP && place != MsBuildSchema.Place.ITEM) || it.name.lowercase() !in present }
            .mapTo(elements) { MsBuildFiles.lookup(it, project, TagInsertHandler) }
    }

    /** Completes the tag the way it is going to be written: `<Nullable>|</Nullable>`, `<PackageReference Include="|" />`. */
    private object TagInsertHandler : InsertHandler<LookupElement> {
        override fun handleInsert(context: InsertionContext, item: LookupElement) {
            val entry = item.`object` as? SchemaEntry ?: return
            MsBuildFiles.restoreCase(context, entry.name)
            val document = context.document
            val offset = context.tailOffset
            // renaming an existing tag: the rest of it is there already
            val next = document.charsSequence.getOrNull(offset)
            if (next != null && (next == '>' || next == '/' || next == ' ' || next == '\t')) return

            val name = entry.name
            val (text, caret) = when {
                entry.kind == SchemaEntry.Kind.ITEM -> " Include=\"\" />" to " Include=\"".length
                entry.kind == SchemaEntry.Kind.TASK -> " />" to 1
                name == "Target" -> " Name=\"\">\n</Target>" to " Name=\"".length
                name == "Import" -> " Project=\"\" />" to " Project=\"".length
                name == "Sdk" -> " Name=\"\" />" to " Name=\"".length
                name == "UsingTask" -> " TaskName=\"\" />" to " TaskName=\"".length
                else -> "></$name>" to 1
            }
            document.insertString(offset, text)
            context.editor.caretModel.moveToOffset(offset + caret)
            // the values of an enumeration, the attributes of a task
            if (entry.values.isNotEmpty() || entry.kind == SchemaEntry.Kind.TASK) AutoPopupController.getInstance(context.project).scheduleAutoPopup(context.editor)
        }
    }
}

/** Attribute names (Condition, Include / Remove / Update, metadata as attributes), and the values of attributes and of properties. */
class MsBuildCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        if (!MsBuildFiles.isMsBuild(parameters.originalFile)) return
        val project = MsBuildFiles.project(parameters.originalFile)
        val parent = position.parent
        when {
            parent is XmlAttribute && position.node.elementType == XmlTokenType.XML_NAME -> {
                val tag = parent.parent ?: return
                val present = tag.attributes.filter { it !== parent }.mapTo(HashSet()) { it.name.lowercase() }
                MsBuildSchema.attributes(tag.localName, MsBuildFiles.ancestors(tag)).filter { it.name.lowercase() !in present }
                    .forEach { result.addElement(MsBuildFiles.lookup(it, project, MsBuildFiles.spelledAsInSchema(XmlAttributeInsertHandler.INSTANCE))) }
            }
            parent is XmlAttributeValue -> {
                val attribute = parent.parent as? XmlAttribute ?: return
                val tag = attribute.parent ?: return
                addValues(result, MsBuildSchema.describeAttribute(tag.localName, MsBuildFiles.ancestors(tag), attribute.name), typed(parameters, parent.valueTextRange.startOffset))
            }
            else -> {
                val text = PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) ?: return
                val tag = text.parentTag ?: return
                if (tag.subTags.isNotEmpty()) return
                addValues(result, MsBuildSchema.describeTag(tag.localName, MsBuildFiles.ancestors(tag)), typed(parameters, text.textRange.startOffset))
            }
        }
    }

    /** What is typed of the current element of a `a;b` list. */
    private fun typed(parameters: CompletionParameters, valueStart: Int): String {
        val text = parameters.editor.document.charsSequence.subSequence(valueStart.coerceAtMost(parameters.offset), parameters.offset).toString()
        return text.substringAfterLast(';').substringAfterLast(',').trimStart()
    }

    private fun addValues(result: CompletionResultSet, entry: SchemaEntry?, typed: String) {
        if (entry == null) return
        val values = result.withPrefixMatcher(typed).caseInsensitive()
        entry.values.forEachIndexed { index, value ->
            // the order of the schema is the order of preference
            values.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(value).withTypeText(entry.name, true).withCaseSensitivity(false).withInsertHandler(MsBuildFiles.spelledAsInSchema(null)), (entry.values.size - index).toDouble()))
        }
    }
}

/** Values outside of a closed enumeration, and the colors of `$(Property)`, `@(Item)`, `%(Metadata)`. */
class MsBuildAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is XmlTag && element !is XmlAttribute && element !is XmlAttributeValue && element !is XmlText) return
        if (!MsBuildFiles.isMsBuild(element.containingFile)) return
        when (element) {
            is XmlTag -> if (element.subTags.isEmpty()) {
                val entry = MsBuildSchema.describeTag(element.localName, MsBuildFiles.ancestors(element)) ?: return
                val range = element.value.textRange
                if (!range.isEmpty) report(holder, entry, element.value.text, range)
            }
            is XmlAttribute -> {
                val tag = element.parent ?: return
                val entry = MsBuildSchema.describeAttribute(tag.localName, MsBuildFiles.ancestors(tag), element.name) ?: return
                val value = element.valueElement ?: return
                report(holder, entry, element.value.orEmpty(), value.valueTextRange)
            }
            else -> highlightReferences(element, holder)
        }
    }

    private fun report(holder: AnnotationHolder, entry: SchemaEntry, value: String, range: TextRange) {
        val problem = MsBuildSchema.problemWith(entry, value) ?: return
        holder.newAnnotation(HighlightSeverity.WARNING, problem).range(range).create()
    }

    private fun highlightReferences(element: PsiElement, holder: AnnotationHolder) {
        val start = element.textRange.startOffset
        for (match in REFERENCE.findAll(element.text)) {
            val key = when (match.value[0]) {
                '$' -> PROPERTY_REFERENCE
                '@' -> ITEM_REFERENCE
                else -> METADATA_REFERENCE
            }
            val name = match.groups[1]!!.range
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(TextRange(start + name.first, start + name.last + 1)).textAttributes(key).create()
        }
    }

    companion object {
        // $(Prop), @(Item) and @(Item->'...'), %(Meta) and %(Item.Meta); property functions $([System...]) are left alone
        private val REFERENCE = Regex("""[$@%]\(([A-Za-z_][\w.\-]*)""")

        val PROPERTY_REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DOTNET_MSBUILD_PROPERTY", DefaultLanguageHighlighterColors.CONSTANT)
        val ITEM_REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DOTNET_MSBUILD_ITEM", DefaultLanguageHighlighterColors.CLASS_NAME)
        val METADATA_REFERENCE: TextAttributesKey = TextAttributesKey.createTextAttributesKey("DOTNET_MSBUILD_METADATA", DefaultLanguageHighlighterColors.METADATA)
    }
}

/** Quick Documentation for the tags and attributes the schema knows, in the file and in the completion list. */
class MsBuildDocumentationProvider : AbstractDocumentationProvider() {
    /** A schema entry as the target of the documentation of a completion item. */
    private class SchemaElement(val entry: SchemaEntry, private val context: PsiElement) : FakePsiElement() {
        override fun getParent(): PsiElement = context
        override fun getName(): String = entry.name
    }

    override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
        if (contextElement == null || !MsBuildFiles.isMsBuild(file)) return null
        val type = contextElement.node.elementType
        if (type != XmlTokenType.XML_NAME && type != XmlTokenType.XML_TAG_NAME) return null
        return entryOf(contextElement.parent)?.let { SchemaElement(it, contextElement) }
    }

    override fun getDocumentationElementForLookupItem(psiManager: PsiManager, item: Any, element: PsiElement?): PsiElement? =
        if (item is SchemaEntry && element != null) SchemaElement(item, element) else null

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? = (element as? SchemaElement)?.let { render(it.entry) }

    private fun entryOf(element: PsiElement?): SchemaEntry? = when (element) {
        is XmlTag -> MsBuildSchema.describeTag(element.localName, MsBuildFiles.ancestors(element))
        is XmlAttribute -> element.parent?.let { MsBuildSchema.describeAttribute(it.localName, MsBuildFiles.ancestors(it), element.name) }
        else -> null
    }

    companion object {
        fun render(entry: SchemaEntry): String = buildString {
            val owner = entry.owner?.takeIf { it != "*" }?.let { " of $it" }.orEmpty()
            append(DocumentationMarkup.DEFINITION_START).append(StringUtil.escapeXmlEntities("${entry.name}: ${entry.kind.title}$owner")).append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START).append(StringUtil.escapeXmlEntities(entry.doc)).append(DocumentationMarkup.CONTENT_END)
            append(DocumentationMarkup.SECTIONS_START)
            if (entry.values.isNotEmpty()) section(if (entry.isOpen) "For example:" else "Values:", entry.values.joinToString(", ") { "<code>${StringUtil.escapeXmlEntities(it)}</code>" })
            if (entry.fragment.packages.isNotEmpty()) section("Comes with:", StringUtil.escapeXmlEntities(entry.fragment.packages.take(MAX_PACKAGES).joinToString(", ")))
            entry.fragment.url?.let { section("${entry.fragment.title}:", "<a href=\"$it\">documentation</a>") }
            append(DocumentationMarkup.SECTIONS_END)
        }

        private fun StringBuilder.section(title: String, content: String) {
            append(DocumentationMarkup.SECTION_HEADER_START).append(title).append(DocumentationMarkup.SECTION_SEPARATOR).append(content).append(DocumentationMarkup.SECTION_END)
        }

        private const val MAX_PACKAGES = 4
    }
}
