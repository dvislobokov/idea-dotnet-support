package io.github.dotnetsupport.msbuild

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Reading and writing the properties of a project file the way a "Project Properties" dialog needs it: the unconditional
 * `PropertyGroup`s only (a value under `Condition` belongs to a configuration the dialog does not edit), the last value wins
 * as in MSBuild. Changes go into the document at offsets the XML PSI points at, so the indentation and everything else of
 * the file stay as they were (the PSI operations of the platform reformat the group with the code style of the IDE).
 */
object ProjectProperties {
    /** `TargetFramework` and `TargetFrameworks` are one setting: which tag is used depends on how many frameworks there are. */
    const val TARGET_FRAMEWORK = "TargetFramework"
    const val TARGET_FRAMEWORKS = "TargetFrameworks"

    /** Unconditional values of [names]; a property that is not in the file is absent from the map. */
    fun read(file: XmlFile, names: Collection<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (group in unconditionalGroups(file)) {
            for (tag in group.subTags) {
                if (tag.localName in names && tag.getAttributeValue("Condition") == null) result[tag.localName] = tag.value.trimmedText
            }
        }
        return result
    }

    /** `TargetFramework` or `TargetFrameworks`, whichever the file has, as a list. */
    fun readTargetFrameworks(file: XmlFile): List<String> {
        val values = read(file, listOf(TARGET_FRAMEWORK, TARGET_FRAMEWORKS))
        return (values[TARGET_FRAMEWORKS] ?: values[TARGET_FRAMEWORK]).orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Sets every property of [changes]: an empty value removes the property (the default of the SDK applies), a new property goes
     * to the end of the first unconditional `PropertyGroup` (created at the top of the file when there is none), an existing one is
     * changed in place — the last unconditional occurrence, the one MSBuild uses. Inside a write action.
     */
    fun write(file: XmlFile, changes: Map<String, String>) {
        val manager = PsiDocumentManager.getInstance(file.project)
        val document = manager.getDocument(file) ?: return
        for ((name, value) in changes) {
            manager.commitDocument(document)
            val groups = unconditionalGroups(file)
            val existing = groups.flatMap { it.subTags.asList() }.lastOrNull { it.localName == name && it.getAttributeValue("Condition") == null }
            when {
                value.isBlank() -> existing?.let { remove(document, it) }
                existing != null -> document.replaceString(existing.value.textRange.startOffset, existing.value.textRange.endOffset, escape(value))
                else -> {
                    val group = groups.firstOrNull()
                    if (group != null) append(document, group) { "<$name>${escape(value)}</$name>" }
                    else file.rootTag?.let { root ->
                        append(document, root, first = true) { indent -> "<PropertyGroup>${eol(document)}$indent$UNIT<$name>${escape(value)}</$name>${eol(document)}$indent</PropertyGroup>" }
                    }
                }
            }
        }
        manager.commitDocument(document)
    }

    /** Frameworks as a list: one goes to `TargetFramework`, several to `TargetFrameworks`, and the other tag is removed. */
    fun writeTargetFrameworks(file: XmlFile, frameworks: List<String>) {
        val cleaned = frameworks.map { it.trim() }.filter { it.isNotEmpty() }
        val changes = when (cleaned.size) {
            0 -> mapOf(TARGET_FRAMEWORK to "", TARGET_FRAMEWORKS to "")
            1 -> mapOf(TARGET_FRAMEWORKS to "", TARGET_FRAMEWORK to cleaned.single())
            else -> mapOf(TARGET_FRAMEWORK to "", TARGET_FRAMEWORKS to cleaned.joinToString(";"))
        }
        write(file, changes)
    }

    private fun unconditionalGroups(file: XmlFile): List<XmlTag> =
        file.rootTag?.subTags.orEmpty().filter { it.localName == "PropertyGroup" && it.getAttributeValue("Condition") == null }

    /** The tag together with the line break and indentation before it, so that no blank line is left behind. */
    private fun remove(document: Document, tag: XmlTag) {
        val whitespace = blankBefore(tag)
        val start = if (whitespace != null) whitespace.textRange.startOffset else tag.textRange.startOffset
        document.deleteString(start, tag.textRange.endOffset)
    }

    /** The blank text before a tag: the XML PSI keeps it as `XmlText` between tags and as `PsiWhiteSpace` elsewhere. */
    private fun blankBefore(tag: XmlTag): PsiElement? = tag.prevSibling?.takeIf { it is PsiWhiteSpace || (it is XmlText && it.text.isBlank()) }

    private const val UNIT = "  "

    /**
     * A line of its own inside [parent], indented as its children are: after the last child, or ([first]) right after the opening tag.
     * [text] gets the indentation of that line, for what spans several lines.
     */
    private fun append(document: Document, parent: XmlTag, first: Boolean = false, text: (indent: String) -> String) {
        val children = parent.subTags
        val indent = children.firstOrNull()?.let(::lineIndent) ?: (lineIndent(parent).orEmpty() + UNIT)
        val eol = eol(document)
        if (first || children.isEmpty()) {
            document.insertString(parent.value.textRange.startOffset, "$eol$eol$indent${text(indent)}")
        } else {
            document.insertString(children.last().textRange.endOffset, "$eol$indent${text(indent)}")
        }
    }

    /** Whitespace at the start of the line the tag begins on; null when the tag does not begin a line. */
    private fun lineIndent(tag: XmlTag): String? {
        val whitespace = blankBefore(tag)?.text ?: return if (tag.prevSibling == null) "" else null
        return if ('\n' in whitespace) whitespace.substringAfterLast('\n') else null
    }

    private fun eol(document: Document): String = if (document.text.contains("\r\n")) "\r\n" else "\n"

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
